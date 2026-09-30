package com.m3u.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/*
 * Match Centre. Live, upcoming and finished football from ESPN's public scoreboard
 * (no key). Lineups come from the same feed's summary call when a match is opened.
 * WhoScored, Sofascore, FBref and Understat do not offer a free keyless API, so this
 * screen does not pretend to call them.
 */

private val MATCH_LEAGUES = listOf(
    "eng.1" to "Premier League",
    "esp.1" to "LaLiga",
    "ger.1" to "Bundesliga",
    "ita.1" to "Serie A",
    "fra.1" to "Ligue 1",
    "uefa.champions" to "Champions League",
    "uefa.europa" to "Europa League",
    "eng.2" to "Championship",
    "usa.1" to "MLS",
    "ned.1" to "Eredivisie",
    "por.1" to "Primeira Liga",
)

private enum class MatchFilter { Live, Upcoming, Results }

@Immutable
private data class FixtureSide(val name: String, val score: String?)

@Immutable
private data class Fixture(
    val id: String,
    val leagueId: String,
    val league: String,
    val home: FixtureSide,
    val away: FixtureSide,
    val state: String,
    val detail: String,
    val venue: String?,
    val start: String?,
)

@Immutable
private data class LineupPlayer(val name: String, val number: String?, val position: String?)

@Immutable
private data class MatchDetail(
    val headline: String?,
    val homeXi: List<LineupPlayer>,
    val awayXi: List<LineupPlayer>,
    val note: String?,
)

@Composable
fun MatchCentreScreen() {
    var leagueIndex by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(MatchFilter.Live) }
    var fixtures by remember { mutableStateOf<List<Fixture>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Fixture?>(null) }
    var detail by remember { mutableStateOf<MatchDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        val loaded = runCatching { MatchFeed.loadAll() }
        loaded.onSuccess { fixtures = it }.onFailure { error = it.message ?: "Scores didn't load" }
        loading = false
    }

    LaunchedEffect(selected?.id) {
        val fixture = selected ?: run {
            detail = null
            return@LaunchedEffect
        }
        detailLoading = true
        detail = runCatching { MatchFeed.loadDetail(fixture) }.getOrNull()
        detailLoading = false
    }

    val league = MATCH_LEAGUES[leagueIndex]
    val shown = fixtures
        .filter { it.leagueId == league.first }
        .filter { fixture ->
            when (filter) {
                MatchFilter.Live -> fixture.state == "in"
                MatchFilter.Upcoming -> fixture.state == "pre"
                MatchFilter.Results -> fixture.state == "post"
            }
        }

    if (selected != null) {
        MatchDetailPage(
            fixture = selected!!,
            detail = detail,
            loading = detailLoading,
            onBack = { selected = null },
        )
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 36.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "Match Centre",
                    color = TvColors.TextPrimary,
                    fontFamily = TvFonts.Accent,
                    fontSize = 28.sp,
                    modifier = Modifier.weight(1f),
                )
                TvActionButton(
                    text = "Refresh",
                    icon = Icons.Rounded.Refresh,
                    onClick = { reload++ },
                )
            }
        }
        item {
            Text(
                text = "Live scores, fixtures and lineups. Source: ESPN public scoreboard.",
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(MATCH_LEAGUES.size) { index ->
                    val item = MATCH_LEAGUES[index]
                    TvActionButton(
                        text = item.second,
                        icon = Icons.Rounded.SportsSoccer,
                        selected = index == leagueIndex,
                        onClick = { leagueIndex = index },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MatchFilter.entries.forEach { item ->
                    TvActionButton(
                        text = item.name,
                        icon = Icons.Rounded.SportsSoccer,
                        selected = filter == item,
                        onClick = { filter = item },
                    )
                }
            }
        }
        when {
            loading && fixtures.isEmpty() -> item { Status("Loading scores…") }
            error != null && fixtures.isEmpty() -> item { Status(error ?: "Scores didn't load") }
            shown.isEmpty() -> item { Status("Nothing in ${filter.name.lowercase()} for ${league.second}.") }
            else -> items(shown, key = { it.id }) { fixture ->
                FocusFrame(
                    onClick = { selected = fixture },
                    semanticsLabel = "${fixture.home.name} versus ${fixture.away.name}",
                    modifier = Modifier.widthIn(max = 980.dp),
                ) { focused ->
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Text(
                            text = fixture.detail,
                            color = if (focused) TvColors.OnFocus else TvColors.Focus,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = "${fixture.home.name}  ${fixture.home.score ?: "–"}    ${fixture.away.score ?: "–"}  ${fixture.away.name}",
                            color = if (focused) TvColors.OnFocus else TvColors.TextPrimary,
                            fontFamily = TvFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val place = listOfNotNull(fixture.venue, fixture.start).joinToString("  ·  ")
                        if (place.isNotBlank()) {
                            Text(
                                text = place,
                                color = if (focused) TvColors.OnFocus else TvColors.TextSecondary,
                                fontFamily = TvFonts.Body,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchDetailPage(
    fixture: Fixture,
    detail: MatchDetail?,
    loading: Boolean,
    onBack: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(start = 36.dp, end = 48.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            TvActionButton(
                text = "Back to fixtures",
                icon = Icons.Rounded.KeyboardArrowLeft,
                onClick = onBack,
            )
        }
        item {
            Text(
                text = "${fixture.home.name}  ${fixture.home.score ?: "–"}  –  ${fixture.away.score ?: "–"}  ${fixture.away.name}",
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Accent,
                fontSize = 26.sp,
            )
        }
        item {
            Text(
                text = listOfNotNull(fixture.league, fixture.detail, fixture.venue).joinToString("  ·  "),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
            )
        }
        detail?.headline?.let { headline -> item { Status(headline) } }
        if (loading) {
            item { Status("Loading lineups…") }
        } else if (detail == null || (detail.homeXi.isEmpty() && detail.awayXi.isEmpty())) {
            item { Status(detail?.note ?: "Lineups are not published for this match yet.") }
        } else {
            item { LineupBlock(fixture.home.name, detail.homeXi) }
            item { LineupBlock(fixture.away.name, detail.awayXi) }
        }
    }
}

@Composable
private fun LineupBlock(team: String, players: List<LineupPlayer>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = team,
            color = TvColors.TextPrimary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        players.forEach { player ->
            val bits = listOfNotNull(player.number, player.position).joinToString("  ")
            Text(
                text = if (bits.isBlank()) player.name else "$bits   ${player.name}",
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(
        text = text,
        color = TvColors.TextSecondary,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        modifier = Modifier.widthIn(max = 860.dp),
    )
}

private object MatchFeed {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun loadAll(): List<Fixture> = coroutineScope {
        MATCH_LEAGUES.map { (id, name) ->
            async { runCatching { scoreboard(id, name) }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    suspend fun loadDetail(fixture: Fixture): MatchDetail = withContext(Dispatchers.IO) {
        val root = get(
            "https://site.api.espn.com/apis/site/v2/sports/soccer/${fixture.leagueId}/summary?event=${fixture.id}"
        )
        val header = root["header"]?.asObject()?.get("competitions")?.asArray()?.firstOrNull()?.asObject()
        val note = header?.text("notes")
            ?: root["header"]?.asObject()?.text("season")
        val rosters = root["rosters"]?.asArray().orEmpty()
        fun xi(side: String): List<LineupPlayer> {
            val roster = rosters.firstOrNull { item ->
                item.asObject()?.get("homeAway")?.textValue() == side ||
                    item.asObject()?.get("team")?.asObject()?.text("displayName") ==
                    if (side == "home") fixture.home.name else fixture.away.name
            }?.asObject()
            val players = roster?.get("roster")?.asArray().orEmpty()
            return players.mapNotNull { element ->
                val player = element.asObject() ?: return@mapNotNull null
                val athlete = player["athlete"]?.asObject()
                val name = athlete?.text("displayName") ?: player.text("displayName") ?: return@mapNotNull null
                val starter = player["starter"]?.textValue()
                if (starter == "false") return@mapNotNull null
                LineupPlayer(
                    name = name,
                    number = athlete?.text("jersey") ?: player.text("jersey"),
                    position = athlete?.get("position")?.asObject()?.text("abbreviation")
                        ?: player.text("position"),
                )
            }
        }
        MatchDetail(
            headline = root["header"]?.asObject()?.get("competitions")?.asArray()
                ?.firstOrNull()?.asObject()?.get("status")?.asObject()?.get("type")?.asObject()?.text("detail"),
            homeXi = xi("home"),
            awayXi = xi("away"),
            note = note,
        )
    }

    private suspend fun scoreboard(leagueId: String, leagueName: String): List<Fixture> =
        withContext(Dispatchers.IO) {
            val root = get("https://site.api.espn.com/apis/site/v2/sports/soccer/$leagueId/scoreboard")
            root["events"]?.asArray().orEmpty().mapNotNull { element ->
                val event = element.asObject() ?: return@mapNotNull null
                val competition = event["competitions"]?.asArray()?.firstOrNull()?.asObject() ?: event
                val competitors = competition["competitors"]?.asArray().orEmpty().mapNotNull { it.asObject() }
                val home = competitors.firstOrNull { it.text("homeAway") == "home" } ?: return@mapNotNull null
                val away = competitors.firstOrNull { it.text("homeAway") == "away" } ?: return@mapNotNull null
                val status = (competition["status"] ?: event["status"])?.asObject()
                val type = status?.get("type")?.asObject()
                Fixture(
                    id = event.text("id") ?: return@mapNotNull null,
                    leagueId = leagueId,
                    league = leagueName,
                    home = FixtureSide(
                        name = home["team"]?.asObject()?.text("displayName") ?: "Home",
                        score = home.text("score"),
                    ),
                    away = FixtureSide(
                        name = away["team"]?.asObject()?.text("displayName") ?: "Away",
                        score = away.text("score"),
                    ),
                    state = type?.text("state") ?: "pre",
                    detail = type?.text("shortDetail") ?: type?.text("detail") ?: "",
                    venue = competition["venue"]?.asObject()?.text("fullName"),
                    start = event.text("date"),
                )
            }
        }

    private fun get(url: String): JsonObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ChudMAXXX/1.0 (Android TV)")
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299 || text.isBlank()) return JsonObject(emptyMap())
            return json.parseToJsonElement(text).asObject() ?: JsonObject(emptyMap())
        } finally {
            connection.disconnect()
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun kotlinx.serialization.json.JsonElement.asArray(): JsonArray? = this as? JsonArray
private fun kotlinx.serialization.json.JsonElement.textValue(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
private fun JsonObject.text(name: String): String? = this[name]?.textValue()?.takeIf { it.isNotBlank() && it != "null" }
