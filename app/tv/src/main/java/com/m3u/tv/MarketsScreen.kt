package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.FiberNew
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

/*
 * Markets tab: a read-only memecoin DEX tracker backed by DEX Screener. Sections for trending
 * (most-boosted) tokens, pump.fun tokens, new listings, your watchlist and search, filterable by
 * chain. Prices refresh every 30 seconds while the tab is open. OK on a token adds it to the
 * watchlist or removes it.
 */

private const val REFRESH_MS = 30_000L
private val CHAINS = listOf(null, "solana", "base", "ethereum", "bsc")

@Composable
fun MarketsScreen(viewModel: MarketsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf<MarketPair?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Search loads when the query is submitted; every other section refreshes on a timer.
    LaunchedEffect(state.section) {
        focused = null
        if (state.section == MarketSection.Search) return@LaunchedEffect
        while (true) {
            viewModel.refresh()
            now = System.currentTimeMillis()
            delay(REFRESH_MS)
        }
    }

    val shown = state.items
        .filter { state.chain == null || it.chainId == state.chain }
        .let { list ->
            if (state.section == MarketSection.Watchlist) list.filter { it.watchKey in watchlist } else list
        }
    val detail = focused?.let { f -> state.items.firstOrNull { it.key == f.key } ?: f }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 40.dp, top = 28.dp, end = 40.dp, bottom = 16.dp)
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.focusGroup()
        ) {
            items(MarketSection.entries, key = { "section-${it.name}" }) { section ->
                TvActionButton(
                    text = stringResource(section.labelRes()),
                    icon = section.icon(),
                    selected = state.section == section,
                    onClick = { viewModel.selectSection(section) },
                )
            }
            items(CHAINS, key = { "chain-${it ?: "all"}" }) { chain ->
                TvActionButton(
                    text = chain?.let(::chainLabel) ?: stringResource(R.string.dial_markets_all_chains),
                    icon = Icons.Rounded.Language,
                    selected = state.chain == chain,
                    onClick = { viewModel.selectChain(chain) },
                )
            }
        }

        if (state.section == MarketSection.Search) {
            Box(Modifier.widthIn(max = 640.dp)) {
                DialTextField(
                    label = stringResource(R.string.dial_markets_search_label),
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    placeholder = stringResource(R.string.dial_markets_search_placeholder),
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                    readOnly = false,
                    onDone = {
                        now = System.currentTimeMillis()
                        viewModel.refresh()
                    },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
            ) {
                StatusLine(state = state, count = shown.size)
                if (shown.isNotEmpty()) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .focusGroup()
                    ) {
                        items(shown, key = { it.key }) { pair ->
                            MarketRow(
                                pair = pair,
                                watched = pair.watchKey in watchlist,
                                onFocus = { focused = pair },
                                onClick = { viewModel.toggleWatch(pair) },
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
            ) {
                if (detail != null) {
                    MarketDetail(
                        pair = detail,
                        watched = detail.watchKey in watchlist,
                        now = now,
                        onToggleWatch = { viewModel.toggleWatch(detail) },
                    )
                } else {
                    MarketsNote(stringResource(R.string.dial_markets_hint))
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: MarketsState, count: Int) {
    val text = when {
        state.failed && count == 0 -> stringResource(R.string.dial_markets_failed)
        state.loading && count == 0 -> stringResource(R.string.dial_markets_loading)
        count == 0 -> when (state.section) {
            MarketSection.Watchlist -> stringResource(R.string.dial_markets_watchlist_empty)
            MarketSection.Search -> stringResource(R.string.dial_markets_search_prompt)
            else -> stringResource(R.string.dial_markets_empty)
        }
        state.failed -> stringResource(R.string.dial_markets_stale)
        else -> stringResource(
            R.string.dial_markets_updated,
            state.updatedAt?.let { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it)) } ?: "—",
        )
    }
    MarketsNote(text)
}

@Composable
private fun MarketRow(
    pair: MarketPair,
    watched: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    FocusFrame(
        onClick = onClick,
        onFocus = onFocus,
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1.02f,
        semanticsLabel = listOf(
            pair.baseSymbol,
            formatPrice(pair.priceUsd),
            formatPercent(pair.change24h),
        ).joinToString(". "),
        modifier = Modifier.fillMaxWidth()
    ) { focused ->
        val primary = if (focused) TvColors.OnFocus else TvColors.TextPrimary
        val secondary = if (focused) TvColors.OnFocus.copy(alpha = 0.75f) else TvColors.TextSecondary
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            TokenIcon(pair.imageUrl, size = 36)
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pair.baseSymbol,
                        color = primary,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (watched) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = if (focused) TvColors.OnFocus else TvColors.Focus,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = "${chainLabel(pair.chainId)}  ${pair.dexId}",
                    color = secondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = formatPrice(pair.priceUsd),
                color = primary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(112.dp)
            )
            ChangeText(pair.change24h, focused, Modifier.width(72.dp))
            Text(
                text = formatUsdCompact(pair.marketCap ?: pair.fdv),
                color = secondary,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(80.dp)
            )
        }
    }
}

@Composable
private fun MarketDetail(
    pair: MarketPair,
    watched: Boolean,
    now: Long,
    onToggleWatch: () -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(TvColors.Surface.copy(alpha = 0.6f))
            .padding(20.dp)
            .focusGroup()
    ) {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenIcon(pair.imageUrl, size = 52)
                Column {
                    Text(
                        text = pair.baseSymbol,
                        color = TvColors.TextPrimary,
                        fontFamily = TvFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pair.baseName,
                        color = TvColors.TextSecondary,
                        fontFamily = TvFonts.Body,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            Text(
                text = formatPrice(pair.priceUsd),
                color = TvColors.Focus,
                fontFamily = TvFonts.Accent,
                fontSize = 30.sp,
                maxLines = 1,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("5m" to pair.change5m, "1h" to pair.change1h, "6h" to pair.change6h, "24h" to pair.change24h)
                    .forEach { (label, value) ->
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = label,
                                color = TvColors.TextMuted,
                                fontFamily = TvFonts.Body,
                                fontSize = 12.sp,
                            )
                            ChangeText(value, focused = false)
                        }
                    }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(stringResource(R.string.dial_markets_market_cap), formatUsdCompact(pair.marketCap))
                Stat(stringResource(R.string.dial_markets_fdv), formatUsdCompact(pair.fdv))
                Stat(stringResource(R.string.dial_markets_liquidity), formatUsdCompact(pair.liquidityUsd))
                Stat(stringResource(R.string.dial_markets_volume), formatUsdCompact(pair.volume24h))
                if (pair.buys24h != null && pair.sells24h != null) {
                    Stat(
                        stringResource(R.string.dial_markets_txns),
                        stringResource(R.string.dial_markets_txns_value, pair.buys24h, pair.sells24h),
                    )
                }
                Stat(stringResource(R.string.dial_markets_age), formatAge(pair.createdAt, now))
                Stat(
                    stringResource(R.string.dial_markets_exchange),
                    listOfNotNull(chainLabel(pair.chainId), pair.dexId.takeIf { it.isNotBlank() }).joinToString("  "),
                )
                Stat(stringResource(R.string.dial_markets_address), shortAddress(pair.baseAddress))
                pair.boosts?.takeIf { it > 0 }?.let {
                    Stat(stringResource(R.string.dial_markets_boosts_label), it.toString())
                }
            }
        }
        pair.description?.takeIf { it.isNotBlank() }?.let { description ->
            item {
                Text(
                    text = description,
                    color = TvColors.TextSecondary,
                    fontFamily = TvFonts.Body,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            TvActionButton(
                text = stringResource(
                    if (watched) R.string.dial_markets_watch_remove else R.string.dial_markets_watch_add
                ),
                icon = if (watched) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                onClick = onToggleWatch,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChangeText(value: Double?, focused: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        focused -> TvColors.OnFocus
        value == null -> TvColors.TextMuted
        value >= 0.0 -> TvColors.Positive
        else -> TvColors.Danger
    }
    Text(
        text = formatPercent(value),
        color = color,
        fontFamily = TvFonts.Body,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
private fun TokenIcon(url: String?, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun MarketsNote(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

private fun MarketSection.labelRes(): Int = when (this) {
    MarketSection.Trending -> R.string.dial_markets_trending
    MarketSection.PumpFun -> R.string.dial_markets_pumpfun
    MarketSection.New -> R.string.dial_markets_new
    MarketSection.Watchlist -> R.string.dial_markets_watchlist
    MarketSection.Search -> R.string.dial_markets_search
}

private fun MarketSection.icon() = when (this) {
    MarketSection.Trending -> Icons.AutoMirrored.Rounded.TrendingUp
    MarketSection.PumpFun -> Icons.Rounded.LocalFireDepartment
    MarketSection.New -> Icons.Rounded.FiberNew
    MarketSection.Watchlist -> Icons.Rounded.Star
    MarketSection.Search -> Icons.Rounded.Search
}
