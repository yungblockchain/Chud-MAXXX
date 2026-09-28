package com.m3u.tv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MarketSection { Trending, PumpFun, New, Watchlist, Search }

@Immutable
data class MarketsState(
    val section: MarketSection = MarketSection.Trending,
    /** Chain filter; null shows every chain. */
    val chain: String? = null,
    val query: String = "",
    val items: List<MarketPair> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val updatedAt: Long? = null,
)

@HiltViewModel
class MarketsViewModel @Inject constructor(
    private val store: DialSettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(MarketsState())
    val state: StateFlow<MarketsState> = _state.asStateFlow()

    val watchlist: StateFlow<List<String>> = store.watchlist

    private var loadJob: Job? = null

    fun selectSection(section: MarketSection) {
        if (_state.value.section == section) return
        loadJob?.cancel()
        _state.update {
            it.copy(section = section, items = emptyList(), loading = false, failed = false, updatedAt = null)
        }
    }

    fun selectChain(chain: String?) = _state.update { it.copy(chain = chain) }

    fun updateQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleWatch(pair: MarketPair) = store.toggleWatch(pair.watchKey)

    /** Reloads the current section. The screen calls this on a 30-second timer while visible. */
    fun refresh() {
        val requested = _state.value
        loadJob?.cancel()
        _state.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val items = load(requested.section, requested.query)
                _state.update { current ->
                    if (current.section != requested.section) current
                    else current.copy(
                        items = items,
                        loading = false,
                        failed = false,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep showing the last good data; the screen notes that the update failed.
                _state.update { current ->
                    if (current.section != requested.section) current
                    else current.copy(loading = false, failed = true)
                }
            }
        }
    }

    private suspend fun load(section: MarketSection, query: String): List<MarketPair> = when (section) {
        MarketSection.Trending -> DexScreener.tokens(DexScreener.topBoosted().take(MAX_TOKENS))
        MarketSection.New -> DexScreener.tokens(DexScreener.latestProfiles().take(MAX_TOKENS))
        MarketSection.PumpFun -> {
            val refs = (DexScreener.latestProfiles() + DexScreener.latestBoosted() + DexScreener.topBoosted())
                .filter { it.chainId == "solana" && it.tokenAddress.endsWith("pump") }
                .distinctBy { it.tokenAddress }
                .take(MAX_TOKENS)
            DexScreener.tokens(refs)
        }
        MarketSection.Watchlist -> DexScreener.tokens(
            store.watchlist.value.mapNotNull { TokenRef.fromWatchKey(it) }
        )
        MarketSection.Search -> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) emptyList()
            else DexScreener.search(trimmed)
                // One row per token: its deepest pool.
                .groupBy { it.key }
                .map { (_, pairs) -> pairs.maxByOrNull { it.liquidityUsd ?: 0.0 }!! }
                .sortedByDescending { it.liquidityUsd ?: 0.0 }
                .take(MAX_TOKENS)
        }
    }

    private companion object {
        const val MAX_TOKENS = 60
    }
}
