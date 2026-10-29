package com.example.data.monitor

import android.content.Context
import com.example.data.local.FavoriteProductEntity
import com.example.data.model.StoreType
import com.example.data.notification.PriceNotificationHelper
import com.example.data.repository.AffiliateProductRepository
import com.example.data.repository.FavoriteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class PriceCheckSummary(
    val checkedCount: Int,
    val dropsDetectedCount: Int,
    val notificationsSentCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Gerenciador de Monitoramento de Preços:
 * - Monitora EXCLUSIVAMENTE produtos salvos em FAVORITOS ("produtos salvos em favoritos somente").
 * - Detecta variações de preços no AliExpress e Shopee.
 * - Quando a queda for superior a 10% (> 10%), dispara notificação nativa do Android via [PriceNotificationHelper].
 * - Persiste o status no Room Database para atualização em tempo real na interface.
 */
class PriceMonitoringManager(
    private val context: Context,
    private val favoriteRepository: FavoriteRepository,
    private val affiliateRepository: AffiliateProductRepository
) {

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _lastCheckSummary = MutableStateFlow<PriceCheckSummary?>(null)
    val lastCheckSummary: StateFlow<PriceCheckSummary?> = _lastCheckSummary.asStateFlow()

    /**
     * Realiza a checagem de preços de todos os produtos atualmente salvos em favoritos.
     * Calcula se houve redução superior a 10% em relação ao preço inicial salvo.
     */
    suspend fun checkFavoritePrices(): PriceCheckSummary {
        _isChecking.value = true
        var dropsCount = 0
        var notificationsCount = 0

        try {
            val favorites = favoriteRepository.getAllFavoritesList()
            val now = System.currentTimeMillis()

            for (fav in favorites) {
                // Obter o novo preço conferindo no repositório de afiliados
                val updatedPrice = fetchUpdatedPriceForFavorite(fav)

                if (updatedPrice != null && updatedPrice < fav.initialSavedPrice) {
                    val dropPercent = ((fav.initialSavedPrice - updatedPrice) / fav.initialSavedPrice) * 100.0

                    // Requisito estrito: queda de valor SUPERIOR a 10%
                    if (dropPercent > 10.0) {
                        dropsCount++
                        val roundedDrop = (dropPercent * 10.0).roundToInt() / 10.0

                        // Dispara notificação se ainda não notificado com esse preço ou se foi uma nova queda
                        val shouldNotify = !fav.hasPriceDropAlert || updatedPrice < (fav.lastNotifiedPrice - 0.5)
                        if (shouldNotify) {
                            notificationsCount++
                            PriceNotificationHelper.sendPriceDropNotification(
                                context = context,
                                productId = fav.id,
                                productTitle = fav.title,
                                storeName = fav.storeName,
                                oldPrice = fav.initialSavedPrice,
                                newPrice = updatedPrice,
                                dropPercent = roundedDrop
                            )
                        }

                        // Atualiza o banco de dados de favoritos
                        favoriteRepository.updatePriceDrop(
                            id = fav.id,
                            newPrice = updatedPrice,
                            dropPercent = roundedDrop,
                            hasAlert = true,
                            timestamp = now
                        )
                    } else {
                        // Preço baixou mas menos de 10%, atualiza sem alarme
                        favoriteRepository.updatePriceDrop(
                            id = fav.id,
                            newPrice = updatedPrice,
                            dropPercent = (dropPercent * 10.0).roundToInt() / 10.0,
                            hasAlert = false,
                            timestamp = now
                        )
                    }
                } else if (updatedPrice != null) {
                    // Preço permaneceu igual ou subiu
                    favoriteRepository.updatePriceDrop(
                        id = fav.id,
                        newPrice = updatedPrice,
                        dropPercent = 0.0,
                        hasAlert = false,
                        timestamp = now
                    )
                }
            }

            val summary = PriceCheckSummary(
                checkedCount = favorites.size,
                dropsDetectedCount = dropsCount,
                notificationsSentCount = notificationsCount,
                timestamp = now
            )
            _lastCheckSummary.value = summary
            return summary
        } finally {
            _isChecking.value = false
        }
    }

    /**
     * Busca o preço mais recente no AliExpress ou Shopee para o produto favoritado.
     */
    private suspend fun fetchUpdatedPriceForFavorite(fav: FavoriteProductEntity): Double? {
        return try {
            val queryWords = fav.title.split(" ")
                .filter { it.length > 2 }
                .take(4)
                .joinToString(" ")

            val searchResult = affiliateRepository.searchProducts(
                query = queryWords,
                forceRefresh = true
            )

            val isAli = fav.storeName == StoreType.ALIEXPRESS.name
            val candidates = if (isAli) searchResult.aliExpressProducts else searchResult.shopeeProducts

            // Procura o produto exato por id ou melhor correspondência
            val matched = candidates.firstOrNull { it.id == fav.id }
                ?: candidates.firstOrNull()

            matched?.promoPrice ?: fav.promoPrice
        } catch (_: Exception) {
            fav.promoPrice
        }
    }

    /**
     * Permite simular instantaneamente uma queda de preço superior a 10% (padrão: 15% ou personalizado)
     * em um produto favoritado para testar na prática a notificação do Android e a interface.
     */
    suspend fun simulatePriceDrop(productId: String, dropPercentage: Double = 15.0): Boolean {
        val fav = favoriteRepository.getFavoriteById(productId) ?: return false
        val basePrice = fav.initialSavedPrice.takeIf { it > 0.0 } ?: fav.promoPrice

        // Calcula novo preço com desconto superior a 10%
        val discountFactor = 1.0 - (dropPercentage / 100.0)
        val newPrice = (basePrice * discountFactor * 100.0).roundToInt() / 100.0
        val effectiveDrop = ((basePrice - newPrice) / basePrice) * 100.0
        val now = System.currentTimeMillis()

        // Dispara notificação do sistema
        PriceNotificationHelper.sendPriceDropNotification(
            context = context,
            productId = fav.id,
            productTitle = fav.title,
            storeName = fav.storeName,
            oldPrice = basePrice,
            newPrice = newPrice,
            dropPercent = effectiveDrop
        )

        // Atualiza entidade salva no Room
        favoriteRepository.updatePriceDrop(
            id = fav.id,
            newPrice = newPrice,
            dropPercent = (effectiveDrop * 10.0).roundToInt() / 10.0,
            hasAlert = true,
            timestamp = now
        )

        _lastCheckSummary.value = PriceCheckSummary(
            checkedCount = 1,
            dropsDetectedCount = 1,
            notificationsSentCount = 1,
            timestamp = now
        )

        return true
    }

    /**
     * Inicia o monitoramento periódico em segundo plano para checar preços a cada intervalo
     */
    fun startPeriodicMonitoring(
        scope: CoroutineScope,
        intervalMillis: Long = 15 * 60 * 1000L // 15 minutos
    ) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMillis)
                try {
                    checkFavoritePrices()
                } catch (_: Exception) {
                    // Silencioso em caso de erro de rede em background
                }
            }
        }
    }
}
