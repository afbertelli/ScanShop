package com.example.data.repository

import com.example.data.local.FavoriteDao
import com.example.data.local.FavoriteProductEntity
import com.example.data.model.MatchedProduct
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavoriteRepository(
    private val favoriteDao: FavoriteDao
) {
    val favorites: Flow<List<MatchedProduct>> = favoriteDao.observeFavorites()
        .map { entities -> entities.map { it.toMatchedProduct() } }

    suspend fun toggleFavorite(product: MatchedProduct) {
        if (favoriteDao.isFavorite(product.id)) {
            favoriteDao.deleteById(product.id)
        } else {
            favoriteDao.upsertFavorite(FavoriteProductEntity.fromMatchedProduct(product))
        }
    }

    suspend fun removeFavorite(productId: String) {
        favoriteDao.deleteById(productId)
    }

    suspend fun clearAll() {
        favoriteDao.deleteAll()
    }
}
