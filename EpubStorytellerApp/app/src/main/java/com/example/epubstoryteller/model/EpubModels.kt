package com.example.epubstoryteller.model

data class EpubParagraph(
    val globalIndex: Int,
    val chapterIndex: Int,
    val chapterTitle: String,
    val text: String,
    val isHeading: Boolean = false
)

data class EpubChapter(
    val index: Int,
    val title: String,
    val startParagraphIndex: Int,
    val endParagraphIndex: Int
)

data class EpubBook(
    val bookId: String,
    val title: String,
    val author: String,
    val rawLanguageTag: String,
    val detectedLanguageBcp47: String, // e.g. "pt-BR"
    val chapters: List<EpubChapter>,
    val paragraphs: List<EpubParagraph>
)

data class VoiceOption(
    val id: String,
    val label: String,
    val description: String
)

object StorytellerVoices {
    // Vozes de estúdio nativas suportadas pelo Gemini 3.8 Live (speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName)
    // mais adequadas para narração de livros (Storyteller)
    val DEFAULT_VOICES = listOf(
        VoiceOption("Sulafat", "Sulafat (Storyteller Calorosa / Envolvente)", "Timbre quente e expressivo, excelente para romances e suspense"),
        VoiceOption("Charon", "Charon (Narrador Grave / Misterioso)", "Voz profunda, firme e imersiva, ideal para romances de mistério e suspense"),
        VoiceOption("Vindemiatrix", "Vindemiatrix (Narradora Suave / Contadora de Histórias)", "Entonação gentil e cadenciada para leitura longa"),
        VoiceOption("Sadaltager", "Sadaltager (Narrador Erudito / Clássico)", "Tom articulado e dramático"),
        VoiceOption("Aoede", "Aoede (Narradora Natural / Fluida)", "Leitura fluida e dinâmica"),
        VoiceOption("Kore", "Kore (Narradora Firme / Clara)", "Alta clareza e presença vocal"),
        VoiceOption("Puck", "Puck (Narrador Expressivo / Vibrante)", "Grande alcance dinâmico para diálogos")
    )
}
