package com.example.epubstoryteller.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.epubstoryteller.data.ReadingProgressStore
import com.example.epubstoryteller.epub.EpubParser
import com.example.epubstoryteller.model.EpubBook
import com.example.epubstoryteller.model.StorytellerVoices
import com.example.epubstoryteller.model.VoiceOption
import com.example.epubstoryteller.network.GeminiLiveStorytellerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val isLoadingBook: Boolean = true,
    val book: EpubBook? = null,
    val currentParagraphIndex: Int = 0,
    val isPlaying: Boolean = false,
    val connectionStatus: String = "Pronto",
    val liveTranscript: String = "",
    val apiKey: String = "",
    val selectedVoiceName: String = "Sulafat",
    val availableVoices: List<VoiceOption> = StorytellerVoices.DEFAULT_VOICES,
    val resumedFromSavedState: Boolean = false,
    val errorMessage: String? = null,
    val searchQuery: String = ""
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val progressStore = ReadingProgressStore(application)
    private val epubParser = EpubParser()

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            apiKey = progressStore.getApiKey(),
            selectedVoiceName = progressStore.getVoiceName()
        )
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val liveClient = GeminiLiveStorytellerClient(
        onParagraphStarted = { index ->
            _uiState.update { it.copy(currentParagraphIndex = index, liveTranscript = "", errorMessage = null) }
        },
        onParagraphFinished = { finishedIndex ->
            // Avança automaticamente para o próximo parágrafo e salva o progresso
            val book = _uiState.value.book ?: return@GeminiLiveStorytellerClient
            val nextIndex = finishedIndex + 1
            if (nextIndex <= book.paragraphs.lastIndex && _uiState.value.isPlaying) {
                progressStore.saveBookPosition(book.bookId, nextIndex)
                _uiState.update { it.copy(currentParagraphIndex = nextIndex) }
                speakParagraphAt(nextIndex)
            } else {
                _uiState.update { it.copy(isPlaying = false, connectionStatus = "Fim do livro alcançado") }
            }
        },
        onTranscriptChunk = { chunk ->
            _uiState.update { state ->
                state.copy(liveTranscript = (state.liveTranscript + chunk).takeLast(320))
            }
        },
        onConnectionStatusChanged = { status ->
            _uiState.update { it.copy(connectionStatus = status) }
        },
        onError = { error ->
            _uiState.update { it.copy(errorMessage = error, isPlaying = false) }
        }
    )

    init {
        loadBundledSampleEpub()
        refreshExtendedVoicesIfKeyPresent()
    }

    fun loadBundledSampleEpub() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBook = true, errorMessage = null) }
            try {
                val parsedBook = withContext(Dispatchers.IO) {
                    getApplication<Application>().assets.open("O_Codigo_da_Vinci.epub").use { stream ->
                        epubParser.parse(stream, "O Codigo da Vinci - Dan Brown.epub")
                    }
                }
                applyLoadedBook(parsedBook)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        errorMessage = "Erro ao abrir EPUB de teste: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun loadExternalEpub(uri: Uri) {
        viewModelScope.launch {
            pause()
            _uiState.update { it.copy(isLoadingBook = true, errorMessage = null) }
            try {
                val parsedBook = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openInputStream(uri)?.use { stream ->
                        epubParser.parse(stream, uri.lastPathSegment ?: "livro.epub")
                    } ?: throw IllegalArgumentException("Não foi possível ler o arquivo selecionado")
                }
                applyLoadedBook(parsedBook)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        errorMessage = "Erro ao abrir EPUB: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    private fun applyLoadedBook(book: EpubBook) {
        val hadSaved = progressStore.hasSavedPosition(book.bookId)
        val savedIdx = progressStore.getSavedBookPosition(book.bookId)
            .coerceIn(0, (book.paragraphs.size - 1).coerceAtLeast(0))

        _uiState.update {
            it.copy(
                isLoadingBook = false,
                book = book,
                currentParagraphIndex = savedIdx,
                resumedFromSavedState = hadSaved && savedIdx > 0,
                connectionStatus = if (hadSaved && savedIdx > 0) {
                    "Posição restaurada no parágrafo #${savedIdx + 1}"
                } else {
                    "Livro carregado (${book.paragraphs.size} parágrafos)"
                }
            )
        }
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            pause()
        } else {
            playFromCurrentPosition()
        }
    }

    fun playFromCurrentPosition() {
        val state = _uiState.value
        val book = state.book ?: return
        if (state.apiKey.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Configure sua Gemini API Key (ícone de chave no topo) antes de iniciar a leitura.") }
            return
        }
        _uiState.update { it.copy(isPlaying = true, errorMessage = null) }
        progressStore.saveBookPosition(book.bookId, state.currentParagraphIndex)
        speakParagraphAt(state.currentParagraphIndex)
    }

    /**
     * Permite ao usuário escolher um novo ponto inicial de leitura a qualquer momento,
     * INCLUSIVE durante a leitura em voz alta:
     * - Salva imediatamente a nova posição no armazenamento persistente.
     * - Se já estiver lendo (ou se `startReadingImmediately == true`), interrompe o áudio atual na hora
     *   e começa a narrar a partir do parágrafo escolhido.
     */
    fun selectReadingPoint(paragraphIndex: Int, startReadingImmediately: Boolean = _uiState.value.isPlaying) {
        val book = _uiState.value.book ?: return
        val clampedIndex = paragraphIndex.coerceIn(0, (book.paragraphs.size - 1).coerceAtLeast(0))

        progressStore.saveBookPosition(book.bookId, clampedIndex)
        _uiState.update {
            it.copy(
                currentParagraphIndex = clampedIndex,
                resumedFromSavedState = false,
                liveTranscript = ""
            )
        }

        if (startReadingImmediately) {
            if (_uiState.value.apiKey.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Configure sua Gemini API Key para iniciar a narração.") }
                return
            }
            _uiState.update { it.copy(isPlaying = true, errorMessage = null) }
            speakParagraphAt(clampedIndex)
        }
    }

    fun skipRelative(offset: Int) {
        val current = _uiState.value.currentParagraphIndex
        selectReadingPoint(current + offset, startReadingImmediately = _uiState.value.isPlaying)
    }

    fun pause() {
        liveClient.pausePlayback()
        _uiState.update { it.copy(isPlaying = false) }
    }

    private fun speakParagraphAt(index: Int) {
        val state = _uiState.value
        val book = state.book ?: return
        val paragraph = book.paragraphs.getOrNull(index) ?: return

        liveClient.connectAndSpeakParagraph(
            apiKey = state.apiKey,
            voiceName = state.selectedVoiceName,
            languageBcp47 = book.detectedLanguageBcp47,
            paragraphIndex = index,
            paragraphText = paragraph.text
        )
    }

    fun updateApiKeyAndVoice(newApiKey: String, newVoiceName: String) {
        val trimmedKey = newApiKey.trim()
        val trimmedVoice = newVoiceName.trim().ifBlank { "Sulafat" }
        progressStore.saveApiKey(trimmedKey)
        progressStore.saveVoiceName(trimmedVoice)

        val wasPlaying = _uiState.value.isPlaying
        _uiState.update {
            it.copy(
                apiKey = trimmedKey,
                selectedVoiceName = trimmedVoice,
                errorMessage = null
            )
        }
        refreshExtendedVoicesIfKeyPresent()

        // Se estava lendo quando trocou a voz, reconecta e continua do parágrafo atual com a nova voz
        if (wasPlaying) {
            speakParagraphAt(_uiState.value.currentParagraphIndex)
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun refreshExtendedVoicesIfKeyPresent() {
        val key = _uiState.value.apiKey
        if (key.isBlank()) return
        viewModelScope.launch {
            val extended = liveClient.fetchExtendedPtBrStorytellerVoices(key)
            if (extended.isNotEmpty()) {
                val combined = (StorytellerVoices.DEFAULT_VOICES + extended).distinctBy { it.id }
                _uiState.update { it.copy(availableVoices = combined) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        liveClient.release()
    }
}
