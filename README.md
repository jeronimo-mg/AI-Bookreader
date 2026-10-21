# 📚 EPUB Storyteller — Leitor de Livros em Voz Alta com Gemini 3.8 Live

Leitor de arquivos **EPUB** para **Android (Kotlin + Jetpack Compose)** e **Web/PWA (HTML5 Standalone)** que narra livros em voz alta utilizando a API em tempo real **`gemini-3.8-live`** via WebSocket (`BidiGenerateContent`), com atuação estilo **Storyteller** em **Português Brasileiro (`pt-BR`)**, memorização automática de progresso e seleção livre do ponto de leitura — **inclusive durante a fala**.

---

## ✨ Funcionalidades Principais

- **🎙️ Narração em Tempo Real com `gemini-3.8-live`**:
  - Conexão WebSocket direta ao endpoint `BidiGenerateContent` (`models/gemini-3.8-live`) usando sua chave de API do Google AI Studio.
  - Streaming de áudio nativo em **PCM 16-bit, 24 kHz, mono** reproduzido com baixa latência via `AudioTrack` (no Android) ou `AudioContext` (no Web App).
  - Renovação preventiva automática da sessão WebSocket antes do limite de 15 minutos da Live API.
- **🇧🇷 Estilo Storyteller em Português Brasileiro (`pt-BR`)**:
  - Configuração de voz via `speechConfig` (`languageCode: "pt-BR"`, `prebuiltVoiceConfig.voiceName`) combinada com instruções de sistema (`systemInstruction`) para atuação dramática de contador de histórias.
  - Suporte às vozes de estúdio do Gemini 3.8 Live (**`Sulafat`**, **`Charon`**, **`Vindemiatrix`**, **`Sadaltager`**, **`Aoede`**, **`Kore`**, **`Puck`**) e consulta dinâmica à **Extended Voice Library** (`GET /v1beta/voices?language_code=pt-BR&context=Audiobook`).
  - Detecção automática do idioma do livro: mesmo em edições com metadado `<dc:language>UND</dc:language>` ou grafia lusitana (como a edição Bertrand de *O Código Da Vinci*), o app força a pronúncia e a cadência naturais do **Português do Brasil**.
- **💾 Memorização Automática de Onde Parou**:
  - Salva automaticamente o índice do parágrafo atual a cada avanço de parágrafo ou mudança manual (`SharedPreferences` no Android / `localStorage` no Web App), vinculado ao hash único do livro (`bookId`).
  - Ao reabrir o app, restaura exatamente o ponto onde a leitura parou.
- **🎯 Escolha do Ponto de Leitura (Mesmo Durante a Fala)**:
  - Toque em **qualquer parágrafo** na tela (ou no botão **▶ Ler daqui**), escolha um capítulo em **📑 Capítulos**, arraste a **barra de progresso** ou use a **busca por palavra/número do parágrafo**.
  - Se o Gemini estiver lendo em voz alta no momento do clique, o buffer de áudio local é limpo instantaneamente (`flush`) e um novo turno com `turnComplete: true` interrompe a geração anterior no servidor, iniciando a leitura do novo ponto na mesma hora.

---

## 📁 Estrutura do Repositório

```text
.
├── leitor_epub_gemini_live.html          # Versão Web/PWA pronta para abrir direto no navegador (PC ou Android)
├── O Codigo da Vinci - Dan Brown.epub    # Livro EPUB de teste
├── .github/workflows/build-apk.yml       # GitHub Actions: compila o APK Android na nuvem automaticamente
└── EpubStorytellerApp/                   # Projeto Android nativo (Kotlin + Jetpack Compose)
    ├── app/
    │   ├── build.gradle.kts
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── assets/
    │       │   └── O_Codigo_da_Vinci.epub
    │       └── java/com/example/epubstoryteller/
    │           ├── MainActivity.kt
    │           ├── data/ReadingProgressStore.kt
    │           ├── epub/EpubParser.kt
    │           ├── model/EpubModels.kt
    │           ├── network/GeminiLiveStorytellerClient.kt
    │           └── ui/ReaderViewModel.kt
    ├── build.gradle.kts
    ├── gradle.properties
    └── settings.gradle.kts
```

---

## 🚀 Como Usar e Testar (Sem Precisar do Android Studio)

### Opção 1: Gerar o APK Android automaticamente no GitHub (GitHub Actions)
1. Envie este repositório para o seu GitHub (veja os comandos na seção abaixo).
2. No GitHub, acesse a aba **Actions** do seu repositório.
3. O workflow **"Build Android APK (Sem Android Studio)"** será executado automaticamente (ou clique em **Run workflow**).
4. Ao finalizar (cerca de 2 a 3 minutos), role até a seção **Artifacts** da execução e baixe o arquivo **`EpubStoryteller-debug-apk`** (`app-debug.apk`).
5. Instale o `.apk` no seu celular Android, toque no ícone **🔑** no topo, insira sua **Gemini API Key** e inicie a leitura!

### Opção 2: Usar Imediatamente no Navegador do Celular ou PC (`leitor_epub_gemini_live.html`)
1. Abra o arquivo `leitor_epub_gemini_live.html` no navegador **Chrome** (no Android ou no computador).
2. O livro *O Código Da Vinci* (5.461 parágrafos e 104 capítulos) já vem pré-carregado, e você também pode abrir qualquer outro arquivo `.epub` clicando no botão **📂 EPUB**.
3. Clique em **🔑 API & Voz**, cole sua chave de API do [Google AI Studio](https://aistudio.google.com/) e escolha a voz desejada (`Sulafat`, `Charon`, `Vindemiatrix`, etc.).
4. Clique em **▶** na barra inferior ou toque em qualquer parágrafo para ouvir!

### Opção 3: Compilar Localmente no Android Studio
1. Abra a pasta `EpubStorytellerApp` no **Android Studio** (JDK 17 / Android SDK 34).
2. Clique em **Run 'app'** em um dispositivo Android físico ou emulador (API 26+).

---

## 📤 Como Enviar Este Código para um Novo Repositório no GitHub

O repositório Git já foi inicializado e preparado com o commit inicial. Basta criar um repositório vazio no [GitHub](https://github.com/new) e executar:

```bash
git remote add origin https://github.com/SEU_USUARIO/SEU_REPOSITORIO.git
git branch -M main
git push -u origin main
```
