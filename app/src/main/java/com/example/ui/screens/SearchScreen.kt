package com.example.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.MatchedProduct
import com.example.data.model.SearchPipelineStage
import com.example.data.model.SearchSessionResult
import com.example.data.model.StoreFetchReport
import com.example.data.model.StoreType
import com.example.ui.components.SearchBarComponent
import com.example.ui.components.StoreSectionCard
import com.example.ui.components.shareProduct
import com.example.ui.theme.AliExpressRed
import com.example.ui.theme.DealGreen
import com.example.ui.theme.DealGreenBg
import com.example.ui.util.DeepLinkUtils
import com.example.ui.viewmodel.SearchUiState

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onSearch: (String) -> Unit,
    favoriteIds: Set<String> = emptySet(),
    onToggleFavorite: ((MatchedProduct) -> Unit)? = null,
    onLoadMoreStore: (StoreType) -> Unit = {},
    onRetryStore: (StoreType) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()

            if (!spokenText.isNullOrBlank()) {
                onQueryChanged(spokenText)
                onSearch(spokenText)
            }
        }
    }

    val startVoiceSearch = {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale o produto que procura...")
            }
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                "Reconhecimento de voz não suportado neste aparelho.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("search_screen")
    ) {
        SearchBarComponent(
            query = state.query,
            onQueryChanged = onQueryChanged,
            onSearch = onSearch,
            onVoiceSearchClick = startVoiceSearch
        )

        when (val stage = state.stage) {
            is SearchPipelineStage.Idle -> IdleSearchHero()

            is SearchPipelineStage.AnalyzingIntent,
            is SearchPipelineStage.FetchingStores,
            is SearchPipelineStage.NeuralAndAiScoring -> {
                PipelineProgressCard(
                    query = state.lastSubmittedQuery.orEmpty(),
                    stage = stage
                )
            }

            is SearchPipelineStage.Error -> {
                ErrorStateCard(message = stage.message)
            }

            is SearchPipelineStage.Completed -> {
                val result = state.sessionResult
                if (result == null) {
                    IdleSearchHero()
                } else {
                    val openStoreHandler = remember(context) {
                        { product: MatchedProduct ->
                            DeepLinkUtils.openAffiliateUrl(
                                context = context,
                                store = product.store,
                                affiliateUrl = product.affiliateUrl,
                                deepLinkUrl = product.deepLinkUrl
                            )
                        }
                    }
                    val shareProductHandler = remember(context) {
                        { product: MatchedProduct ->
                            shareProduct(context, product)
                        }
                    }
                    CompletedSearchContent(
                        result = result,
                        loadingMoreStores = state.loadingMoreStores,
                        storeHasMore = state.storeHasMore,
                        favoriteIds = favoriteIds,
                        onToggleFavorite = onToggleFavorite,
                        onLoadMoreStore = onLoadMoreStore,
                        onRetryStore = onRetryStore,
                        onOpenStore = openStoreHandler,
                        onShare = shareProductHandler
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleSearchHero() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFF1EE)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = AliExpressRed,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = "Busca em Tempo Real",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Compare resultados ao vivo no Mercado Livre, Shopee e AliExpress digitando o nome, marca ou modelo do produto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun StableSearchSpinner() {
    Box(
        modifier = Modifier.size(28.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            color = AliExpressRed,
            strokeWidth = 3.dp
        )
    }
}

@Composable
private fun PipelineProgressCard(
    query: String,
    stage: SearchPipelineStage
) {
    val activeStep = when (stage) {
        SearchPipelineStage.AnalyzingIntent -> 1
        SearchPipelineStage.FetchingStores -> 2
        SearchPipelineStage.NeuralAndAiScoring -> 3
        else -> 1
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .testTag("pipeline_progress_card"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    key("stable_search_spinner") {
                        StableSearchSpinner()
                    }
                    Column {
                        Text(
                            text = "Buscando \"$query\"",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Consultando lojas em tempo real",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFFF1EE),
                    border = BorderStroke(1.dp, AliExpressRed.copy(alpha = 0.30f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("waiting_highlight_banner")
                ) {
                    Text(
                        text = stringResource(R.string.search_waiting_highlight),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AliExpressRed,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                PipelineStepRow(
                    stepNumber = 1,
                    currentStep = activeStep,
                    label = "Preparando consulta do produto"
                )
                PipelineStepRow(
                    stepNumber = 2,
                    currentStep = activeStep,
                    label = "Consultando Mercado Livre, Shopee e AliExpress"
                )
                PipelineStepRow(
                    stepNumber = 3,
                    currentStep = activeStep,
                    label = "Selecionando os melhores resultados"
                )
            }
        }
    }
}

@Composable
private fun PipelineStepRow(
    stepNumber: Int,
    currentStep: Int,
    label: String
) {
    val done = currentStep > stepNumber
    val active = currentStep == stepNumber
    val targetBgColor = when {
        done -> DealGreenBg
        active -> Color(0xFFFFF1EE)
        else -> Color(0xFFF8FAFC)
    }
    val targetTextColor = when {
        done -> DealGreen
        active -> AliExpressRed
        else -> Color(0xFF94A3B8)
    }
    val bgColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(durationMillis = 220),
        label = "step_bg_$stepNumber"
    )
    val textColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(durationMillis = 220),
        label = "step_fg_$stepNumber"
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (done) "OK" else "$stepNumber",
                style = MaterialTheme.typography.labelSmall,
                color = textColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

@Composable
private fun ErrorStateCard(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, Color(0xFFFECACA))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SearchOff,
                    contentDescription = null,
                    tint = AliExpressRed,
                    modifier = Modifier.size(34.dp)
                )
                Text(
                    text = "Falha na consulta em tempo real",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CompletedSearchContent(
    result: SearchSessionResult,
    loadingMoreStores: Set<StoreType>,
    storeHasMore: Map<StoreType, Boolean>,
    favoriteIds: Set<String>,
    onToggleFavorite: ((MatchedProduct) -> Unit)?,
    onLoadMoreStore: (StoreType) -> Unit,
    onRetryStore: (StoreType) -> Unit,
    onOpenStore: (MatchedProduct) -> Unit,
    onShare: (MatchedProduct) -> Unit
) {
    val orderedStores = remember {
        listOf(StoreType.MERCADO_LIVRE, StoreType.SHOPEE, StoreType.ALIEXPRESS)
    }
    val reportsByStore = remember(result.storeReports) {
        result.storeReports.associateBy { it.store }
    }
    val productsByStore = remember(result.products) {
        result.products.groupBy { it.store }
    }
    val cleanQuery = result.intentProfile.searchQueriesPt.firstOrNull().orEmpty()
        .ifBlank { result.intentProfile.rawQuery }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val targetSectionHeight = ((maxHeight - 28.dp) / 3f).coerceAtLeast(134.dp)
        val targetCardHeight = (targetSectionHeight - 42.dp).coerceAtLeast(96.dp)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("search_results_list"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            items(
                items = orderedStores,
                key = { it.name }
            ) { storeType ->
                val report = reportsByStore[storeType] ?: StoreFetchReport(
                    store = storeType,
                    rawCandidatesCount = 0,
                    approvedCount = 0
                )
                val storeProducts = productsByStore[storeType].orEmpty()
                val storeLoadMore = remember(storeType, onLoadMoreStore) { { onLoadMoreStore(storeType) } }
                val storeRetry = remember(storeType, onRetryStore) { { onRetryStore(storeType) } }
                StoreSectionCard(
                    store = storeType,
                    report = report,
                    products = storeProducts,
                    emptyFallbackLabel = "Nenhuma oferta encontrada em ${storeType.displayName} para \"$cleanQuery\".",
                    onOpenStore = onOpenStore,
                    onShare = onShare,
                    favoriteIds = favoriteIds,
                    onToggleFavorite = onToggleFavorite,
                    isLoadingMore = storeType in loadingMoreStores,
                    hasMore = storeHasMore[storeType] ?: false,
                    onLoadMore = storeLoadMore,
                    onRetryStore = storeRetry,
                    minSectionHeight = targetSectionHeight,
                    cardHeight = targetCardHeight
                )
            }
        }
    }
}
