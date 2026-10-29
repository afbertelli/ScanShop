package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.AiSearchService
import com.example.data.model.ComparisonResult
import com.example.data.model.Product
import com.example.data.model.StoreType
import com.example.data.repository.AffiliateProductRepository
import com.example.data.repository.FavoriteRepository
import com.example.data.repository.ProductFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.example.data.monitor.PriceCheckSummary
import com.example.data.monitor.PriceMonitoringManager

enum class ViewMode {
    SIDE_BY_SIDE,
    UNIFIED_LIST;

    companion object {
        val UNIFIED = UNIFIED_LIST
        val SPLIT = SIDE_BY_SIDE
    }
}

data class SearchUiState(
    val query: String = "",
    val filter: ProductFilter = ProductFilter.RELEVANCE,
    val isSearching: Boolean = false,
    val comparisonResult: ComparisonResult? = null,
    val recentSearches: List<String> = listOf("Fone Bluetooth", "Smartwatch", "Teclado Mecânico", "Mouse Gamer"),
    val viewMode: ViewMode = ViewMode.SIDE_BY_SIDE,
    val errorMessage: String? = null
)

data class DealsUiState(
    val isLoading: Boolean = false,
    val superDeals: List<Product> = emptyList(),
    val selectedStore: StoreType? = null
) {
    val deals: List<Product> get() = superDeals
    val filteredDeals: List<Product> get() = if (selectedStore == null) superDeals else superDeals.filter { it.store == selectedStore }
    val selectedStoreFilter: StoreType? get() = selectedStore
}

class AliShopViewModel(
    private val affiliateRepository: AffiliateProductRepository,
    private val favoriteRepository: FavoriteRepository,
    private val aiSearchService: AiSearchService? = null,
    private val priceMonitoringManager: PriceMonitoringManager? = null
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _dealsState = MutableStateFlow(DealsUiState())
    val dealsState: StateFlow<DealsUiState> = _dealsState.asStateFlow()

    val favorites: StateFlow<List<Product>> = favoriteRepository.allFavorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = favorites.map { list ->
        list.map { it.id }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isCheckingPrices: StateFlow<Boolean> = priceMonitoringManager?.isChecking
        ?: MutableStateFlow(false).asStateFlow()

    val priceCheckSummary: StateFlow<PriceCheckSummary?> = priceMonitoringManager?.lastCheckSummary
        ?: MutableStateFlow<PriceCheckSummary?>(null).asStateFlow()

    init {
        loadDeals()
        priceMonitoringManager?.startPeriodicMonitoring(viewModelScope)
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun onQueryChanged(newQuery: String) {
        _searchState.value = _searchState.value.copy(query = newQuery)
    }

    fun performSearch(queryToSearch: String = _searchState.value.query, forceRefresh: Boolean = false) {
        val trimmed = queryToSearch.trim()
        if (trimmed.isEmpty()) {
            _searchState.value = _searchState.value.copy(
                query = "",
                comparisonResult = null,
                isSearching = false
            )
            return
        }

        viewModelScope.launch {
            _searchState.value = _searchState.value.copy(
                isSearching = true,
                errorMessage = null,
                query = trimmed
            )

            // Add to recent searches if not present
            val currentRecent = _searchState.value.recentSearches.toMutableList()
            if (!currentRecent.contains(trimmed)) {
                currentRecent.add(0, trimmed)
                if (currentRecent.size > 8) currentRecent.removeLast()
            }

            try {
                val baseResult = affiliateRepository.searchProducts(
                    query = trimmed,
                    filter = _searchState.value.filter,
                    forceRefresh = forceRefresh
                )

                // Optional AI refinement
                val finalResult = if (aiSearchService != null) {
                    try {
                        aiSearchService.refineComparison(trimmed, baseResult)
                    } catch (e: Exception) {
                        baseResult
                    }
                } else {
                    baseResult
                }

                _searchState.value = _searchState.value.copy(
                    comparisonResult = finalResult,
                    recentSearches = currentRecent,
                    isSearching = false
                )
            } catch (e: Exception) {
                _searchState.value = _searchState.value.copy(
                    comparisonResult = null,
                    recentSearches = currentRecent,
                    isSearching = false,
                    errorMessage = "Não foi possível carregar os produtos em tempo real. Verifique sua conexão e tente novamente."
                )
            }
        }
    }

    fun setFilter(newFilter: ProductFilter) {
        _searchState.value = _searchState.value.copy(filter = newFilter)
        if (_searchState.value.query.isNotBlank()) {
            performSearch()
        }
    }

    fun toggleViewMode() {
        val newMode = if (_searchState.value.viewMode == ViewMode.UNIFIED_LIST) {
            ViewMode.SIDE_BY_SIDE
        } else {
            ViewMode.UNIFIED_LIST
        }
        _searchState.value = _searchState.value.copy(viewMode = newMode)
    }

    fun refreshDeals() {
        loadDeals(forceRefresh = true)
    }

    fun loadDeals(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _dealsState.value = _dealsState.value.copy(isLoading = true)
            val allDeals = affiliateRepository.getAllDeals(forceRefresh = forceRefresh)
            _dealsState.value = _dealsState.value.copy(
                isLoading = false,
                superDeals = allDeals
            )
        }
    }

    fun setDealsStoreFilter(store: StoreType?) {
        _dealsState.value = _dealsState.value.copy(
            selectedStore = store
        )
    }

    fun toggleFavorite(product: Product) {
        viewModelScope.launch {
            if (favoriteIds.value.contains(product.id)) {
                favoriteRepository.removeFavorite(product.id)
            } else {
                favoriteRepository.addFavorite(product)
            }
        }
    }

    fun removeFavorite(productId: String) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(productId)
        }
    }

    /**
     * Dispara a verificação imediata de preços para todos os itens favoritados
     */
    fun checkFavoritePrices() {
        viewModelScope.launch {
            priceMonitoringManager?.checkFavoritePrices()
        }
    }

    /**
     * Simula uma queda de preço superior a 10% (ex: 15%) em um produto salvo nos favoritos,
     * disparando notificação nativa do Android imediatamente para testar o sistema.
     */
    fun simulatePriceDrop(productId: String, dropPercentage: Double = 15.0) {
        viewModelScope.launch {
            priceMonitoringManager?.simulatePriceDrop(productId, dropPercentage)
        }
    }
}
