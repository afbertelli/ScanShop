package com.example.data.search

import java.text.Normalizer

data class HeadAndTail(
    val head: String,
    val tail: String
)

object TextNormalizer {

    private val whitespaceRegex = Regex("\\s+")
    private val nonAlnumKeepSpaceRegex = Regex("[^a-z0-9\\s]")
    private val digitBoundaryRegex = Regex("(?<=\\d)\\s+(?=(gb|tb|mb|v|w|hz|khz|mah|mm|cm|m|l|kg|g|ml|pol)\\b)")
    private val brandNumberGluedRegex = Regex("\\b(iphone|ipad|galaxy|moto|redmi|poco|pixel|playstation|ps|xbox|switch)(\\d+)\\b")
    private val explicitCompatMarkerRegex = Regex(
        "\\b(compativel com|compativel|serve em|serve para|indicado para|adaptavel em|compatible with|fits|fit for|replacement for|designed for|usado em)\\b"
    )
    private val genericPrepRegex = Regex("\\b(para|p/|for)\\b")
    private val accessoryPrefixHintTokens = setOf(
        "capa", "capinha", "case", "cover", "bumper", "shell", "silicone",
        "pelicula", "peliculas", "glass", "protector", "film", "adesivo", "sticker",
        "cabo", "cabos", "cable", "carregador", "charger", "fonte", "adaptador", "adapter",
        "bateria", "battery", "tela", "display", "lcd", "touch", "conector",
        "suporte", "holder", "stand", "mount", "tripod", "tripe", "bracket",
        "controle", "pulseira", "bracelete", "strap", "almofada", "earpad", "earpads",
        "bolsa", "bag", "mochila", "estojo", "pouch", "peca", "reposicao", "replacement", "spare"
    )

    private val smartvRegex = Regex("\\bsmartv\\b")
    private val smarttvRegex = Regex("\\bsmarttv\\b")
    private val smarthphoneRegex = Regex("\\bsmarthphone\\b")
    private val smartfoneRegex = Regex("\\bsmartfone\\b")
    private val smatphoneRegex = Regex("\\bsmatphone\\b")
    private val tphoneRegex = Regex("\\btphone\\b")
    private val cellphoneRegex = Regex("\\bcellphone\\b")
    private val mobilephoneRegex = Regex("\\bmobilephone\\b")
    private val celuarRegex = Regex("\\bceluar\\b")
    private val notbookRegex = Regex("\\bnotbook\\b")

    private val querySmartvRegex = Regex("(?i)\\bsmartv\\b")
    private val querySmarttvRegex = Regex("(?i)\\bsmarttv\\b")
    private val querySmarthphoneRegex = Regex("(?i)\\bsmarthphone\\b")
    private val querySmartfoneRegex = Regex("(?i)\\bsmartfone\\b")
    private val querySmatphoneRegex = Regex("(?i)\\bsmatphone\\b")
    private val queryTphoneRegex = Regex("(?i)\\btphone\\b")
    private val queryCeluarRegex = Regex("(?i)\\bceluar\\b")
    private val queryBrandNumberGluedRegex = Regex("(?i)\\b(iphone|galaxy|redmi|poco|playstation)(\\d+)\\b")

    private const val NORM_CACHE_MAX_ENTRIES = 256
    private const val NORM_CACHE_MAX_INPUT_LEN = 180
    private val normCache = object : LinkedHashMap<String, String>(NORM_CACHE_MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > NORM_CACHE_MAX_ENTRIES
        }
    }

    private val stopWords = setOf(
        "de", "da", "do", "das", "dos", "e", "em", "na", "no", "nas", "nos",
        "com", "sem", "por", "para", "um", "uma", "o", "a", "os", "as",
        "the", "and", "with", "for", "of", "in", "on", "to", "by", "from",
        "original", "novo", "nova", "promocao", "oferta", "envio", "frete", "gratis"
    )

    private fun stripAccentsFast(lower: String): String {
        var hasNonAscii = false
        for (i in lower.indices) {
            if (lower[i].code >= 128) {
                hasNonAscii = true
                break
            }
        }
        if (!hasNonAscii) return lower
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) {
                    append(ch)
                }
            }
        }
    }

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.length <= NORM_CACHE_MAX_INPUT_LEN) {
            synchronized(normCache) {
                normCache[raw]?.let { return it }
            }
        }
        val noAccents = stripAccentsFast(raw.lowercase())
        val unified = noAccents
            .replace(smartvRegex, "smart tv")
            .replace(smarttvRegex, "smart tv")
            .replace(smarthphoneRegex, "smartphone")
            .replace(smartfoneRegex, "smartphone")
            .replace(smatphoneRegex, "smartphone")
            .replace(tphoneRegex, "smartphone")
            .replace(cellphoneRegex, "cell phone")
            .replace(mobilephoneRegex, "mobile phone")
            .replace(celuarRegex, "celular")
            .replace(notbookRegex, "notebook")
            .replace("wi-fi", "wifi")
            .replace("sem-fio", "sem fio")
            .replace("type-c", "usbc")
            .replace("type c", "usbc")
            .replace("usb-c", "usbc")
            .replace("127v", "110v")
            .replace("127 v", "110v")
            .replace("-", " ")
            .replace("/", " ")
            .replace("_", " ")
        val cleaned = nonAlnumKeepSpaceRegex.replace(unified, " ")
        val splitGlued = brandNumberGluedRegex.replace(cleaned) { m -> "${m.groupValues[1]} ${m.groupValues[2]}" }
        val joinedUnits = digitBoundaryRegex.replace(splitGlued, "")
        val result = whitespaceRegex.replace(joinedUnits, " ").trim()
        if (raw.length <= NORM_CACHE_MAX_INPUT_LEN) {
            synchronized(normCache) {
                normCache[raw] = result
            }
        }
        return result
    }

    fun normalizeSearchQueryText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return trimmed
            .replace(querySmartvRegex, "smart tv")
            .replace(querySmarttvRegex, "smart tv")
            .replace(querySmarthphoneRegex, "smartphone")
            .replace(querySmartfoneRegex, "smartphone")
            .replace(querySmatphoneRegex, "smartphone")
            .replace(queryTphoneRegex, "smartphone")
            .replace(queryCeluarRegex, "celular")
            .replace(queryBrandNumberGluedRegex) { m ->
                "${m.groupValues[1]} ${m.groupValues[2]}"
            }
            .replace(whitespaceRegex, " ")
            .trim()
    }

    fun tokenize(raw: String, dropStopWords: Boolean = true): List<String> {
        val norm = normalize(raw)
        if (norm.isBlank()) return emptyList()
        return norm.split(' ')
            .asSequence()
            .filter { it.isNotBlank() }
            .filter { !dropStopWords || it !in stopWords }
            .distinct()
            .toList()
    }

    fun splitHeadAndCompatibilityTail(rawTitle: String): HeadAndTail {
        val norm = normalize(rawTitle)
        val explicitMatch = explicitCompatMarkerRegex.find(norm)
        if (explicitMatch != null) {
            val head = norm.substring(0, explicitMatch.range.first).trim()
            val tail = norm.substring(explicitMatch.range.last + 1).trim()
            if (head.isNotBlank()) return HeadAndTail(head = head, tail = tail)
        }
        val prepMatch = genericPrepRegex.find(norm)
        if (prepMatch != null) {
            val head = norm.substring(0, prepMatch.range.first).trim()
            val headWords = head.split(' ').filter { it.isNotBlank() }
            if (head.isNotBlank() && headWords.any { it in accessoryPrefixHintTokens }) {
                val tail = norm.substring(prepMatch.range.last + 1).trim()
                return HeadAndTail(head = head, tail = tail)
            }
        }
        return HeadAndTail(head = norm, tail = "")
    }

    fun extractAlphanumericCodes(raw: String): List<String> {
        val tokens = tokenize(raw, dropStopWords = false)
        return tokens.filter { token ->
            token.any { it.isDigit() } && token.length >= 2
        }.distinct()
    }

    fun buildCharNGrams(text: String, n: Int = 3): List<String> {
        val norm = normalize(text)
        if (norm.isBlank()) return emptyList()
        val padded = "^$norm$"
        if (padded.length < n) return listOf(padded)
        val grams = ArrayList<String>(padded.length - n + 1)
        for (i in 0..(padded.length - n)) {
            grams.add(padded.substring(i, i + n))
        }
        return grams
    }
}
