package com.example.epubstoryteller.epub

import com.example.epubstoryteller.model.EpubBook
import com.example.epubstoryteller.model.EpubChapter
import com.example.epubstoryteller.model.EpubParagraph
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class EpubParser {

    // Reconhece apenas numerais reais (romanos, arábicos ou por extenso em português) após "CAPÍTULO",
    // evitando falsos positivos em frases do texto como "esse capítulo fantástico".
    private val chapterHeadingRegex = Regex(
        pattern = """(?:^|\s)(PRÓLOGO|PROLOGO|PREFÁCIO|PREFACIO|POSFÁCIO|POSFACIO|EPÍLOGO|EPILOGO|(?:CAPÍTULO|CAPITULO)\s+(?:\d+|[IVXLCDM]+|(?:UM|DOIS|TRÊS|TRES|QUATRO|CINCO|SEIS|SETE|OITO|NOVE|DEZ|ONZE|DOZE|TREZE|CATORZE|QUATORZE|QUINZE|DEZASSEIS|DEZESSEIS|DEZASSETE|DEZESSETE|DEZOITO|DEZANOVE|DEZENOVE|VINTE|TRINTA|QUARENTA|CINQUENTA|SESSENTA|SETENTA|OITENTA|NOVENTA|CEM|CENTO)(?:\s+E\s+(?:UM|DOIS|TRÊS|TRES|QUATRO|CINCO|SEIS|SETE|OITO|NOVE|DEZ|ONZE|DOZE|TREZE|CATORZE|QUATORZE|QUINZE|DEZASSEIS|DEZESSEIS|DEZASSETE|DEZESSETE|DEZOITO|DEZANOVE|DEZENOVE|VINTE|TRINTA|QUARENTA|CINQUENTA|SESSENTA|SETENTA|OITENTA|NOVENTA))*))\s*$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    fun parse(inputStream: InputStream, fallbackSourceName: String = "book.epub"): EpubBook {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val containerBytes = entries["META-INF/container.xml"]
            ?: throw IllegalArgumentException("Arquivo EPUB inválido: META-INF/container.xml não encontrado")
        val opfPath = parseContainerXml(String(containerBytes, Charsets.UTF_8))
        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""

        val opfBytes = entries[opfPath]
            ?: throw IllegalArgumentException("Arquivo OPF não encontrado em $opfPath")
        val opfMeta = parseOpf(String(opfBytes, Charsets.UTF_8))

        val rawParagraphs = mutableListOf<Pair<String, Boolean>>()

        for (idref in opfMeta.spineIdRefs) {
            val href = opfMeta.manifest[idref] ?: continue
            val fullPath = normalizePath(opfDir + href)
            val htmlBytes = entries[fullPath] ?: entries[href] ?: continue
            val html = String(htmlBytes, Charsets.UTF_8)
            val doc = Jsoup.parse(html)

            val elements = doc.select("h1, h2, h3, h4, p")
            for (el in elements) {
                val cleaned = el.text().replace(Regex("\\s+"), " ").trim()
                if (cleaned.length < 2) continue
                if (cleaned.startsWith("@page") || cleaned.equals("index", ignoreCase = true)) continue

                val isHeaderTag = el.tagName().startsWith("h", ignoreCase = true)
                splitEmbeddedChapterHeadings(cleaned, isHeaderTag, rawParagraphs)
            }
        }

        val chapters = mutableListOf<EpubChapter>()
        val finalParagraphs = mutableListOf<EpubParagraph>()

        var currentChapterTitle = "Início / Introdução"
        var currentChapterIdx = 0
        var chapterStartParaIdx = 0

        for ((idx, pair) in rawParagraphs.withIndex()) {
            val (text, isHeading) = pair
            if (isHeading && idx > chapterStartParaIdx) {
                chapters.add(
                    EpubChapter(
                        index = currentChapterIdx,
                        title = currentChapterTitle,
                        startParagraphIndex = chapterStartParaIdx,
                        endParagraphIndex = idx - 1
                    )
                )
                currentChapterIdx++
                currentChapterTitle = text
                chapterStartParaIdx = idx
            } else if (isHeading && idx == 0) {
                currentChapterTitle = text
            }

            finalParagraphs.add(
                EpubParagraph(
                    globalIndex = idx,
                    chapterIndex = currentChapterIdx,
                    chapterTitle = currentChapterTitle,
                    text = text,
                    isHeading = isHeading
                )
            )
        }

        if (finalParagraphs.isNotEmpty()) {
            chapters.add(
                EpubChapter(
                    index = currentChapterIdx,
                    title = currentChapterTitle,
                    startParagraphIndex = chapterStartParaIdx,
                    endParagraphIndex = finalParagraphs.lastIndex
                )
            )
        }

        val sampleText = finalParagraphs.take(40).joinToString(" ") { it.text }
        val detectedLang = detectLanguageBcp47(opfMeta.language, sampleText)

        val uniqueSeed = "${opfMeta.identifier.ifBlank { fallbackSourceName }}_${opfMeta.title}_${finalParagraphs.size}"
        val bookId = sha256Short(uniqueSeed)

        return EpubBook(
            bookId = bookId,
            title = opfMeta.title.ifBlank { fallbackSourceName.removeSuffix(".epub") },
            author = opfMeta.author.ifBlank { "Autor Desconhecido" },
            rawLanguageTag = opfMeta.language.ifBlank { "UND" },
            detectedLanguageBcp47 = detectedLang,
            chapters = chapters,
            paragraphs = finalParagraphs
        )
    }

    private fun splitEmbeddedChapterHeadings(
        paragraphText: String,
        isHtmlHeaderTag: Boolean,
        out: MutableList<Pair<String, Boolean>>
    ) {
        if (isHtmlHeaderTag) {
            out.add(paragraphText to true)
            return
        }

        val match = chapterHeadingRegex.find(paragraphText)
        if (match == null) {
            out.add(paragraphText to false)
            return
        }

        val heading = match.groupValues[1].trim()
        val before = paragraphText.substring(0, match.range.first).trim()

        if (before.isEmpty()) {
            out.add(heading to true)
        } else {
            out.add(before to false)
            out.add(heading to true)
        }
    }

    private fun detectLanguageBcp47(opfLang: String, sampleText: String): String {
        val normalizedOpf = opfLang.trim().lowercase()
        if (normalizedOpf.startsWith("pt")) {
            return "pt-BR"
        }

        val lowerSample = " ${sampleText.lowercase()} "
        val ptMarkers = listOf(" não ", " que ", " para ", " com ", " uma ", " por ", " mais ", " mas ", " como ", " estava ")
        val ptHits = ptMarkers.count { lowerSample.contains(it) }
        if (ptHits >= 3) {
            return "pt-BR"
        }

        if (normalizedOpf.isNotBlank() && normalizedOpf != "und") {
            return opfLang.trim()
        }
        return "pt-BR"
    }

    private fun parseContainerXml(xml: String): String {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.endsWith("rootfile")) {
                val fullPath = parser.getAttributeValue(null, "full-path")
                if (!fullPath.isNullOrBlank()) return fullPath
            }
            event = parser.next()
        }
        return "OEBPS/content.opf"
    }

    private data class OpfMetadata(
        val title: String,
        val author: String,
        val language: String,
        val identifier: String,
        val manifest: Map<String, String>,
        val spineIdRefs: List<String>
    )

    private fun parseOpf(xml: String): OpfMetadata {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var title = ""
        var author = ""
        var language = ""
        var identifier = ""
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name.substringAfter(":")
                when (tag) {
                    "title" -> if (title.isBlank()) title = parser.nextText().trim()
                    "creator" -> if (author.isBlank()) author = parser.nextText().trim()
                    "language" -> if (language.isBlank()) language = parser.nextText().trim()
                    "identifier" -> if (identifier.isBlank()) identifier = parser.nextText().trim()
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            manifest[id] = href
                        }
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (!idref.isNullOrBlank()) {
                            spine.add(idref)
                        }
                    }
                }
            }
            event = parser.next()
        }
        return OpfMetadata(title, author, language, identifier, manifest, spine)
    }

    private fun normalizePath(path: String): String {
        val parts = mutableListOf<String>()
        for (segment in path.split("/")) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun sha256Short(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}
