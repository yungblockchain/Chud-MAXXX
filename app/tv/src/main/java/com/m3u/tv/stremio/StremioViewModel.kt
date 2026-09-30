package com.m3u.tv.stremio

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.dao.ChannelDao
import com.m3u.data.database.dao.PlaylistDao
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import com.m3u.tv.SecretName
import com.m3u.tv.SecretStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StremioPage { Browse, Details, Streams, Addons }

@Immutable
data class StremioUiState(
    val page: StremioPage = StremioPage.Browse,
    val rows: List<CatalogRow> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val details: MetaDetails? = null,
    val detailsLoading: Boolean = false,
    val season: Int? = null,
    val streams: List<StreamSource> = emptyList(),
    val streamsLoading: Boolean = false,
    val resolving: String? = null,
    val addons: List<InstalledAddon> = emptyList(),
    val hasRealDebrid: Boolean = false,
    val hasTorBox: Boolean = false,
    val p2p: Boolean = true,
    val torrServe: String = "http://127.0.0.1:8090",
    val torrServeUp: Boolean? = null,
)

@HiltViewModel
class StremioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: StremioAddonStore,
    private val secrets: SecretStore,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    private val playerManager: PlayerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(StremioUiState())
    val state: StateFlow<StremioUiState> = _state.asStateFlow()

    private var playTitle: String = ""
    private var playPoster: String? = null
    private var playRelation: String = ""
    private var playCategory: String = "Addons"

    init {
        viewModelScope.launch {
            store.addons.collect { list ->
                _state.update {
                    it.copy(
                        addons = list,
                        hasRealDebrid = secrets.has(SecretName.RealDebrid),
                        hasTorBox = secrets.has(SecretName.TorBox),
                        p2p = store.p2pEnabled,
                        torrServe = store.torrServeUrl,
                    )
                }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val addons = store.enabled().filter { addon ->
                "catalog" in addon.resources && addon.catalogs.isNotEmpty()
            }
            val rows = mutableListOf<CatalogRow>()
            for (addon in addons) {
                val catalogs = addon.catalogs
                    .filter { it.type == "movie" || it.type == "series" }
                    .filterNot { it.extra.singleOrNull() == "search" }
                    .take(3)
                for (catalog in catalogs) {
                    val items = runCatching {
                        StremioClient.catalog(addon, catalog.type, catalog.id).take(24)
                    }.getOrDefault(emptyList())
                    if (items.isNotEmpty()) {
                        rows += CatalogRow(
                            addonId = addon.id,
                            addonName = addon.name,
                            type = catalog.type,
                            catalogId = catalog.id,
                            name = catalog.name.ifBlank { addon.name },
                            items = items,
                        )
                    }
                }
            }
            _state.update {
                it.copy(
                    loading = false,
                    rows = rows,
                    message = if (rows.isEmpty() && addons.isNotEmpty()) "Those addons didn't return a catalog." else null,
                )
            }
        }
    }

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null, page = StremioPage.Browse) }
            val items = mutableListOf<CatalogItem>()
            for (addon in store.enabled()) {
                items += runCatching { StremioClient.search(addon, trimmed) }.getOrDefault(emptyList())
            }
            val unique = items.distinctBy { it.type to it.id }.take(40)
            _state.update {
                it.copy(
                    loading = false,
                    rows = if (unique.isEmpty()) emptyList() else listOf(
                        CatalogRow("search", "Search", "movie", "search", "Results for “$trimmed”", unique),
                    ),
                    message = if (unique.isEmpty()) "Nothing matched." else null,
                )
            }
        }
    }

    fun openAddons() = _state.update {
        it.copy(
            page = StremioPage.Addons,
            message = null,
            hasRealDebrid = secrets.has(SecretName.RealDebrid),
            hasTorBox = secrets.has(SecretName.TorBox),
        )
    }

    fun openItem(item: CatalogItem) {
        playTitle = item.name
        playPoster = item.poster
        playRelation = "${item.type}:${item.id}"
        playCategory = if (item.type == "series") "Series" else "Films"
        viewModelScope.launch {
            _state.update { it.copy(page = StremioPage.Details, detailsLoading = true, details = null, message = null) }
            val addon = store.enabled().firstOrNull { "meta" in it.resources }
            val fetched = addon?.let { source ->
                runCatching { StremioClient.meta(source, item.type, item.id) }.getOrNull()
            }
            val details = fetched ?: MetaDetails(
                id = item.id,
                type = item.type,
                name = item.name,
                poster = item.poster,
                background = item.background,
                logo = null,
                description = item.description,
                releaseInfo = item.releaseInfo,
                imdbRating = item.imdbRating,
                genres = emptyList(),
                runtime = null,
                director = emptyList(),
                cast = emptyList(),
                imdbId = item.id.takeIf { it.startsWith("tt") },
                videos = emptyList(),
            )
            _state.update {
                it.copy(
                    detailsLoading = false,
                    details = details,
                    season = details.videos.mapNotNull { video -> video.season }.minOrNull(),
                )
            }
        }
    }

    fun selectSeason(season: Int) = _state.update { it.copy(season = season) }

    fun loadStreams(type: String, id: String, title: String) {
        playTitle = title
        playRelation = "$type:$id"
        viewModelScope.launch {
            _state.update { it.copy(page = StremioPage.Streams, streamsLoading = true, streams = emptyList(), message = null) }
            val found = mutableListOf<StreamSource>()
            for (addon in store.enabled().filter { "stream" in it.resources }) {
                found += runCatching { StremioClient.streams(addon, type, id) }.getOrDefault(emptyList())
            }
            val sorted = found.distinctBy { it.playableUrl to it.name }.sortedWith(
                compareByDescending<StreamSource> { it.isDebrid }
                    .thenByDescending { qualityRank(it.quality) }
                    .thenByDescending { it.seeders?.toIntOrNull() ?: 0 },
            )
            _state.update {
                it.copy(
                    streamsLoading = false,
                    streams = sorted,
                    message = if (sorted.isEmpty()) "No streams. Install Torrentio or AIOStreams, and add a debrid token if you have one." else null,
                )
            }
        }
    }

    fun play(source: StreamSource, onPlaying: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(resolving = source.name, message = null) }
            runCatching {
                val resolved = StreamResolver.resolve(
                    source = source,
                    realDebrid = secrets.get(SecretName.RealDebrid),
                    torbox = secrets.get(SecretName.TorBox),
                    p2pEnabled = store.p2pEnabled,
                    torrServeUrl = store.torrServeUrl,
                    cacheDir = context.cacheDir,
                )
                val id = rememberChannel(resolved.url)
                if (id == 0) throw DebridException("Couldn't save this title to the library")
                playerManager.play(MediaCommand.Url(channelId = id, url = resolved.url, title = playTitle))
                resolved.via
            }.onSuccess { via ->
                _state.update { it.copy(resolving = null, message = "Playing with $via") }
                onPlaying()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(resolving = null, message = error.message ?: "Couldn't play that") }
            }
        }
    }

    fun installPreset(preset: PresetAddon) {
        if (preset.manifestUrl.isBlank()) {
            _state.update { it.copy(message = "Paste the manifest URL from your ${preset.name} config in Settings → Addons.") }
            return
        }
        val url = if ("torrentio" in preset.manifestUrl) torrentioUrl() else preset.manifestUrl
        install(url)
    }

    fun installStarter() {
        viewModelScope.launch {
            installAwait(AddonCatalogPresets.all.first { it.id == "com.linvo.cinemeta" }.manifestUrl)
            installAwait(torrentioUrl())
            refresh()
        }
    }

    fun install(url: String) {
        viewModelScope.launch {
            try {
                installAwait(url)
                if (_state.value.rows.isEmpty()) refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "Couldn't add that addon") }
            }
        }
    }

    fun remove(id: String) = store.remove(id)

    fun setEnabled(id: String, enabled: Boolean) = store.setEnabled(id, enabled)

    fun saveSecret(name: SecretName, value: String) {
        secrets.put(name, value)
        _state.update {
            it.copy(
                hasRealDebrid = secrets.has(SecretName.RealDebrid),
                hasTorBox = secrets.has(SecretName.TorBox),
            )
        }
        syncTorrentio()
    }

    fun removeSecret(name: SecretName) {
        secrets.remove(name)
        _state.update {
            it.copy(
                hasRealDebrid = secrets.has(SecretName.RealDebrid),
                hasTorBox = secrets.has(SecretName.TorBox),
            )
        }
        syncTorrentio()
    }

    fun setP2p(enabled: Boolean) {
        store.p2pEnabled = enabled
        _state.update { it.copy(p2p = enabled) }
    }

    fun setTorrServe(url: String) {
        store.torrServeUrl = url
        _state.update { it.copy(torrServe = store.torrServeUrl, torrServeUp = null) }
    }

    fun testTorrServe() {
        viewModelScope.launch {
            val up = TorrServeClient.alive(store.torrServeUrl)
            _state.update { it.copy(torrServeUp = up, message = if (up) "TorrServe is running." else "TorrServe didn't answer.") }
        }
    }

    /** True when Back stayed inside Addons. */
    fun back(): Boolean {
        val next = when (_state.value.page) {
            StremioPage.Streams -> StremioPage.Details
            StremioPage.Details -> StremioPage.Browse
            StremioPage.Addons -> StremioPage.Browse
            StremioPage.Browse -> return false
        }
        _state.update { it.copy(page = next, resolving = null, message = null) }
        return true
    }

    private suspend fun installAwait(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http")) {
            _state.update { it.copy(message = "Paste a full manifest URL, starting with https://") }
            return
        }
        _state.update { it.copy(message = "Installing…") }
        runCatching { StremioClient.fetchManifest(trimmed) }
            .onSuccess { addon ->
                store.upsert(addon)
                _state.update { it.copy(message = "Installed ${addon.name}") }
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(message = error.message ?: "Addon didn't answer") }
            }
    }

    private fun torrentioUrl(): String = TorrentioConfig.manifestUrl(
        secrets.get(SecretName.RealDebrid),
        secrets.get(SecretName.TorBox),
    )

    private fun syncTorrentio() {
        if (store.addons.value.none { it.manifestUrl.contains("torrentio.strem.fun") || it.id.contains("torrentio") }) return
        viewModelScope.launch {
            val url = torrentioUrl()
            runCatching { StremioClient.fetchManifest(url) }
                .onSuccess { store.upsert(it) }
                .onFailure { store.replaceTorrentio(url) }
        }
    }

    private suspend fun rememberChannel(url: String): Int {
        if (playlistDao.get(StremioIds.PLAYLIST_URL) == null) {
            playlistDao.insertOrReplace(
                Playlist(
                    title = StremioIds.PLAYLIST_TITLE,
                    url = StremioIds.PLAYLIST_URL,
                    source = DataSource.M3U,
                ),
            )
        }
        val existing = channelDao.getByPlaylistUrlAndRelationId(StremioIds.PLAYLIST_URL, playRelation)
        val id = channelDao.insertOrReplace(
            Channel(
                url = url,
                category = playCategory,
                title = playTitle,
                cover = playPoster ?: _state.value.details?.poster,
                playlistUrl = StremioIds.PLAYLIST_URL,
                id = existing?.id ?: 0,
                relationId = playRelation,
            ),
        ).toInt()
        if (id != 0) return id
        return channelDao.getByPlaylistUrlAndRelationId(StremioIds.PLAYLIST_URL, playRelation)?.id ?: 0
    }

    private fun qualityRank(quality: String?): Int = when {
        quality == null -> 0
        "2160" in quality -> 5
        "1080" in quality -> 4
        "720" in quality -> 3
        "480" in quality -> 2
        else -> 1
    }
}
