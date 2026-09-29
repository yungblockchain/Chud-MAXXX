package com.m3u.tv

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.m3u.data.database.model.DataSource
import com.m3u.data.database.model.Playlist
import com.m3u.data.parser.xtream.XtreamInput
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.worker.SubscriptionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/* -------------------------------------------------------------------------------------------------
 * Dial: native Xtream Codes sign-in and account status for the TV app.
 *
 * Upstream M3UAndroid only lets the TV build receive Xtream accounts pushed from the phone app.
 * On a Fire TV Stick there usually is no phone app, so Dial signs in directly on the TV:
 *   1. check the credentials against player_api.php (fast, clear error messages), then
 *   2. hand them to the existing SubscriptionWorker, which imports live, movies and series.
 * ---------------------------------------------------------------------------------------------- */

data class XtreamCredentials(
    val server: String,
    val username: String,
    val password: String,
)

/** What went wrong when the provider's server couldn't be used, so the app can say so plainly. */
enum class XtreamProblem { Generic, HostNotFound, Refused, Timeout, Secure, Forbidden, HttpStatus, NotXtream }

sealed interface XtreamAccountStatus {
    data object Loading : XtreamAccountStatus
    data class Unreachable(
        val problem: XtreamProblem = XtreamProblem.Generic,
        /** Host and port, for messages ("example.com:8080"). */
        val host: String = "",
        val httpCode: Int? = null,
    ) : XtreamAccountStatus
    data object Rejected : XtreamAccountStatus
    data class Ready(
        val status: String?,
        val expiresAtMillis: Long?,
        val activeConnections: Int?,
        val maxConnections: Int?,
        val trial: Boolean,
    ) : XtreamAccountStatus {
        /** Panels omit `status` now and then; treat a missing value as active. */
        val active: Boolean get() = status == null || status.equals("Active", ignoreCase = true)
    }
}

internal object XtreamClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MAX_BODY_CHARS = 512 * 1024

    /** Turns "example.com:8080/", "http://example.com:8080/c/" etc. into "http://example.com:8080". */
    fun normalizeServer(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) trimmed else "http://$trimmed"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port
        return if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
    }

    /**
     * Providers often send one M3U link instead of three separate fields, e.g.
     * http://host:8080/get.php?username=U&password=P&type=m3u_plus
     * If the server field holds such a link, read the credentials out of it.
     */
    fun credentialsFromLink(input: String): XtreamCredentials? {
        val trimmed = input.trim()
        if (!trimmed.contains("username=") || !trimmed.contains("password=")) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
        val username = uri.getQueryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
        val password = uri.getQueryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
        val server = normalizeServer(withScheme) ?: return null
        return XtreamCredentials(server, username, password)
    }

    suspend fun fetchAccount(credentials: XtreamCredentials): XtreamAccountStatus =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append(credentials.server)
                append("/player_api.php?username=")
                append(URLEncoder.encode(credentials.username, Charsets.UTF_8.name()))
                append("&password=")
                append(URLEncoder.encode(credentials.password, Charsets.UTF_8.name()))
            }
            val host = hostLabel(credentials.server)
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "ChudStreams/1.0 (Android TV)")
                }
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_UNAUTHORIZED -> return@withContext XtreamAccountStatus.Rejected
                    // 403 usually means the provider is blocking the device, app or network,
                    // not a wrong password (panels answer those with auth = 0).
                    HttpURLConnection.HTTP_FORBIDDEN -> return@withContext XtreamAccountStatus.Unreachable(
                        XtreamProblem.Forbidden, host, code,
                    )
                    in 200..299 -> Unit
                    else -> return@withContext XtreamAccountStatus.Unreachable(
                        XtreamProblem.HttpStatus, host, code,
                    )
                }
                val body = connection.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(8 * 1024)
                    val out = StringBuilder()
                    while (out.length < MAX_BODY_CHARS) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        out.appendRange(buffer, 0, read)
                    }
                    out.toString()
                }
                parseAccount(body, host)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.HostNotFound, host)
            } catch (e: SocketTimeoutException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Timeout, host)
            } catch (e: ConnectException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Refused, host)
            } catch (e: NoRouteToHostException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Refused, host)
            } catch (e: SSLException) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Secure, host)
            } catch (e: Exception) {
                XtreamAccountStatus.Unreachable(XtreamProblem.Generic, host)
            } finally {
                connection?.disconnect()
            }
        }

    /** "http://example.com:8080" -> "example.com:8080", for messages. */
    fun hostLabel(server: String): String {
        val uri = runCatching { Uri.parse(server) }.getOrNull() ?: return server
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return server
        return if (uri.port > 0) "$host:${uri.port}" else host
    }

    internal fun parseAccount(body: String, host: String = ""): XtreamAccountStatus {
        // Anything other than a JSON object is usually a web page: a portal, block page or check.
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return XtreamAccountStatus.Unreachable(XtreamProblem.NotXtream, host)
        val user = root["user_info"] as? JsonObject ?: return XtreamAccountStatus.Rejected
        fun field(name: String): String? = (user[name] as? JsonPrimitive)?.contentOrNull
        if (field("auth") == "0") return XtreamAccountStatus.Rejected
        return XtreamAccountStatus.Ready(
            status = field("status"),
            expiresAtMillis = field("exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
            activeConnections = field("active_cons")?.toIntOrNull(),
            maxConnections = field("max_connections")?.toIntOrNull(),
            trial = field("is_trial") == "1",
        )
    }
}

enum class XtreamSignInError {
    MissingServer,
    MissingCredentials,
    Unreachable,
    Rejected,
    AccountInactive,
    ImportFailed,
}

sealed interface XtreamSignInPhase {
    data object Idle : XtreamSignInPhase
    data object Checking : XtreamSignInPhase
    data object Importing : XtreamSignInPhase
    data object Done : XtreamSignInPhase
    data class Failed(
        val error: XtreamSignInError,
        val detail: String? = null,
        val unreachable: XtreamAccountStatus.Unreachable? = null,
    ) : XtreamSignInPhase
}

@Immutable
data class XtreamSignInForm(
    val name: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val phase: XtreamSignInPhase = XtreamSignInPhase.Idle,
) {
    val busy: Boolean
        get() = phase == XtreamSignInPhase.Checking || phase == XtreamSignInPhase.Importing
}

@Immutable
data class XtreamAccount(
    val key: String,
    val title: String,
    val credentials: XtreamCredentials,
    val playlistUrls: List<String>,
)

@HiltViewModel
class XtreamAccountViewModel @Inject constructor(
    private val workManager: WorkManager,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    private val _form = MutableStateFlow(XtreamSignInForm())
    val form: StateFlow<XtreamSignInForm> = _form.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, XtreamAccountStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, XtreamAccountStatus>> = _statuses.asStateFlow()

    val accounts: StateFlow<List<XtreamAccount>> = playlistRepository
        .observeAll()
        .map { playlists -> playlists.toXtreamAccounts() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var signInJob: Job? = null

    init {
        viewModelScope.launch {
            accounts.collect { list ->
                list.filter { it.key !in _statuses.value }.forEach { refreshStatus(it) }
            }
        }
    }

    fun updateName(value: String) = _form.update { it.copy(name = value, phase = it.idleUnlessBusy()) }
    fun updateServer(value: String) = _form.update { it.copy(server = value, phase = it.idleUnlessBusy()) }
    fun updateUsername(value: String) = _form.update { it.copy(username = value, phase = it.idleUnlessBusy()) }
    fun updatePassword(value: String) = _form.update { it.copy(password = value, phase = it.idleUnlessBusy()) }

    fun resetForm() {
        signInJob?.cancel()
        _form.value = XtreamSignInForm()
    }

    fun signIn() {
        val current = _form.value
        if (current.busy) return

        val fromLink = XtreamClient.credentialsFromLink(current.server)
        val server = fromLink?.server ?: XtreamClient.normalizeServer(current.server)
        val username = fromLink?.username ?: current.username.trim()
        val password = fromLink?.password ?: current.password.trim()

        if (server == null) {
            fail(XtreamSignInError.MissingServer)
            return
        }
        if (username.isEmpty() || password.isEmpty()) {
            _form.update { it.copy(server = server, username = username, password = password) }
            fail(XtreamSignInError.MissingCredentials)
            return
        }
        val credentials = XtreamCredentials(server, username, password)
        _form.update {
            it.copy(
                server = server,
                username = username,
                password = password,
                phase = XtreamSignInPhase.Checking,
            )
        }

        signInJob = viewModelScope.launch {
            when (val status = XtreamClient.fetchAccount(credentials)) {
                is XtreamAccountStatus.Unreachable -> fail(XtreamSignInError.Unreachable, unreachable = status)
                XtreamAccountStatus.Rejected -> fail(XtreamSignInError.Rejected)
                XtreamAccountStatus.Loading -> Unit
                is XtreamAccountStatus.Ready -> {
                    if (!status.active) {
                        fail(XtreamSignInError.AccountInactive, status.status)
                    } else {
                        importAccount(credentials, _form.value.name.trim(), status)
                    }
                }
            }
        }
    }

    private suspend fun importAccount(
        credentials: XtreamCredentials,
        name: String,
        status: XtreamAccountStatus.Ready,
    ) {
        val title = name.ifEmpty { Uri.parse(credentials.server).host ?: credentials.server }
        val workId = try {
            SubscriptionWorker.xtream(
                workManager = workManager,
                title = title,
                url = "",
                basicUrl = credentials.server,
                username = credentials.username,
                password = credentials.password,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(XtreamSignInError.ImportFailed)
            return
        }
        _form.update { it.copy(phase = XtreamSignInPhase.Importing) }
        _statuses.update { it + (accountKey(credentials) to status) }

        val finished = workManager
            .getWorkInfoByIdFlow(workId)
            .first { info -> info == null || info.state.isFinished }
        if (finished == null || finished.state == WorkInfo.State.SUCCEEDED) {
            // Keep the server so adding a second login on the same panel is quick.
            _form.update {
                XtreamSignInForm(server = it.server, phase = XtreamSignInPhase.Done)
            }
        } else {
            fail(XtreamSignInError.ImportFailed)
        }
    }

    fun refreshStatus(account: XtreamAccount) {
        _statuses.update { it + (account.key to XtreamAccountStatus.Loading) }
        viewModelScope.launch {
            val status = XtreamClient.fetchAccount(account.credentials)
            _statuses.update { it + (account.key to status) }
        }
    }

    fun remove(account: XtreamAccount) {
        viewModelScope.launch {
            account.playlistUrls.forEach { url ->
                runCatching { playlistRepository.unsubscribe(url) }
                    .onFailure { if (it is CancellationException) throw it }
            }
            _statuses.update { it - account.key }
        }
    }

    private fun fail(
        error: XtreamSignInError,
        detail: String? = null,
        unreachable: XtreamAccountStatus.Unreachable? = null,
    ) {
        _form.update { it.copy(phase = XtreamSignInPhase.Failed(error, detail, unreachable)) }
    }

    private fun XtreamSignInForm.idleUnlessBusy(): XtreamSignInPhase =
        if (busy) phase else XtreamSignInPhase.Idle

    private fun List<Playlist>.toXtreamAccounts(): List<XtreamAccount> =
        filter { it.source == DataSource.Xtream }
            .mapNotNull { playlist ->
                val input = XtreamInput.decodeFromPlaylistUrlOrNull(playlist.url)
                    ?: return@mapNotNull null
                val server = XtreamClient.normalizeServer(input.basicUrl) ?: return@mapNotNull null
                playlist to XtreamCredentials(server, input.username, input.password)
            }
            .groupBy { (_, credentials) -> accountKey(credentials) }
            .map { (key, entries) ->
                XtreamAccount(
                    key = key,
                    title = entries.first().first.title,
                    credentials = entries.first().second,
                    playlistUrls = entries.map { (playlist, _) -> playlist.url },
                )
            }
            .sortedBy { it.title.lowercase() }

    private fun accountKey(credentials: XtreamCredentials): String =
        "${credentials.server.lowercase()}|${credentials.username}"
}
