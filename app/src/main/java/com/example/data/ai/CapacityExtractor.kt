package com.example.data.ai

import java.text.Normalizer
import java.util.Locale

/**
 * Extrator Neural de Capacidade, Memória e Especificações Técnicas Fundamentais.
 * Resolve ambiguidades entre RAM e Armazenamento (ex: 8+256GB, 128GB como armazenamento e não RAM),
 * além de telas (polegadas), resolução, taxa de Hz e bateria.
 */
object CapacityExtractor {

    private fun norm(text: String): String {
        val nfkd = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return nfkd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
    }

    data class CapacitySpecs(
        val ram: String? = null,
        val storage: String? = null,
        val display: String? = null,
        val battery: String? = null,
        val refreshRateHz: String? = null,
        val resolution: String? = null,
        val capacityLabel: String? = null
    )

    /**
     * Extrai todas as dimensões de capacidade de forma estritamente coerente com o produto
     */
    fun extract(title: String, isQuery: Boolean = false): CapacitySpecs {
        val clean = norm(title)

        var detectedRam: String? = null
        var detectedStorage: String? = null

        // 1. Padrão Combo direto: "8+256GB", "8GB+256GB", "12/512GB", "12GB/512GB", "16GB+1TB", "8G 256G"
        val comboPattern = Regex("\\b(2|4|6|8|12|16|24|32)\\s*(?:gb|g)?\\s*[+/]\\s*(\\d{2,4})\\s*(gb|g|tb|t)\\b", RegexOption.IGNORE_CASE)
        val comboMatch = comboPattern.find(clean)

        if (comboMatch != null) {
            val rVal = comboMatch.groupValues[1]
            val sVal = comboMatch.groupValues[2]
            val sUnit = comboMatch.groupValues[3].uppercase(Locale.ROOT)
            detectedRam = "${rVal}GB RAM"
            detectedStorage = "${sVal}$sUnit"
        }

        // 2. RAM explícita: "X GB RAM" ou "LPDDR5 16GB" ou "DDR4 8GB"
        if (detectedRam == null) {
            val explicitRam = Regex("\\b(2|3|4|6|8|12|16|18|24|32|64)\\s*(?:gb|g)\\s*(?:ram|lpddr[45]x?|ddr[345])\\b", RegexOption.IGNORE_CASE).find(clean)
                ?: Regex("\\b(?:ram|memoria)\\s*:?\\s*(2|3|4|6|8|12|16|24|32|64)\\s*(?:gb|g)?\\b", RegexOption.IGNORE_CASE).find(clean)

            if (explicitRam != null) {
                val num = explicitRam.groupValues.lastOrNull { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }
                if (num != null) {
                    val type = when {
                        clean.contains("ddr5") || clean.contains("lpddr5") -> "DDR5"
                        clean.contains("ddr4") || clean.contains("lpddr4") -> "DDR4"
                        clean.contains("ddr3") -> "DDR3"
                        else -> "RAM"
                    }
                    detectedRam = "${num}GB $type"
                }
            }
        }

        // 3. Armazenamento explícito: "X GB ROM" ou "X GB SSD" ou "X TB NVMe" ou capacidades SSD (120GB, 240GB, 480GB, 512GB, 1TB, etc.)
        if (detectedStorage == null) {
            // TB match
            val tbMatch = Regex("\\b(1|2|4|8)\\s*(?:tb|t)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (tbMatch != null) {
                val tbNum = tbMatch.groupValues[1]
                detectedStorage = when {
                    clean.contains("nvme") || clean.contains("m.2") -> "${tbNum}TB SSD NVMe"
                    clean.contains("ssd") -> "${tbNum}TB SSD"
                    else -> "${tbNum}TB"
                }
            } else {
                // GB match de armazenamento
                val gbStorage = Regex("\\b(32|64|120|128|240|250|256|480|500|512|960|1000)\\s*(?:gb|g)\\b", RegexOption.IGNORE_CASE).find(clean)
                if (gbStorage != null) {
                    val capNum = gbStorage.groupValues[1]
                    // Se o RAM detectado anteriormente pegou esse mesmo número, ignore para não duplicar
                    if (detectedRam == null || !detectedRam.startsWith(capNum)) {
                        detectedStorage = when {
                            clean.contains("nvme") || clean.contains("m.2") -> "${capNum}GB SSD NVMe"
                            clean.contains("ssd") -> "${capNum}GB SSD"
                            clean.contains("rom") -> "${capNum}GB ROM"
                            else -> "${capNum}GB"
                        }
                    }
                }
            }
        }

        // Se o produto for pente de memória RAM exclusivo (ex: "Memória RAM 16GB DDR4")
        if (clean.contains("memoria ram") || clean.contains("memoriaram") || clean.contains("pente de memoria")) {
            val ramOnly = Regex("\\b(4|8|16|32|64)\\s*(?:gb|g)\\b", RegexOption.IGNORE_CASE).find(clean)
            if (ramOnly != null) {
                detectedRam = "${ramOnly.groupValues[1]}GB RAM"
                detectedStorage = null // Memória RAM não tem SSD
            }
        }

        // 4. Tela (Polegadas)
        val screenMatch = Regex("\\b(6\\.[0-9]{1,2}|7\\.[0-9]{1,2}|1[3-7]\\.[0-9]|2[4789]|3[24]|4[03]|5[058]|6[50]|7[05])\\s*(\"|polegadas|pol|-inch|inch)\\b", RegexOption.IGNORE_CASE).find(clean)
        val detectedScreen = screenMatch?.groupValues?.get(1)?.let { "$it\"" }

        // 5. Resolução
        val resolution = when {
            clean.contains("8k") -> "8K Ultra HD"
            clean.contains("4k") || clean.contains("uhd") || clean.contains("2160p") -> "4K Ultra HD"
            clean.contains("qhd") || clean.contains("2k") || clean.contains("1440p") -> "2K QHD"
            clean.contains("full hd") || clean.contains("fhd") || clean.contains("1080p") -> "Full HD 1080p"
            clean.contains("hd+") || clean.contains("720p") -> "HD+"
            else -> null
        }

        // 6. Taxa de Atualização Hz
        val hzMatch = Regex("\\b(60|75|90|120|144|165|240|360)\\s*hz\\b", RegexOption.IGNORE_CASE).find(clean)?.value?.uppercase(Locale.ROOT)

        // 7. Bateria (mAh)
        val batteryMatch = Regex("\\b([1-9][0-9]{2,4})\\s*mah\\b", RegexOption.IGNORE_CASE).find(clean)?.value?.uppercase(Locale.ROOT)

        // 8. Rótulo Sintético de Capacidade para Visualização Profissional
        val label = when {
            detectedRam != null && detectedStorage != null -> "${detectedRam.replace(" RAM", "")} + $detectedStorage"
            detectedStorage != null -> detectedStorage
            detectedRam != null -> detectedRam
            detectedScreen != null && resolution != null -> "$detectedScreen $resolution"
            detectedScreen != null -> detectedScreen
            batteryMatch != null -> batteryMatch
            else -> null
        }

        return CapacitySpecs(
            ram = detectedRam,
            storage = detectedStorage,
            display = detectedScreen,
            battery = batteryMatch,
            refreshRateHz = hzMatch,
            resolution = resolution,
            capacityLabel = label
        )
    }
}
