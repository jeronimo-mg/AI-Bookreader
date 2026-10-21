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

/**
 * Cliente WebSocket bidirecional para o modelo `gemini-3.8-live` via API oficial BidiGenerateContent.
 *
 * Inclui:
 * - Suporte a execução em segundo plano / tela desligada em conjunto com `StorytellerPlaybackService`.
 * - Reconexão automática transparente caso ocorra queda momentânea de socket ("Software caused connection abort"
 *   ou transição Wi-Fi / Dados Móveis) enquanto `isPlaying == true`, retomando automaticamente o parágrafo ativo.
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null
    private var sessionConnectedAtMs: Long = 0L

    private val isSetupComplete = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val activeParagraphIndex = AtomicInteger(-1)
    private var activeParagraphText: String = ""
    private val utteranceGenerationId = AtomicInteger(0)
    private val reconnectAttempts = AtomicInteger(0)

    private var currentApiKey: String = ""
    private var currentVoiceName: String = "Sulafat"
    private var currentLanguageCode: String = "pt-BR"
    private var pendingParagraphToSpeak: Pair<Int, String>? = null
    private var completionMonitorJob: Job? = null
    private var reconnectJob: Job? = null

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

        track.play()
        audioTrack = track
        return track
    }

    fun connectAndSpeakParagraph(
        apiKey: String,
        voiceName: String,
        languageBcp47: String,
        paragraphIndex: Int,
        paragraphText: String
    ) {
        if (apiKey.isBlank()) {
            onError("Informe sua chave de API do Gemini nas configurações.")
            return
        }

        reconnectJob?.cancel()
        isPlaying.set(true)
        activeParagraphIndex.set(paragraphIndex)
        activeParagraphText = paragraphText
        val genId = utteranceGenerationId.incrementAndGet()

        flushLocalAudioBuffer()

        val sessionExpired = (System.currentTimeMillis() - sessionConnectedAtMs) > SESSION_MAX_AGE_MS
        val configChanged = apiKey != currentApiKey || voiceName != currentVoiceName || languageBcp47 != currentLanguageCode

        if (webSocket == null || !isSetupComplete.get() || sessionExpired || configChanged) {
            currentApiKey = apiKey
            currentVoiceName = voiceName
            currentLanguageCode = languageBcp47
            pendingParagraphToSpeak = paragraphIndex to paragraphText
            openNewWebSocketSession()
        } else {
            sendSpeakTurn(paragraphIndex, paragraphText, genId)
        }
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
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                if (ws !== webSocket) return
                handleServerMessage(bytes.utf8())
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

    /**
     * Caso o sistema operacional ou a rede derrube momentaneamente a conexão
     * (ex.: "Software caused connection abort" na transição de rede ou bloqueio de tela),
     * reconecta automaticamente em segundo plano e retoma o parágrafo atual sem interromper a experiência.
     */
    private fun handleUnexpectedDisconnect(reason: String) {
        if (!isPlaying.get()) {
            onConnectionStatusChanged("Desconectado")
            return
        }

        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt <= MAX_AUTO_RECONNECT_ATTEMPTS) {
            val targetIdx = activeParagraphIndex.get()
            val targetText = activeParagraphText
            onConnectionStatusChanged("Reconectando automaticamente ($attempt/$MAX_AUTO_RECONNECT_ATTEMPTS)...")
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(1200L * attempt)
                if (isPlaying.get() && targetIdx >= 0 && targetText.isNotEmpty()) {
                    pendingParagraphToSpeak = targetIdx to targetText
                    openNewWebSocketSession()
                }
            }
        } else {
            reconnectAttempts.set(0)
            onConnectionStatusChanged("Desconectado")
            onError("Erro na conexão Live API após várias tentativas: $reason")
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
            1. Leia APENAS e FIELMENTE o trecho enviado pelo usuário, palavra por palavra, do início ao fim.
            2. NUNCA adicione introduções ("Claro, vou ler", "Aqui está"), comentários, resumos ou encerramentos.
            3. Use entonação envolvente de contador de histórias (storyteller): dê vida aos diálogos, faça pausas dramáticas naturais na pontuação e transmita a emoção e o suspense da cena.
            4. Mesmo que o texto escrito contenha grafia de Portugal (ex: "facto", "exacto", "estava a olhar"), pronuncie com sotaque e fonética natural do Português Brasileiro (pt-BR).
            """.trimIndent()
        } else {
            """
            You are a professional audiobook storyteller.
            Read ONLY the exact text provided by the user, verbatim from start to finish, with rich storytelling emotion, natural pacing, and distinct character cadence. Never add introductory or concluding remarks.
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

    private fun sendSpeakTurn(paragraphIndex: Int, paragraphText: String, genId: Int) {
        val ws = webSocket ?: return
        onConnectionStatusChanged("Narrando parágrafo #${paragraphIndex + 1}")
        onParagraphStarted(paragraphIndex)

        val promptText = "NARRE LITERALMENTE ESTE TRECHO:\n\n$paragraphText"
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

    private fun handleServerMessage(rawJson: String) {
        val expectedGenId = utteranceGenerationId.get()
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                isSetupComplete.set(true)
                reconnectAttempts.set(0)
                onConnectionStatusChanged("Conectado (Gemini 3.8 Live)")
                val pending = pendingParagraphToSpeak
                pendingParagraphToSpeak = null
                if (pending != null && isPlaying.get()) {
                    sendSpeakTurn(pending.first, pending.second, expectedGenId)
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
                                writePcmToAudioTrack(pcmBytes, expectedGenId)
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
                val finishedIdx = activeParagraphIndex.get()
                completionMonitorJob?.cancel()
                completionMonitorJob = scope.launch {
                    delay(250)
                    if (isPlaying.get() && expectedGenId == utteranceGenerationId.get()) {
                        onParagraphFinished(finishedIdx)
                    }
                }
            }
        } catch (e: Exception) {
            onError("Erro ao processar resposta do Gemini 3.8 Live: ${e.localizedMessage}")
        }
    }

    private fun writePcmToAudioTrack(pcmBytes: ByteArray, genId: Int) {
        if (!isPlaying.get() || genId != utteranceGenerationId.get()) return
        val track = ensureAudioTrack()
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        track.write(pcmBytes, 0, pcmBytes.size, AudioTrack.WRITE_BLOCKING)
    }

    fun pausePlayback() {
        isPlaying.set(false)
        reconnectAttempts.set(0)
        reconnectJob?.cancel()
        utteranceGenerationId.incrementAndGet()
        completionMonitorJob?.cancel()
        flushLocalAudioBuffer()
        onConnectionStatusChanged("Pausado")
    }

    private fun flushLocalAudioBuffer() {
        try {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
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
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.close(1000, "App closed")
        audioTrack?.release()
        audioTrack = null
    }
}
