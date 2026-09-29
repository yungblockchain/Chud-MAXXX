package com.m3u.tv

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.isSeries
import com.m3u.data.database.model.isVod
import com.m3u.data.tv.model.keyCode
import com.m3u.i18n.R.string

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

    // Films and series open their details page; live channels play straight away.
    val openOrPlay: (Channel) -> Unit = { channel ->
        val playlist = state.playlists.firstOrNull { it.url == channel.playlistUrl }
        if (playlist != null && (playlist.isVod || playlist.isSeries)) {
            dial.openDetails(channel, playlist)
        } else {
            viewModel.play(channel)
            surface = TvSurface.Player
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
                    guideContent = {
                        GuideScreen(
                            state = state,
                            dial = dial,
                            onSelectPlaylist = viewModel::selectPlaylist,
                            onPlayLive = { channel ->
                                viewModel.play(channel)
                                surface = TvSurface.Player
                            },
                            onPlayCatchUp = { channel, programme ->
                                dial.playCatchUp(channel, programme)
                                surface = TvSurface.Player
                            },
                        )
                    },
                    dialSettingsContent = {
                        DialSettingsScreen(
                            preferences = preferences,
                            onUpdate = dial::updatePreferences,
                            onClearHistory = dial::clearContinueWatching,
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
                    dial.playFilm(current.channel, fromStart)
                    surface = TvSurface.Player
                },
                onContinueSeries = { progress ->
                    dial.continueSeries(current.channel, progress)
                    surface = TvSurface.Player
                },
                onPlayEpisode = { episode ->
                    dial.playEpisode(current.channel, episode, fromStart = false)
                    surface = TvSurface.Player
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

        remoteControlCode?.let { code ->
            val displayCode = code.toString().padStart(6, '0')
            val spokenCode = displayCode.toCharArray().joinToString(separator = " ")
            val pairingCodeDescription =
                stringResource(string.ui_remote_control_pairing_code, spokenCode)
            // Phone-remote pairing code, kept small at the foot of the menu rail so it never
            // covers the tab headers (Markets chips, Guide dates) in the top-right corner.
            Text(
                text = displayCode,
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .width(112.dp)
                    .padding(bottom = 2.dp)
                    .clearAndSetSemantics {
                        contentDescription = pairingCodeDescription
                        liveRegion = LiveRegionMode.Polite
                    }
            )
        }
    }
}
