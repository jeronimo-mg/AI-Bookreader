package com.example.epubstoryteller.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.example.epubstoryteller.model.VoiceOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Cliente WebSocket bidirecional para o modelo `gemini-3.8-live` via API oficial BidiGenerateContent.
 *
 * Proteções contra corte das últimas palavras do parágrafo:
 * 1. O buffer interno do `AudioTrack` comporta ~2 segundos de áudio (96.000 bytes). `AudioTrack.write()` retorna
 *    assim que os bytes entram no buffer de memória, até 2 segundos ANTES de saírem fisicamente no alto-falante.
 * 2. Agora monitoramos `totalFramesWritten` vs `audioTrack.playbackHeadPosition` em `waitForAudioTrackToDrain()`,
 *    aguardando o alto-falante reproduzir 100% das amostras do parágrafo antes de avançar para o próximo.
 * 3. No avanço automático entre parágrafos (`flushExistingAudio = false`), NÃO executamos `track.flush()`,
 *    preservando qualquer cauda acústica final no DAC de áudio do Android. `flushLocalAudioBuffer()` só é
 *    chamado quando o usuário muda manualmente de parágrafo ou pausa a leitura.
 */
class GeminiLiveStorytellerClient(
    private val onParagraphStarted: (Int) -> Unit,
    private val onParagraphFinished: (Int) -> Unit,
    private val onTranscriptChunk: (String) -> Unit,
    private val onConnectionStatusChanged: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val MODEL_NAME = "models/gemini-3.8-live"
        private const val SAMPLE_RATE_HZ = 24000
        private const val SESSION_MAX_AGE_MS = 12 * 60 * 1000L
        private const val MAX_AUTO_RECONNECT_ATTEMPTS = 5
        private const val MAX_SEGMENT_CHARS = 380
    }

    private sealed class AudioQueueItem {
        data class PcmChunk(val pcmBytes: ByteArray, val genId: Int) : AudioQueueItem()
        data class SegmentTurnComplete(
            val paragraphIndex: Int,
            val segmentIndex: Int,
            val totalSegments: Int,
            val genId: Int
        ) : AudioQueueItem()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var sessionConnectedAtMs: Long = 0L

    private val isSetupComplete = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val activeParagraphIndex = AtomicInteger(-1)
    private var activeSegments: List<String> = emptyList()
    private val activeSegmentIndex = AtomicInteger(0)
    private val utteranceGenerationId = AtomicInteger(0)
    private val reconnectAttempts = AtomicInteger(0)

    // Contador exato de frames PCM de 16 bits (1 frame = 2 bytes) enviados ao AudioTrack desde o último flush
    private val totalFramesWritten = AtomicLong(0L)

    private var currentApiKey: String = ""
    private var currentVoiceName: String = "Sulafat"
    private var currentLanguageCode: String = "pt-BR"
    private var pendingSpeakRequest: Boolean = false
    private var reconnectJob: Job? = null

    private var audioChannel = Channel<AudioQueueItem>(Channel.UNLIMITED)
    private var audioConsumerJob: Job? = null

    init {
        startAudioConsumerLoop()
    }

    private fun startAudioConsumerLoop() {
        audioConsumerJob?.cancel()
        audioConsumerJob = scope.launch(Dispatchers.IO) {
            for (item in audioChannel) {
                if (!isPlaying.get()) continue
                when (item) {
                    is AudioQueueItem.PcmChunk -> {
                        if (item.genId == utteranceGenerationId.get()) {
                            writePcmToAudioTrackBlocking(item.pcmBytes, item.genId)
                        }
                    }
                    is AudioQueueItem.SegmentTurnComplete -> {
                        if (item.genId == utteranceGenerationId.get() && isPlaying.get()) {
                            val nextSegIdx = item.segmentIndex + 1
                            if (nextSegIdx < item.totalSegments) {
                                // Aguarda o buffer do AudioTrack terminar de falar o segmento atual antes de pedir o próximo
                                waitForAudioTrackToDrain(item.genId, extraPauseMs = 60L)
                                if (isPlaying.get() && item.genId == utteranceGenerationId.get()) {
                                    activeSegmentIndex.set(nextSegIdx)
                                    sendCurrentSegmentTurn(item.genId)
                                }
                            } else {
                                // Aguarda 100% dos frames no buffer de 2 segundos do AudioTrack saírem no alto-falante!
                                waitForAudioTrackToDrain(item.genId, extraPauseMs = 180L)
                                if (isPlaying.get() && item.genId == utteranceGenerationId.get()) {
                                    onParagraphFinished(item.paragraphIndex)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Aguarda até que o `playbackHeadPosition` do `AudioTrack` alcance `totalFramesWritten`,
     * garantindo que nenhuma sílaba ou palavra final que ainda estava nos 2 segundos de buffer
     * de hardware seja cortada ao mudar para o próximo parágrafo.
     */
    private suspend fun waitForAudioTrackToDrain(genId: Int, extraPauseMs: Long) {
        val track = audioTrack ?: return
        if (track.state != AudioTrack.STATE_INITIALIZED) return

        val targetFrames = totalFramesWritten.get()
        val startWaitMs = System.currentTimeMillis()
        val maxWaitMs = 3500L

        while (isPlaying.get() && genId == utteranceGenerationId.get()) {
            val playedFrames = try {
                track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            } catch (_: Exception) {
                targetFrames
            }
            val remainingFrames = targetFrames - playedFrames
            if (remainingFrames <= 0L) break
            if (System.currentTimeMillis() - startWaitMs > maxWaitMs) break

            val sleepMs = ((remainingFrames * 1000L) / SAMPLE_RATE_HZ).coerceIn(20L, 80L)
            delay(sleepMs)
        }

        if (extraPauseMs > 0 && isPlaying.get() && genId == utteranceGenerationId.get()) {
            delay(extraPauseMs)
        }
    }

    private fun resetAudioQueue() {
        val oldChannel = audioChannel
        audioChannel = Channel(Channel.UNLIMITED)
        oldChannel.cancel()
        startAudioConsumerLoop()
    }

    private fun ensureAudioTrack(): AudioTrack {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED) {
            return existing
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE_HZ * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        totalFramesWritten.set(0L)
        track.play()
        audioTrack = track
        return track
    }

    /**
     * @param flushExistingAudio `true` quando o usuário clicou manualmente em outro parágrafo (interrompe na hora);
     *                           `false` quando o leitor avançou naturalmente ao terminar o parágrafo anterior (sem cortar a cauda de áudio).
     */
    fun connectAndSpeakParagraph(
        apiKey: String,
        voiceName: String,
        languageBcp47: String,
        paragraphIndex: Int,
        paragraphText: String,
        flushExistingAudio: Boolean = true
    ) {
        if (apiKey.isBlank()) {
            onError("Informe sua chave de API do Gemini nas configurações.")
            return
        }

        reconnectJob?.cancel()
        isPlaying.set(true)
        activeParagraphIndex.set(paragraphIndex)
        activeSegments = splitIntoSpeechSegments(paragraphText)
        activeSegmentIndex.set(0)
        val genId = utteranceGenerationId.incrementAndGet()

        if (flushExistingAudio) {
            resetAudioQueue()
            flushLocalAudioBuffer()
        }
        onParagraphStarted(paragraphIndex)

        val sessionExpired = (System.currentTimeMillis() - sessionConnectedAtMs) > SESSION_MAX_AGE_MS
        val configChanged = apiKey != currentApiKey || voiceName != currentVoiceName || languageBcp47 != currentLanguageCode

        if (webSocket == null || !isSetupComplete.get() || sessionExpired || configChanged) {
            currentApiKey = apiKey
            currentVoiceName = voiceName
            currentLanguageCode = languageBcp47
            pendingSpeakRequest = true
            openNewWebSocketSession()
        } else {
            sendCurrentSegmentTurn(genId)
        }
    }

    private fun splitIntoSpeechSegments(text: String): List<String> {
        val cleaned = text.trim()
        if (cleaned.length <= MAX_SEGMENT_CHARS) {
            return listOf(cleaned)
        }

        val sentences = cleaned.split(Regex("(?<=[.!?;:])\\s+"))
        val segments = mutableListOf<String>()
        val current = StringBuilder()

        for (sentence in sentences) {
            if (current.isEmpty()) {
                current.append(sentence)
            } else if (current.length + 1 + sentence.length <= MAX_SEGMENT_CHARS) {
                current.append(" ").append(sentence)
            } else {
                segments.add(current.toString())
                current.clear()
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) {
            segments.add(current.toString())
        }
        return segments.ifEmpty { listOf(cleaned) }
    }

    private fun openNewWebSocketSession() {
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()

        isSetupComplete.set(false)
        onConnectionStatusChanged("Conectando ao Gemini 3.8 Live...")

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$currentApiKey"
        val request = Request.Builder().url(wsUrl).build()

        val newSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (ws !== webSocket) return
                sessionConnectedAtMs = System.currentTimeMillis()
                onConnectionStatusChanged("Configurando Storyteller ($currentLanguageCode)...")
                val setupJson = buildSetupMessageJson(
                    voiceName = currentVoiceName,
                    languageBcp47 = currentLanguageCode
                )
                ws.send(setupJson)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (ws !== webSocket) return
                handleServerMessageNonBlocking(text)
            }

            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                if (ws !== webSocket) return
                handleServerMessageNonBlocking(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws !== webSocket) return
                isSetupComplete.set(false)
                handleUnexpectedDisconnect(t.localizedMessage ?: "Queda de conexão")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return
                isSetupComplete.set(false)
                if (isPlaying.get() && code != 1000) {
                    handleUnexpectedDisconnect("Sessão encerrada ($code)")
                } else {
                    onConnectionStatusChanged("Sessão encerrada ($code)")
                }
            }
        })
        webSocket = newSocket
    }

    private fun handleUnexpectedDisconnect(reason: String) {
        if (!isPlaying.get()) {
            onConnectionStatusChanged("Desconectado")
            return
        }

        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt <= MAX_AUTO_RECONNECT_ATTEMPTS) {
            onConnectionStatusChanged("Reconectando ($attempt/$MAX_AUTO_RECONNECT_ATTEMPTS)...")
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(1000L * attempt)
                if (isPlaying.get() && activeParagraphIndex.get() >= 0 && activeSegments.isNotEmpty()) {
                    pendingSpeakRequest = true
                    openNewWebSocketSession()
                }
            }
        } else {
            reconnectAttempts.set(0)
            onConnectionStatusChanged("Desconectado")
            onError("Erro na conexão Live API: $reason")
        }
    }

    private fun buildSetupMessageJson(voiceName: String, languageBcp47: String): String {
        val isPortuguese = languageBcp47.lowercase().startsWith("pt")
        val targetLangCode = if (isPortuguese) "pt-BR" else languageBcp47

        val storytellerPrompt = if (isPortuguese) {
            """
            RESPOND IN PORTUGUESE (BRAZIL). YOU MUST RESPOND UNMISTAKABLY IN PORTUGUESE (BRAZIL) WITH AN AUTHENTIC BRAZILIAN PORTUGUESE ACCENT (pt-BR).
            Você é um narrador profissional de audiolivros (estilo Storyteller) em Português do Brasil (pt-BR).
            REGRAS ESTRITAS DE NARRAÇÃO:
            1. Leia APENAS e FIELMENTE o trecho enviado pelo usuário, palavra por palavra, do início ao fim, pronunciando claramente até a última sílaba da última palavra.
            2. NUNCA adicione introduções ("Claro, vou ler", "Aqui está"), comentários, resumos ou encerramentos.
            3. Use entonação envolvente de contador de histórias (storyteller): dê vida aos diálogos, faça pausas dramáticas naturais na pontuação e transmita a emoção e o suspense da cena.
            4. Mesmo que o texto escrito contenha grafia de Portugal (ex: "facto", "exacto", "estava a olhar"), pronuncie com sotaque e fonética natural do Português Brasileiro (pt-BR).
            """.trimIndent()
        } else {
            """
            You are a professional audiobook storyteller.
            Read ONLY the exact text provided by the user, verbatim from start to finish, pronouncing clearly through the very last word, with rich storytelling emotion. Never add introductory or concluding remarks.
            """.trimIndent()
        }

        val setupObj = JSONObject().apply {
            put("model", MODEL_NAME)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("languageCode", targetLangCode)
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName)
                        })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", storytellerPrompt)
                }))
            })
            put("realtimeInputConfig", JSONObject().apply {
                put("automaticActivityDetection", JSONObject().apply {
                    put("disabled", true)
                })
            })
            put("outputAudioTranscription", JSONObject())
        }

        return JSONObject().put("setup", setupObj).toString()
    }

    private fun sendCurrentSegmentTurn(genId: Int) {
        val ws = webSocket ?: return
        val paraIdx = activeParagraphIndex.get()
        val segIdx = activeSegmentIndex.get()
        val segments = activeSegments
        val segmentText = segments.getOrNull(segIdx) ?: return

        val segLabel = if (segments.size > 1) " (parte ${segIdx + 1}/${segments.size})" else ""
        onConnectionStatusChanged("Narrando parágrafo #${paraIdx + 1}$segLabel")

        val promptText = "NARRE LITERALMENTE ESTE TRECHO ATÉ A ÚLTIMA PALAVRA:\n\n$segmentText"
        val clientContentMsg = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", promptText)
                    }))
                }))
                put("turnComplete", true)
            })
        }

        val sent = ws.send(clientContentMsg.toString())
        if (!sent && isPlaying.get()) {
            handleUnexpectedDisconnect("Falha ao enviar trecho no socket")
        }
    }

    private fun handleServerMessageNonBlocking(rawJson: String) {
        val expectedGenId = utteranceGenerationId.get()
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                isSetupComplete.set(true)
                reconnectAttempts.set(0)
                onConnectionStatusChanged("Conectado (Gemini 3.8 Live)")
                if (pendingSpeakRequest && isPlaying.get()) {
                    pendingSpeakRequest = false
                    sendCurrentSegmentTurn(expectedGenId)
                }
                return
            }

            val serverContent = root.optJSONObject("serverContent") ?: return

            if (!isPlaying.get() || expectedGenId != utteranceGenerationId.get()) {
                return
            }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            if (modelTurn != null) {
                val parts = modelTurn.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val b64Audio = inlineData.optString("data", "")
                            if (b64Audio.isNotEmpty() && expectedGenId == utteranceGenerationId.get()) {
                                reconnectAttempts.set(0)
                                val pcmBytes = Base64.decode(b64Audio, Base64.DEFAULT)
                                audioChannel.trySend(AudioQueueItem.PcmChunk(pcmBytes, expectedGenId))
                            }
                        }
                    }
                }
            }

            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            if (outputTranscription != null) {
                val transcriptText = outputTranscription.optString("text", "")
                if (transcriptText.isNotEmpty()) {
                    onTranscriptChunk(transcriptText)
                }
            }

            val turnComplete = serverContent.optBoolean("turnComplete", false)
            if (turnComplete && isPlaying.get() && expectedGenId == utteranceGenerationId.get()) {
                audioChannel.trySend(
                    AudioQueueItem.SegmentTurnComplete(
                        paragraphIndex = activeParagraphIndex.get(),
                        segmentIndex = activeSegmentIndex.get(),
                        totalSegments = activeSegments.size,
                        genId = expectedGenId
                    )
                )
            }
        } catch (e: Exception) {
            onError("Erro ao processar resposta do Gemini 3.8 Live: ${e.localizedMessage}")
        }
    }

    private fun writePcmToAudioTrackBlocking(pcmBytes: ByteArray, genId: Int) {
        if (!isPlaying.get() || genId != utteranceGenerationId.get()) return
        val track = ensureAudioTrack()
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        var offset = 0
        val chunkSize = 4800
        while (offset < pcmBytes.size && isPlaying.get() && genId == utteranceGenerationId.get()) {
            val len = minOf(chunkSize, pcmBytes.size - offset)
            val written = track.write(pcmBytes, offset, len, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
            totalFramesWritten.addAndGet((written / 2).toLong())
        }
    }

    fun pausePlayback() {
        isPlaying.set(false)
        pendingSpeakRequest = false
        reconnectAttempts.set(0)
        reconnectJob?.cancel()
        utteranceGenerationId.incrementAndGet()
        resetAudioQueue()
        flushLocalAudioBuffer()
        onConnectionStatusChanged("Pausado")
    }

    private fun flushLocalAudioBuffer() {
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                    totalFramesWritten.set(0L)
                    track.play()
                }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun fetchExtendedPtBrStorytellerVoices(apiKey: String): List<VoiceOption> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        val url = "https://generativelanguage.googleapis.com/v1beta/voices?language_code=pt-BR&context=Audiobook&page_size=50"
        val req = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey.trim())
            .get()
            .build()
        try {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val voicesArr = json.optJSONArray("voices") ?: return@withContext emptyList()
                val result = mutableListOf<VoiceOption>()
                for (i in 0 until voicesArr.length()) {
                    val v = voicesArr.optJSONObject(i) ?: continue
                    val id = v.optString("id", "").ifBlank { v.optString("name", "") }
                    val displayName = v.optString("displayName", v.optString("display_name", id))
                    val desc = v.optString("description", "Extended Voice Library (pt-BR Audiobook)")
                    if (id.isNotBlank()) {
                        result.add(VoiceOption(id, "$displayName (pt-BR Library)", desc))
                    }
                }
                result
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun release() {
        pausePlayback()
        audioConsumerJob?.cancel()
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.close(1000, "App closed")
        audioTrack?.release()
        audioTrack = null
    }
}
