package com.example

import com.example.data.ai.AiSearchService
import com.example.data.model.ComparisonResult
import com.example.data.model.Product
import com.example.data.model.StoreType
import com.example.data.repository.QueryIntentEngine
import com.example.data.repository.RelevanceGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes unitários JUnit 4 para validação estrita do RelevanceGate e regressão do refineComparison.
 */
class RelevanceGateTest {

    private fun checkRelevance(
        title: String,
        query: String,
        price: Double = 0.0,
        auditor: com.example.data.repository.GeminiGrayZoneAuditor? = null
    ): Boolean {
        val intent = QueryIntentEngine.parseQueryIntent(query)
        val result = RelevanceGate.evaluate(
            title = title,
            price = price,
            query = query,
            intent = intent,
            auditor = auditor
        )
        return result.isApproved
    }

    @Test
    fun testMouseGamer() {
        val query = "mouse gamer"
        // Aceitar produto legítimo
        assertTrue(checkRelevance("Mouse Gamer Sem Fio RGB 12000 DPI", query))

        // Rejeitar acessórios e tapetes
        assertFalse(checkRelevance("Tapete de mesa gamer grande", query))
        assertFalse(checkRelevance("Pés de mouse skates", query))
        assertFalse(checkRelevance("Suporte para mouse", query))
    }

    @Test
    fun testPlacaMaeX99() {
        val query = "placa mae x99"
        // Aceitar placa-mãe x99 real
        assertTrue(checkRelevance("Placa Mãe X99 LGA2011-3 DDR4", query))

        // Rejeitar chipset diferente (B450), módulo TPM e espelho traseiro
        assertFalse(checkRelevance("Placa Mãe B450", query))
        assertFalse(checkRelevance("Módulo TPM 2.0", query))
        assertFalse(checkRelevance("Espelho I/O para placa x99", query))
    }

    @Test
    fun testSmartTvSamsung43() {
        val query = "smart tv samsung 43"
        // Aceitar TV Samsung 43 legítima
        assertTrue(checkRelevance("Smart TV Samsung 43 polegadas 4K", query, price = 1899.0))

        // Rejeitar controle remoto, suporte de parede e marca concorrente (LG)
        assertFalse(checkRelevance("Controle remoto Samsung TV", query, price = 25.0))
        assertFalse(checkRelevance("Suporte de parede TV 43", query, price = 39.0))
        assertFalse(checkRelevance("Smart TV LG 43", query, price = 1799.0))
    }

    @Test
    fun testIPhone13() {
        val query = "iphone 13"
        // Aceitar iPhone 13 real
        assertTrue(checkRelevance("iPhone 13 128GB", query, price = 3500.0))

        // Rejeitar capa e modelo divergente (iPhone 12)
        assertFalse(checkRelevance("Capa iPhone 13", query, price = 20.0))
        assertFalse(checkRelevance("iPhone 12 128GB", query, price = 2800.0))
    }

    @Test
    fun testTenisNikeMasculino() {
        val query = "tenis nike masculino"
        // Aceitar tênis Nike masculino
        assertTrue(checkRelevance("Tênis Nike Air Max Masculino", query))

        // Rejeitar tênis feminino e cadarço avulso
        assertFalse(checkRelevance("Tênis Nike Feminino", query))
        assertFalse(checkRelevance("Cadarço para tênis Nike", query))
    }

    @Test
    fun testFoneBluetooth() {
        val query = "fone bluetooth"
        // Aceitar fone TWS
        assertTrue(checkRelevance("Fone de Ouvido Bluetooth TWS", query))

        // Rejeitar almofada para fone
        assertFalse(checkRelevance("Almofada para fone de ouvido", query))
    }

    @Test
    fun testBuscaGenericaCafeteiraItaliana() {
        val query = "cafeteira italiana 6 xicaras"
        // Aceitar quando todos os termos significativos estão presentes
        assertTrue(checkRelevance("Cafeteira Italiana 6 Xícaras em Aço Inox", query))

        // Rejeitar item parcial ou divergente
        assertFalse(checkRelevance("Xícara de porcelana", query))
    }

    @Test
    fun testRegressaoBugPreenchimentoArtificial() = runBlocking {
        val aiService = AiSearchService()
        val query = "placa mae x99"

        // Criar 5 produtos genuínos de placa-mãe X99
        val approvedAli = (1..5).map { idx ->
            Product(
                id = "ali_approved_$idx",
                store = StoreType.ALIEXPRESS,
                title = "Placa Mãe X99 LGA 2011-3 Machinist MR9A Pro DDR4 v$idx",
                imageUrl = "https://example.com/mobo$idx.jpg",
                originalPrice = 500.0,
                promoPrice = 380.0,
                discountPercentage = 24,
                rating = 4.9,
                salesCount = 800,
                freeShipping = true,
                nationalSeller = false,
                couponAvailable = false,
                affiliateUrl = "https://ali.com/item/$idx",
                deepLinkUrl = "aliexpress://item/$idx"
            )
        }

        // Criar 40 produtos reprovados (espelhos, TPM, B450, cabos)
        val rejectedAli = (1..40).map { idx ->
            val junkTitle = when (idx % 4) {
                0 -> "Espelho I/O Shield Traseiro Placa X99 Mod $idx"
                1 -> "Módulo TPM 2.0 para Placa Mãe $idx"
                2 -> "Placa Mãe B450 Gaming AM4 $idx"
                else -> "Cabo SATA 3 para Placa X99 $idx"
            }
            Product(
                id = "ali_rejected_$idx",
                store = StoreType.ALIEXPRESS,
                title = junkTitle,
                imageUrl = "https://example.com/junk$idx.jpg",
                originalPrice = 40.0,
                promoPrice = 25.0,
                discountPercentage = 30,
                rating = 4.2,
                salesCount = 50,
                freeShipping = false,
                nationalSeller = false,
                couponAvailable = false,
                affiliateUrl = "https://ali.com/junk/$idx",
                deepLinkUrl = "aliexpress://junk/$idx"
            )
        }

        val baseResult = ComparisonResult(
            query = query,
            aliExpressProducts = approvedAli + rejectedAli, // Total 45 itens
            shopeeProducts = emptyList(),
            unifiedSorted = approvedAli + rejectedAli
        )

        val refined = aiService.refineComparison(query, baseResult)

        // Verificação estrita: deve devolver EXATAMENTE 5 produtos, NUNCA completar para 35 ou 50!
        assertEquals("Deve conter exatamente os 5 produtos aprovados pelo filtro", 5, refined.aliExpressProducts.size)
        assertEquals("Nenhum produto deve ter badge artificial de 85% Compatível",
            0, refined.aliExpressProducts.count { it.aiConfidenceBadge == "85% Compatível" })

        // Todos os 5 retornados devem ser legítimos X99
        for (prod in refined.aliExpressProducts) {
            assertTrue(prod.title.contains("X99", ignoreCase = true))
            assertFalse(prod.title.contains("Espelho", ignoreCase = true))
            assertFalse(prod.title.contains("TPM", ignoreCase = true))
            assertFalse(prod.title.contains("B450", ignoreCase = true))
        }
    }

    @Test
    fun testTecnologiaSecaoE() {
        // REJEITAR:
        assertFalse(checkRelevance("Mouse Sem Fio Silencioso Escritório Ergonômico Vertical 1600DPI", "mouse gamer"))
        assertFalse(checkRelevance("Mouse com fio USB Óptico 1200DPI", "mouse sem fio"))
        assertFalse(checkRelevance("Teclado USB com fio de membrana ABNT2 escritório", "teclado mecanico"))
        assertFalse(checkRelevance("Fone de ouvido com fio P2 3.5mm com microfone", "fone bluetooth"))
        assertFalse(checkRelevance("Relógio de Pulso Masculino Quartzo Analógico Aço Inoxidável", "smartwatch"))
        assertFalse(checkRelevance("Notebook Intel Celeron 4GB 128GB escritório estudante", "notebook gamer"))
        assertFalse(checkRelevance("Caixa Externa Enclosure para SSD M.2 NVMe 1TB USB-C", "ssd 1tb"))
        assertFalse(checkRelevance("Cadeira de Escritório Giratória Malha Ergonômica", "cadeira gamer"))
        assertFalse(checkRelevance("TV Box 4K Android 13 Conversor Smart Wifi", "smart tv", price = 120.0))
        assertFalse(checkRelevance("Processador Intel Core i3 10100 LGA1200", "processador intel core i5 12400"))
        assertFalse(checkRelevance("Apple AirPods 3 com estojo de carregamento", "airpods pro 2"))
        assertFalse(checkRelevance("Monitor 24 polegadas 75Hz Full HD escritório", "monitor gamer 144hz"))

        // ACEITAR:
        assertTrue(checkRelevance("Memória RAM 16G DDR4 3200MHz Desktop Gamer", "memoria ram 16gb"))
        assertTrue(checkRelevance("Kit Memória RAM 2x8GB DDR4 3200MHz Dissipador", "memoria ram 16gb"))
        assertTrue(checkRelevance("Placa de Vídeo RTX 4060 8GB GDDR6 Gaming OC", "rtx 4060"))
        assertTrue(checkRelevance("Placa Gráfica Gaming RTX 3060 12GB Ventoinha Dupla", "rtx 3060"))
        assertTrue(checkRelevance("2024 Novo Mouse Sem Fio Bluetooth 2.4G Recarregável Silencioso Ergonômico Gamer Para Computador Laptop PC", "mouse gamer"))
        assertTrue(checkRelevance("Teclado Mecânico Sem Fio 75% Hot Swap RGB Gasket Bluetooth 5.0 2.4G", "teclado mecanico"))
        assertTrue(checkRelevance("TWS Fones De Ouvido Sem Fio Bluetooth 5.3 Redução de Ruído HiFi Esportes à prova d água", "fone bluetooth"))
        assertTrue(checkRelevance("Série 9 Smart Watch Homens Mulheres Bluetooth Chamada Fitness Tracker NFC", "smartwatch"))
        assertTrue(checkRelevance("Headphone Sem Fio Over-Ear Bluetooth 5.0 Dobrável", "fone de ouvido"))
    }

    @Test
    fun testNaoTecnologiaSecaoE() {
        // Auditor falso simulando a resposta do Gemini para casos de zona cinzenta
        val fakeGeminiAuditor = object : com.example.data.repository.GeminiGrayZoneAuditor {
            override suspend fun auditBatch(query: String, items: List<Pair<String, String>>): Map<String, Boolean> {
                return items.associate { (id, title) ->
                    // Simula Gemini identificando que bateria avulsa não é a furadeira pesquisada
                    val isBateriaAvulsa = title.contains("Bateria", ignoreCase = true) &&
                            query.contains("furadeira", ignoreCase = true) &&
                            !title.contains("Furadeira Parafusadeira", ignoreCase = true)
                    id to !isBateriaAvulsa
                }
            }
        }

        // 1. Tênis corrida masculino
        assertTrue(checkRelevance("Tênis de Corrida Masculino Respirável Leve Amortecimento", "tenis corrida masculino"))
        assertFalse(checkRelevance("Tênis Feminino Casual Plataforma", "tenis corrida masculino"))
        assertFalse(checkRelevance("Palmilha de Gel para Tênis de Corrida", "tenis corrida masculino"))
        assertFalse(checkRelevance("Meia Esportiva Masculina para Corrida 5 Pares", "tenis corrida masculino"))

        // 2. Perfume masculino 100ml
        assertTrue(checkRelevance("Perfume Masculino Amadeirado 100ml Eau de Parfum", "perfume masculino 100ml"))
        assertFalse(checkRelevance("Perfume Feminino Floral 100ml", "perfume masculino 100ml"))
        assertFalse(checkRelevance("Frasco Vazio Spray para Perfume 100ml", "perfume masculino 100ml"))

        // 3. Panela de pressão 5 litros
        assertTrue(checkRelevance("Panela de Pressão Inox 5 Litros Antiaderente", "panela de pressao 5 litros"))
        assertFalse(checkRelevance("Tampa para Panela de Pressão 5 Litros", "panela de pressao 5 litros"))
        assertFalse(checkRelevance("Panela Elétrica Multifuncional 5L", "panela de pressao 5 litros"))

        // 4. Fita LED RGB 5 metros
        assertTrue(checkRelevance("Fita LED RGB 5 Metros 5050 Com Controle Remoto", "fita led rgb 5 metros"))
        assertFalse(checkRelevance("Controle Remoto para Fita LED RGB", "fita led rgb 5 metros"))
        assertFalse(checkRelevance("Fonte 12V para Fita LED 5 metros", "fita led rgb 5 metros"))

        // 5. Bolsa feminina
        assertTrue(checkRelevance("Bolsa Feminina Transversal Couro PU Pequena", "bolsa feminina"))
        assertFalse(checkRelevance("Bolsa Masculina Executiva Couro", "bolsa feminina"))
        assertFalse(checkRelevance("Alça para Bolsa Feminina Corrente", "bolsa feminina"))

        // 6. Óculos de sol polarizado
        assertTrue(checkRelevance("Óculos de Sol Polarizado UV400 Masculino Esportivo", "oculos de sol polarizado"))
        assertFalse(checkRelevance("Estojo para Óculos de Sol Rígido", "oculos de sol polarizado"))
        assertFalse(checkRelevance("Óculos de Grau Armação Acetato", "oculos de sol polarizado"))

        // 7. Furadeira bateria 21v
        assertTrue(checkRelevance("Furadeira Parafusadeira Sem Fio 21V 2 Baterias", "furadeira bateria 21v", auditor = fakeGeminiAuditor))
        assertFalse(checkRelevance("Broca para Furadeira Kit 21 Peças", "furadeira bateria 21v", auditor = fakeGeminiAuditor))
        assertFalse(checkRelevance("Bateria 21V para Furadeira Makita", "furadeira bateria 21v", auditor = fakeGeminiAuditor))

        // 8. Brinquedo carrinho controle remoto
        assertTrue(checkRelevance("Carrinho de Controle Remoto 4x4 Off Road Brinquedo", "brinquedo carrinho controle remoto"))
        assertFalse(checkRelevance("Controle Remoto Universal para TV", "brinquedo carrinho controle remoto"))

        // 9. Capa iphone 13
        assertTrue(checkRelevance("Capa de Silicone para iPhone 13 Pro", "capa iphone 13"))
        assertFalse(checkRelevance("Capa para iPhone 12", "capa iphone 13"))
        assertFalse(checkRelevance("Apple iPhone 13 128GB", "capa iphone 13"))

        // 10. Camiseta masculina algodao
        assertTrue(checkRelevance("Camiseta Masculina 100% Algodão Gola Redonda", "camiseta masculina algodao"))
        assertFalse(checkRelevance("Camiseta Feminina Algodão Estampada", "camiseta masculina algodao"))
        assertFalse(checkRelevance("Bermuda Masculina Algodão", "camiseta masculina algodao"))

        // 11. Garrafa termica 1 litro
        assertTrue(checkRelevance("Garrafa Térmica Inox 1 Litro Vácuo", "garrafa termica 1 litro"))
        assertTrue(checkRelevance("Garrafa Térmica Inox 1L Isolamento a Vácuo", "garrafa termica 1 litro"))
        assertFalse(checkRelevance("Tampa para Garrafa Térmica 1 Litro", "garrafa termica 1 litro"))

        // 12. Mochila escolar
        assertTrue(checkRelevance("Mochila Escolar Infantil Impermeável Grande", "mochila escolar"))
        assertFalse(checkRelevance("Lancheira para Mochila Escolar", "mochila escolar"))

        // 13. Relógio feminino
        assertTrue(checkRelevance("Relógio Feminino Quartzo Aço Rosa", "relogio feminino"))
        assertFalse(checkRelevance("Pulseira para Relógio Feminino Aço", "relogio feminino"))
        assertFalse(checkRelevance("Relógio Masculino Esportivo", "relogio feminino"))

        // 14. Ventilador de mesa
        assertTrue(checkRelevance("Ventilador de Mesa Silencioso USB Portátil 3 Velocidades", "ventilador de mesa"))
        assertFalse(checkRelevance("Hélice para Ventilador de Mesa", "ventilador de mesa"))
        assertFalse(checkRelevance("Ventilador de Teto com Luz", "ventilador de mesa"))

        // 15. Aspirador de po robo
        assertTrue(checkRelevance("Robô Aspirador de Pó Inteligente Wifi Varredura", "aspirador de po robo"))
        assertFalse(checkRelevance("Filtro para Robô Aspirador de Pó", "aspirador de po robo"))
        assertFalse(checkRelevance("Aspirador de Pó Portátil de Carro", "aspirador de po robo"))
    }
}
