package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.data.ai.AiSearchService
import com.example.data.local.AliShopDatabase
import com.example.data.repository.AffiliateProductRepository
import com.example.data.repository.FavoriteRepository
import com.example.ui.AliShopApp
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AliShopViewModel

import com.example.data.monitor.PriceMonitoringManager
import com.example.data.notification.PriceNotificationHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        PriceNotificationHelper.createNotificationChannel(applicationContext)

        val db = AliShopDatabase.getDatabase(applicationContext)

        val favoriteRepository = FavoriteRepository(db.favoriteDao())
        val affiliateRepository = AffiliateProductRepository()
        val aiSearchService = AiSearchService()
        val priceMonitoringManager = PriceMonitoringManager(
            context = applicationContext,
            favoriteRepository = favoriteRepository,
            affiliateRepository = affiliateRepository
        )

        val viewModel = AliShopViewModel(
            affiliateRepository = affiliateRepository,
            favoriteRepository = favoriteRepository,
            aiSearchService = aiSearchService,
            priceMonitoringManager = priceMonitoringManager
        )

        val initialTab = intent.getIntExtra("EXTRA_NAVIGATE_TAB", -1)
        if (initialTab >= 0) {
            viewModel.selectTab(initialTab)
        }

        setContent {
            MyApplicationTheme {
                AliShopApp(viewModel = viewModel)
            }
        }
    }
}
