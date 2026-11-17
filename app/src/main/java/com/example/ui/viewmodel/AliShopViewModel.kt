package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.MatchedProduct
import com.example.data.model.PromoPipelineStage
import com.example.data.model.PromoSessionResult
import com.example.data.model.SearchPipelineStage
import com.example.data.model.SearchSessionResult
import com.example.data.model.StoreType
import com.example.data.repository.FavoriteRepository
import com.example.data.search.RealTimeSearchOrchestrator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "AliShopViewModel"

data class SearchUiState(
    val query: String = "",
    val lastSubmittedQuery: String? = null,
    val stage: SearchPipelineStage = SearchPipelineStage.Idle,
    val sessionResult: SearchSessionResult? = null,
    val loadingMoreStores: Set<StoreType> = emptySet(),
    val storeHasMore: Map<StoreType, Boolean> = emptyMap(),
    val storePages: Map<StoreType, Int> = emptyMap()
)

data class PromoUiState(
    val stage: PromoPipelineStage = PromoPipelineStage.Idle,
    val isRefreshing: Boolean = false,
    val sessionResult: PromoSessionResult? = null,
    val loadingMoreStores: Set<StoreType> = emptySet(),
    val storeHasMore: Map<StoreType, Boolean> = emptyMap(),
    val storePages: Map<StoreType, Int> = emptyMap()
)

class AliShopViewModel(
    private val searchOrchestrator: RealTimeSearchOrchestrator,
    private val favoriteRepository: FavoriteRepository? = null
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    val favorites: StateFlow<List<MatchedProduct>> =
        (favoriteRepository?.favorites ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = favorites
        .map { list -> list.mapTo(HashSet()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _promoState = MutableStateFlow(PromoUiState())
    val promoState: StateFlow<PromoUiState> = _promoState.asStateFlow()

    private var activeSearchJob: Job? = null
    private var activePromoJob: Job? = null
    private val searchBackfillJobs = mutableMapOf<StoreType, Job>()
    private val searchLoadMoreJobs = mutableMapOf<StoreType, Job>()
    private val promoLoadMoreJobs = mutableMapOf<StoreType, Job>()
    private val searchStateMergeMutex = Mutex()
    private val storeOperationMutexes = StoreType.entries.associateWith { Mutex() }

    fun toggleFavorite(product: MatchedProduct) {
        viewModelScope.launch {
            favoriteRepository?.toggleFavorite(product)
        }
    }

    fun clearAllFavorites() {
        viewModelScope.launch {
            favoriteRepository?.clearAll()
        }
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
        if (index == 1 && _promoState.value.stage is PromoPipelineStage.Idle) {
            loadWeeklyPromos()
        }
    }

    fun loadWeeklyPromos(forceRefresh: Boolean = false) {
        if (!forceRefresh && _promoState.value.stage is PromoPipelineStage.Loading) return
        if (!forceRefresh && _promoState.value.isRefreshing) return

        activePromoJob?.cancel()
        promoLoadMoreJobs.values.forEach { it.cancel() }
        promoLoadMoreJobs.clear()
        val existing = _promoState.value.sessionResult
        _promoState.value = _promoState.value.copy(
            stage = if (existing == null) PromoPipelineStage.Loading else PromoPipelineStage.Completed,
            isRefreshing = true,
            loadingMoreStores = emptySet()
        )

        activePromoJob = viewModelScope.launch {
            try {
                val result = searchOrchestrator.fetchWeeklyBestDeals()
                _promoState.value = PromoUiState(
                    stage = PromoPipelineStage.Completed,
                    isRefreshing = false,
                    sessionResult = result,
                    loadingMoreStores = emptySet(),
                    storeHasMore = result.storeReports.associate { it.store to (it.approvedCount > 0) },
                    storePages = StoreType.entries.associateWith { 1 }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Weekly promos fetch failed", e)
                _promoState.value = if (existing != null) {
                    _promoState.value.copy(stage = PromoPipelineStage.Completed, isRefreshing = false)
                } else {
                    PromoUiState(
                        stage = PromoPipelineStage.Error("Não foi possível carregar os melhores da semana. Toque em Atualizar."),
                        isRefreshing = false,
                        sessionResult = null
                    )
                }
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        _searchState.value = _searchState.value.copy(query = newQuery)
    }

    fun performSearch(queryToSearch: String = _searchState.value.query) {
        val trimmed = queryToSearch.trim()
        activeSearchJob?.cancel()
        searchBackfillJobs.values.forEach { it.cancel() }
        searchBackfillJobs.clear()
        searchLoadMoreJobs.values.forEach { it.cancel() }
        searchLoadMoreJobs.clear()

        if (trimmed.isBlank()) {
            _searchState.value = SearchUiState(query = "")
            return
        }

        _searchState.value = _searchState.value.copy(
            query = trimmed,
            lastSubmittedQuery = trimmed,
            stage = SearchPipelineStage.AnalyzingIntent,
            sessionResult = null,
            loadingMoreStores = emptySet(),
            storeHasMore = emptyMap(),
            storePages = emptyMap()
        )

        activeSearchJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val result = searchOrchestrator.executeSearch(trimmed) { stage ->
                    _searchState.value = _searchState.value.copy(stage = stage)
                }
                _searchState.value = _searchState.value.copy(
                    stage = SearchPipelineStage.Completed,
                    sessionResult = result,
                    loadingMoreStores = emptySet(),
                    storeHasMore = result.storeReports.associate { it.store to (it.hasMore ?: (it.approvedCount > 0)) },
                    storePages = StoreType.entries.associateWith { 1 }
                )
                launchBackgroundStoreBackfill(trimmed, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Real-time search failed", e)
                _searchState.value = _searchState.value.copy(
                    stage = SearchPipelineStage.Error(
                        "Não foi possível concluir a busca em tempo real. Verifique sua conexão e tente novamente."
                    )
                )
            }
        }
    }

    private fun launchBackgroundStoreBackfill(
        querySnapshot: String,
        initialSession: SearchSessionResult,
        targetStores: List<StoreType> = StoreType.entries
    ) {
        val storesToFill = targetStores.filter { store ->
            val report = initialSession.storeReports.firstOrNull { it.store == store }
            val currentCount = initialSession.products.count { it.store == store }
            if (store == StoreType.MERCADO_LIVRE && report?.hasMore == false) return@filter false
            currentCount in 1 until 30 || (report != null && report.rawCandidatesCount > currentCount && currentCount < 30)
        }
        if (storesToFill.isEmpty()) return

        for (store in storesToFill) {
            searchBackfillJobs[store]?.cancel()
            searchBackfillJobs[store] = viewModelScope.launch {
                try {
                    storeOperationMutexes.getValue(store).withLock {
                        val currentLatest = _searchState.value
                        if (currentLatest.lastSubmittedQuery != querySnapshot) return@withLock
                        val latestSessionBefore = currentLatest.sessionResult ?: return@withLock
                        val currentPage = currentLatest.storePages[store] ?: 1
                        val (storeUpdatedSession, newPage, hasMore) =
                            searchOrchestrator.backfillStoreToMinimum(
                                currentSession = latestSessionBefore,
                                store = store,
                                targetCount = 30,
                                startPage = currentPage
                            )
                        searchStateMergeMutex.withLock {
                            val latest = _searchState.value
                            val latestSession = latest.sessionResult
                            if (latest.lastSubmittedQuery == querySnapshot && latestSession != null) {
                                val storeNewItems = storeUpdatedSession.products.filter { it.store == store }
                                val mergedProducts = (latestSession.products + storeNewItems).distinctBy { it.id }
                                val updatedReport = storeUpdatedSession.storeReports.firstOrNull { it.store == store }
                                val mergedReports = latestSession.storeReports.map { r ->
                                    if (r.store == store && updatedReport != null) {
                                        updatedReport.copy(approvedCount = mergedProducts.count { it.store == store })
                                    } else {
                                        r
                                    }
                                }
                                val existingHasMore = latest.storeHasMore[store] ?: true
                                val resolvedHasMore = if (store == StoreType.MERCADO_LIVRE) {
                                    hasMore && mergedProducts.count { it.store == store } > 0
                                } else {
                                    (hasMore || existingHasMore) && mergedProducts.count { it.store == store } > 0
                                }
                                _searchState.value = latest.copy(
                                    sessionResult = latestSession.copy(
                                        products = mergedProducts,
                                        storeReports = mergedReports
                                    ),
                                    storePages = latest.storePages + (store to maxOf(latest.storePages[store] ?: 1, newPage)),
                                    storeHasMore = latest.storeHasMore + (store to resolvedHasMore)
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Background backfill skipped for $store", e)
                }
            }
        }
    }

    fun retrySearchStore(store: StoreType) {
        val current = _searchState.value
        val session = current.sessionResult ?: return
        if (store in current.loadingMoreStores) return
        val querySnapshot = current.lastSubmittedQuery ?: return

        _searchState.value = current.copy(loadingMoreStores = current.loadingMoreStores + store)
        searchLoadMoreJobs[store]?.cancel()
        searchLoadMoreJobs[store] = viewModelScope.launch {
            try {
                val retried = searchOrchestrator.retrySingleStore(session, store)
                val storeCount = retried.products.count { it.store == store }
                searchStateMergeMutex.withLock {
                    val latest = _searchState.value
                    val latestSession = latest.sessionResult
                    if (latest.lastSubmittedQuery == querySnapshot && latestSession != null) {
                        val mergedProducts = (latestSession.products.filter { it.store != store } +
                            retried.products.filter { it.store == store }).distinctBy { it.id }
                        val updatedReport = retried.storeReports.firstOrNull { it.store == store }
                        val mergedReports = latestSession.storeReports.map { r ->
                            if (r.store == store && updatedReport != null) updatedReport else r
                        }
                        val nextSession = latestSession.copy(
                            products = mergedProducts,
                            storeReports = mergedReports
                        )
                        val hasMoreForStore = updatedReport?.hasMore ?: (storeCount > 0)
                        _searchState.value = latest.copy(
                            sessionResult = nextSession,
                            loadingMoreStores = latest.loadingMoreStores - store,
                            storeHasMore = latest.storeHasMore + (store to hasMoreForStore),
                            storePages = latest.storePages + (store to 1)
                        )
                        if (storeCount in 1 until 30 && hasMoreForStore) {
                            launchBackgroundStoreBackfill(
                                querySnapshot = querySnapshot,
                                initialSession = nextSession,
                                targetStores = listOf(store)
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Retry failed for $store", e)
            } finally {
                val latest = _searchState.value
                if (store in latest.loadingMoreStores) {
                    _searchState.value = latest.copy(loadingMoreStores = latest.loadingMoreStores - store)
                }
            }
        }
    }

    fun loadMoreSearchStore(store: StoreType) {
        val current = _searchState.value
        if (current.sessionResult == null) return
        if (current.stage !is SearchPipelineStage.Completed) return
        if (store in current.loadingMoreStores || current.storeHasMore[store] == false) return

        val querySnapshot = current.lastSubmittedQuery
        _searchState.value = current.copy(loadingMoreStores = current.loadingMoreStores + store)

        searchLoadMoreJobs[store]?.cancel()
        searchLoadMoreJobs[store] = viewModelScope.launch {
            try {
                storeOperationMutexes.getValue(store).withLock {
                    val snapshotBeforeFetch = _searchState.value
                    val activeSession = snapshotBeforeFetch.sessionResult ?: return@withLock
                    if (snapshotBeforeFetch.lastSubmittedQuery != querySnapshot) return@withLock
                    val nextPage = (snapshotBeforeFetch.storePages[store] ?: 1) + 1

                    val (storeUpdatedSession, fetchedPage, hasMore) = searchOrchestrator.loadMoreSearchResults(
                        currentSession = activeSession,
                        store = store,
                        nextPage = nextPage
                    )
                    searchStateMergeMutex.withLock {
                        val latest = _searchState.value
                        val latestSession = latest.sessionResult
                        if (latest.lastSubmittedQuery == querySnapshot && latestSession != null) {
                            val newItemsForStore = storeUpdatedSession.products.filter { it.store == store }
                            val mergedProducts = (latestSession.products + newItemsForStore).distinctBy { it.id }
                            val updatedStoreReport = storeUpdatedSession.storeReports.firstOrNull { it.store == store }
                            val mergedReports = latestSession.storeReports.map { report ->
                                if (report.store == store && updatedStoreReport != null) {
                                    updatedStoreReport.copy(approvedCount = mergedProducts.count { it.store == store })
                                } else {
                                    report
                                }
                            }
                            _searchState.value = latest.copy(
                                sessionResult = latestSession.copy(
                                    products = mergedProducts,
                                    storeReports = mergedReports,
                                    usedAiVerifier = latestSession.usedAiVerifier || storeUpdatedSession.usedAiVerifier
                                ),
                                loadingMoreStores = latest.loadingMoreStores - store,
                                storeHasMore = latest.storeHasMore + (store to hasMore),
                                storePages = latest.storePages + (store to maxOf(latest.storePages[store] ?: 1, fetchedPage))
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Load more search items failed for $store", e)
                val latest = _searchState.value
                _searchState.value = latest.copy(
                    loadingMoreStores = latest.loadingMoreStores - store
                )
            } finally {
                val latest = _searchState.value
                if (store in latest.loadingMoreStores) {
                    _searchState.value = latest.copy(loadingMoreStores = latest.loadingMoreStores - store)
                }
            }
        }
    }

    fun loadMorePromoStore(store: StoreType) {
        val current = _promoState.value
        val session = current.sessionResult ?: return
        if (store in current.loadingMoreStores || current.storeHasMore[store] == false) return

        val nextPage = (current.storePages[store] ?: 1) + 1
        val updatedAtSnapshot = session.updatedAtMillis
        _promoState.value = current.copy(loadingMoreStores = current.loadingMoreStores + store)

        promoLoadMoreJobs[store]?.cancel()
        promoLoadMoreJobs[store] = viewModelScope.launch {
            try {
                val (storeUpdatedPromo, hasMore) = searchOrchestrator.loadMoreWeeklyDeals(
                    currentPromo = session,
                    store = store,
                    nextPage = nextPage
                )
                val latest = _promoState.value
                val latestSession = latest.sessionResult
                if (latestSession != null && latestSession.updatedAtMillis == updatedAtSnapshot) {
                    val newItemsForStore = storeUpdatedPromo.products.filter { it.store == store }
                    val mergedProducts = (latestSession.products + newItemsForStore).distinctBy { it.id }
                    val updatedStoreReport = storeUpdatedPromo.storeReports.firstOrNull { it.store == store }
                    val mergedReports = latestSession.storeReports.map { report ->
                        if (report.store == store && updatedStoreReport != null) {
                            updatedStoreReport.copy(approvedCount = mergedProducts.count { it.store == store })
                        } else {
                            report
                        }
                    }
                    _promoState.value = latest.copy(
                        sessionResult = latestSession.copy(
                            products = mergedProducts,
                            storeReports = mergedReports
                        ),
                        loadingMoreStores = latest.loadingMoreStores - store,
                        storeHasMore = latest.storeHasMore + (store to hasMore),
                        storePages = latest.storePages + (store to nextPage)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Load more promo items failed for $store", e)
                val latest = _promoState.value
                _promoState.value = latest.copy(
                    loadingMoreStores = latest.loadingMoreStores - store
                )
            } finally {
                val latest = _promoState.value
                if (store in latest.loadingMoreStores) {
                    _promoState.value = latest.copy(loadingMoreStores = latest.loadingMoreStores - store)
                }
            }
        }
    }

    fun retryPromoStore(store: StoreType) {
        val current = _promoState.value
        val session = current.sessionResult ?: run {
            loadWeeklyPromos(forceRefresh = true)
            return
        }
        if (store in current.loadingMoreStores) return

        val updatedAtSnapshot = session.updatedAtMillis
        _promoState.value = current.copy(loadingMoreStores = current.loadingMoreStores + store)
        promoLoadMoreJobs[store]?.cancel()
        promoLoadMoreJobs[store] = viewModelScope.launch {
            try {
                val retried = searchOrchestrator.retryWeeklyStore(session, store)
                val storeCount = retried.products.count { it.store == store }
                val latest = _promoState.value
                val latestSession = latest.sessionResult
                if (latestSession != null && latestSession.updatedAtMillis == updatedAtSnapshot) {
                    val mergedProducts = (latestSession.products.filter { it.store != store } +
                        retried.products.filter { it.store == store }).distinctBy { it.id }
                    val updatedReport = retried.storeReports.firstOrNull { it.store == store }
                    val mergedReports = latestSession.storeReports.map { r ->
                        if (r.store == store && updatedReport != null) updatedReport else r
                    }
                    _promoState.value = latest.copy(
                        sessionResult = latestSession.copy(
                            products = mergedProducts,
                            storeReports = mergedReports,
                            updatedAtMillis = retried.updatedAtMillis
                        ),
                        loadingMoreStores = latest.loadingMoreStores - store,
                        storeHasMore = latest.storeHasMore + (store to (storeCount > 0)),
                        storePages = latest.storePages + (store to 1)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Retry promo store failed for $store", e)
            } finally {
                val latest = _promoState.value
                if (store in latest.loadingMoreStores) {
                    _promoState.value = latest.copy(loadingMoreStores = latest.loadingMoreStores - store)
                }
            }
        }
    }
}
