package com.example.epubstoryteller

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.epubstoryteller.model.EpubChapter
import com.example.epubstoryteller.model.EpubParagraph
import com.example.epubstoryteller.model.VoiceOption
import com.example.epubstoryteller.ui.ReaderUiState
import com.example.epubstoryteller.ui.ReaderViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantém a tela/CPU ativa durante a narração
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD4AF37), // Dourado inspirado em O Código Da Vinci
                    secondary = Color(0xFF8AB4F8),
                    background = Color(0xFF121318),
                    surface = Color(0xFF1E2028)
                )
            ) {
                val readerViewModel: ReaderViewModel = viewModel()
                val uiState by readerViewModel.uiState.collectAsState()

                val openEpubLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        readerViewModel.loadExternalEpub(uri)
                    }
                }

                EpubStorytellerScreen(
                    uiState = uiState,
                    onTogglePlayPause = readerViewModel::togglePlayPause,
                    onSelectParagraph = { index, forcePlay ->
                        readerViewModel.selectReadingPoint(index, forcePlay)
                    },
                    onSkipRelative = readerViewModel::skipRelative,
                    onOpenExternalBook = {
                        openEpubLauncher.launch(arrayOf("application/epub+zip", "application/octet-stream"))
                    },
                    onSaveSettings = readerViewModel::updateApiKeyAndVoice,
                    onSearchQueryChange = readerViewModel::updateSearchQuery,
                    onDismissError = readerViewModel::clearError
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpubStorytellerScreen(
    uiState: ReaderUiState,
    onTogglePlayPause: () -> Unit,
    onSelectParagraph: (Int, Boolean) -> Unit,
    onSkipRelative: (Int) -> Unit,
    onOpenExternalBook: () -> Unit,
    onSaveSettings: (String, String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onDismissError: () -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showChaptersSheet by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var autoFollowSpeech by remember { mutableStateOf(true) }

    // Rola automaticamente para o parágrafo ativo quando ele muda durante a leitura
    LaunchedEffect(uiState.currentParagraphIndex, uiState.book?.bookId) {
        if (autoFollowSpeech && uiState.book != null && uiState.currentParagraphIndex >= 0) {
            listState.animateScrollToItem(uiState.currentParagraphIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.book?.title ?: "Carregando EPUB...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        val book = uiState.book
                        if (book != null) {
                            val currentChapter = book.chapters.lastOrNull {
                                it.startParagraphIndex <= uiState.currentParagraphIndex
                            }?.title ?: ""
                            Text(
                                text = "$currentChapter • Idioma: ${book.detectedLanguageBcp47} • Voz: ${uiState.selectedVoiceName}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                                color = Color(0xFFB0B6C6)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showChaptersSheet = true }) {
                        Icon(Icons.Default.List, contentDescription = "Capítulos")
                    }
                    IconButton(onClick = { showJumpDialog = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Escolher Ponto / Buscar")
                    }
                    IconButton(onClick = onOpenExternalBook) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Abrir outro arquivo EPUB")
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Key,
                            contentDescription = "Configurar API Key e Voz",
                            tint = if (uiState.apiKey.isBlank()) Color(0xFFFF6B6B) else Color(0xFFD4AF37)
                        )
                    }
                }
            )
        },
        bottomBar = {
            PlayerControlBar(
                uiState = uiState,
                autoFollowSpeech = autoFollowSpeech,
                onToggleAutoFollow = {
                    autoFollowSpeech = !autoFollowSpeech
                    if (autoFollowSpeech && uiState.book != null) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(uiState.currentParagraphIndex)
                        }
                    }
                },
                onTogglePlayPause = onTogglePlayPause,
                onSkipRelative = onSkipRelative,
                onSeekToParagraph = { targetIdx ->
                    onSelectParagraph(targetIdx, uiState.isPlaying)
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (uiState.isLoadingBook) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                val book = uiState.book
                if (book != null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Banner informativo de memorização e escolha de ponto inicial
                        Surface(
                            color = Color(0xFF1A2436),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Toque em qualquer parágrafo para definir o ponto de leitura (mesmo durante a fala).",
                                    fontSize = 12.sp,
                                    color = Color(0xFF9AC2FF),
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(uiState.currentParagraphIndex)
                                        }
                                    }
                                ) {
                                    Text("Ir p/ Atual (#${uiState.currentParagraphIndex + 1})", fontSize = 12.sp)
                                }
                            }
                        }

                        if (uiState.errorMessage != null) {
                            Surface(
                                color = Color(0xFF5A1D1D),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB4AB))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = uiState.errorMessage,
                                        color = Color(0xFFFFDAD6),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { showSettingsDialog = true }) {
                                        Text("API Key")
                                    }
                                    IconButton(onClick = onDismissError) {
                                        Icon(Icons.Default.Close, contentDescription = "Fechar")
                                    }
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = book.paragraphs,
                                key = { it.globalIndex }
                            ) { paragraph ->
                                val isCurrent = paragraph.globalIndex == uiState.currentParagraphIndex
                                ParagraphItemCard(
                                    paragraph = paragraph,
                                    isCurrent = isCurrent,
                                    isPlaying = uiState.isPlaying && isCurrent,
                                    onSelectAndKeepState = {
                                        // Se já estiver lendo em voz alta, muda imediatamente a voz para este parágrafo;
                                        // se estiver pausado, posiciona o marcador aqui e salva.
                                        onSelectParagraph(paragraph.globalIndex, uiState.isPlaying)
                                    },
                                    onPlayImmediatelyFromHere = {
                                        // Botão dedicado para iniciar a leitura imediatamente a partir deste parágrafo
                                        onSelectParagraph(paragraph.globalIndex, true)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showChaptersSheet && uiState.book != null) {
        ChaptersModalSheet(
            chapters = uiState.book.chapters,
            currentParagraphIndex = uiState.currentParagraphIndex,
            onSelectChapter = { chapter ->
                showChaptersSheet = false
                onSelectParagraph(chapter.startParagraphIndex, uiState.isPlaying)
            },
            onDismiss = { showChaptersSheet = false }
        )
    }

    if (showJumpDialog && uiState.book != null) {
        SearchAndJumpDialog(
            paragraphs = uiState.book.paragraphs,
            currentParagraphIndex = uiState.currentParagraphIndex,
            searchQuery = uiState.searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onJumpToParagraph = { targetIndex, playNow ->
                showJumpDialog = false
                onSelectParagraph(targetIndex, playNow)
            },
            onDismiss = { showJumpDialog = false }
        )
    }

    if (showSettingsDialog) {
        ApiKeyAndVoiceDialog(
            currentApiKey = uiState.apiKey,
            currentVoice = uiState.selectedVoiceName,
            availableVoices = uiState.availableVoices,
            onSave = { key, voice ->
                onSaveSettings(key, voice)
                showSettingsDialog = false
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
private fun ParagraphItemCard(
    paragraph: EpubParagraph,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onSelectAndKeepState: () -> Unit,
    onPlayImmediatelyFromHere: () -> Unit
) {
    val backgroundColor = when {
        isCurrent -> Color(0xFF282518)
        paragraph.isHeading -> Color(0xFF191C24)
        else -> Color(0xFF1A1C22)
    }
    val borderColor = if (isCurrent) Color(0xFFD4AF37) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .border(
                width = if (isCurrent) 1.5.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onSelectAndKeepState() }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#${paragraph.globalIndex + 1} • ${paragraph.chapterTitle}",
                fontSize = 11.sp,
                color = if (isCurrent) Color(0xFFD4AF37) else Color(0xFF8A90A2),
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Text(
                        text = if (isPlaying) "NARRANDO AGORA" else "PONTO SALVO",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD4AF37),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                TextButton(
                    onClick = onPlayImmediatelyFromHere,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Ler a partir daqui",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text("Ler daqui", fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = paragraph.text,
            fontSize = if (paragraph.isHeading) 18.sp else 15.5.sp,
            fontWeight = if (paragraph.isHeading) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 23.sp,
            color = if (isCurrent) Color(0xFFFFF8E7) else Color(0xFFE3E5EC)
        )
    }
}

@Composable
private fun PlayerControlBar(
    uiState: ReaderUiState,
    autoFollowSpeech: Boolean,
    onToggleAutoFollow: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipRelative: (Int) -> Unit,
    onSeekToParagraph: (Int) -> Unit
) {
    val totalParagraphs = uiState.book?.paragraphs?.size ?: 1
    var sliderPosition by remember(uiState.currentParagraphIndex) {
        mutableFloatStateOf(uiState.currentParagraphIndex.toFloat())
    }

    Surface(
        color = Color(0xFF1E2028),
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (uiState.liveTranscript.isNotBlank()) {
                Text(
                    text = "🎙️ ${uiState.liveTranscript}",
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFFD4AF37),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Barra de progresso que permite arrastar para qualquer ponto do livro mesmo durante a fala
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${uiState.currentParagraphIndex + 1}",
                    fontSize = 11.sp,
                    color = Color(0xFFB0B6C6)
                )
                Slider(
                    value = sliderPosition.coerceIn(0f, (totalParagraphs - 1).coerceAtLeast(1).toFloat()),
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = {
                        onSeekToParagraph(sliderPosition.toInt())
                    },
                    valueRange = 0f..(totalParagraphs - 1).coerceAtLeast(1).toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = "$totalParagraphs",
                    fontSize = 11.sp,
                    color = Color(0xFFB0B6C6)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.connectionStatus,
                    fontSize = 11.sp,
                    color = Color(0xFF9EA4B6),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSkipRelative(-5) }) {
                        Icon(Icons.Default.FastRewind, contentDescription = "Voltar 5 parágrafos")
                    }
                    IconButton(onClick = { onSkipRelative(-1) }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Parágrafo anterior")
                    }

                    FilledIconButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pausar" else "Narrar com Gemini 3.8 Live"
                        )
                    }

                    IconButton(onClick = { onSkipRelative(1) }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Próximo parágrafo")
                    }
                    IconButton(onClick = { onSkipRelative(5) }) {
                        Icon(Icons.Default.FastForward, contentDescription = "Avançar 5 parágrafos")
                    }
                }

                IconButton(onClick = onToggleAutoFollow) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Auto-rolagem",
                        tint = if (autoFollowSpeech) Color(0xFFD4AF37) else Color.Gray
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChaptersModalSheet(
    chapters: List<EpubChapter>,
    currentParagraphIndex: Int,
    onSelectChapter: (EpubChapter) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Capítulos Detectados (${chapters.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxHeight(0.7f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(chapters) { chapter ->
                    val isCurrent = currentParagraphIndex in chapter.startParagraphIndex..chapter.endParagraphIndex
                    Surface(
                        color = if (isCurrent) Color(0xFF2D291B) else Color(0xFF252832),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectChapter(chapter) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chapter.title,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) Color(0xFFD4AF37) else Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Parágrafo #${chapter.startParagraphIndex + 1}",
                                fontSize = 12.sp,
                                color = Color(0xFF9EA4B6)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndJumpDialog(
    paragraphs: List<EpubParagraph>,
    currentParagraphIndex: Int,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onJumpToParagraph: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var paragraphNumberInput by remember { mutableStateOf((currentParagraphIndex + 1).toString()) }

    val matchingParagraphs = remember(searchQuery, paragraphs) {
        val q = searchQuery.trim()
        if (q.length < 2) emptyList()
        else paragraphs.filter { it.text.contains(q, ignoreCase = true) }.take(40)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escolher Ponto de Leitura") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "1. Pular diretamente para o número do parágrafo (1 a ${paragraphs.size}):",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = paragraphNumberInput,
                        onValueChange = { paragraphNumberInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Nº do Parágrafo") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val idx = (paragraphNumberInput.toIntOrNull() ?: 1) - 1
                            onJumpToParagraph(idx, true)
                        }
                    ) {
                        Text("Ler Daqui")
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "2. Ou buscar trecho no livro e iniciar dali:",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("Buscar palavra ou frase (ex: Saunière, Louvre)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matchingParagraphs) { p ->
                        Surface(
                            color = Color(0xFF262934),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJumpToParagraph(p.globalIndex, true) }
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "#${p.globalIndex + 1} (${p.chapterTitle})",
                                    fontSize = 11.sp,
                                    color = Color(0xFFD4AF37),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = p.text,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )
}

@Composable
private fun ApiKeyAndVoiceDialog(
    currentApiKey: String,
    currentVoice: String,
    availableVoices: List<VoiceOption>,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var selectedVoice by remember { mutableStateOf(currentVoice) }
    var showApiKeyPlain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini 3.8 Live • API Key & Voz") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Chave de API (Google AI Studio)") },
                    singleLine = true,
                    visualTransformation = if (showApiKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKeyPlain = !showApiKeyPlain }) {
                            Icon(
                                imageVector = if (showApiKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Mostrar/Ocultar chave"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Voz Storyteller (configurada com pt-BR nativo):",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(availableVoices) { voice ->
                        val isSelected = selectedVoice.equals(voice.id, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) Color(0xFF2D291B) else Color.Transparent)
                                .clickable { selectedVoice = voice.id }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedVoice = voice.id }
                            )
                            Column(modifier = Modifier.padding(start = 6.dp)) {
                                Text(text = voice.label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(text = voice.description, fontSize = 11.sp, color = Color(0xFFA0A6B8))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = selectedVoice,
                    onValueChange = { selectedVoice = it },
                    label = { Text("Ou digite um ID de voz (ex: Sulafat, Charon, voice_...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(apiKey, selectedVoice) }) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
