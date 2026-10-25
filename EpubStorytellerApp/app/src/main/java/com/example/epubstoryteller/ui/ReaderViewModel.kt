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
import com.example.epubstoryteller.service.StorytellerPlaybackService
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
            syncForegroundService(isPlaying = true, paragraphIndex = index)
        },
        onParagraphFinished = { finishedIndex ->
            val book = _uiState.value.book ?: return@GeminiLiveStorytellerClient
            val nextIndex = finishedIndex + 1
            if (nextIndex <= book.paragraphs.lastIndex && _uiState.value.isPlaying) {
                progressStore.saveBookPosition(book.bookId, nextIndex)
                _uiState.update { it.copy(currentParagraphIndex = nextIndex) }
                speakParagraphAt(nextIndex, flushExistingAudio = false)
            } else {
                _uiState.update { it.copy(isPlaying = false, connectionStatus = "Fim do livro alcançado") }
                syncForegroundService(isPlaying = false, paragraphIndex = finishedIndex)
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
            syncForegroundService(isPlaying = false, paragraphIndex = _uiState.value.currentParagraphIndex)
        }
    )

    init {
        // Registra callbacks para os controles da notificação na tela de bloqueio / segundo plano
        StorytellerPlaybackService.onTogglePlayPauseCommand = { togglePlayPause() }
        StorytellerPlaybackService.onPrevParagraphCommand = { skipRelative(-1) }
        StorytellerPlaybackService.onNextParagraphCommand = { skipRelative(1) }

        restoreLastOpenedBookIfPresent()
        refreshExtendedVoicesIfKeyPresent()
    }

    private fun syncForegroundService(isPlaying: Boolean, paragraphIndex: Int) {
        val app = getApplication<Application>()
        val book = _uiState.value.book
        val paragraph = book?.paragraphs?.getOrNull(paragraphIndex)
        val title = book?.title ?: "EPUB Storyteller"
        val subtitle = if (paragraph != null) {
            "Parágrafo #${paragraphIndex + 1} • ${paragraph.chapterTitle}"
        } else {
            "Parágrafo #${paragraphIndex + 1}"
        }
        StorytellerPlaybackService.startOrUpdate(
            context = app,
            bookTitle = title,
            subtitle = subtitle,
            isPlaying = isPlaying
        )
    }

    private fun restoreLastOpenedBookIfPresent() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val cachedBookFile = java.io.File(app.filesDir, "last_user_book.epub")
            if (!cachedBookFile.exists() || cachedBookFile.length() == 0L) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        book = null,
                        connectionStatus = "Abra um arquivo EPUB para iniciar"
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isLoadingBook = true, errorMessage = null) }
            try {
                val savedFileName = progressStore.getLastBookFileName() ?: "livro.epub"
                val parsedBook = withContext(Dispatchers.IO) {
                    cachedBookFile.inputStream().use { stream ->
                        epubParser.parse(stream, savedFileName)
                    }
                }
                applyLoadedBook(parsedBook)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        book = null,
                        connectionStatus = "Abra um arquivo EPUB para iniciar"
                    )
                }
            }
        }
    }

    fun loadBundledClassicEpub() {
        viewModelScope.launch {
            pause()
            _uiState.update { it.copy(isLoadingBook = true, errorMessage = null) }
            try {
                val app = getApplication<Application>()
                val cachedBookFile = java.io.File(app.filesDir, "last_user_book.epub")
                val parsedBook = withContext(Dispatchers.IO) {
                    app.assets.open("O_Retrato_de_Dorian_Gray.epub").use { assetStream ->
                        cachedBookFile.outputStream().use { out ->
                            assetStream.copyTo(out)
                        }
                    }
                    progressStore.saveLastBookFileName("O Retrato de Dorian Gray - Oscar Wilde.epub")
                    cachedBookFile.inputStream().use { stream ->
                        epubParser.parse(stream, "O Retrato de Dorian Gray - Oscar Wilde.epub")
                    }
                }
                applyLoadedBook(parsedBook)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingBook = false,
                        errorMessage = "Erro ao abrir livro clássico: ${e.localizedMessage}"
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
                val app = getApplication<Application>()
                val cachedBookFile = java.io.File(app.filesDir, "last_user_book.epub")
                val fileName = uri.lastPathSegment ?: "livro.epub"
                val parsedBook = withContext(Dispatchers.IO) {
                    val resolver = app.contentResolver
                    resolver.openInputStream(uri)?.use { stream ->
                        cachedBookFile.outputStream().use { out ->
                            stream.copyTo(out)
                        }
                    } ?: throw IllegalArgumentException("Não foi possível ler o arquivo selecionado")
                    progressStore.saveLastBookFileName(fileName)
                    cachedBookFile.inputStream().use { stream ->
                        epubParser.parse(stream, fileName)
                    }
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
        syncForegroundService(isPlaying = true, paragraphIndex = state.currentParagraphIndex)
        speakParagraphAt(state.currentParagraphIndex)
    }

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
            syncForegroundService(isPlaying = true, paragraphIndex = clampedIndex)
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
        syncForegroundService(isPlaying = false, paragraphIndex = _uiState.value.currentParagraphIndex)
    }

    private fun speakParagraphAt(index: Int, flushExistingAudio: Boolean = true) {
        val state = _uiState.value
        val book = state.book ?: return
        val paragraph = book.paragraphs.getOrNull(index) ?: return

        liveClient.connectAndSpeakParagraph(
            apiKey = state.apiKey,
            voiceName = state.selectedVoiceName,
            languageBcp47 = book.detectedLanguageBcp47,
            paragraphIndex = index,
            paragraphText = paragraph.text,
            flushExistingAudio = flushExistingAudio
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
        StorytellerPlaybackService.stop(getApplication())
        liveClient.release()
    }
}
