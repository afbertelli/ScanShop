package com.example.data.search

import java.net.URLDecoder

object HtmlTextDecoder {

    private val namedEntities = mapOf(
        "&amp;" to "&",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&lt;" to "<",
        "&gt;" to ">",
        "&nbsp;" to " ",
        "&dollar;" to "$",
        "&aacute;" to "á",
        "&Aacute;" to "Á",
        "&agrave;" to "à",
        "&Agrave;" to "À",
        "&acirc;" to "â",
        "&Acirc;" to "Â",
        "&atilde;" to "ã",
        "&Atilde;" to "Ã",
        "&auml;" to "ä",
        "&Auml;" to "Ä",
        "&eacute;" to "é",
        "&Eacute;" to "É",
        "&egrave;" to "è",
        "&Egrave;" to "È",
        "&ecirc;" to "ê",
        "&Ecirc;" to "Ê",
        "&iacute;" to "í",
        "&Iacute;" to "Í",
        "&igrave;" to "ì",
        "&Igrave;" to "Ì",
        "&icirc;" to "î",
        "&Icirc;" to "Î",
        "&oacute;" to "ó",
        "&Oacute;" to "Ó",
        "&ograve;" to "ò",
        "&Ograve;" to "Ò",
        "&ocirc;" to "ô",
        "&Ocirc;" to "Ô",
        "&otilde;" to "õ",
        "&Otilde;" to "Õ",
        "&uacute;" to "ú",
        "&Uacute;" to "Ú",
        "&ugrave;" to "ù",
        "&Ugrave;" to "Ù",
        "&ucirc;" to "û",
        "&Ucirc;" to "Û",
        "&uuml;" to "ü",
        "&Uuml;" to "Ü",
        "&ccedil;" to "ç",
        "&Ccedil;" to "Ç",
        "&ntilde;" to "ñ",
        "&Ntilde;" to "Ñ",
        "&ordm;" to "º",
        "&ordf;" to "ª",
        "&deg;" to "°",
        "&ndash;" to "-",
        "&mdash;" to "-",
        "&lsquo;" to "'",
        "&rsquo;" to "'",
        "&ldquo;" to "\"",
        "&rdquo;" to "\"",
        "&copy;" to "©",
        "&reg;" to "®",
        "&trade;" to "™",
        "&hellip;" to "..."
    )

    private val unicodeEscapeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
    private val decimalEntityRegex = Regex("""&#(\d{1,6});""")
    private val hexEntityRegex = Regex("""&#x([0-9a-fA-F]{1,6});""", RegexOption.IGNORE_CASE)
    private val namedEntityRegex = Regex("""&[A-Za-z0-9#]+;""")
    private val mojibakeTriggerRegex = Regex("""Ã[\u0080-\u00BF\u2030\u0160\u0152\u017D\u2018-\u2022\u02C6]|Â[\u0080-\u00BF]|Ãƒ|â[\u0080-\u00BF]""")
    private val cp1252Charset = runCatching { charset("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
    private val brokenMaeRegex = Regex("""(?i)\bplaca[\s-]+m(?:\s*e|\uFFFDe|\uFFFD\s*e|\?e|ae)\b""")
    private val standaloneBrokenMaeRegex = Regex("""\bm[\uFFFD?]e\b|\bM[\uFFFD?]e\b|\bm[\uFFFD?]\s+e\b|\bM[\uFFFD?]\s+e\b""")
    private val nonWhitespaceRegex = Regex("""\S+""")
    private val slugMlbPrefixRegex = Regex("""^MLB-?\d+-?""", RegexOption.IGNORE_CASE)
    private val slugJmSuffixRegex = Regex("""-?_JM$""", RegexOption.IGNORE_CASE)
    private val slugSeparatorRegex = Regex("""[-_+]+""")
    private val wsRegex = Regex("\\s+")
    private val blockedImagePatterns = listOf(
        ".svg", "favicon", "sprite", "id=odls", "odls.", "/sa/simg/", "/rp/",
        "bing.com/rs/", "yimg.com/pv/", "yimg.com/rz/", "yimg.com/os/", "yimg.com/nn/",
        "default_avatar", "placeholder", "1x1."
    )

    fun decodeUrlSafe(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw

        // 1) Decode \uXXXX escapes (e.g. \u00e3 -> ã, \u002F -> /)
        if (text.contains("\\u")) {
            text = unicodeEscapeRegex.replace(text) { m ->
                val code = m.groupValues[1].toIntOrNull(16)
                if (code != null && code in 1..0xFFFF) code.toChar().toString() else m.value
            }
        }

        // 2) Two passes to handle double-encoded entities like &amp;atilde;
        var entityPass = 0
        while (entityPass < 2 && text.contains('&')) {
            val before = text
            if (text.contains("&#")) {
                text = decimalEntityRegex.replace(text) { m ->
                    val cp = m.groupValues[1].toIntOrNull()
                    if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
                }
                text = hexEntityRegex.replace(text) { m ->
                    val cp = m.groupValues[1].toIntOrNull(16)
                    if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
                }
            }
            text = namedEntityRegex.replace(text) { m ->
                namedEntities[m.value] ?: m.value
            }
            if (text == before) break
            entityPass++
        }

        // 3) Repair single & double UTF-8-as-ISO-8859-1 / Windows-1252 mojibake (e.g. "MÃ£e", "MÃƒÂ£e" -> "Mãe")
        repeat(2) {
            val repaired = repairUtf8Mojibake(text)
            if (repaired == text) return@repeat
            text = repaired
        }
        return text
    }

    fun decode(raw: String): String {
        var text = decodeUrlSafe(raw)
        if (text.isBlank()) return ""

        // 4) Repair corrupted "mãe" / broken visible text patterns without corrupting URLs or raw HTML markup
        if (text.contains('\uFFFD') || text.contains('?')) {
            text = standaloneBrokenMaeRegex.replace(text) { m ->
                if (m.value.first().isUpperCase()) "Mãe" else "mãe"
            }
        }
        val looksLikeUrlOrMarkup = text.contains("http://", true) ||
            text.contains("https://", true) ||
            text.contains("<a ", true) ||
            text.contains("href=", true)
        if (!looksLikeUrlOrMarkup) {
            text = brokenMaeRegex.replace(text, "Placa Mãe")
        }

        return text
    }

    fun repairUtf8Mojibake(input: String): String {
        if (!mojibakeTriggerRegex.containsMatchIn(input)) return input
        return nonWhitespaceRegex.replace(input) { match ->
            val token = match.value
            if (!mojibakeTriggerRegex.containsMatchIn(token)) return@replace token
            val bytes = token.toByteArray(cp1252Charset)
            val redecoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return@replace token
            if (redecoded.contains('\uFFFD')) token else redecoded
        }
    }

    fun decodeUrlSlugToTitle(slug: String): String {
        val percentDecoded = runCatching {
            URLDecoder.decode(slug.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(slug)
        val cleaned = decode(percentDecoded)
            .replace(slugMlbPrefixRegex, "")
            .replace(slugJmSuffixRegex, "")
            .replace(slugSeparatorRegex, " ")
            .replace(wsRegex, " ")
            .trim()
        val withMaeRestored = brokenMaeRegex.replace(cleaned, "Placa Mãe")
        return withMaeRestored.split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            when {
                word.equals("mãe", ignoreCase = true) || word.equals("mae", ignoreCase = true) -> "Mãe"
                word.length <= 2 && word.all { it.isLetter() } -> word.lowercase()
                else -> word.replaceFirstChar { ch -> ch.titlecase() }
            }
        }
    }

    fun normalizeImageUrl(raw: String): String {
        val decoded = decodeUrlSafe(raw)
            .replace("\\/", "/")
            .trim()
            .trim('"', '\'', '\\', ' ')
            .replace(" ", "%20")
        if (decoded.isBlank() || decoded.startsWith("data:image", ignoreCase = true)) return ""
        val withScheme = when {
            decoded.startsWith("https://", ignoreCase = true) -> decoded
            decoded.startsWith("http://", ignoreCase = true) -> "https://" + decoded.substring(7)
            decoded.startsWith("//") -> "https:$decoded"
            else -> return ""
        }
        val lower = withScheme.lowercase()
        if (blockedImagePatterns.any { it in lower }) return ""
        if (lower.endsWith("logo.png") || lower.endsWith("logo.jpg") || lower.contains("/logos/")) return ""
        return withScheme
    }
}
