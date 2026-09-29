package com.m3u.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.tv.model.keyCode
import com.m3u.i18n.R.string
import kotlinx.coroutines.delay

/** Launch extra naming the tab to open first, e.g. `--es destination games` (see [tvDestinationFromExtra]). */
const val EXTRA_DESTINATION = "destination"

/**
 * Maps the [EXTRA_DESTINATION] launch extra to a tab: a tab name such as "games" or "guide", or
 * "settings" for the settings tab. Anything else means the normal start.
 */
fun tvDestinationFromExtra(value: String?): TvDestination? {
    val name = value?.trim()?.lowercase() ?: return null
    if (name == "settings") return TvDestination.Status
    return TvDestination.entries.firstOrNull { it.name.lowercase() == name }
}

/** How long the "press Back again" hint waits for the second press. */
private const val EXIT_WINDOW_MS = 2_500L

/**
 * Closes the app for good: the screen goes, then the process, so nothing stays in memory.
 * (Android would otherwise keep it cached in the background.)
 */
private fun closeAppCompletely(activity: Activity) {
    activity.finishAndRemoveTask()
    Handler(Looper.getMainLooper()).postDelayed(
        { Process.killProcess(Process.myPid()) },
        EXIT_PROCESS_DELAY_MS,
    )
}

private const val EXIT_PROCESS_DELAY_MS = 400L

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun App(
    initialDestination: TvDestination? = null,
    viewModel: TvHomeViewModel = hiltViewModel(),
    dial: DialViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val reconnecting by viewModel.reconnecting.collectAsStateWithLifecycle()
    val playbackFailed by viewModel.playbackFailed.collectAsStateWithLifecycle()
    val remoteControlCode by viewModel.remoteControlCode.collectAsStateWithLifecycle()
    val view = LocalView.current
    val localeTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val context = LocalContext.current
    val diagnosticsShareTitle = stringResource(string.feat_setting_extension_diagnostics_share_title)
    val currentDiagnosticsShareTitle by rememberUpdatedState(diagnosticsShareTitle)
    var destination by remember { mutableStateOf(initialDestination ?: TvDestination.Home) }
    var surface by remember { mutableStateOf(TvSurface.Browse) }
    val closePlayer = {
        viewModel.releasePlayer()
        surface = TvSurface.Browse
    }

    // Dial: settings, details pages, continue watching.
    val preferences by dial.preferences.collectAsStateWithLifecycle()
    val details by dial.details.collectAsStateWithLifecycle()
    val continueWatching by dial.continueWatching.collectAsStateWithLifecycle()

    // What's playing, and whether up/down should flip channels. Flipping walks the list the channel
    // was opened from: the selected playlist if it's in there, otherwise favourites.
    val playingId = currentChannel?.id
    val playingPlaylist = state.playlists.firstOrNull { it.url == currentChannel?.playlistUrl }
    val catchUp = currentChannel?.url?.contains("/timeshift/") == true
    val live = !catchUp &&
        (playingPlaylist == null || !(playingPlaylist.isVod || playingPlaylist.isSeries))
    val zapChannels = when {
        playingId == null || !live -> emptyList()
        state.channels.any { it.id == playingId } -> state.channels
        state.favorites.any { it.id == playingId } -> state.favorites
        else -> listOfNotNull(currentChannel)
    }
    val zapIndex = zapChannels.indexOfFirst { it.id == playingId }
    val zap: (Int) -> Unit = { step ->
        if (zapIndex >= 0 && zapChannels.size > 1) {
            viewModel.play(zapChannels[Math.floorMod(zapIndex + step, zapChannels.size)])
        }
    }

    // Films and series open their details page; live channels play straight away, in the
    // built-in player or in VLC / another app if that's the choice in Settings.
    val openOrPlay: (Channel) -> Unit = { channel ->
        val playlist = state.playlists.firstOrNull { it.url == channel.playlistUrl }
        if (playlist != null && (playlist.isVod || playlist.isSeries)) {
            dial.openDetails(channel, playlist)
        } else if (dial.playsExternally(channel)) {
            dial.playLiveExternally(channel)
        } else {
            viewModel.play(channel)
            surface = TvSurface.Player
        }
    }

    // Outside players: start them, and save where they stopped when they return.
    var pendingExternal by remember { mutableStateOf<ExternalPlayback?>(null) }
    val externalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val playback = pendingExternal ?: return@rememberLauncherForActivityResult
        pendingExternal = null
        ExternalPlayers.resultOf(result.data)?.let { (position, duration) ->
            dial.onExternalResult(playback, position, duration)
        }
    }
    val chooserTitle = stringResource(R.string.dial_player_choose)
    val noPlayerMessage = stringResource(R.string.dial_player_none)
    LaunchedEffect(dial) {
        dial.externalPlayback.collect { playback ->
            pendingExternal = playback
            try {
                externalLauncher.launch(ExternalPlayers.intentFor(playback, chooserTitle))
            } catch (_: ActivityNotFoundException) {
                pendingExternal = null
                Toast.makeText(context, noPlayerMessage, Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(dial) {
        dial.messages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // Launch screen with the spinning mascot, once per app start (not on rotation or resume).
    var splashDone by rememberSaveable { mutableStateOf(false) }
    val showSplash = !splashDone && preferences.launchAnimation

    var startupHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (startupHandled) return@LaunchedEffect
        startupHandled = true
        // A tab asked for by the launch intent wins over the startup setting.
        if (initialDestination != null) return@LaunchedEffect
        when (dial.preferences.value.startup) {
            DialStartup.Home -> Unit
            DialStartup.Guide -> destination = TvDestination.Guide
            DialStartup.LastChannel -> if (dial.playLastChannel()) surface = TvSurface.Player
        }
    }
    LaunchedEffect(playingId, live, playingPlaylist != null) {
        if (playingId != null && live && playingPlaylist != null) dial.rememberLastChannel(playingId)
    }
    LaunchedEffect(surface) {
        if (surface == TvSurface.Browse) dial.refreshAfterPlayback()
    }

    val backTarget = tvAppBackTarget(
        playerVisible = surface == TvSurface.Player,
        providerSubscriptionVisible = state.providerSubscriptionForm != null,
        extensionSettingsVisible = state.extensionSettings != null,
    )
    BackHandler(enabled = backTarget != TvAppBackTarget.ACTIVITY) {
        when (backTarget) {
            TvAppBackTarget.PLAYER -> closePlayer()
            TvAppBackTarget.PROVIDER_SUBSCRIPTION -> viewModel.closeProviderSubscription()
            TvAppBackTarget.EXTENSION_SETTINGS -> viewModel.closeExtensionSettings()
            TvAppBackTarget.ACTIVITY -> Unit
        }
    }

    // Back on the main menu: the first press says "press again", the second closes the app
    // completely (the process ends, so everything it held in memory is freed). Screens with their
    // own Back step (a game, a details page) handle Back first.
    var exitArmedAt by remember { mutableLongStateOf(0L) }
    var exitHintVisible by remember { mutableStateOf(false) }
    BackHandler(
        enabled = backTarget == TvAppBackTarget.ACTIVITY && details == null &&
            !showSplash && preferences.backTwiceToExit
    ) {
        val now = SystemClock.uptimeMillis()
        if (exitHintVisible && now - exitArmedAt < EXIT_WINDOW_MS) {
            viewModel.releasePlayer()
            view.context.findActivity()?.let(::closeAppCompletely)
        } else {
            exitArmedAt = now
            exitHintVisible = true
        }
    }
    LaunchedEffect(exitArmedAt) {
        if (exitArmedAt == 0L) return@LaunchedEffect
        delay(EXIT_WINDOW_MS)
        exitHintVisible = false
    }

    // Home button, screensaver or the TV switching off: stop the stream so it doesn't die in the
    // background, then pick it up again when the app is back on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.sleepPlayer()
                Lifecycle.Event.ON_START -> viewModel.wakePlayer()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(view) {
        viewModel.remoteDirections.collect { direction ->
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, direction.keyCode))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, direction.keyCode))
        }
    }
    LaunchedEffect(viewModel, localeTag) {
        viewModel.updateLocale(localeTag)
    }
    LaunchedEffect(viewModel, context) {
        viewModel.extensionDiagnostics.collect { payload ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, payload)
                    },
                    currentDiagnosticsShareTitle,
                )
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        TvBackdrop(channel = currentChannel ?: state.heroChannel)
        CompositionLocalProvider(
            LocalTvFocusEnabled provides (surface == TvSurface.Browse && details == null && !showSplash)
        ) {
            Row(Modifier.fillMaxSize()) {
                TvNavigationRail(
                    selected = destination,
                    onSelect = { destination = it }
                )
                TvBrowsePane(
                    destination = destination,
                    state = state,
                    onOpenLibrary = { destination = TvDestination.Library },
                    onPlaylist = {
                        viewModel.selectPlaylist(it)
                        destination = TvDestination.Library
                    },
                    onRefresh = viewModel::refreshSelectedPlaylist,
                    onPlay = openOrPlay,
                    onPlayRecent = { state.recent?.let(openOrPlay) },
                    onExternalExtensionsEnabled = viewModel::setExternalExtensionsEnabled,
                    onEnableExtension = viewModel::enableExtensionPlugin,
                    onReauthorizeExtension = viewModel::reauthorizeExtensionPlugin,
                    onDisableExtension = viewModel::disableExtensionPlugin,
                    onRevokeExtension = viewModel::revokeExtensionPlugin,
                    onClearExtensionData = viewModel::clearExtensionData,
                    onExportExtensionDiagnostics = viewModel::exportExtensionDiagnostics,
                    onOpenExtensionSettings = { extensionId ->
                        viewModel.openExtensionSettings(extensionId, localeTag)
                    },
                    onCloseExtensionSettings = viewModel::closeExtensionSettings,
                    onUpdateExtensionSetting = { sectionId, fieldKey, editToken, value ->
                        viewModel.updateExtensionSetting(
                            sectionId,
                            fieldKey,
                            editToken,
                            value,
                            localeTag,
                        )
                    },
                    onRefreshProviders = viewModel::refreshSubscriptionProviders,
                    onOpenProviderSubscription = viewModel::openProviderSubscription,
                    onReauthenticateProvider = viewModel::reauthenticateProviderAccount,
                    onCloseProviderSubscription = viewModel::closeProviderSubscription,
                    onUpdateProviderTitle = viewModel::updateProviderSubscriptionTitle,
                    onSelectProviderKind = viewModel::selectProviderKind,
                    onUpdateProviderSetting = viewModel::updateProviderSetting,
                    onSubmitProviderSubscription = viewModel::submitProviderSubscription,
                    continueWatching = continueWatching,
                    onSelectCategory = viewModel::selectCategory,
                    onSearch = viewModel::search,
                    guideContent = {
                        GuideScreen(
                            state = state,
                            dial = dial,
                            onSelectPlaylist = viewModel::selectPlaylist,
                            onSelectCategory = viewModel::selectCategory,
                            onPlayLive = { channel ->
                                if (dial.playsExternally(channel)) {
                                    dial.playLiveExternally(channel)
                                } else {
                                    viewModel.play(channel)
                                    surface = TvSurface.Player
                                }
                            },
                            onPlayCatchUp = { channel, programme ->
                                if (dial.playsExternally(channel)) {
                                    dial.playCatchUp(channel, programme, external = true)
                                } else {
                                    dial.playCatchUp(channel, programme)
                                    surface = TvSurface.Player
                                }
                            },
                        )
                    },
                    claudeContent = {
                        ClaudeScreen(onPlay = openOrPlay)
                    },
                    dialSettingsContent = {
                        DialSettingsScreen(
                            preferences = preferences,
                            onUpdate = dial::updatePreferences,
                            onClearHistory = dial::clearContinueWatching,
                            pairingCode = remoteControlCode?.toString()?.padStart(6, '0'),
                        )
                    },
                )
            }
        }

        details?.let { current ->
            DetailsScreen(
                state = current,
                active = surface == TvSurface.Browse,
                isFavourite = state.favorites.any { it.id == current.channel.id },
                onPlayFilm = { fromStart ->
                    if (dial.playsExternally(current.channel)) {
                        dial.playFilm(current.channel, fromStart, external = true)
                    } else {
                        dial.playFilm(current.channel, fromStart)
                        surface = TvSurface.Player
                    }
                },
                onContinueSeries = { progress ->
                    if (dial.playsExternally(current.channel)) {
                        dial.continueSeries(current.channel, progress, external = true)
                    } else {
                        dial.continueSeries(current.channel, progress)
                        surface = TvSurface.Player
                    }
                },
                onPlayEpisode = { episode ->
                    if (dial.playsExternally(current.channel)) {
                        dial.playEpisode(current.channel, episode, fromStart = false, external = true)
                    } else {
                        dial.playEpisode(current.channel, episode, fromStart = false)
                        surface = TvSurface.Player
                    }
                },
                onSelectSeason = dial::selectSeason,
                onToggleFavourite = { viewModel.toggleFavorite(current.channel) },
                onBack = dial::closeDetails,
            )
        }

        AnimatedVisibility(
            visible = surface == TvSurface.Player,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TvPlayerScreen(
                player = player,
                channel = currentChannel,
                channelNumber = (zapIndex + 1).takeIf { zapIndex >= 0 && preferences.showChannelNumbers },
                live = live,
                canZap = live && zapIndex >= 0 && zapChannels.size > 1,
                isFavourite = playingId != null && state.favorites.any { it.id == playingId },
                isPlaying = isPlaying,
                playbackState = playbackState,
                reconnecting = reconnecting,
                failed = playbackFailed,
                preferences = preferences,
                onPlayPause = { viewModel.pauseOrContinue(!isPlaying) },
                onNextChannel = { zap(1) },
                onPreviousChannel = { zap(-1) },
                onToggleFavourite = { currentChannel?.let(viewModel::toggleFavorite) },
                onBack = closePlayer,
                onClose = closePlayer
            )
        }

        if (showSplash) {
            BrandSplash(onFinished = { splashDone = true })
        }

        AnimatedVisibility(
            visible = exitHintVisible && surface == TvSurface.Browse,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.dial_exit_hint),
                color = TvColors.OnFocus,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(TvColors.Focus)
                    .padding(horizontal = 24.dp, vertical = 10.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}
