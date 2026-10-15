package com.example.data.repository

import com.example.data.local.FavoriteDao
import com.example.data.local.FavoriteProductEntity
import com.example.data.model.Product
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavoriteRepository(private val favoriteDao: FavoriteDao) {

    val allFavorites: Flow<List<Product>> = favoriteDao.getAllFavorites().map { list ->
        list.map { it.toProduct() }
    }

    val favoriteIds: Flow<Set<String>> = favoriteDao.getAllFavorites().map { list ->
        list.map { it.id }.toSet()
    }

    val favoriteCount: Flow<Int> = favoriteDao.getFavoriteCount()

    fun isFavorite(id: String): Flow<Boolean> = favoriteDao.isFavorite(id)

    suspend fun addFavorite(product: Product) {
        val entity = FavoriteProductEntity.fromProduct(product)
        favoriteDao.insertFavorite(entity)
    }

    suspend fun removeFavorite(productId: String) {
        favoriteDao.deleteFavoriteById(productId)
    }

    suspend fun toggleFavorite(product: Product, isCurrentlyFavorite: Boolean) {
        if (isCurrentlyFavorite) {
            removeFavorite(product.id)
        } else {
            addFavorite(product)
        }
    }
}
