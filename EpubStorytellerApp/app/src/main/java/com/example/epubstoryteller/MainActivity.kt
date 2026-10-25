package com.example.epubstoryteller

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.epubstoryteller.model.EpubChapter
import com.example.epubstoryteller.model.EpubParagraph
import com.example.epubstoryteller.model.VoiceOption
import com.example.epubstoryteller.ui.ReaderUiState
import com.example.epubstoryteller.ui.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppStage {
    SPLASH,
    API_KEY_SETUP,
    READER
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantém a tela ativa quando o app está aberto
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFD4AF37), // Dourado literário clássico
                    secondary = Color(0xFF8AB4F8),
                    background = Color(0xFF121318),
                    surface = Color(0xFF1E2028)
                )
            ) {
                val readerViewModel: ReaderViewModel = viewModel()
                val uiState by readerViewModel.uiState.collectAsState()

                var splashFinished by rememberSaveable { mutableStateOf(false) }
                var userSkippedApiKeySetup by rememberSaveable { mutableStateOf(false) }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                val openEpubLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        readerViewModel.loadExternalEpub(uri)
                    }
                }

                val currentStage = when {
                    !splashFinished -> AppStage.SPLASH
                    uiState.apiKey.isBlank() && !userSkippedApiKeySetup -> AppStage.API_KEY_SETUP
                    else -> AppStage.READER
                }

                Crossfade(
                    targetState = currentStage,
                    animationSpec = tween(durationMillis = 450),
                    label = "AppStageTransition"
                ) { stage ->
                    when (stage) {
                        AppStage.SPLASH -> {
                            AppSplashScreen(
                                onSplashFinished = { splashFinished = true }
                            )
                        }

                        AppStage.API_KEY_SETUP -> {
                            ApiKeyOnboardingScreen(
                                initialApiKey = uiState.apiKey,
                                initialVoice = uiState.selectedVoiceName,
                                availableVoices = uiState.availableVoices,
                                onSaveAndContinue = { apiKey, voice ->
                                    readerViewModel.updateApiKeyAndVoice(apiKey, voice)
                                    userSkippedApiKeySetup = true
                                },
                                onSkipForNow = {
                                    userSkippedApiKeySetup = true
                                }
                            )
                        }

                        AppStage.READER -> {
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
                                onLoadClassicBook = readerViewModel::loadBundledClassicEpub,
                                onSaveSettings = readerViewModel::updateApiKeyAndVoice,
                                onSearchQueryChange = readerViewModel::updateSearchQuery,
                                onDismissError = readerViewModel::clearError
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSplashScreen(
    onSplashFinished: () -> Unit
) {
    var startAnim by remember { mutableStateOf(false) }

    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "SplashAlpha"
    )
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnim) 1f else 0.85f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "SplashScale"
    )

    LaunchedEffect(Unit) {
        startAnim = true
        delay(2000)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0E1017),
                        Color(0xFF181C28),
                        Color(0xFF121318)
                    )
                )
            )
            .clickable { onSplashFinished() }
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .alpha(alphaAnim)
                .scale(scaleAnim)
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF252218))
                    .border(2.dp, Color(0xFFD4AF37), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoStories,
                    contentDescription = "AI Bookreader Logo",
                    tint = Color(0xFFD4AF37),
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "AI Bookreader",
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFFFFF8E7),
                letterSpacing = 0.8.sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Leitor de Livros EPUB em Voz Alta",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFD4AF37),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Surface(
                color = Color(0xFF1E2433),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = Color(0xFF8AB4F8),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Gemini 3.8 Live • Storyteller pt-BR",
                        fontSize = 12.sp,
                        color = Color(0xFFD0D7E8),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(36.dp))

            CircularProgressIndicator(
                color = Color(0xFFD4AF37),
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = "Toque em qualquer lugar para avançar",
            fontSize = 12.sp,
            color = Color(0xFF7D8498),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .alpha(alphaAnim)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyOnboardingScreen(
    initialApiKey: String,
    initialVoice: String,
    availableVoices: List<VoiceOption>,
    onSaveAndContinue: (String, String) -> Unit,
    onSkipForNow: () -> Unit
) {
    var apiKey by rememberSaveable { mutableStateOf(initialApiKey) }
    var selectedVoice by rememberSaveable { mutableStateOf(initialVoice.ifBlank { "Sulafat" }) }
    var showApiKeyPlain by rememberSaveable { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = Color(0xFFD4AF37),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Configurar Chave de API",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(
                text = "Bem-vindo ao AI Bookreader!",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFF8E7)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Para ler seus livros EPUB em voz alta com entonação de contador de histórias em Português do Brasil (Gemini 3.8 Live), você precisa configurar sua chave de API do Google AI Studio.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = Color(0xFFC5CAD8)
            )

            Spacer(Modifier.height(16.dp))

            // Card com o passo a passo detalhado de como criar uma chave API
            ApiKeyInstructionsCard(
                onOpenGoogleAiStudio = {
                    uriHandler.openUri("https://aistudio.google.com/app/apikey")
                }
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Sua Chave de API (Google AI Studio):",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFFF8E7)
            )
            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    validationError = null
                },
                label = { Text("Cole sua chave aqui (ex: AIzaSy...)") },
                singleLine = true,
                visualTransformation = if (showApiKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKeyPlain = !showApiKeyPlain }) {
                        Icon(
                            imageVector = if (showApiKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Mostrar ou ocultar chave"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text?.trim().orEmpty()
                        if (clipText.isNotEmpty()) {
                            apiKey = clipText
                            validationError = null
                        } else {
                            validationError = "A área de transferência está vazia."
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Colar da Área de Transferência", fontSize = 12.sp)
                }
            }

            if (validationError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = validationError!!,
                    color = Color(0xFFFF8A80),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Escolha a Voz do Narrador (Storyteller pt-BR):",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFFF8E7)
            )
            Spacer(Modifier.height(8.dp))

            availableVoices.forEach { voice ->
                val isSelected = selectedVoice.equals(voice.id, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF2D291B) else Color(0xFF1A1C24))
                        .border(
                            width = if (isSelected) 1.dp else 0.dp,
                            color = if (isSelected) Color(0xFFD4AF37) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedVoice = voice.id }
                        .padding(10.dp),
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

            Spacer(Modifier.height(22.dp))

            Button(
                onClick = {
                    val trimmed = apiKey.trim()
                    if (trimmed.isBlank()) {
                        validationError = "Cole sua chave de API acima para salvar, ou toque em 'Configurar depois'."
                    } else {
                        onSaveAndContinue(trimmed, selectedVoice)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Salvar Chave e Abrir Leitor",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = onSkipForNow,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = "Configurar depois (apenas abrir o leitor de EPUB)",
                    color = Color(0xFF9EA4B6),
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ApiKeyInstructionsCard(
    onOpenGoogleAiStudio: () -> Unit
) {
    Surface(
        color = Color(0xFF1A2130),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF2E3B56), RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = null,
                    tint = Color(0xFF8AB4F8),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Como criar sua Chave de API (Passo a Passo)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF8AB4F8)
                )
            }

            Spacer(Modifier.height(10.dp))

            InstructionStepItem(
                number = "1",
                text = "Toque no botão \"Abrir Google AI Studio\" abaixo para abrir a página oficial de chaves do Google."
            )
            InstructionStepItem(
                number = "2",
                text = "Faça login com a sua conta Google (caso ainda não esteja conectado)."
            )
            InstructionStepItem(
                number = "3",
                text = "Toque no botão azul \"Create API key\" (Criar chave de API)."
            )
            InstructionStepItem(
                number = "4",
                text = "Copie o código gerado (ele começa com \"AIza...\"), volte para este aplicativo e cole no campo abaixo."
            )

            Spacer(Modifier.height(12.dp))

            FilledTonalButton(
                onClick = onOpenGoogleAiStudio,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Abrir Google AI Studio (aistudio.google.com)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun InstructionStepItem(number: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color(0xFF2B3752)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD4AF37)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = Color(0xFFDCE1EE),
            modifier = Modifier.weight(1f)
        )
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
    onLoadClassicBook: () -> Unit,
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
                            text = uiState.book?.title ?: "AI Bookreader",
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
                        } else {
                            Text(
                                text = "Voz: ${uiState.selectedVoiceName} • Gemini 3.8 Live",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                                color = Color(0xFFB0B6C6)
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.book != null) {
                        IconButton(onClick = { showChaptersSheet = true }) {
                            Icon(Icons.Default.List, contentDescription = "Capítulos")
                        }
                        IconButton(onClick = { showJumpDialog = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Escolher Ponto / Buscar")
                        }
                    }
                    IconButton(onClick = onOpenExternalBook) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Abrir arquivo EPUB")
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
            if (uiState.book != null) {
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
                if (book == null) {
                    EmptyBookPlaceholder(
                        errorMessage = uiState.errorMessage,
                        onOpenExternalBook = onOpenExternalBook,
                        onLoadClassicBook = onLoadClassicBook,
                        onOpenApiKeySettings = { showSettingsDialog = true },
                        isApiKeyConfigured = uiState.apiKey.isNotBlank()
                    )
                } else {
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
private fun EmptyBookPlaceholder(
    errorMessage: String?,
    onOpenExternalBook: () -> Unit,
    onLoadClassicBook: () -> Unit,
    onOpenApiKeySettings: () -> Unit,
    isApiKeyConfigured: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.MenuBook,
            contentDescription = null,
            tint = Color(0xFFD4AF37),
            modifier = Modifier.size(68.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Abra um Livro EPUB para Começar",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFF8E7),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Escolha qualquer arquivo .epub salvo no seu celular ou tablet. O aplicativo memoriza automaticamente o livro e o parágrafo onde você parou.",
            fontSize = 14.sp,
            color = Color(0xFFB0B6C6),
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        if (errorMessage != null) {
            Spacer(Modifier.height(14.dp))
            Surface(
                color = Color(0xFF5A1D1D),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage,
                    color = Color(0xFFFFDAD6),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onOpenExternalBook,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Escolher Arquivo EPUB no Celular",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onLoadClassicBook,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.AutoStories, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Abrir Clássico: O Retrato de Dorian Gray",
                fontSize = 13.sp
            )
        }

        if (!isApiKeyConfigured) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenApiKeySettings) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = Color(0xFFFF8A80),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Configurar Chave de API do Gemini (veja instruções)",
                    color = Color(0xFFFF8A80),
                    fontSize = 13.sp
                )
            }
        }
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
                    label = { Text("Buscar palavra ou frase no livro") },
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
    var showInstructions by remember { mutableStateOf(currentApiKey.isBlank()) }

    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemini 3.8 Live • API Key & Voz") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                TextButton(
                    onClick = { showInstructions = !showInstructions },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (showInstructions) "Ocultar instruções de como criar a chave" else "Como criar uma chave de API gratuita?",
                        fontSize = 12.sp
                    )
                }

                if (showInstructions) {
                    Spacer(Modifier.height(4.dp))
                    ApiKeyInstructionsCard(
                        onOpenGoogleAiStudio = {
                            uriHandler.openUri("https://aistudio.google.com/app/apikey")
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Chave de API (Google AI Studio)") },
                    singleLine = true,
                    visualTransformation = if (showApiKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (clip.isNotEmpty()) apiKey = clip
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "Colar chave"
                                )
                            }
                            IconButton(onClick = { showApiKeyPlain = !showApiKeyPlain }) {
                                Icon(
                                    imageVector = if (showApiKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Mostrar/Ocultar chave"
                                )
                            }
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

                availableVoices.forEach { voice ->
                    val isSelected = selectedVoice.equals(voice.id, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) Color(0xFF2D291B) else Color.Transparent)
                            .clickable { selectedVoice = voice.id }
                            .padding(6.dp),
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

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = selectedVoice,
                    onValueChange = { selectedVoice = it },
                    label = { Text("Ou digite um ID de voz (ex: Sulafat, Charon)") },
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
