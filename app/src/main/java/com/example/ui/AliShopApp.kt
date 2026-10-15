package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.example.ui.screens.DealsScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.DiscountBadge
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
    val dealsState by viewModel.dealsState.collectAsState()
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
            label = "Ofertas",
            selectedIcon = Icons.Filled.Bolt,
            unselectedIcon = Icons.Outlined.Bolt,
            testTag = "tab_ofertas"
        ),
        BottomNavItem(
            label = "Favoritos",
            selectedIcon = Icons.Filled.Favorite,
            unselectedIcon = Icons.Outlined.FavoriteBorder,
            testTag = "tab_favoritos"
        ),
        BottomNavItem(
            label = "Sobre",
            selectedIcon = Icons.Filled.Info,
            unselectedIcon = Icons.Outlined.Info,
            testTag = "tab_sobre"
        )
    )

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
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("bottom_nav_bar"),
                    containerColor = Color.White,
                    tonalElevation = 0.dp
                ) {
                    navItems.forEachIndexed { index, item ->
                        val isSelected = selectedTab == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { viewModel.selectTab(index) },
                            modifier = Modifier.testTag(item.testTag),
                            icon = {
                                if (index == 2 && favorites.isNotEmpty()) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = DiscountBadge,
                                                contentColor = Color.White
                                            ) {
                                                Text(
                                                    text = "${favorites.size}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = item.label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AliExpressRed,
                                selectedTextColor = AliExpressRed,
                                indicatorColor = Color(0xFFFFEDE8),
                                unselectedIconColor = Color(0xFF64748B),
                                unselectedTextColor = Color(0xFF64748B)
                            )
                        )
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
                    favoriteIds = favoriteIds,
                    onQueryChanged = viewModel::onQueryChanged,
                    onSearch = viewModel::performSearch,
                    onFilterSelected = viewModel::setFilter,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onToggleViewMode = viewModel::toggleViewMode
                )
                1 -> DealsScreen(
                    state = dealsState,
                    favoriteIds = favoriteIds,
                    onStoreFilterSelected = viewModel::setDealsStoreFilter,
                    onToggleFavorite = viewModel::toggleFavorite
                )
                2 -> FavoritesScreen(
                    favorites = favorites,
                    onRemoveFavorite = viewModel::removeFavorite,
                    onNavigateToSearch = { viewModel.selectTab(0) }
                )
                3 -> AboutScreen(
                    onNavigateToSearch = { viewModel.selectTab(0) },
                    onNavigateToDeals = { viewModel.selectTab(1) }
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
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Brand Logo & Name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                // Official ScanShop Brand Icon/Logo (Sacola com 'S' idêntica ao ícone)
                ScanShopLogo(
                    size = 38.dp,
                    modifier = Modifier.testTag("app_brand_logo")
                )

                // Brand Name
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            style = SpanStyle(
                                color = AliExpressRed,
                                fontWeight = FontWeight.Black,
                                fontSize = 21.sp
                            )
                        ) {
                            append("Scan")
                        }
                        withStyle(
                            style = SpanStyle(
                                color = ShopeeOrange,
                                fontWeight = FontWeight.Black,
                                fontSize = 21.sp
                            )
                        ) {
                            append("Shop")
                        }
                    },
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Right side: Real-time Comparator Badge + Info/About Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFF1F5F9))
                        .border(BorderStroke(0.5.dp, Color(0xFFCBD5E1)), RoundedCornerShape(16.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(DealGreen)
                        )
                        Text(
                            text = "Ali vs Shopee",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF334155),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // TopBar Info/About Button
                IconButton(
                    onClick = onOpenAbout,
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
                        )
                        .testTag("top_bar_about_button")
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
