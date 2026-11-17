package com.example

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import com.example.ai.configureAppCheck
import com.example.data.local.ScanShopDatabase
import com.example.data.network.MercadoLivreClient
import com.example.data.network.NetworkClientProvider
import com.example.data.repository.FavoriteRepository
import com.example.data.search.RealTimeSearchOrchestrator
import com.example.ui.ScanShopApp
import com.example.ui.theme.ScanShopTheme
import com.example.ui.viewmodel.ScanShopViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ScanShopViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val mlClient = MercadoLivreClient(applicationContext)
                val searchOrchestrator = RealTimeSearchOrchestrator(mercadoLivreClient = mlClient)
                val database = ScanShopDatabase.getInstance(applicationContext)
                val favoriteRepository = FavoriteRepository(database.favoriteDao())
                return ScanShopViewModel(
                    searchOrchestrator = searchOrchestrator,
                    favoriteRepository = favoriteRepository
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Required: Configure Firebase App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        // Ensure no cached search/promo images remain on disk; Room database stores only Favoritos
        runCatching {
            applicationContext.cacheDir.resolve("image_cache").deleteRecursively()
        }

        Coil.setImageLoader {
            val imageClient = NetworkClientProvider.httpClient.newBuilder()
                .addInterceptor { chain ->
                    val orig = chain.request()
                    val host = orig.url.host.lowercase()
                    val builder = orig.newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        )
                        .header("Accept", "image/webp,image/jpeg,image/png,image/*;q=0.8")
                    when {
                        host.contains("bing.com") || host.contains("bing.net") ->
                            builder.header("Referer", "https://www.bing.com/")
                        host.contains("mlstatic.com") || host.contains("mercadolivre") ->
                            builder.header("Referer", "https://www.mercadolivre.com.br/")
                        host.contains("yimg.com") ->
                            builder.header("Referer", "https://br.images.search.yahoo.com/")
                        host.contains("gstatic.com") ->
                            builder.header("Referer", "https://www.google.com/")
                    }
                    chain.proceed(builder.build())
                }
                .build()
            ImageLoader.Builder(applicationContext)
                .okHttpClient(imageClient)
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(true)
                .build()
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()

        val initialTab = intent.getIntExtra("EXTRA_NAVIGATE_TAB", -1)
        if (initialTab >= 0) {
            viewModel.selectTab(initialTab)
        }

        setContent {
            ScanShopTheme {
                ScanShopApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getIntExtra("EXTRA_NAVIGATE_TAB", -1)
        if (tab >= 0) {
            viewModel.selectTab(tab)
        }
    }
}
