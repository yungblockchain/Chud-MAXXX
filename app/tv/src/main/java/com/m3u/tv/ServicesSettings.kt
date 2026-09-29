package com.m3u.tv

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/* -------------------------------------------------------------------------------------------------
 * Settings > Services: the person's own keys for film info (TMDB, Trakt), markets (CoinMarketCap,
 * Telegram) and crash reports (GitHub). Each is typed here or sent from the phone page, stored
 * encrypted on the Fire TV, and only ever sent to its own service.
 * ---------------------------------------------------------------------------------------------- */

@HiltViewModel
class ServicesSettingsViewModel @Inject constructor(
    private val secrets: SecretStore,
) : ViewModel() {
    val saved: StateFlow<Set<SecretName>> = secrets.saved

    fun save(name: SecretName, value: String) = secrets.put(name, value)

    fun remove(name: SecretName) = secrets.remove(name)
}

@Composable
fun ServicesSettingsScreen(
    viewModel: ServicesSettingsViewModel = hiltViewModel(),
) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<SecretName?>(null) }

    @Composable
    fun KeyRow(name: SecretName, @StringRes label: Int, @StringRes hint: Int, secret: Boolean = true) {
        SecretRow(
            name = name,
            label = stringResource(label),
            hint = stringResource(hint),
            saved = name in saved,
            editing = editing == name,
            onEdit = { editing = it },
            onSave = viewModel::save,
            onRemove = viewModel::remove,
            secret = secret,
        )
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 24.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = stringResource(R.string.dial_services_intro),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                modifier = Modifier.widthIn(max = 820.dp),
            )
        }
        item { SettingsSection(stringResource(R.string.dial_services_section_film)) }
        item { KeyRow(SecretName.Tmdb, R.string.dial_services_tmdb, R.string.dial_services_tmdb_hint) }
        item {
            KeyRow(SecretName.TraktClientId, R.string.dial_services_trakt, R.string.dial_services_trakt_hint)
        }

        item { SettingsSection(stringResource(R.string.dial_services_section_markets)) }
        item {
            KeyRow(SecretName.CoinMarketCap, R.string.dial_services_cmc, R.string.dial_services_cmc_hint)
        }
        item {
            KeyRow(
                SecretName.TelegramBotToken,
                R.string.dial_services_telegram_bot,
                R.string.dial_services_telegram_bot_hint,
            )
        }
        item {
            KeyRow(
                SecretName.TelegramChatId,
                R.string.dial_services_telegram_chat,
                R.string.dial_services_telegram_chat_hint,
                secret = false,
            )
        }
        item {
            KeyRow(
                SecretName.TelegramTradingBot,
                R.string.dial_services_trading_bot,
                R.string.dial_services_trading_bot_hint,
                secret = false,
            )
        }

        item { SettingsSection(stringResource(R.string.dial_services_section_reports)) }
        item {
            KeyRow(SecretName.GitHubRepo, R.string.dial_services_github_repo, R.string.dial_services_github_repo_hint, secret = false)
        }
        item {
            KeyRow(SecretName.GitHubToken, R.string.dial_services_github_token, R.string.dial_services_github_token_hint)
        }
    }
}
