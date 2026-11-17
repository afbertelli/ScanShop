package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteProductEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ScanShopDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao

    companion object {
        private const val LEGACY_DB_NAME = "alishop_database"
        private const val DB_NAME = "scanshop_database"

        @Volatile
        private var instance: ScanShopDatabase? = null

        private fun migrateLegacyDatabaseIfNeeded(context: Context) {
            val newDb = context.getDatabasePath(DB_NAME)
            val legacyDb = context.getDatabasePath(LEGACY_DB_NAME)
            if (!newDb.exists() && legacyDb.exists()) {
                legacyDb.renameTo(newDb)
                listOf("-wal", "-shm", "-journal").forEach { suffix ->
                    val legacySidecar = context.getDatabasePath("$LEGACY_DB_NAME$suffix")
                    if (legacySidecar.exists()) {
                        legacySidecar.renameTo(context.getDatabasePath("$DB_NAME$suffix"))
                    }
                }
            }
        }

        fun getInstance(context: Context): ScanShopDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    migrateLegacyDatabaseIfNeeded(appContext)
                    Room.databaseBuilder(
                        appContext,
                        ScanShopDatabase::class.java,
                        DB_NAME
                    )
                        .fallbackToDestructiveMigration(dropAllTables = true)
                        .build()
                        .also { instance = it }
                }
            }
        }
    }
}
