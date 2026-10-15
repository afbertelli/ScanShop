package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite_products ORDER BY savedAtTimestamp DESC")
    fun getAllFavorites(): Flow<List<FavoriteProductEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(product: FavoriteProductEntity)

    @Query("DELETE FROM favorite_products WHERE id = :id")
    suspend fun deleteFavoriteById(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_products WHERE id = :id)")
    fun isFavorite(id: String): Flow<Boolean>

    @Query("SELECT COUNT(*) FROM favorite_products")
    fun getFavoriteCount(): Flow<Int>
}
