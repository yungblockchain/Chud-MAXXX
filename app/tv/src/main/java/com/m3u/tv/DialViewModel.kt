package com.m3u.tv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.copyXtreamEpisode
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.service.MediaCommand
import com.m3u.data.service.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class DetailsKind { Film, Series }

@Immutable
data class DetailsState(
    val channel: Channel,
    val kind: DetailsKind,
    val loading: Boolean = true,
    val film: VodDetails? = null,
    val series: SeriesDetails? = null,
    val selectedSeason: String? = null,
    /** Saved position for a film, or for the series' last-opened episode; 0 if none. */
    val resumeMs: Long = 0L,
    val seriesProgress: SeriesProgress? = null,
)

@Immutable
data class GuideSchedule(
    val channelId: Int,
    val loading: Boolean = true,
    val programmes: List<GuideProgramme> = emptyList(),
    val supported: Boolean = true,
)

private data class TimedListing(val fetchedAt: Long, val programmes: List<GuideProgramme>)

@HiltViewModel
class DialViewModel @Inject constructor(
    private val store: DialSettingsStore,
    private val playerManager: PlayerManager,
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    val preferences: StateFlow<DialPreferences> = store.preferences

    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<Channel>>(emptyList())
    val continueWatching: StateFlow<List<Channel>> = _continueWatching.asStateFlow()

    private val _nowNext = MutableStateFlow<Map<Int, List<GuideProgramme>>>(emptyMap())
    val nowNext: StateFlow<Map<Int, List<GuideProgramme>>> = _nowNext.asStateFlow()

    private val _schedule = MutableStateFlow<GuideSchedule?>(null)
    val schedule: StateFlow<GuideSchedule?> = _schedule.asStateFlow()

    /** Full listings for the timeline grid, by channel id. Absent = not loaded yet. */
    private val _listings = MutableStateFlow<Map<Int, List<GuideProgramme>>>(emptyMap())
    val listings: StateFlow<Map<Int, List<GuideProgramme>>> = _listings.asStateFlow()

    private var detailsJob: Job? = null
    private var scheduleJob: Job? = null
    private val nowNextCache = mutableMapOf<Int, TimedListing>()
    private val nowNextInFlight = mutableSetOf<Int>()
    private val guideRequests = Semaphore(4)
    private val credentialsCache = mutableMapOf<String, XtreamCredentials?>()
    private val listingFetchedAt = mutableMapOf<Int, Long>()
    private val listingInFlight = mutableSetOf<Int>()
    private val listingOrder = ArrayDeque<Int>()

    init {
        viewModelScope.launch {
            store.history.collect { refreshContinueWatching() }
        }
    }

    /* ------------------------------------------------------------------ settings */

    fun updatePreferences(transform: (DialPreferences) -> DialPreferences) = store.update(transform)

    fun clearContinueWatching() {
        store.clearHistory()
        _continueWatching.value = emptyList()
    }

    /* ------------------------------------------------------------------ startup */

    fun rememberLastChannel(channelId: Int) {
        store.lastChannelId = channelId
    }

    /** Plays the last live channel watched. Returns false if there isn't one any more. */
    suspend fun playLastChannel(): Boolean {
        val id = store.lastChannelId ?: return false
        val channel = channelRepository.get(id) ?: return false
        playerManager.play(MediaCommand.Common(channel.id))
        return true
    }

    /* ------------------------------------------------------------------ details */

    fun openDetails(channel: Channel, playlist: Playlist?) {
        val kind = if (playlist?.isSeries == true) DetailsKind.Series else DetailsKind.Film
        _details.value = DetailsState(channel = channel, kind = kind)
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            val credentials = credentialsFor(channel.playlistUrl)
            val itemId = XtreamCatalog.idFromUrl(channel.url)
            when (kind) {
                DetailsKind.Film -> {
                    val film = if (credentials != null && itemId != null) {
                        XtreamCatalog.vodDetails(credentials, itemId)
                    } else null
                    val resume = savedPosition(channel.url)
                    updateDetailsFor(channel.id) {
                        it.copy(loading = false, film = film, resumeMs = resume)
                    }
                }
                DetailsKind.Series -> {
                    val series = if (credentials != null && itemId != null) {
                        XtreamCatalog.seriesDetails(credentials, itemId)
                    } else null
                    val progress = store.seriesProgress(channel.id)
                    val resume = progress?.let { savedPosition(episodeUrl(channel, it)) } ?: 0L
                    val season = progress?.season
                        ?.takeIf { key -> series?.seasons?.any { it.key == key } == true }
                        ?: series?.seasons?.firstOrNull()?.key
                    updateDetailsFor(channel.id) {
                        it.copy(
                            loading = false,
                            series = series,
                            selectedSeason = season,
                            seriesProgress = progress,
                            resumeMs = resume,
                        )
                    }
                }
            }
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        _details.value = null
    }

    /** Applies [transform] only if the details page still shows [channelId]. */
    private fun updateDetailsFor(channelId: Int, transform: (DetailsState) -> DetailsState) {
        _details.update { current ->
            if (current != null && current.channel.id == channelId) transform(current) else current
        }
    }

    fun selectSeason(key: String) {
        _details.update { it?.copy(selectedSeason = key) }
    }

    /** Re-reads saved positions after the player closes, so "Resume from" stays accurate. */
    fun refreshAfterPlayback() {
        refreshContinueWatching()
        val current = _details.value ?: return
        viewModelScope.launch {
            val resume = when (current.kind) {
                DetailsKind.Film -> savedPosition(current.channel.url)
                DetailsKind.Series -> store.seriesProgress(current.channel.id)
                    ?.let { savedPosition(episodeUrl(current.channel, it)) } ?: 0L
            }
            val progress = store.seriesProgress(current.channel.id)
            updateDetailsFor(current.channel.id) {
                it.copy(resumeMs = resume, seriesProgress = progress)
            }
        }
    }

    fun playFilm(channel: Channel, fromStart: Boolean) {
        store.recordOnDemand(channel.id)
        viewModelScope.launch {
            val resume = preferences.value.resumePlayback && !fromStart
            if (fromStart) playerManager.onResetPlayback(channel.url)
            playerManager.play(MediaCommand.Common(channel.id), applyContinueWatching = resume)
        }
    }

    fun playEpisode(series: Channel, episode: SeriesEpisode, fromStart: Boolean) {
        store.recordOnDemand(series.id)
        store.saveSeriesProgress(
            series.id,
            SeriesProgress(
                season = episode.season,
                episodeId = episode.id,
                episodeNum = episode.episodeNum,
                title = episode.title,
                containerExtension = episode.containerExtension,
            )
        )
        viewModelScope.launch {
            val info = episode.toEpisodeInfo()
            val resume = preferences.value.resumePlayback && !fromStart
            if (fromStart) playerManager.onResetPlayback(series.copyXtreamEpisode(info).url)
            playerManager.play(MediaCommand.XtreamEpisode(series.id, info), applyContinueWatching = resume)
        }
    }

    /** "Continue S2 E5": the saved episode, resumed where it stopped. */
    fun continueSeries(series: Channel, progress: SeriesProgress) {
        playEpisode(
            series = series,
            episode = SeriesEpisode(
                id = progress.episodeId,
                season = progress.season,
                episodeNum = progress.episodeNum,
                title = progress.title.orEmpty(),
                containerExtension = progress.containerExtension,
                plot = null,
                duration = null,
                image = null,
            ),
            fromStart = false,
        )
    }

    private fun episodeUrl(series: Channel, progress: SeriesProgress): String =
        series.copyXtreamEpisode(
            SeriesEpisode(
                id = progress.episodeId,
                season = progress.season,
                episodeNum = progress.episodeNum,
                title = progress.title.orEmpty(),
                containerExtension = progress.containerExtension,
                plot = null,
                duration = null,
                image = null,
            ).toEpisodeInfo()
        ).url

    private suspend fun savedPosition(url: String): Long =
        runCatching { playerManager.getCwPosition(url) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            ?.takeIf { it > MIN_RESUME_MS }
            ?: 0L

    /* ------------------------------------------------------------------ continue watching */

    fun refreshContinueWatching() {
        val ids = store.history.value
        viewModelScope.launch {
            val items = ids.mapNotNull { id ->
                val channel = channelRepository.get(id)
                if (channel == null) {
                    store.forget(id)
                    return@mapNotNull null
                }
                val playlist = playlistRepository.get(channel.playlistUrl)
                when {
                    playlist?.isSeries == true ->
                        channel.takeIf { store.seriesProgress(id) != null }
                    playlist?.isVod == true ->
                        channel.takeIf { savedPosition(channel.url) > 0L }
                    else -> null
                }
            }
            _continueWatching.value = items
        }
    }

    /* ------------------------------------------------------------------ guide and catch-up */

    fun requestNowNext(channel: Channel) {
        val now = System.currentTimeMillis()
        val cached = nowNextCache[channel.id]
        val fresh = cached != null &&
            now - cached.fetchedAt < NOW_NEXT_TTL_MS &&
            cached.programmes.firstOrNull()?.hasEndedBy(now) != true
        if (fresh || !nowNextInFlight.add(channel.id)) return
        viewModelScope.launch {
            try {
                val listings = guideRequests.withPermit {
                    val credentials = credentialsFor(channel.playlistUrl)
                    val streamId = XtreamCatalog.idFromUrl(channel.url)
                    if (credentials == null || streamId == null) emptyList()
                    else XtreamCatalog.shortEpg(credentials, streamId)
                }
                val current = listings.filterNot { it.hasEndedBy(System.currentTimeMillis()) }
                nowNextCache[channel.id] = TimedListing(System.currentTimeMillis(), current)
                _nowNext.update { it + (channel.id to current) }
            } finally {
                nowNextInFlight.remove(channel.id)
            }
        }
    }

    /**
     * Loads one channel's full listing for the timeline grid. Called as rows scroll into view;
     * cached for [LISTING_TTL_MS], and only the most recent [MAX_LISTINGS] channels are kept.
     */
    fun requestListing(channel: Channel) {
        val fetchedAt = listingFetchedAt[channel.id]
        if (fetchedAt != null && System.currentTimeMillis() - fetchedAt < LISTING_TTL_MS) return
        if (!listingInFlight.add(channel.id)) return
        viewModelScope.launch {
            try {
                val programmes = guideRequests.withPermit {
                    val credentials = credentialsFor(channel.playlistUrl)
                    val streamId = XtreamCatalog.idFromUrl(channel.url)
                    if (credentials == null || streamId == null) emptyList()
                    else XtreamCatalog.fullEpg(credentials, streamId)
                }
                val now = System.currentTimeMillis()
                val trimmed = programmes.filter {
                    it.endMillis > now - GRID_PAST_MS && it.startMillis < now + GRID_FUTURE_MS
                }
                listingFetchedAt[channel.id] = now
                listingOrder.remove(channel.id)
                listingOrder.addLast(channel.id)
                val evicted = mutableListOf<Int>()
                while (listingOrder.size > MAX_LISTINGS) {
                    val oldest = listingOrder.removeFirst()
                    listingFetchedAt.remove(oldest)
                    evicted += oldest
                }
                _listings.update { (it - evicted.toSet()) + (channel.id to trimmed) }
            } finally {
                listingInFlight.remove(channel.id)
            }
        }
    }

    fun loadSchedule(channel: Channel) {
        if (_schedule.value?.channelId == channel.id && _schedule.value?.loading == false) return
        scheduleJob?.cancel()
        _schedule.value = GuideSchedule(channelId = channel.id)
        scheduleJob = viewModelScope.launch {
            // Let focus settle while scrolling the channel list before asking the server.
            delay(250)
            val credentials = credentialsFor(channel.playlistUrl)
            val streamId = XtreamCatalog.idFromUrl(channel.url)
            if (credentials == null || streamId == null) {
                _schedule.value = GuideSchedule(channel.id, loading = false, supported = false)
                return@launch
            }
            val now = System.currentTimeMillis()
            val programmes = XtreamCatalog.fullEpg(credentials, streamId)
                .filter { it.endMillis > now - ARCHIVE_WINDOW_MS && it.startMillis < now + FUTURE_WINDOW_MS }
                // Past programmes are only useful if they can be replayed.
                .filter { !it.hasEndedBy(now) || it.hasArchive }
            _schedule.value = GuideSchedule(channel.id, loading = false, programmes = programmes)
        }
    }

    fun playCatchUp(channel: Channel, programme: GuideProgramme) {
        viewModelScope.launch {
            val credentials = credentialsFor(channel.playlistUrl) ?: return@launch
            val streamId = XtreamCatalog.idFromUrl(channel.url) ?: return@launch
            val url = XtreamCatalog.timeshiftUrl(credentials, streamId, programme) ?: return@launch
            playerManager.play(
                MediaCommand.Url(channelId = channel.id, url = url, title = programme.title),
                applyContinueWatching = false,
            )
        }
    }

    private suspend fun credentialsFor(playlistUrl: String): XtreamCredentials? {
        if (credentialsCache.containsKey(playlistUrl)) return credentialsCache[playlistUrl]
        val credentials = XtreamCatalog.credentialsFor(playlistRepository.get(playlistUrl))
        credentialsCache[playlistUrl] = credentials
        return credentials
    }

    private companion object {
        const val MIN_RESUME_MS = 30_000L
        const val NOW_NEXT_TTL_MS = 10 * 60_000L
        const val ARCHIVE_WINDOW_MS = 7 * 24 * 60 * 60_000L
        const val FUTURE_WINDOW_MS = 24 * 60 * 60_000L
        const val LISTING_TTL_MS = 30 * 60_000L
        const val MAX_LISTINGS = 300
        const val GRID_PAST_MS = 26 * 60 * 60_000L
        const val GRID_FUTURE_MS = 26 * 60 * 60_000L
    }
}
