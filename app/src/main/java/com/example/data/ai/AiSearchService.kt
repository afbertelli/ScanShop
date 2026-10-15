package com.example.data.ai

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.ComparisonResult
import com.example.data.model.Product
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AiSearchInsight(
    val detectedBrand: String? = null,
    val detectedModel: String? = null,
    val category: String? = null,
    val categoryKey: String? = null,
    val targetGender: String? = null, // "Masculino", "Feminino", "Infantil", "Unissex"
    val detectedItemType: String? = null, // "Tênis", "Camiseta", "Vestido", "Boné", "Sapato", etc.
    val isAccessoryIntent: Boolean = false,
    val brandAliases: Set<String> = emptySet(),
    val optimizedQueries: List<String> = emptyList(),
    val summary: String? = null,
    val filteredAccessoriesCount: Int = 0,
    val isAiEnhanced: Boolean = false
)

data class ProductAiEvaluation(
    val isGenuineMatch: Boolean,
    val isAccessoryOrPart: Boolean,
    val isGenderConflict: Boolean = false,
    val brandMatched: Boolean = false,
    val categoryMatched: Boolean = false,
    val modelMatched: Boolean = false,
    val genderMatched: Boolean = true,
    val itemTypeMatched: Boolean = true,
    val relevanceScore: Int = 0,
    val aiBrand: String? = null,
    val aiModel: String? = null,
    val aiGender: String? = null,
    val aiItemType: String? = null,
    val badgeLabel: String? = null
)

/**
 * Motor de Inteligência Artificial Especializado em Identificação de Marca, Modelo,
 * Categoria, Gênero (Masculino / Feminino / Infantil / Unissex) e Tipo de Artigo
 * integrado em tempo real com as Engines de Busca do AliExpress e Shopee.
 *
 * Características da Arquitetura:
 * 1. Análise Semântica On-Device (0ms de latência):
 *    - Hardware & Tech: Placas-Mãe, GPUs, CPUs, Smartphones, Drones, Fones.
 *    - Moda & Vestuário: Camisetas, Camisas, Calças, Bermudas, Vestidos, Saias, Casacos, Lingerie, Moda Praia.
 *    - Calçados: Tênis, Sapatos Sociais, Sandálias, Chinelos, Botas, Chuteiras, Mocassins, Scarpins.
 *    - Acessórios de Cabeça: Bonés (aba curva/reta, trucker), Chapéus, Gorros, Viseiras, Boinas.
 *    - Acessórios Pessoais: Relógios, Smartwatches, Óculos de Sol, Bolsas, Mochilas, Perfumes.
 *    - Gênero: Desambiguação estrita (Masculino, Feminino, Infantil, Unissex) eliminando 100%
 *      de ofertas de gênero trocado.
 * 2. Filtro Anti-Acessórios e Anti-Peças com Detecção de Anomalia de Preço:
 *    - Elimina espelhos de placa-mãe, módulos TPM, dissipadores, parafusos, adesivos.
 *    - Elimina cadarços, palmilhas, meias, chaveiros de tênis ao buscar calçados.
 *    - Elimina suportes de parede e moldes ao buscar bonés.
 *    - Elimina cabides, tecidos por metro e manequins ao buscar vestidos ou roupas.
 *    - Elimina pulseiras avulsas ao buscar relógios.
 *    - Guarda de Preço em tempo real.
 * 3. Otimizador de Queries para APIs de E-commerce:
 *    - Gera buscas otimizadas para Shopee e AliExpress combinando gênero, marca e tipo.
 */
class AiSearchService {

    companion object {
        private const val TAG = "AiSearchService"
        private const val MODEL_NAME = "gemini-3.8-flash"
        private const val GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

        // Canonical Category Synonyms
        val CATEGORY_SYNONYMS: Map<String, Set<String>> = mapOf(
            // Hardware & Eletrônicos
            "placa mae" to setOf("placa mae", "placamae", "motherboard", "mainboard", "mobo"),
            "placa de video" to setOf("placa de video", "placadevideo", "gpu", "graphics card", "rtx", "gtx", "radeon", "geforce"),
            "processador" to setOf("processador", "cpu", "ryzen", "core i3", "core i5", "core i7", "core i9", "xeon"),
            "memoria ram" to setOf("memoria ram", "memoriaram", "ddr4", "ddr5", "ddr3", "ram"),
            "fonte" to setOf("fonte", "fonte atx", "power supply", "psu"),
            "gabinete" to setOf("gabinete", "case gamer"),
            "ssd" to setOf("ssd", "nvme", "m.2 nvme", "disco solido"),
            "smartphone" to setOf("smartphone", "celular", "telefone", "galaxy", "iphone", "redmi", "poco", "moto g", "moto edge", "xiaomi", "zenfone", "s23", "s24", "s25", "a15", "a16", "a32", "a54", "a55"),
            "celular" to setOf("smartphone", "celular", "telefone", "galaxy", "iphone", "redmi", "poco", "moto g", "moto edge", "xiaomi", "zenfone", "s23", "s24", "s25", "a15", "a16", "a32", "a54", "a55"),
            "smart tv" to setOf("smart tv", "smarttv", "smartv", "tv smart", "televisor", "tv led", "tv 4k", "televisao", "tv dled", "tv uhd", "roku tv", "google tv", "android tv", "tv 32", "tv 43", "tv 50", "tv 55", "tv 65", "crystal uhd", "qled", "oled", "tv"),
            "tv" to setOf("smart tv", "smarttv", "smartv", "tv smart", "televisor", "tv led", "tv 4k", "televisao", "tv dled", "tv uhd", "roku tv", "google tv", "android tv", "tv 32", "tv 43", "tv 50", "tv 55", "tv 65", "crystal uhd", "qled", "oled", "tv"),
            "monitor" to setOf("monitor", "monitor pc", "monitor de pc", "monitor para pc", "monitor gamer", "monitor led", "monitor ips", "monitor full hd", "monitor 75hz", "monitor 144hz", "tela pc", "display pc", "widescreen", "ultrawide"),
            "fone" to setOf("fone", "fones", "headset", "earphone", "earbuds", "headphone"),
            "drone" to setOf("drone", "drones", "quadcopter"),

            // Calçados & Sapatos
            "tenis" to setOf("tenis", "sneaker", "sneakers", "sapatenis", "calcado", "calcados"),
            "sapato" to setOf("sapato", "sapato social", "mocassim", "oxford", "scarpin", "anabela"),
            "sandalia" to setOf("sandalia", "sandalias", "rasteirinha", "papete", "salto alto", "tamanco"),
            "chinelo" to setOf("chinelo", "chinelos", "slide", "sandalia de dedo", "havaianas"),
            "bota" to setOf("bota", "botas", "coturno", "coturnos"),
            "chuteira" to setOf("chuteira", "chuteiras", "futsal", "society", "chuteira campo"),

            // Roupas & Vestuário
            "camiseta" to setOf("camiseta", "camisetas", "t shirt", "tshirt", "t-shirt", "camiseteria"),
            "camisa" to setOf("camisa", "camisas", "camisa social", "camisa polo", "polo", "regata", "camisao"),
            "calca" to setOf("calca", "calcas", "calca jeans", "calca cargo", "calca moletom", "calca social", "legging"),
            "bermuda" to setOf("bermuda", "bermudas", "short", "shorts", "bermudao"),
            "vestido" to setOf("vestido", "vestidos", "vestidinho", "chemise"),
            "saia" to setOf("saia", "saias", "saia midi", "minisaia"),
            "jaqueta" to setOf("jaqueta", "jaquetas", "casaco", "casacos", "moletom", "moletons", "blazer", "cardigan", "corta vento"),
            "cueca" to setOf("cueca", "cuecas", "boxer", "slip"),
            "lingerie" to setOf("calcinha", "calcinhas", "sutia", "sutias", "lingerie", "lingeries", "babydoll", "top fitness"),
            "moda praia" to setOf("biquini", "biquinis", "maio", "maios", "sunga", "sungas", "canga"),

            // Acessórios de Cabeça & Bonés
            "bone" to setOf("bone", "bones", "snapback", "trucker", "aba curva", "aba reta", "strapback"),
            "chapeu" to setOf("chapeu", "chapeus", "bucket hat", "gorro", "gorros", "touca", "toucas", "viseira", "boina"),

            // Acessórios Pessoais & Moda
            "relogio" to setOf("relogio", "relogios", "smartwatch", "smart watch", "relogio digital", "relogio analogico"),
            "oculos" to setOf("oculos", "oculos de sol", "oculos de grau", "armacao de oculos", "sunglasses"),
            "bolsa" to setOf("bolsa", "bolsas", "bolsa feminina", "bolsa transversal", "tiracolo", "clutch", "tote"),
            "mochila" to setOf("mochila", "mochilas", "mala", "mochila escolar", "pochete", "carteira"),
            "perfume" to setOf("perfume", "perfumes", "colonia", "fragrancia", "eau de parfum", "eau de toilette")
        )

        // Known PC Hardware Chipsets & Sockets
        val HARDWARE_CHIPSETS: List<String> = listOf(
            // AMD
            "b650", "b650m", "b550", "b550m", "b450", "b450m", "b350", "a620", "a620m", "a520", "a520m", "a320", "a320m",
            "x670", "x670e", "x570", "x470", "x370", "b840", "b850", "x870", "am4", "am5",
            // Intel
            "b760", "b760m", "b660", "b660m", "b560", "b560m", "b460", "b460m", "b365", "b360", "b250", "b150", "b85", "b75",
            "h810", "h610", "h610m", "h510", "h510m", "h410", "h410m", "h310", "h110", "h81", "h61",
            "z890", "z790", "z690", "z590", "z490", "z390", "z370", "z270", "z170", "z97",
            "x99", "x79", "lga1700", "lga 1700", "lga1200", "lga 1200", "lga1151", "lga 1151", "lga1155", "lga 1155", "lga2011", "lga 2011"
        )

        // Known Hardware Series
        val HARDWARE_SERIES: List<String> = listOf(
            "mortar", "tomahawk", "bazooka", "torpedo", "gaming plus", "gaming x", "ventus", "mech",
            "tuf", "rog", "strix", "prime", "aorus", "eagle", "steel legend", "phantom", "taichi", "pro"
        )

        // Canonical Brand Knowledge Base (Tecnologia, Moda, Calçados, Bonés, Relógios e Perfumes)
        private val BRAND_DATABASE: Map<String, BrandMeta> = mapOf(
            // --- Hardware & Tech ---
            "msi" to BrandMeta(
                displayName = "MSI",
                aliases = setOf("msi", "micro star"),
                category = "Placas-Mãe & Hardware MSI",
                defaultPrefixes = listOf("MSI Motherboard", "Placa Mãe MSI", "MSI")
            ),
            "asus" to BrandMeta(
                displayName = "ASUS",
                aliases = setOf("asus", "rog", "tuf", "prime"),
                category = "Placas-Mãe & Hardware ASUS",
                defaultPrefixes = listOf("ASUS Motherboard", "Placa Mãe ASUS", "ASUS")
            ),
            "gigabyte" to BrandMeta(
                displayName = "Gigabyte",
                aliases = setOf("gigabyte", "aorus", "eagle"),
                category = "Placas-Mãe & Hardware Gigabyte",
                defaultPrefixes = listOf("Gigabyte Motherboard", "Placa Mãe Gigabyte", "Gigabyte")
            ),
            "asrock" to BrandMeta(
                displayName = "ASRock",
                aliases = setOf("asrock", "steel legend", "taichi"),
                category = "Placas-Mãe & Hardware ASRock",
                defaultPrefixes = listOf("ASRock Motherboard", "Placa Mãe ASRock", "ASRock")
            ),
            "colorful" to BrandMeta(
                displayName = "Colorful",
                aliases = setOf("colorful", "cvn", "igame"),
                category = "Placas-Mãe & GPUs Colorful",
                defaultPrefixes = listOf("Colorful Motherboard", "Placa Mãe Colorful", "Colorful")
            ),
            "biostar" to BrandMeta(
                displayName = "Biostar",
                aliases = setOf("biostar"),
                category = "Placas-Mãe Biostar",
                defaultPrefixes = listOf("Biostar Motherboard", "Placa Mãe Biostar", "Biostar")
            ),
            "maxsun" to BrandMeta(
                displayName = "Maxsun",
                aliases = setOf("maxsun", "terminator", "challenger"),
                category = "Placas-Mãe Maxsun",
                defaultPrefixes = listOf("Maxsun Motherboard", "Placa Mãe Maxsun", "Maxsun")
            ),
            "soyo" to BrandMeta(
                displayName = "SOYO",
                aliases = setOf("soyo", "dragon"),
                category = "Hardware & Placas SOYO",
                defaultPrefixes = listOf("SOYO", "Placa SOYO")
            ),
            "huananzhi" to BrandMeta(
                displayName = "HUANANZHI",
                aliases = setOf("huananzhi"),
                category = "Hardware & Placas-Mãe",
                defaultPrefixes = listOf("HUANANZHI", "Placa Mãe HUANANZHI")
            ),
            "machinist" to BrandMeta(
                displayName = "MACHINIST",
                aliases = setOf("machinist"),
                category = "Hardware & Placas-Mãe",
                defaultPrefixes = listOf("MACHINIST", "Placa Mãe MACHINIST")
            ),
            "intel" to BrandMeta(
                displayName = "Intel",
                aliases = setOf("intel", "core i3", "core i5", "core i7", "core i9", "xeon"),
                category = "Processadores & Hardware Intel",
                defaultPrefixes = listOf("Intel Core", "Processador Intel")
            ),
            "amd" to BrandMeta(
                displayName = "AMD",
                aliases = setOf("amd", "ryzen", "radeon"),
                category = "Processadores & GPUs AMD",
                defaultPrefixes = listOf("AMD Ryzen", "Processador AMD")
            ),
            "ryzen" to BrandMeta(
                displayName = "AMD Ryzen",
                aliases = setOf("ryzen", "amd ryzen"),
                category = "Processadores Ryzen",
                defaultPrefixes = listOf("AMD Ryzen", "Processador Ryzen")
            ),
            "samsung" to BrandMeta(
                displayName = "Samsung",
                aliases = setOf("samsung", "galaxy", "crystal uhd", "qled", "neo qled", "odyssey"),
                category = "Smartphones, TVs & Monitores Samsung",
                defaultPrefixes = listOf("Samsung Galaxy", "Smart TV Samsung", "Monitor Samsung", "Samsung")
            ),
            "lg" to BrandMeta(
                displayName = "LG",
                aliases = setOf("lg", "nanocell", "oled", "ultragear"),
                category = "Smart TVs & Monitores LG",
                defaultPrefixes = listOf("Smart TV LG", "Monitor LG", "LG")
            ),
            "tcl" to BrandMeta(
                displayName = "TCL",
                aliases = setOf("tcl", "semp tcl", "semp"),
                category = "Smart TVs TCL",
                defaultPrefixes = listOf("Smart TV TCL", "TCL")
            ),
            "aoc" to BrandMeta(
                displayName = "AOC",
                aliases = setOf("aoc", "aoc roku", "hero", "agon"),
                category = "Smart TVs & Monitores AOC",
                defaultPrefixes = listOf("Smart TV AOC", "Monitor AOC", "AOC")
            ),
            "dell" to BrandMeta(
                displayName = "Dell",
                aliases = setOf("dell", "alienware", "optiplex"),
                category = "Monitores & PCs Dell",
                defaultPrefixes = listOf("Monitor Dell", "Dell")
            ),
            "acer" to BrandMeta(
                displayName = "Acer",
                aliases = setOf("acer", "nitro", "predator"),
                category = "Monitores & Notebooks Acer",
                defaultPrefixes = listOf("Monitor Acer", "Acer")
            ),
            "philips" to BrandMeta(
                displayName = "Philips",
                aliases = setOf("philips", "ambilight"),
                category = "Smart TVs & Monitores Philips",
                defaultPrefixes = listOf("Smart TV Philips", "Monitor Philips", "Philips")
            ),
            "philco" to BrandMeta(
                displayName = "Philco",
                aliases = setOf("philco", "fast smart"),
                category = "Smart TVs Philco",
                defaultPrefixes = listOf("Smart TV Philco", "Philco")
            ),
            "motorola" to BrandMeta(
                displayName = "Motorola",
                aliases = setOf("motorola", "moto g", "moto edge", "moto e"),
                category = "Smartphones Motorola",
                defaultPrefixes = listOf("Smartphone Motorola", "Moto G", "Motorola")
            ),
            "realme" to BrandMeta(
                displayName = "Realme",
                aliases = setOf("realme", "narzo"),
                category = "Smartphones Realme",
                defaultPrefixes = listOf("Smartphone Realme", "Realme")
            ),
            "xiaomi" to BrandMeta(
                displayName = "Xiaomi",
                aliases = setOf("xiaomi", "poco", "redmi", "mi"),
                category = "Smartphones & Wearables",
                defaultPrefixes = listOf("Xiaomi", "Smartphone Xiaomi")
            ),
            "apple" to BrandMeta(
                displayName = "Apple",
                aliases = setOf("apple", "iphone", "ipad", "macbook", "airpods"),
                category = "Dispositivos Apple",
                defaultPrefixes = listOf("Apple", "iPhone")
            ),
            "dji" to BrandMeta(
                displayName = "DJI",
                aliases = setOf("dji", "mavic", "mini", "drone", "avata", "osmo"),
                category = "Drones & Filmagem",
                defaultPrefixes = listOf("Drone DJI", "DJI")
            ),

            // --- Calçados, Moda Esportiva & Tênis ---
            "nike" to BrandMeta(
                displayName = "Nike",
                aliases = setOf("nike", "air max", "dunk", "air force", "jordan", "pegasus", "revolution"),
                category = "Calçados & Moda Nike",
                defaultPrefixes = listOf("Nike", "Tênis Nike")
            ),
            "adidas" to BrandMeta(
                displayName = "Adidas",
                aliases = setOf("adidas", "ultraboost", "superstar", "samba", "stan smith", "yeezy", "adizero", "forum"),
                category = "Calçados & Moda Adidas",
                defaultPrefixes = listOf("Adidas", "Tênis Adidas")
            ),
            "puma" to BrandMeta(
                displayName = "Puma",
                aliases = setOf("puma", "suede", "rs-x", "carina", "smash"),
                category = "Calçados & Moda Puma",
                defaultPrefixes = listOf("Puma", "Tênis Puma")
            ),
            "vans" to BrandMeta(
                displayName = "Vans",
                aliases = setOf("vans", "old skool", "sk8", "authentic", "era", "slip-on", "slip on"),
                category = "Tênis & Moda Vans",
                defaultPrefixes = listOf("Vans", "Tênis Vans")
            ),
            "converse" to BrandMeta(
                displayName = "Converse",
                aliases = setOf("converse", "all star", "allstar", "chuck taylor"),
                category = "Tênis Converse",
                defaultPrefixes = listOf("Converse All Star", "Tênis Converse")
            ),
            "all star" to BrandMeta(
                displayName = "Converse All Star",
                aliases = setOf("all star", "allstar", "converse"),
                category = "Tênis All Star",
                defaultPrefixes = listOf("Converse All Star", "All Star")
            ),
            "new balance" to BrandMeta(
                displayName = "New Balance",
                aliases = setOf("new balance", "newbalance", "nb 574", "nb 550", "nb 327", "nb 9060"),
                category = "Tênis New Balance",
                defaultPrefixes = listOf("New Balance", "Tênis New Balance")
            ),
            "asics" to BrandMeta(
                displayName = "Asics",
                aliases = setOf("asics", "gel nimbus", "gel kayano", "gel cumulus", "gel"),
                category = "Tênis & Corrida Asics",
                defaultPrefixes = listOf("Asics", "Tênis Asics")
            ),
            "mizuno" to BrandMeta(
                displayName = "Mizuno",
                aliases = setOf("mizuno", "wave creation", "wave prophecy", "wave rider", "mizuno wave"),
                category = "Tênis Mizuno",
                defaultPrefixes = listOf("Mizuno", "Tênis Mizuno")
            ),
            "olympikus" to BrandMeta(
                displayName = "Olympikus",
                aliases = setOf("olympikus", "corre 3", "corre 2", "corre grafeno"),
                category = "Tênis Olympikus",
                defaultPrefixes = listOf("Olympikus", "Tênis Olympikus")
            ),
            "fila" to BrandMeta(
                displayName = "Fila",
                aliases = setOf("fila", "disruptor", "kr5", "float"),
                category = "Calçados & Moda Fila",
                defaultPrefixes = listOf("Fila", "Tênis Fila")
            ),
            "under armour" to BrandMeta(
                displayName = "Under Armour",
                aliases = setOf("under armour", "underarmour", "curry"),
                category = "Esporte Under Armour",
                defaultPrefixes = listOf("Under Armour")
            ),
            "havaianas" to BrandMeta(
                displayName = "Havaianas",
                aliases = setOf("havaianas", "havaiana"),
                category = "Chinelos & Sandálias Havaianas",
                defaultPrefixes = listOf("Havaianas", "Chinelo Havaianas")
            ),
            "melissa" to BrandMeta(
                displayName = "Melissa",
                aliases = setOf("melissa", "possession", "campana"),
                category = "Calçados & Sandálias Melissa",
                defaultPrefixes = listOf("Melissa", "Sandália Melissa")
            ),
            "arezzo" to BrandMeta(
                displayName = "Arezzo",
                aliases = setOf("arezzo"),
                category = "Calçados Femininos Arezzo",
                defaultPrefixes = listOf("Arezzo", "Sapato Arezzo")
            ),
            "schutz" to BrandMeta(
                displayName = "Schutz",
                aliases = setOf("schutz"),
                category = "Calçados & Bolsas Schutz",
                defaultPrefixes = listOf("Schutz")
            ),
            "santa lolla" to BrandMeta(
                displayName = "Santa Lolla",
                aliases = setOf("santa lolla", "santalolla"),
                category = "Calçados & Bolsas Santa Lolla",
                defaultPrefixes = listOf("Santa Lolla")
            ),

            // --- Moda, Roupas & Vestuário ---
            "lacoste" to BrandMeta(
                displayName = "Lacoste",
                aliases = setOf("lacoste", "crocodilo"),
                category = "Moda & Calçados Lacoste",
                defaultPrefixes = listOf("Lacoste", "Camisa Polo Lacoste")
            ),
            "tommy hilfiger" to BrandMeta(
                displayName = "Tommy Hilfiger",
                aliases = setOf("tommy hilfiger", "tommy", "hilfiger"),
                category = "Moda Tommy Hilfiger",
                defaultPrefixes = listOf("Tommy Hilfiger", "Camisa Tommy")
            ),
            "calvin klein" to BrandMeta(
                displayName = "Calvin Klein",
                aliases = setOf("calvin klein", "calvinklein", "ck"),
                category = "Moda & Underwear Calvin Klein",
                defaultPrefixes = listOf("Calvin Klein", "Cueca Calvin Klein")
            ),
            "polo ralph lauren" to BrandMeta(
                displayName = "Ralph Lauren",
                aliases = setOf("ralph lauren", "polo ralph lauren", "polo"),
                category = "Moda Ralph Lauren",
                defaultPrefixes = listOf("Ralph Lauren", "Polo Ralph Lauren")
            ),
            "reserva" to BrandMeta(
                displayName = "Reserva",
                aliases = setOf("reserva", "reserva pica pau"),
                category = "Moda Reserva",
                defaultPrefixes = listOf("Reserva", "Camiseta Reserva")
            ),
            "zara" to BrandMeta(
                displayName = "Zara",
                aliases = setOf("zara"),
                category = "Moda & Roupas Zara",
                defaultPrefixes = listOf("Zara", "Vestido Zara")
            ),
            "hering" to BrandMeta(
                displayName = "Hering",
                aliases = setOf("hering", "basico hering"),
                category = "Roupas Básicas Hering",
                defaultPrefixes = listOf("Hering", "Camiseta Hering")
            ),
            "farm" to BrandMeta(
                displayName = "Farm Rio",
                aliases = setOf("farm rio", "farm", "vestido farm"),
                category = "Moda Feminina Farm Rio",
                defaultPrefixes = listOf("Farm Rio", "Vestido Farm")
            ),

            // --- Bonés & Acessórios de Cabeça ---
            "new era" to BrandMeta(
                displayName = "New Era",
                aliases = setOf("new era", "newera", "neweracap", "59fifty", "9forty", "9fifty", "ny yankees", "la dodgers"),
                category = "Bonés New Era",
                defaultPrefixes = listOf("Boné New Era", "New Era")
            ),

            // --- Óculos & Acessórios ---
            "oakley" to BrandMeta(
                displayName = "Oakley",
                aliases = setOf("oakley", "juliet", "holbrook", "romeo", "double x"),
                category = "Óculos & Vestuário Oakley",
                defaultPrefixes = listOf("Oakley", "Óculos Oakley")
            ),
            "ray-ban" to BrandMeta(
                displayName = "Ray-Ban",
                aliases = setOf("ray ban", "rayban", "ray-ban", "aviador", "wayfarer", "clubmaster"),
                category = "Óculos Ray-Ban",
                defaultPrefixes = listOf("Ray-Ban", "Óculos Ray-Ban")
            ),

            // --- Relógios ---
            "casio" to BrandMeta(
                displayName = "Casio",
                aliases = setOf("casio", "g-shock", "g shock", "gshock", "vintage casio"),
                category = "Relógios Casio",
                defaultPrefixes = listOf("Casio G-Shock", "Relógio Casio")
            ),
            "invicta" to BrandMeta(
                displayName = "Invicta",
                aliases = setOf("invicta", "pro diver", "zeus"),
                category = "Relógios Invicta",
                defaultPrefixes = listOf("Relógio Invicta", "Invicta")
            ),
            "technos" to BrandMeta(
                displayName = "Technos",
                aliases = setOf("technos"),
                category = "Relógios Technos",
                defaultPrefixes = listOf("Relógio Technos", "Technos")
            ),
            "orient" to BrandMeta(
                displayName = "Orient",
                aliases = setOf("orient", "orient automatico"),
                category = "Relógios Orient",
                defaultPrefixes = listOf("Relógio Orient", "Orient")
            ),
            "rolex" to BrandMeta(
                displayName = "Rolex",
                aliases = setOf("rolex", "submariner", "daytona", "datejust"),
                category = "Relógios de Luxo Rolex",
                defaultPrefixes = listOf("Rolex")
            ),

            // --- Perfumes & Cosméticos ---
            "carolina herrera" to BrandMeta(
                displayName = "Carolina Herrera",
                aliases = setOf("carolina herrera", "212", "212 vip", "good girl", "bad boy"),
                category = "Perfumes Carolina Herrera",
                defaultPrefixes = listOf("Perfume 212", "Carolina Herrera")
            ),
            "paco rabanne" to BrandMeta(
                displayName = "Paco Rabanne",
                aliases = setOf("paco rabanne", "one million", "1 million", "invictus", "olympea"),
                category = "Perfumes Paco Rabanne",
                defaultPrefixes = listOf("Perfume Paco Rabanne", "One Million")
            ),
            "boticario" to BrandMeta(
                displayName = "O Boticário",
                aliases = setOf("boticario", "o boticario", "malbec", "lily", "elysee", "quasar", "floratta"),
                category = "Perfumaria O Boticário",
                defaultPrefixes = listOf("O Boticário", "Perfume Boticário")
            ),
            "natura" to BrandMeta(
                displayName = "Natura",
                aliases = setOf("natura", "kaiak", "essencial", "homem sagaz", "luna"),
                category = "Perfumaria Natura",
                defaultPrefixes = listOf("Natura", "Perfume Natura")
            )
        )

        // Palavras indicativas de Acessórios & Peças secundárias em geral
        private val GENERAL_ACCESSORY_WORDS = setOf(
            "capa", "capinha", "case", "cover", "película", "pelicula", "vidro temperado",
            "suporte", "stand", "holder", "tripé", "tripe", "adesivo", "skin", "sticker",
            "cabo", "cable", "carregador", "charger", "fonte", "estojo",
            "maleta", "adaptador", "adapter", "pilha",
            "bateria", "baterias", "peça", "peças", "peca", "pecas", "reposição", "reposicao",
            "chaveiro", "pingente", "boneco", "miniatura",
            // Hardware
            "espelho", "espelhos", "shield", "shields", "modulo tpm", "tpm 2.0", "tpm2.0", "bateria cmos", "suporte lga",
            "parafuso m.2", "dissipador m.2", "bracket", "backplate", "io shield", "baffle"
        )

        // Subcategorias de Moda com palavras-chave de detecção
        private val ITEM_TYPE_TAXONOMY: List<Pair<String, List<String>>> = listOf(
            // Calçados
            "Tênis" to listOf("tenis", "sneaker", "sneakers", "sapatenis"),
            "Sapato Social" to listOf("sapato social", "sapato", "oxford"),
            "Chuteira" to listOf("chuteira", "chuteiras", "futsal", "society"),
            "Sandália" to listOf("sandalia", "sandalias", "rasteirinha", "papete", "anabela", "salto alto", "scarpin", "tamanco"),
            "Chinelo" to listOf("chinelo", "chinelos", "slide"),
            "Bota" to listOf("bota", "botas", "coturno", "coturnos"),
            "Mocassim" to listOf("mocassim", "sapatilha", "sapatilhas"),

            // Acessórios de cabeça
            "Boné" to listOf("bone", "bones", "snapback", "trucker", "aba curva", "aba reta", "strapback"),
            "Chapéu" to listOf("chapeu", "chapeus", "bucket hat", "gorro", "gorros", "touca", "toucas", "viseira", "boina"),

            // Roupas & Vestuário
            "Camiseta" to listOf("camiseta", "camisetas", "t shirt", "tshirt", "t-shirt"),
            "Camisa" to listOf("camisa social", "camisa polo", "camisa", "camisas", "regata", "regatas"),
            "Calça" to listOf("calca jeans", "calca cargo", "calca moletom", "calca social", "calca", "calcas", "legging"),
            "Bermuda / Short" to listOf("bermuda", "bermudas", "short", "shorts", "bermudao"),
            "Vestido" to listOf("vestido", "vestidos", "vestidinho", "chemise"),
            "Saia" to listOf("saia", "saias"),
            "Casaco / Jaqueta" to listOf("jaqueta", "jaquetas", "casaco", "casacos", "moletom", "moletons", "blazer", "corta vento", "cardigan"),
            "Roupa Íntima" to listOf("cueca", "cuecas", "boxer", "calcinha", "calcinhas", "sutia", "sutias", "lingerie", "lingeries", "pijama", "pijamas"),
            "Moda Praia" to listOf("biquini", "biquinis", "maio", "maios", "sunga", "sungas"),

            // Acessórios pessoais & Telas
            "Smart TV" to listOf("smart tv", "smarttv", "smartv", "tv smart", "televisor", "televisao", "tv led", "tv 4k"),
            "Monitor" to listOf("monitor", "monitor pc", "monitor gamer", "tela pc"),
            "Smartphone" to listOf("smartphone", "celular", "telefone", "galaxy", "iphone", "redmi", "poco", "moto g"),
            "Relógio" to listOf("relogio", "relogios", "smartwatch", "smart watch"),
            "Óculos" to listOf("oculos de sol", "oculos de grau", "oculos", "armacao"),
            "Bolsa / Mochila" to listOf("bolsa", "bolsas", "mochila", "mochilas", "carteira", "carteiras", "pochete", "mala"),
            "Perfume" to listOf("perfume", "perfumes", "colonia", "fragrancia", "eau de parfum")
        )

        /**
         * Normaliza diacríticos, acentos, hífens, pontuações, parênteses e abreviações (smartv -> smart tv).
         */
        fun normalizeText(input: String): String {
            if (input.isBlank()) return ""
            val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
            var clean = nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
                .replace("[\\(\\)\\[\\]\\-_/\\\\+]+".toRegex(), " ")
                .lowercase(Locale.ROOT)
                .trim()
            clean = clean.replace("\\bsmartv\\b".toRegex(), "smart tv")
            clean = clean.replace("\\bsmarttv\\b".toRegex(), "smart tv")
            clean = clean.replace("\\bde pc\\b".toRegex(), "pc")
            clean = clean.replace("\\bpara pc\\b".toRegex(), "pc")
            return clean.replace("\\s+".toRegex(), " ").trim()
        }

        /**
         * Detector Inteligente de Gênero (Masculino, Feminino, Infantil, Unissex)
         * aplicado sobre a Query do usuário ou Título do produto.
         */
        fun detectGender(text: String): String? {
            val norm = normalizeText(text)
            if (norm.isBlank()) return null

            val isUnisex = Regex("\\b(unissex|unisex|neutro)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)
            val isKids = Regex("\\b(infantil|infantis|crianca|criancas|menino|menina|bebe|bebes|kids|boy|girl|recem nascido|body bebe)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)
            val isMale = Regex("\\b(masculino|masculina|masculinos|masculinas|homem|homens|masc|men|man|mens|male|rapaz|cueca|sunga)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)
            val isFemale = Regex("\\b(feminino|feminina|femininos|femininas|mulher|mulheres|fem|women|woman|womens|female|dama|damas|vestido|saia|calcinha|sutia|lingerie|biquini|maio|scarpin|salto alto|cropped)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)

            return when {
                isUnisex -> "Unissex"
                isKids -> "Infantil"
                isMale && !isFemale -> "Masculino"
                isFemale && !isMale -> "Feminino"
                isMale && isFemale -> "Unissex"
                else -> null
            }
        }

        /**
         * Detector do Tipo de Artigo / Vestuário
         */
        fun detectItemType(text: String): String? {
            val norm = normalizeText(text)
            if (norm.isBlank()) return null
            for ((displayName, keywords) in ITEM_TYPE_TAXONOMY) {
                for (kw in keywords) {
                    val r = Regex("\\b${Regex.escape(kw)}\\b", RegexOption.IGNORE_CASE)
                    if (r.containsMatchIn(norm)) {
                        return displayName
                    }
                }
            }
            return null
        }

        /**
         * Verifica se existe Conflito Direto de Gênero entre o desejo do usuário e o produto ofertado.
         * Exemplo: usuário busca "tênis masculino" e o marketplace retorna "tênis feminino rosa".
         */
        fun isGenderConflict(targetGender: String?, productTitle: String): Boolean {
            if (targetGender.isNullOrBlank()) return false
            val norm = normalizeText(productTitle)

            // Produtos unissex nunca conflitam
            if (Regex("\\b(unissex|unisex|neutro)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)) {
                return false
            }

            val hasMale = Regex("\\b(masculino|masculina|masculinos|masculinas|homem|homens|masc|men|man|mens|male|rapaz|cueca|sunga)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)
            val hasFemale = Regex("\\b(feminino|feminina|femininos|femininas|mulher|mulheres|fem|women|woman|womens|female|dama|vestido|saia|calcinha|sutia|lingerie|biquini|maio|scarpin|salto alto|cropped)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)
            val hasKids = Regex("\\b(infantil|infantis|crianca|criancas|menino|menina|bebe|bebes|kids|boy|girl)\\b", RegexOption.IGNORE_CASE).containsMatchIn(norm)

            return when (targetGender) {
                "Masculino" -> {
                    // Usuário quer masculino -> rejeita se for estritamente feminino e NÃO contiver masculinos
                    hasFemale && !hasMale
                }
                "Feminino" -> {
                    // Usuário quer feminino -> rejeita se for estritamente masculino e NÃO contiver femininos
                    hasMale && !hasFemale
                }
                "Infantil" -> {
                    // Usuário quer infantil -> rejeita se for estritamente adulto sem menção infantil
                    !hasKids && (hasMale || hasFemale)
                }
                else -> false
            }
        }

        /**
         * Filtro Rigoroso de Acessórios, Peças Falsas e Conflitos Específicos por Categoria
         */
        fun isAccessoryOrPart(
            title: String,
            price: Double = 0.0,
            categoryKey: String? = null,
            userWantsAccessory: Boolean = false
        ): Boolean {
            if (userWantsAccessory) return false
            val normTitle = normalizeText(title)
            if (normTitle.isBlank()) return false

            // 1. Contexto de Hardware & Placas-Mãe
            val isMotherboardContext = categoryKey == "placa mae" ||
                    normTitle.contains("placa mae") ||
                    normTitle.contains("placamae") ||
                    normTitle.contains("motherboard")

            if (isMotherboardContext) {
                // Guarda de Preço: Placa-mãe funcional nunca custa menos de R$ 65,00
                if (price in 0.01..65.0) return true

                val criticalMotherboardAccessories = listOf(
                    "espelho", "espelhos", "io shield", "i o shield", "shield", "shields", "baffle", "blende",
                    "modulo tpm", "tpm 2.0", "tpm2.0", "tpm", "chip tpm", "tpm pin", "lpc pin", "tpm header",
                    "antena wifi", "antena wi fi", "antena", "antenna",
                    "adesivo", "etiqueta", "badge", "sticker", "logo", "logotipo", "emblema",
                    "bateria cmos", "bateria rtc", "cr2032",
                    "suporte lga", "bracket", "backplate", "suporte am4", "suporte am5", "trava cpu",
                    "parafuso", "parafusos", "parafuso m.2", "parafusos m.2", "standoff",
                    "dissipador", "dissipador m.2", "thermal pad", "pasta termica", "heatsink",
                    "cabo sata", "cabo rgb", "splitter", "divisor rgb", "fita led", "rgb strip",
                    // Falsos positivos decorativos
                    "dia das maes", "dia da maes", "mae de deus", "mae do mar", "maternidade",
                    "quadro decorativo", "placa decorativa", "placa mdf", "mdf", "enfeite", "guirlanda",
                    "porta chaves", "chaveiro", "pingente", "boneco", "action figure"
                )

                if (criticalMotherboardAccessories.any { term ->
                    val r = Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(term)
                }) {
                    return true
                }
            }

            // 2. Contexto de Calçados & Tênis & Sapatos
            val isFootwearContext = categoryKey in listOf("tenis", "sapato", "chuteira", "sandalia", "bota", "chinelo") ||
                    normTitle.contains("tenis") || normTitle.contains("sapato") || normTitle.contains("chuteira") ||
                    normTitle.contains("sandalia") || normTitle.contains("chinelo") || normTitle.contains("bota")

            if (isFootwearContext) {
                // Guarda de Preço: Calçado funcional dificilmente custa < R$ 18,00 na Shopee
                // Se custa < R$ 18,00 e menciona meias/cadarço/chaveiro/adesivo, é periférico!
                val footwearAccessories = listOf(
                    "cadarco", "cadarcos", "palmilha", "palmilhas", "par de meias", "meia soquete", "kit meias",
                    "chaveiro tenis", "chaveiro de tenis", "pingente tenis", "calcadeira", "limpador tenis",
                    "graxa", "adesivo tenis", "protetor de calcanhar", "protetor calcanhar", "trava de chuteira"
                )
                if (footwearAccessories.any { acc ->
                    val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(acc)
                }) {
                    return true
                }

                if (price in 0.01..18.0 && (normTitle.contains("meia") || normTitle.contains("cadarco") || normTitle.contains("chaveiro"))) {
                    return true
                }
            }

            // 3. Contexto de Bonés & Chapéus
            val isHeadwearContext = categoryKey in listOf("bone", "chapeu") ||
                    normTitle.contains("bone") || normTitle.contains("chapeu")

            if (isHeadwearContext) {
                val headwearAccessories = listOf(
                    "suporte de bone", "suporte para bone", "organizador de bone", "organizador para bone",
                    "molde de bone", "molde para lavar", "lavador de bone", "gancho para bone",
                    "porta bone", "porta bones", "suporte adesivo para bone", "cabide de bone"
                )
                if (headwearAccessories.any { acc ->
                    val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(acc)
                }) {
                    return true
                }
            }

            // 4. Contexto de Roupas & Vestuário (Vestidos, Camisas, Calças)
            val isClothingContext = categoryKey in listOf("vestido", "camisa", "camiseta", "calca", "bermuda", "saia", "jaqueta") ||
                    normTitle.contains("vestido") || normTitle.contains("camisa") || normTitle.contains("calca")

            if (isClothingContext) {
                val clothingAccessories = listOf(
                    "cabide", "cabides", "manequim", "molde de costura", "molde para costura",
                    "tecido por metro", "metro de tecido", "linha de costura", "etiqueta avulsa",
                    "remendo termocolante", "ziper avulso", "botao avulso"
                )
                if (clothingAccessories.any { acc ->
                    val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(acc)
                }) {
                    return true
                }
            }

            // 5. Contexto de Relógios & Smartwatches
            val isWatchContext = categoryKey == "relogio" || normTitle.contains("relogio") || normTitle.contains("smartwatch")
            if (isWatchContext) {
                val watchAccessories = listOf(
                    "pulseira para relogio", "pulseira de relogio", "pulseira compativel", "pulseira silicone",
                    "pelicula para relogio", "pelicula relogio", "capa protetora relogio", "carregador relogio",
                    "cabo carregador smartwatch", "caixa vazia"
                )
                if (watchAccessories.any { acc ->
                    val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(acc)
                }) {
                    return true
                }
            }

            // 6. Contexto de Perfumes & Fragrâncias
            val isPerfumeContext = categoryKey == "perfume" || normTitle.contains("perfume")
            if (isPerfumeContext) {
                val perfumeAccessories = listOf(
                    "frasco vazio", "decanter vazio", "borrifador avulso", "amostra 1ml", "tubete vazio"
                )
                if (perfumeAccessories.any { acc ->
                    val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(acc)
                }) {
                    return true
                }
                if (price in 0.01..15.0 && (normTitle.contains("decanter") || normTitle.contains("amostra"))) {
                    return true
                }
            }

            // 7. Contexto de Smart TVs
            val isTvContext = categoryKey in listOf("smart tv", "tv") ||
                    normTitle.contains("smart tv") || normTitle.contains("smarttv") || normTitle.contains("smartv") ||
                    normTitle.contains("televisor") || normTitle.contains("televisao") || normTitle.contains("tv led") ||
                    normTitle.contains("tv 4k")
            if (isTvContext) {
                // Guarda de Preço: TV funcional nunca custa menos de R$ 350,00
                if (price in 0.01..350.0) return true

                val tvAccessories = listOf(
                    "suporte para tv", "suporte de tv", "suporte articulado", "suporte fixo", "suporte parede",
                    "suporte universal", "controle remoto", "controle universal", "placa t-con", "placa t con",
                    "barra de led", "barras de led", "fita led para tv", "cabo de forca", "conversor digital",
                    "antena digital", "painel para tv", "rack para tv", "capa para tv"
                )
                if (tvAccessories.any { acc ->
                    val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(acc)
                }) {
                    return true
                }
            }

            // 8. Contexto de Monitores & Monitores de PC
            val isMonitorContext = categoryKey == "monitor" ||
                    normTitle.contains("monitor") || normTitle.contains("monitor gamer")
            if (isMonitorContext) {
                // Guarda de Preço: Monitor funcional nunca custa menos de R$ 150,00
                if (price in 0.01..150.0) return true

                val monitorAccessories = listOf(
                    "suporte para monitor", "suporte de monitor", "suporte vesa", "braco articulado",
                    "braco a gas", "braco para monitor", "fonte para monitor", "fonte monitor",
                    "cabo vga", "cabo hdmi", "capa para monitor", "luminaria para monitor", "light bar",
                    "prateleira bobino", "prateleira para monitor", "elevador de monitor", "base para monitor",
                    "porta cpu", "suporte dell optiplex", "fita de luz rgb",
                    "monitor de pressao", "pressao arterial", "pressao dos pneus", "tpms", "monitor cardiaco",
                    "monitor de audio"
                )
                if (monitorAccessories.any { acc ->
                    val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(acc)
                }) {
                    return true
                }
            }

            // 9. Contexto de Smartphones & Celulares
            val isSmartphoneContext = categoryKey in listOf("smartphone", "celular") ||
                    normTitle.contains("smartphone") || normTitle.contains("celular")
            if (isSmartphoneContext) {
                // Guarda de Preço: Smartphone funcional nunca custa menos de R$ 140,00
                if (price in 0.01..140.0) return true

                val smartphoneAccessories = listOf(
                    "capa", "capinha", "case", "pelicula", "pelicula 3d", "pelicula ceramica", "pelicula vidro",
                    "suporte para celular", "suporte veicular", "suporte de mesa", "suporte magnetico",
                    "microfone para celular", "microfone lapela", "microfone boya", "tripe para celular",
                    "mini tripe", "lente para celular", "pochete para smartphone", "estojo para smartphone",
                    "cola b7000", "tela touch display", "display frontal", "placa mae celular",
                    "cao robo", "brinquedo mecanico"
                )
                if (smartphoneAccessories.any { acc ->
                    val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                    r.containsMatchIn(normTitle) || normTitle.contains(acc)
                }) {
                    return true
                }
            }

            // 10. Primeira palavra é claramente um acessório periférico
            val firstWords = normTitle.split("\\s+".toRegex()).take(3).joinToString(" ")
            val leadingAccWords = listOf(
                "espelho", "modulo", "suporte", "cabo", "case", "capa", "antena", "parafuso",
                "adesivo", "bateria", "peca", "pecas", "cadarco", "palmilha", "cabide", "pulseira",
                "controle", "fita led", "barra led", "luminaria", "braco", "prateleira", "pelicula",
                "carregador", "microfone", "tripe"
            )
            if (leadingAccWords.any { acc ->
                val r = Regex("\\b${Regex.escape(acc)}\\b", RegexOption.IGNORE_CASE)
                r.containsMatchIn(firstWords) || firstWords.contains(acc)
            }) {
                return true
            }

            // 11. Estrutura gramatical de acessório ("<termo> para placa", "<termo> para bone", etc.)
            val connectors = listOf(" para ", " p ", " compativel com ", " for ")
            for (conn in connectors) {
                if (normTitle.contains(conn)) {
                    val prefix = normTitle.substringBefore(conn)
                    if (leadingAccWords.any { acc -> prefix.contains(acc) }) {
                        return true
                    }
                }
            }

            return false
        }
    }

    private data class BrandMeta(
        val displayName: String,
        val aliases: Set<String>,
        val category: String,
        val defaultPrefixes: List<String>
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun getApiKey(): String {
        return try {
            val keyFromBuildConfig = try {
                BuildConfig::class.java.getField("GEMINI_API_KEY").get(null) as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            if (keyFromBuildConfig.isNotBlank() &&
                keyFromBuildConfig != "YOUR_GEMINI_API_KEY" &&
                keyFromBuildConfig != "DEFAULT_GEMINI_KEY"
            ) {
                return keyFromBuildConfig
            }

            val envKeyField = try {
                BuildConfig::class.java.getField("GEMINI_ENV_KEY").get(null) as? String ?: ""
            } catch (e: Exception) {
                ""
            }
            if (envKeyField.isNotBlank() && envKeyField != "YOUR_GEMINI_API_KEY") {
                return envKeyField
            }

            val systemEnv = System.getenv("GEMINI_API_KEY") ?: ""
            if (systemEnv.isNotBlank() && systemEnv != "YOUR_GEMINI_API_KEY") {
                return systemEnv
            }

            "AQ.Ab8RN6LZYOsHwkf3yp8SbAHd_NO5ZK7wfe8m7H0TftL1wmFJ_w"
        } catch (e: Exception) {
            "AQ.Ab8RN6LZYOsHwkf3yp8SbAHd_NO5ZK7wfe8m7H0TftL1wmFJ_w"
        }
    }

    /**
     * Analisador Inteligente de Busca:
     * 1. Executa inferência on-device semântica em tempo real (0ms).
     * 2. Quando a chave do Gemini estiver configurada, enriquece via Gemini 3.5 Flash na nuvem.
     * 3. Retorna insight completo para as engines de busca com Gênero, Tipo, Marca e Modelo.
     */
    suspend fun analyzeQuery(query: String): AiSearchInsight = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext AiSearchInsight()
        }

        // 1. Instant on-device semantic analysis
        val localInsight = analyzeLocal(trimmed)

        // 2. Check if Gemini API is available
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            return@withContext localInsight
        }

        // 3. Enhance with Gemini 3.5 Flash cloud intelligence
        try {
            val prompt = """
                Analise a seguinte busca de e-commerce (AliExpress e Shopee): "$trimmed"
                Retorne estritamente um JSON com a seguinte estrutura:
                {
                  "detectedBrand": string ou null,
                  "detectedModel": string ou null,
                  "targetGender": "Masculino" | "Feminino" | "Infantil" | "Unissex" | null,
                  "detectedItemType": string ou null,
                  "category": string ou null,
                  "isAccessoryIntent": boolean,
                  "optimizedQueries": ["string"],
                  "summary": "Resumo de 1 linha com emojis para UI"
                }
            """.trimIndent()

            val requestJson = JSONObject().apply {
                put("contents", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", org.json.JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("response_mime_type", "application/json")
                    put("temperature", 0.1)
                })
            }

            val request = Request.Builder()
                .url("$GEMINI_ENDPOINT?key=$apiKey")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string() ?: ""
                val rootJson = JSONObject(bodyString)
                val candidates = rootJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val content = candidates.getJSONObject(0).optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "")
                        if (text.isNotBlank()) {
                            val parsed = JSONObject(text)
                            val brand = parsed.optString("detectedBrand").takeIf { it.isNotBlank() && it != "null" }
                                ?: localInsight.detectedBrand
                            val model = parsed.optString("detectedModel").takeIf { it.isNotBlank() && it != "null" }
                                ?: localInsight.detectedModel
                            val gender = parsed.optString("targetGender").takeIf { it.isNotBlank() && it != "null" }
                                ?: localInsight.targetGender
                            val itemType = parsed.optString("detectedItemType").takeIf { it.isNotBlank() && it != "null" }
                                ?: localInsight.detectedItemType
                            val cat = parsed.optString("category").takeIf { it.isNotBlank() && it != "null" }
                                ?: localInsight.category
                            val isAcc = parsed.optBoolean("isAccessoryIntent", localInsight.isAccessoryIntent)
                            val summary = parsed.optString("summary").takeIf { it.isNotBlank() && it != "null" }
                                ?: localInsight.summary

                            val extraQueries = mutableListOf<String>()
                            val queriesArray = parsed.optJSONArray("optimizedQueries")
                            if (queriesArray != null) {
                                for (i in 0 until queriesArray.length()) {
                                    val q = queriesArray.optString(i).trim()
                                    if (q.isNotBlank() && !extraQueries.contains(q)) {
                                        extraQueries.add(q)
                                    }
                                }
                            }

                            val combinedQueries = (localInsight.optimizedQueries + extraQueries).distinct()

                            return@withContext localInsight.copy(
                                detectedBrand = brand,
                                detectedModel = model,
                                targetGender = gender,
                                detectedItemType = itemType,
                                category = cat,
                                isAccessoryIntent = isAcc,
                                optimizedQueries = combinedQueries,
                                summary = summary,
                                isAiEnhanced = true
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Gemini enhancement skipped (${e.message}), continuing with local AI engine.")
        }

        return@withContext localInsight
    }

    /**
     * Motor de Inferência Semântica Local On-Device (0ms):
     * - Identifica Marca, Modelo, Chipset, Categoria, Gênero e Tipo de Artigo
     * - Gera variantes de alta conversão para Shopee e AliExpress
     */
    fun analyzeLocal(query: String): AiSearchInsight {
        val normalized = normalizeText(query)
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }

        // 1. Detecta intenção de acessório
        val isAccessory = GENERAL_ACCESSORY_WORDS.any { normalized.contains(normalizeText(it)) }

        // 2. Identifica Gênero
        val targetGender = detectGender(normalized)

        // 3. Identifica Tipo de Artigo
        val detectedItemType = detectItemType(normalized)

        // 4. Identifica Categoria Canônica
        var detectedCategoryKey: String? = null
        for ((catKey, synonyms) in CATEGORY_SYNONYMS) {
            val matched = normalized == catKey ||
                    normalized.contains(catKey) ||
                    synonyms.any { syn ->
                        val r = Regex("\\b${Regex.escape(syn)}\\b", RegexOption.IGNORE_CASE)
                        r.containsMatchIn(normalized) || normalized.contains(syn)
                    }
            if (matched) {
                detectedCategoryKey = catKey
                break
            }
        }
        if (detectedCategoryKey == null && detectedItemType != null) {
            detectedCategoryKey = when (detectedItemType) {
                "Tênis" -> "tenis"
                "Sapato Social", "Mocassim" -> "sapato"
                "Chuteira" -> "chuteira"
                "Sandália" -> "sandalia"
                "Chinelo" -> "chinelo"
                "Bota" -> "bota"
                "Boné" -> "bone"
                "Chapéu" -> "chapeu"
                "Camiseta" -> "camiseta"
                "Camisa" -> "camisa"
                "Calça" -> "calca"
                "Bermuda / Short" -> "bermuda"
                "Vestido" -> "vestido"
                "Saia" -> "saia"
                "Casaco / Jaqueta" -> "jaqueta"
                "Roupa Íntima" -> "cueca"
                "Moda Praia" -> "moda praia"
                "Relógio" -> "relogio"
                "Óculos" -> "oculos"
                "Bolsa / Mochila" -> "bolsa"
                "Perfume" -> "perfume"
                else -> null
            }
        }

        // 5. Identifica Marca no catálogo
        var foundBrandKey: String? = null
        var foundBrandMeta: BrandMeta? = null

        for (token in tokens) {
            if (BRAND_DATABASE.containsKey(token)) {
                foundBrandKey = token
                foundBrandMeta = BRAND_DATABASE[token]
                break
            }
        }

        if (foundBrandMeta == null) {
            for ((key, meta) in BRAND_DATABASE) {
                if (normalized.contains(key)) {
                    foundBrandKey = key
                    foundBrandMeta = meta
                    break
                }
            }
        }

        // 6. Identifica Chipset ou Modelo de Hardware específico
        var recognizedChipset: String? = null
        for (chipset in HARDWARE_CHIPSETS) {
            val r = Regex("\\b${Regex.escape(chipset)}\\b", RegexOption.IGNORE_CASE)
            if (r.containsMatchIn(normalized)) {
                recognizedChipset = chipset.uppercase(Locale.ROOT)
                break
            }
        }

        // 7. Extrai tokens do Modelo (descontando Marca, Categoria, Gênero e Acessórios)
        var queryWithoutCatAndBrand = normalized
        if (detectedCategoryKey != null) {
            queryWithoutCatAndBrand = queryWithoutCatAndBrand.replace(detectedCategoryKey, " ")
        }
        if (foundBrandKey != null) {
            queryWithoutCatAndBrand = queryWithoutCatAndBrand.replace(foundBrandKey, " ")
        }
        if (foundBrandMeta != null) {
            for (alias in foundBrandMeta.aliases) {
                queryWithoutCatAndBrand = queryWithoutCatAndBrand.replace(alias, " ")
            }
        }
        // Remove palavras comuns de gênero para isolar o modelo
        val genderFilterWords = listOf("masculino", "masculina", "feminino", "feminina", "infantil", "unissex", "homem", "mulher", "kids")
        for (gw in genderFilterWords) {
            queryWithoutCatAndBrand = queryWithoutCatAndBrand.replace(gw, " ")
        }

        val stopModelTokens = setOf(
            "smartv", "smarttv", "smart", "tv", "de", "pc", "para", "monitor", "smartphone",
            "celular", "telefone", "gamer", "full", "hd", "4k", "led", "ips", "polegadas",
            "original", "novo", "barato", "promocao", "oferta", "com", "sem", "em"
        )
        val modelTokens = queryWithoutCatAndBrand.split("\\s+".toRegex()).filter { token ->
            token.length >= 2 && !GENERAL_ACCESSORY_WORDS.contains(token) && !stopModelTokens.contains(token)
        }

        val detectedModel = when {
            recognizedChipset != null && modelTokens.isNotEmpty() -> {
                recognizedChipset
            }
            modelTokens.isNotEmpty() -> {
                modelTokens.joinToString(" ")
            }
            recognizedChipset != null -> {
                recognizedChipset
            }
            else -> null
        }

        val detectedBrand = foundBrandMeta?.displayName ?: foundBrandKey?.replaceFirstChar { it.uppercase() }

        val categoryDisplay = when (detectedCategoryKey) {
            "placa mae" -> "Placas-Mãe"
            "placa de video" -> "Placas de Vídeo"
            "processador" -> "Processadores"
            "memoria ram" -> "Memória RAM"
            "fonte" -> "Fontes"
            "smartphone", "celular" -> "Smartphones"
            "smart tv", "tv" -> "Smart TVs"
            "monitor" -> "Monitores & Telas"
            "fone" -> "Fones & Áudio"
            "drone" -> "Drones"
            "tenis" -> "Tênis & Calçados"
            "sapato" -> "Sapatos"
            "sandalia" -> "Sandálias"
            "chinelo" -> "Chinelos"
            "bota" -> "Botas"
            "chuteira" -> "Chuteiras"
            "bone" -> "Bonés"
            "chapeu" -> "Chapéus"
            "camiseta" -> "Camisetas"
            "camisa" -> "Camisas"
            "calca" -> "Calças"
            "bermuda" -> "Bermudas & Shorts"
            "vestido" -> "Vestidos"
            "saia" -> "Saias"
            "jaqueta" -> "Casacos & Jaquetas"
            "cueca", "lingerie" -> "Moda Íntima"
            "moda praia" -> "Moda Praia"
            "relogio" -> "Relógios"
            "oculos" -> "Óculos"
            "bolsa", "mochila" -> "Bolsas & Mochilas"
            "perfume" -> "Perfumaria"
            else -> foundBrandMeta?.category ?: "Produtos Oficiais"
        }

        // 8. Geração de Queries Otimizadas para os Marketplaces (Shopee & AliExpress)
        val optimizedQueries = mutableListOf<String>()
        optimizedQueries.add(query)

        // Expansão específica para Smart TVs
        if (detectedCategoryKey in listOf("smart tv", "tv")) {
            if (foundBrandKey != null) {
                val brandName = foundBrandMeta?.displayName ?: foundBrandKey.uppercase(Locale.ROOT)
                optimizedQueries.add("smart tv $foundBrandKey 43")
                optimizedQueries.add("smart tv $foundBrandKey 32")
                optimizedQueries.add("smart tv $foundBrandKey 50")
                optimizedQueries.add("$foundBrandKey tv tizen")
                optimizedQueries.add("smart tv monitor $foundBrandKey")
                optimizedQueries.add("tv $foundBrandKey crystal uhd")
                optimizedQueries.add("smart tv $foundBrandKey dled")
                optimizedQueries.add("smart tv $brandName 4k")
            } else {
                optimizedQueries.add("smart tv 32 dled")
                optimizedQueries.add("smart tv roku")
                optimizedQueries.add("smart tv philco 32")
                optimizedQueries.add("smart tv tcl 32")
                optimizedQueries.add("smart tv 4k 50")
                optimizedQueries.add("smart tv aoc 32")
                optimizedQueries.add("smart tv 43 polegadas")
                optimizedQueries.add("tv led 32 smart")
                optimizedQueries.add("televisao smart tv 32")
                optimizedQueries.add("smart tv box 4k")
                optimizedQueries.add("smart tv stick 4k")
            }
        }

        // Expansão específica para Monitores
        if (detectedCategoryKey == "monitor") {
            if (foundBrandKey != null) {
                val brandName = foundBrandMeta?.displayName ?: foundBrandKey.uppercase(Locale.ROOT)
                optimizedQueries.add("monitor $foundBrandKey 24")
                optimizedQueries.add("monitor $foundBrandKey full hd")
                optimizedQueries.add("monitor gamer $foundBrandKey")
                optimizedQueries.add("monitor $foundBrandKey 27")
                optimizedQueries.add("monitor $brandName 100hz")
                optimizedQueries.add("monitor $foundBrandKey ips")
                optimizedQueries.add("monitor $foundBrandKey 144hz")
            } else {
                optimizedQueries.add("monitor gamer 24")
                optimizedQueries.add("monitor full hd 24")
                optimizedQueries.add("monitor gamer 144hz")
                optimizedQueries.add("monitor pc led 24")
                optimizedQueries.add("monitor samsung 24")
                optimizedQueries.add("monitor lg 24")
                optimizedQueries.add("monitor dell 24")
                optimizedQueries.add("monitor 24 polegadas")
                optimizedQueries.add("monitor ips 100hz")
                optimizedQueries.add("monitor gamer 27")
            }
        }

        // Expansão específica para Smartphones
        if (detectedCategoryKey in listOf("smartphone", "celular")) {
            if (foundBrandKey != null) {
                val brandName = foundBrandMeta?.displayName ?: foundBrandKey.uppercase(Locale.ROOT)
                if (foundBrandKey == "samsung") {
                    optimizedQueries.add("celular samsung galaxy 128gb")
                    optimizedQueries.add("smartphone galaxy a15")
                    optimizedQueries.add("smartphone galaxy a55")
                    optimizedQueries.add("smartphone samsung 128gb")
                    optimizedQueries.add("samsung galaxy 5g 128gb")
                    optimizedQueries.add("samsung galaxy a16 128gb")
                    optimizedQueries.add("samsung galaxy a25 128gb")
                    optimizedQueries.add("celular samsung galaxy 256gb")
                    optimizedQueries.add("smartphone samsung galaxy")
                } else if (foundBrandKey in listOf("xiaomi", "redmi", "poco")) {
                    optimizedQueries.add("smartphone poco")
                    optimizedQueries.add("smartphone xiaomi redmi 128gb")
                    optimizedQueries.add("celular xiaomi poco 5g")
                    optimizedQueries.add("xiaomi redmi note 128gb")
                    optimizedQueries.add("smartphone xiaomi 256gb")
                } else if (foundBrandKey == "motorola") {
                    optimizedQueries.add("smartphone motorola moto g 128gb")
                    optimizedQueries.add("celular motorola moto g")
                    optimizedQueries.add("motorola moto g 128gb")
                    optimizedQueries.add("motorola edge 128gb")
                } else if (foundBrandKey == "apple") {
                    optimizedQueries.add("apple iphone 128gb")
                    optimizedQueries.add("iphone 12 128gb")
                    optimizedQueries.add("iphone 11 128gb")
                    optimizedQueries.add("iphone 13 128gb")
                } else {
                    optimizedQueries.add("smartphone $foundBrandKey 128gb")
                    optimizedQueries.add("celular $foundBrandKey 128gb")
                    optimizedQueries.add("smartphone $brandName 256gb")
                }
            } else {
                optimizedQueries.add("smartphone 128gb")
                optimizedQueries.add("smartphone 256gb")
                optimizedQueries.add("celular samsung galaxy 128gb")
                optimizedQueries.add("smartphone poco")
                optimizedQueries.add("smartphone xiaomi redmi 128gb")
                optimizedQueries.add("smartphone motorola moto g 128gb")
                optimizedQueries.add("celular smartphone 128gb android")
            }
        }

        // Expansão específica para Placas-Mãe
        if (detectedCategoryKey == "placa mae" && foundBrandKey != null) {
            val brandUpper = foundBrandMeta?.displayName ?: foundBrandKey.uppercase(Locale.ROOT)
            optimizedQueries.add("placa mae $foundBrandKey")
            optimizedQueries.add("placa-mãe $foundBrandKey")
            optimizedQueries.add("$brandUpper motherboard")
            optimizedQueries.add("$brandUpper mainboard")
            optimizedQueries.add("$foundBrandKey placa mae")
            if (detectedModel != null) {
                optimizedQueries.add("placa mae $foundBrandKey $detectedModel")
                optimizedQueries.add("$brandUpper $detectedModel motherboard")
                optimizedQueries.add("$foundBrandKey $detectedModel")
            }
        } else if (detectedCategoryKey == "placa mae") {
            optimizedQueries.add("placa-mãe")
            optimizedQueries.add("motherboard")
            optimizedQueries.add("kit placa mae")
        }

        // Expansão específica para Moda / Calçados / Bonés com Gênero
        if (targetGender != null || detectedItemType != null) {
            val item = detectedItemType?.lowercase(Locale.ROOT) ?: detectedCategoryKey ?: ""
            val brand = foundBrandKey ?: ""
            val gender = targetGender?.lowercase(Locale.ROOT) ?: ""

            if (item.isNotBlank() && gender.isNotBlank() && brand.isNotBlank()) {
                optimizedQueries.add("$item $brand $gender")
                optimizedQueries.add("$item $gender $brand")
                optimizedQueries.add("$brand $item $gender")
            } else if (item.isNotBlank() && gender.isNotBlank()) {
                optimizedQueries.add("$item $gender")
                if (item == "tenis") {
                    optimizedQueries.add("tenis casual $gender")
                    optimizedQueries.add("tenis corrida $gender")
                } else if (item == "bone") {
                    optimizedQueries.add("bone aba curva $gender")
                    optimizedQueries.add("bone trucker $gender")
                } else if (item == "vestido") {
                    optimizedQueries.add("vestido longo $gender")
                    optimizedQueries.add("vestido festa $gender")
                }
            } else if (item.isNotBlank() && brand.isNotBlank()) {
                optimizedQueries.add("$item $brand")
                optimizedQueries.add("$brand $item")
            }
        } else if (foundBrandMeta != null && detectedModel != null) {
            for (prefix in foundBrandMeta.defaultPrefixes) {
                val q1 = "$prefix $detectedModel"
                val q2 = "$detectedModel $prefix"
                if (!optimizedQueries.contains(q1)) optimizedQueries.add(q1)
                if (!optimizedQueries.contains(q2)) optimizedQueries.add(q2)
            }
        }

        // 9. Resumo Inteligente para a Barra de IA
        val summary = when {
            detectedBrand != null && detectedItemType != null && targetGender != null -> {
                "IA: $detectedBrand • $detectedItemType ($targetGender)"
            }
            detectedBrand != null && detectedModel != null -> {
                "IA: $detectedBrand $detectedModel • $categoryDisplay"
            }
            detectedItemType != null && targetGender != null -> {
                "IA: $detectedItemType ($targetGender) • Filtro Ativo"
            }
            detectedBrand != null && targetGender != null -> {
                "IA: $detectedBrand • Moda $targetGender"
            }
            detectedBrand != null && detectedCategoryKey != null -> {
                "IA: $categoryDisplay $detectedBrand • Catálogo Oficial"
            }
            detectedBrand != null -> {
                "IA: $detectedBrand • $categoryDisplay"
            }
            targetGender != null -> {
                "IA: Artigos $targetGender • Filtro Ativo"
            }
            detectedCategoryKey != null -> {
                "IA: $categoryDisplay • Filtro Ativo"
            }
            isAccessory -> {
                "IA: Acessórios & Peças • Compatibilidade Ativa"
            }
            else -> {
                "IA Ativa • Busca Otimizada"
            }
        }

        return AiSearchInsight(
            detectedBrand = detectedBrand,
            detectedModel = detectedModel,
            category = categoryDisplay,
            categoryKey = detectedCategoryKey,
            targetGender = targetGender,
            detectedItemType = detectedItemType,
            isAccessoryIntent = isAccessory,
            brandAliases = foundBrandMeta?.aliases ?: emptySet(),
            optimizedQueries = optimizedQueries.distinct(),
            summary = summary,
            isAiEnhanced = false
        )
    }

    /**
     * Avaliador de Produtos Retornados pelo Motor de IA:
     * Examina cada produto individual da Shopee e do AliExpress:
     * - Verifica se é produto genuíno ou acessório/peça conflitante
     * - Valida concordância de Gênero (Masculino vs Feminino vs Infantil)
     * - Valida concordância de Marca, Categoria, Modelo e Tipo de Artigo
     * - Atribui pontuação e gera etiqueta de certificação visual com selo IA
     */
    fun evaluateProduct(
        product: Product,
        insight: AiSearchInsight
    ): ProductAiEvaluation {
        val normTitle = normalizeText(product.title)

        // 1. Verificação de Acessórios & Conflitos Periféricos
        val isAcc = isAccessoryOrPart(
            title = product.title,
            price = product.promoPrice,
            categoryKey = insight.categoryKey,
            userWantsAccessory = insight.isAccessoryIntent
        )

        if (isAcc) {
            return ProductAiEvaluation(
                isGenuineMatch = false,
                isAccessoryOrPart = true,
                isGenderConflict = false,
                relevanceScore = 0
            )
        }

        // 2. Verificação de Conflito de Gênero
        val genderConflict = isGenderConflict(insight.targetGender, product.title)
        if (genderConflict) {
            return ProductAiEvaluation(
                isGenuineMatch = false,
                isAccessoryOrPart = false,
                isGenderConflict = true,
                genderMatched = false,
                relevanceScore = 0
            )
        }

        val productGender = detectGender(product.title)
        val productItemType = detectItemType(product.title)

        // 3. Validação da Categoria
        var categoryMatched = true
        if (insight.categoryKey != null) {
            val synonyms = CATEGORY_SYNONYMS[insight.categoryKey] ?: emptySet()
            categoryMatched = synonyms.any { normTitle.contains(it) } ||
                    (productItemType != null && insight.detectedItemType != null && productItemType == insight.detectedItemType)
            if (!categoryMatched) {
                return ProductAiEvaluation(
                    isGenuineMatch = false,
                    isAccessoryOrPart = false,
                    isGenderConflict = false,
                    categoryMatched = false,
                    relevanceScore = 0
                )
            }
        }

        // 4. Validação da Marca
        var brandMatched = false
        var identifiedBrand: String? = null
        if (insight.brandAliases.isNotEmpty()) {
            brandMatched = insight.brandAliases.any { alias ->
                val r = Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE)
                r.containsMatchIn(normTitle) || normTitle.contains(alias)
            }
            if (!brandMatched) {
                return ProductAiEvaluation(
                    isGenuineMatch = false,
                    isAccessoryOrPart = false,
                    isGenderConflict = false,
                    brandMatched = false,
                    categoryMatched = categoryMatched,
                    relevanceScore = 0
                )
            }
            identifiedBrand = insight.detectedBrand
        }

        // 5. Validação do Modelo / Chipset
        var modelMatched = false
        var identifiedModel: String? = null
        if (!insight.detectedModel.isNullOrBlank()) {
            val modelLower = normalizeText(insight.detectedModel)
            val modelCondensed = modelLower.replace(" ", "")
            val titleCondensed = normTitle.replace(" ", "")
            val modelTokens = modelLower.split("\\s+".toRegex()).filter { it.length >= 2 }

            modelMatched = titleCondensed.contains(modelCondensed) ||
                    (modelTokens.isNotEmpty() && modelTokens.all { normTitle.contains(it) })

            if (!modelMatched) {
                val numericTokens = modelTokens.filter { it.any { ch -> ch.isDigit() } }
                if (numericTokens.isNotEmpty() && !numericTokens.any { normTitle.contains(it) }) {
                    return ProductAiEvaluation(
                        isGenuineMatch = false,
                        isAccessoryOrPart = false,
                        isGenderConflict = false,
                        brandMatched = brandMatched,
                        categoryMatched = categoryMatched,
                        modelMatched = false,
                        relevanceScore = 20
                    )
                }
            }
            identifiedModel = insight.detectedModel
        }

        // 6. Validação do Tipo de Artigo e Gênero
        val genderMatched = insight.targetGender == null || productGender == insight.targetGender || productGender == "Unissex"
        val itemTypeMatched = insight.detectedItemType == null || productItemType == insight.detectedItemType

        // 7. Cálculo do Score de Relevância do Motor de IA
        var score = 70
        if (categoryMatched && insight.categoryKey != null) score += 30
        if (brandMatched) score += 30
        if (modelMatched) score += 25
        if (genderMatched && insight.targetGender != null) score += 25
        if (itemTypeMatched && insight.detectedItemType != null) score += 20

        val firstWords = normTitle.split("\\s+".toRegex()).take(5).joinToString(" ")
        if (insight.detectedBrand != null && firstWords.contains(normalizeText(insight.detectedBrand))) {
            score += 15
        }

        // 8. Rótulo Visual do Selo IA
        val badge = when {
            brandMatched && insight.targetGender != null && productItemType != null -> {
                "✨ IA Match: $identifiedBrand • $productItemType ${insight.targetGender}"
            }
            brandMatched && modelMatched -> "✨ IA Match: $identifiedBrand $identifiedModel"
            insight.targetGender != null && productItemType != null -> {
                "✨ IA Match: $productItemType ${insight.targetGender}"
            }
            brandMatched && insight.category != null -> "✨ IA Match: $identifiedBrand ${insight.category}"
            brandMatched -> "✨ IA Match: $identifiedBrand"
            categoryMatched && insight.category != null -> "✨ IA Match: ${insight.category}"
            insight.targetGender != null -> "✨ IA Match: Artigo ${insight.targetGender}"
            else -> "✨ IA Match"
        }

        return ProductAiEvaluation(
            isGenuineMatch = score >= 90,
            isAccessoryOrPart = false,
            isGenderConflict = false,
            brandMatched = brandMatched,
            categoryMatched = categoryMatched,
            modelMatched = modelMatched,
            genderMatched = genderMatched,
            itemTypeMatched = itemTypeMatched,
            relevanceScore = score.coerceIn(0, 160),
            aiBrand = identifiedBrand,
            aiModel = identifiedModel,
            aiGender = productGender ?: insight.targetGender,
            aiItemType = productItemType ?: insight.detectedItemType,
            badgeLabel = badge
        )
    }

    /**
     * Validador Semântico de Título do Produto
     */
    fun matchesProductTitle(
        cleanTitle: String,
        cleanQuery: String,
        insight: AiSearchInsight,
        price: Double = 0.0
    ): Boolean {
        val normTitle = normalizeText(cleanTitle)
        val normQuery = normalizeText(cleanQuery)
        if (normQuery.isEmpty()) return true

        // Verifica acessório ou conflito de gênero
        if (isAccessoryOrPart(cleanTitle, price, insight.categoryKey, insight.isAccessoryIntent)) {
            return false
        }
        if (isGenderConflict(insight.targetGender, cleanTitle)) {
            return false
        }

        // 1. Categoria
        if (insight.categoryKey != null) {
            val synonyms = CATEGORY_SYNONYMS[insight.categoryKey] ?: emptySet()
            val categoryMatched = synonyms.any { normTitle.contains(it) }
            if (!categoryMatched) return false
        }

        // 2. Marca
        if (insight.brandAliases.isNotEmpty()) {
            val brandMatched = insight.brandAliases.any { alias ->
                val r = Regex("\\b${Regex.escape(alias)}\\b", RegexOption.IGNORE_CASE)
                r.containsMatchIn(normTitle) || normTitle.contains(alias)
            }
            if (!brandMatched) return false
        }

        // 3. Modelo
        if (!insight.detectedModel.isNullOrBlank()) {
            val modelLower = normalizeText(insight.detectedModel)
            val modelCondensed = modelLower.replace(" ", "")
            val titleCondensed = normTitle.replace(" ", "")
            val modelTokens = modelLower.split("\\s+".toRegex()).filter { it.length >= 2 }

            val modelMatched = titleCondensed.contains(modelCondensed) ||
                    (modelTokens.isNotEmpty() && modelTokens.all { normTitle.contains(it) })

            if (!modelMatched) {
                val numericTokens = modelTokens.filter { it.any { ch -> ch.isDigit() } }
                if (numericTokens.isNotEmpty() && !numericTokens.any { normTitle.contains(it) }) {
                    return false
                }
            }
            return true
        }

        if (insight.categoryKey != null || insight.brandAliases.isNotEmpty() || insight.targetGender != null) {
            return true
        }

        // Fallback genérico para termos soltos
        val tokens = normQuery.split("\\s+".toRegex()).filter { it.length >= 2 }
        if (tokens.isEmpty()) return true

        val matchedCount = tokens.count { token ->
            val singular = if (token.endsWith("s") && token.length > 3) token.dropLast(1) else token
            val plural = if (!token.endsWith("s")) "${token}s" else token
            val r = Regex("\\b(${Regex.escape(token)}|${Regex.escape(singular)}|${Regex.escape(plural)})\\b", RegexOption.IGNORE_CASE)
            r.containsMatchIn(normTitle) || normTitle.contains(token)
        }

        if (tokens.size == 2) {
            val combined = tokens[0] + tokens[1]
            val revCombined = tokens[1] + tokens[0]
            return matchedCount == 2 || normTitle.contains(combined) || normTitle.contains(revCombined)
        }

        return matchedCount >= (tokens.size - 1).coerceAtLeast(1)
    }

    /**
     * Refina os resultados da busca com inteligência artificial:
     * - Analisa semântica da busca (marca, categoria, gênero, modelo)
     * - Filtra peças espúrias, acessórios e conflitos de gênero
     * - Destaca melhores ofertas com selo de match verificado
     */
    suspend fun refineComparison(query: String, baseResult: ComparisonResult): ComparisonResult = withContext(Dispatchers.Default) {
        val insight = try {
            analyzeQuery(query)
        } catch (e: Exception) {
            analyzeLocal(query)
        }

        var filteredAccessories = 0
        val refinedAli = baseResult.aliExpressProducts.mapNotNull { prod ->
            val eval = evaluateProduct(prod, insight)
            if (eval.isAccessoryOrPart || eval.isGenderConflict) {
                filteredAccessories++
                null
            } else {
                prod.copy(
                    isExactAiMatch = eval.isGenuineMatch,
                    aiMatchBadge = eval.badgeLabel,
                    aiGender = eval.aiGender
                )
            }
        }

        val refinedShopee = baseResult.shopeeProducts.mapNotNull { prod ->
            val eval = evaluateProduct(prod, insight)
            if (eval.isAccessoryOrPart || eval.isGenderConflict) {
                filteredAccessories++
                null
            } else {
                prod.copy(
                    isExactAiMatch = eval.isGenuineMatch,
                    aiMatchBadge = eval.badgeLabel,
                    aiGender = eval.aiGender
                )
            }
        }

        val updatedInsight = insight.copy(filteredAccessoriesCount = filteredAccessories)

        val finalAli = if (refinedAli.isNotEmpty()) refinedAli else baseResult.aliExpressProducts
        val finalShopee = if (refinedShopee.isNotEmpty()) refinedShopee else baseResult.shopeeProducts
        val combined = (finalAli + finalShopee).sortedBy { it.promoPrice }
        val winner = combined.minByOrNull { it.promoPrice }

        val avgAli = if (finalAli.isNotEmpty()) finalAli.map { it.promoPrice }.average() else 0.0
        val avgShp = if (finalShopee.isNotEmpty()) finalShopee.map { it.promoPrice }.average() else 0.0
        val delta = if (avgAli > 0 && avgShp > 0) {
            val diff = kotlin.math.abs(avgAli - avgShp)
            val higher = maxOf(avgAli, avgShp)
            ((diff / higher) * 100).toInt().toDouble()
        } else 0.0

        baseResult.copy(
            aliExpressProducts = finalAli,
            shopeeProducts = finalShopee,
            unifiedSorted = if (combined.isNotEmpty()) combined else baseResult.unifiedSorted,
            winnerProduct = winner ?: baseResult.winnerProduct,
            averageAliExpressPrice = if (avgAli > 0) avgAli else baseResult.averageAliExpressPrice,
            averageShopeePrice = if (avgShp > 0) avgShp else baseResult.averageShopeePrice,
            priceDeltaPercentage = if (delta > 0) delta else baseResult.priceDeltaPercentage,
            aiInsight = updatedInsight
        )
    }
}

