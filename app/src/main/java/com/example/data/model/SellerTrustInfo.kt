package com.example.data.model

enum class SellerBadgeType {
    OFFICIAL,    // Loja Oficial / Choice Oficial (Dourado / Azul Real)
    VERIFIED,    // Vendedor Verificado (Verde Esmeralda / Teal)
    TRUSTED,     // Vendedor Confiável (Roxo / Índigo)
    STANDARD     // Vendedor Regular
}

data class SellerTrustInfo(
    val sellerName: String,
    val trustScore: Int,                 // 0 a 100
    val isVerified: Boolean,             // true se trustScore >= 80 ou Loja Oficial
    val badgeText: String,               // ex: "✓ Vendedor Verificado", "🛡️ Loja Oficial"
    val badgeType: SellerBadgeType,
    val rating: Double,                  // 1.0 a 5.0
    val salesCount: Int,                 // Quantidade de vendas
    val positiveRatingPercent: Int,      // ex: 98%
    val hasDeliveryGuarantee: Boolean,   // Envio Rápido / Garantido
    val isNationalStock: Boolean,        // Estoque Brasil / CNPJ
    val responseRatePercent: Int = 96,   // Taxa de resposta no chat
    val returnGuaranteeDays: Int = 7,    // Prazo de devolução garantida
    val evaluationReasons: List<String> = emptyList() // Critérios avaliados pelo algoritmo
)
