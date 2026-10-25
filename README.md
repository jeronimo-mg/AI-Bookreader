# 📚 AI Bookreader — Leitor de Livros EPUB em Voz Alta com Gemini 3.8 Live

Leitor de arquivos **EPUB** para **Android (Kotlin + Jetpack Compose)** e **Web/PWA (HTML5 Standalone)** que narra livros em voz alta utilizando a API em tempo real **`gemini-3.8-live`** via WebSocket (`BidiGenerateContent`), com atuação estilo **Storyteller** em **Português Brasileiro (`pt-BR`)**, memorização automática de progresso, reprodução em segundo plano / tela desligada e seleção livre do ponto de leitura — **inclusive durante a fala**.

---

## ✨ Funcionalidades Principais

- **🎬 Tela de Abertura & Configuração Guiada de API Key**:
  - Tela de abertura animada no aplicativo Android seguida automaticamente por uma tela dedicada de configuração da Chave de API (quando ainda não configurada), com instruções passo a passo de como criar sua chave no Google AI Studio e atalho para colar da área de transferência.
- **🎙️ Narração em Tempo Real com `gemini-3.8-live`**:
  - Conexão WebSocket direta ao endpoint `BidiGenerateContent` (`models/gemini-3.8-live`) usando sua chave de API do Google AI Studio.
  - Streaming de áudio nativo em **PCM 16-bit, 24 kHz, mono** reproduzido com baixa latência via `AudioTrack` (no Android) ou `AudioContext` (no Web App), aguardando a drenagem completa do buffer físico (`playbackHeadPosition`) entre parágrafos.
- **🇧🇷 Estilo Storyteller em Português Brasileiro (`pt-BR`)**:
  - Configuração de voz via `speechConfig` (`languageCode: "pt-BR"`, `prebuiltVoiceConfig.voiceName`) combinada com instruções de sistema (`systemInstruction`) para atuação dramática de contador de histórias.
  - Suporte às vozes de estúdio do Gemini 3.8 Live (**`Sulafat`**, **`Charon`**, **`Vindemiatrix`**, **`Sadaltager`**, **`Aoede`**, **`Kore`**, **`Puck`**) e consulta dinâmica à **Extended Voice Library**.
- **🔒 Funciona com a Tela Desligada ou em Segundo Plano**:
  - Serviço em primeiro plano (`StorytellerPlaybackService` com `mediaPlayback`), `PARTIAL_WAKE_LOCK` e `WifiLock` para continuar narrando mesmo quando você bloqueia a tela ou alterna para outros aplicativos.
- **💾 Memorização Automática do Livro e de Onde Parou**:
  - Salva automaticamente o último arquivo EPUB aberto pelo usuário e o índice do parágrafo atual a cada avanço de parágrafo ou mudança manual (`SharedPreferences` no Android / `localStorage` no Web App).
- **🎯 Escolha do Ponto de Leitura (Mesmo Durante a Fala)**:
  - Toque em **qualquer parágrafo** na tela (ou no botão **▶ Ler daqui**), escolha um capítulo em **📑 Capítulos**, arraste a **barra de progresso** ou use a **busca por palavra/número do parágrafo**.

---

## 📁 Estrutura do Repositório

```text
.
├── leitor_epub_gemini_live.html          # Versão Web/PWA pronta para abrir direto no navegador (PC ou Android)
├── .github/workflows/build-apk.yml       # GitHub Actions: compila o APK Android na nuvem automaticamente
└── EpubStorytellerApp/                   # Projeto Android nativo (Kotlin + Jetpack Compose)
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── assets/
    │       │   └── O_Retrato_de_Dorian_Gray.epub
    │       └── java/com/example/epubstoryteller/
    │           ├── MainActivity.kt
    │           ├── data/ReadingProgressStore.kt
    │           ├── epub/EpubParser.kt
    │           ├── model/EpubModels.kt
    │           ├── network/GeminiLiveStorytellerClient.kt
    │           ├── service/StorytellerPlaybackService.kt
    │           └── ui/ReaderViewModel.kt
    ├── build.gradle.kts
    ├── gradle.properties
    └── settings.gradle.kts
```
