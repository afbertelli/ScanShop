package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.ScanShopLogo
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.PromoScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.ShopeeOrange
import com.example.ui.viewmodel.AliShopViewModel

data class BottomNavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
fun AliShopApp(
    viewModel: AliShopViewModel,
    modifier: Modifier = Modifier
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val promoState by viewModel.promoState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()

    val navItems = listOf(
        BottomNavItem(
            label = "Busca",
            selectedIcon = Icons.Filled.Search,
            unselectedIcon = Icons.Outlined.Search,
            testTag = "tab_busca"
        ),
        BottomNavItem(
            label = "Promoções",
            selectedIcon = Icons.Filled.LocalOffer,
            unselectedIcon = Icons.Outlined.LocalOffer,
            testTag = "tab_promocoes"
        ),
        BottomNavItem(
            label = "Favoritos",
            selectedIcon = Icons.Filled.Favorite,
            unselectedIcon = Icons.Outlined.FavoriteBorder,
            testTag = "tab_favoritos"
        )
    )

    BackHandler(enabled = selectedTab != 0) {
        viewModel.selectTab(0)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AliShopTopBar(
                isAboutSelected = selectedTab == 3,
                onOpenAbout = { viewModel.selectTab(3) }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                color = Color.White,
                shadowElevation = 2.dp,
                border = BorderStroke(0.5.dp, Color(0xFFE5E7EB))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("bottom_nav_bar"),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    navItems.forEachIndexed { index, item ->
                        val isSelected = selectedTab == index
                        val tintColor = if (isSelected) AliExpressRed else Color(0xFF64748B)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { viewModel.selectTab(index) }
                                .testTag(item.testTag)
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                        ) {
                            if (index == 2 && favorites.isNotEmpty()) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = AliExpressRed,
                                            contentColor = Color.White
                                        ) {
                                            Text(
                                                text = if (favorites.size > 99) "99+" else favorites.size.toString(),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label,
                                        tint = tintColor,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                    tint = tintColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Text(
                                text = item.label,
                                fontSize = 10.5.sp,
                                lineHeight = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = tintColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> SearchScreen(
                    state = searchState,
                    onQueryChanged = viewModel::onQueryChanged,
                    onSearch = viewModel::performSearch,
                    favoriteIds = favoriteIds,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onLoadMoreStore = viewModel::loadMoreSearchStore,
                    onRetryStore = viewModel::retrySearchStore
                )
                1 -> PromoScreen(
                    state = promoState,
                    onRefresh = { viewModel.loadWeeklyPromos(forceRefresh = true) },
                    favoriteIds = favoriteIds,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onLoadMoreStore = viewModel::loadMorePromoStore,
                    onRetryStore = viewModel::retryPromoStore
                )
                2 -> FavoritesScreen(
                    favorites = favorites,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onClearAll = viewModel::clearAllFavorites,
                    onNavigateToSearch = { viewModel.selectTab(0) }
                )
                3 -> AboutScreen(
                    onNavigateToSearch = { viewModel.selectTab(0) }
                )
            }
        }
    }
}

@Composable
fun AliShopTopBar(
    isAboutSelected: Boolean = false,
    onOpenAbout: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        color = Color.White,
        shadowElevation = 1.dp,
        border = BorderStroke(0.5.dp, Color(0xFFE5E7EB))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 2.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ScanShopLogo(
                    size = 28.dp,
                    modifier = Modifier.testTag("app_brand_logo")
                )

                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                color = AliExpressRed,
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp
                            )
                        ) {
                            append("Scan")
                        }
                        withStyle(
                            style = SpanStyle(
                                color = ShopeeOrange,
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp
                            )
                        ) {
                            append("Shop")
                        }
                    },
                    maxLines = 1,
                    softWrap = false
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier.testTag("badge_live_comparison")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(AliExpressRed)
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(ShopeeOrange)
                        )
                        Spacer(modifier = Modifier.width(1.dp))
                        Text(
                            text = "AO VIVO",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DealGreen,
                            maxLines = 1
                        )
                    }
                }

                IconButton(
                    onClick = onOpenAbout,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("top_bar_about_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(if (isAboutSelected) Color(0xFFFFEDE8) else Color(0xFFF8FAFC))
                            .border(
                                BorderStroke(
                                    0.5.dp,
                                    if (isAboutSelected) AliExpressRed.copy(alpha = 0.5f) else Color(0xFFE2E8F0)
                                ),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isAboutSelected) Icons.Filled.Info else Icons.Outlined.Info,
                            contentDescription = "Sobre o Desenvolvedor e o App",
                            tint = if (isAboutSelected) AliExpressRed else Color(0xFF64748B),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
