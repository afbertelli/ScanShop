package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import com.example.data.ai.AiSearchService
import com.example.data.local.AliShopDatabase
import com.example.data.repository.AffiliateProductRepository
import com.example.data.repository.FavoriteRepository
import com.example.ui.AliShopApp
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AliShopViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = Room.databaseBuilder(
            applicationContext,
            AliShopDatabase::class.java,
            "alishop_database"
        ).fallbackToDestructiveMigration().build()

        val favoriteRepository = FavoriteRepository(db.favoriteDao())
        val affiliateRepository = AffiliateProductRepository()
        val aiSearchService = AiSearchService()

        val viewModel = AliShopViewModel(
            affiliateRepository = affiliateRepository,
            favoriteRepository = favoriteRepository,
            aiSearchService = aiSearchService
        )

        setContent {
            MyApplicationTheme {
                AliShopApp(viewModel = viewModel)
            }
        }
    }
}
