package com.m3u.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/* ---------------------------------------------------------------------------------------------
 * Sign in
 * ------------------------------------------------------------------------------------------- */

@Composable
fun XtreamSignInScreen(
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false,
    onCancel: (() -> Unit)? = null,
    viewModel: XtreamAccountViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val serverFocus = remember { FocusRequester() }
    val signInFocus = remember { FocusRequester() }

    // Wait until nothing covers the screen (e.g. the launch animation) before taking focus.
    val focusAllowed = LocalTvFocusEnabled.current
    LaunchedEffect(requestInitialFocus, focusAllowed) {
        if (requestInitialFocus && focusAllowed) {
            yield()
            runCatching { serverFocus.requestFocus() }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 32.dp, end = 64.dp, bottom = 32.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .weight(0.9f)
                .widthIn(max = 520.dp)
        ) {
            Text(
                text = stringResource(R.string.dial_signin_title),
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 42.sp,
            )
            Text(
                text = stringResource(R.string.dial_signin_body),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 18.sp,
                lineHeight = 28.sp,
            )
            Text(
                text = stringResource(R.string.dial_signin_keyboard_hint),
                color = TvColors.TextMuted,
                fontFamily = TvFonts.Body,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .focusGroup()
        ) {
            DialTextField(
                label = stringResource(R.string.dial_field_server),
                value = form.server,
                onValueChange = viewModel::updateServer,
                placeholder = stringResource(R.string.dial_field_server_placeholder),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                readOnly = form.busy,
                focusRequester = serverFocus,
            )
            DialTextField(
                label = stringResource(R.string.dial_field_username),
                value = form.username,
                onValueChange = viewModel::updateUsername,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
                readOnly = form.busy,
            )
            DialTextField(
                label = stringResource(R.string.dial_field_password),
                value = form.password,
                onValueChange = viewModel::updatePassword,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
                readOnly = form.busy,
                secret = true,
            )
            DialTextField(
                label = stringResource(R.string.dial_field_name),
                value = form.name,
                onValueChange = viewModel::updateName,
                placeholder = stringResource(R.string.dial_field_name_placeholder),
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                readOnly = form.busy,
                onDone = {
                    runCatching { signInFocus.requestFocus() }
                    viewModel.signIn()
                },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                TvActionButton(
                    text = stringResource(R.string.dial_action_sign_in),
                    icon = Icons.Rounded.CheckCircle,
                    onClick = viewModel::signIn,
                    enabled = !form.busy,
                    focusableWhenDisabled = true,
                    focusRequester = signInFocus,
                )
                if (onCancel != null) {
                    TvActionButton(
                        text = stringResource(R.string.dial_action_cancel),
                        icon = Icons.Rounded.Close,
                        onClick = onCancel,
                    )
                }
            }
            SignInMessage(form.phase)
        }
    }
}

@Composable
private fun SignInMessage(phase: XtreamSignInPhase) {
    val (text, color) = when (phase) {
        XtreamSignInPhase.Idle -> return
        XtreamSignInPhase.Checking ->
            stringResource(R.string.dial_signin_checking) to TvColors.TextSecondary
        XtreamSignInPhase.Importing ->
            stringResource(R.string.dial_signin_importing) to TvColors.TextSecondary
        XtreamSignInPhase.Done ->
            stringResource(R.string.dial_signin_done) to TvColors.Positive
        is XtreamSignInPhase.Failed -> signInErrorText(phase) to TvColors.Danger
    }
    Text(
        text = text,
        color = color,
        fontFamily = TvFonts.Body,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun signInErrorText(failure: XtreamSignInPhase.Failed): String = when (failure.error) {
    XtreamSignInError.MissingServer -> stringResource(R.string.dial_error_missing_server)
    XtreamSignInError.MissingCredentials -> stringResource(R.string.dial_error_missing_credentials)
    XtreamSignInError.Unreachable -> stringResource(R.string.dial_error_unreachable)
    XtreamSignInError.Rejected -> stringResource(R.string.dial_error_rejected)
    XtreamSignInError.AccountInactive -> failure.detail
        ?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.dial_error_inactive, it) }
        ?: stringResource(R.string.dial_error_inactive_generic)
    XtreamSignInError.ImportFailed -> stringResource(R.string.dial_error_import)
}

/**
 * Single-line field that behaves on a remote: up/down leave the field instead of moving the
 * caret, and the centre button opens the on-screen keyboard.
 */
@Composable
internal fun DialTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    readOnly: Boolean,
    placeholder: String = "",
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
    onDone: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = if (focused) TvColors.Focus else TvColors.TextSecondary,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            singleLine = true,
            textStyle = TextStyle(
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontSize = 18.sp,
            ),
            cursorBrush = SolidColor(TvColors.Focus),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onDone = {
                    keyboard?.hide()
                    onDone()
                },
            ),
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionCenter -> {
                                keyboard?.show()
                                true
                            }
                            else -> false
                        }
                    }
                }
                .semantics {
                    contentDescription = label
                    if (secret) password()
                }
                .background(
                    if (focused) TvColors.SurfaceRaised else TvColors.Surface.copy(alpha = 0.72f),
                    HudShape
                )
                .border(
                    width = if (focused) 3.dp else 1.dp,
                    color = if (focused) TvColors.Focus else TvColors.Focus.copy(alpha = 0.25f),
                    shape = HudShape,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            color = TvColors.TextMuted,
                            fontFamily = TvFonts.Body,
                            fontSize = 18.sp,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/* ---------------------------------------------------------------------------------------------
 * Accounts
 * ------------------------------------------------------------------------------------------- */

@Composable
fun XtreamAccountScreen(
    viewModel: XtreamAccountViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(form.phase) {
        if (adding && form.phase == XtreamSignInPhase.Done) adding = false
    }

    if (accounts.isEmpty() || adding) {
        XtreamSignInScreen(
            viewModel = viewModel,
            onCancel = if (accounts.isEmpty()) null else {
                {
                    viewModel.resetForm()
                    adding = false
                }
            },
        )
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(start = 48.dp, top = 48.dp, end = 64.dp, bottom = 48.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusGroup()
    ) {
        item {
            SectionTitle(
                title = stringResource(R.string.dial_accounts_title),
                subtitle = stringResource(R.string.dial_accounts_subtitle),
            )
        }
        items(accounts, key = { it.key }) { account ->
            AccountCard(
                account = account,
                status = statuses[account.key] ?: XtreamAccountStatus.Loading,
                onRefresh = { viewModel.refreshStatus(account) },
                onRemove = { viewModel.remove(account) },
            )
        }
        item {
            TvActionButton(
                text = stringResource(R.string.dial_action_add_account),
                icon = Icons.Rounded.Add,
                onClick = {
                    viewModel.resetForm()
                    adding = true
                },
            )
        }
    }
}

@Composable
private fun AccountCard(
    account: XtreamAccount,
    status: XtreamAccountStatus,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirmingRemove by remember(account.key) { mutableStateOf(false) }
    LaunchedEffect(confirmingRemove) {
        if (confirmingRemove) {
            delay(4_000)
            confirmingRemove = false
        }
    }
    val host = account.credentials.server
        .substringAfter("://")

    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 880.dp)
            .background(TvColors.Surface.copy(alpha = 0.86f), RoundedCornerShape(16.dp))
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = account.title,
                color = TvColors.TextPrimary,
                fontFamily = TvFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.dial_account_login, account.credentials.username, host),
                color = TvColors.TextSecondary,
                fontFamily = TvFonts.Body,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            val (statusText, statusColor) = statusLine(status)
            AccountFact(stringResource(R.string.dial_account_status_label), statusText, statusColor)
            if (status is XtreamAccountStatus.Ready) {
                AccountFact(
                    label = stringResource(R.string.dial_account_expires_label),
                    value = expiryText(status.expiresAtMillis),
                    valueColor = expiryColor(status.expiresAtMillis),
                )
                AccountFact(
                    label = stringResource(R.string.dial_account_connections_label),
                    value = connectionsText(status),
                    valueColor = TvColors.TextPrimary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvActionButton(
                text = stringResource(R.string.dial_action_check_again),
                icon = Icons.Rounded.Refresh,
                onClick = onRefresh,
                enabled = status != XtreamAccountStatus.Loading,
                focusableWhenDisabled = true,
            )
            TvActionButton(
                text = stringResource(
                    if (confirmingRemove) R.string.dial_action_remove_confirm
                    else R.string.dial_action_remove
                ),
                icon = Icons.Rounded.Delete,
                onClick = {
                    if (confirmingRemove) onRemove() else confirmingRemove = true
                },
            )
        }
    }
}

@Composable
private fun AccountFact(label: String, value: String, valueColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = TvColors.TextMuted,
            fontFamily = TvFonts.Body,
            fontSize = 13.sp,
        )
        Text(
            text = value,
            color = valueColor,
            fontFamily = TvFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun statusLine(status: XtreamAccountStatus): Pair<String, Color> = when (status) {
    XtreamAccountStatus.Loading ->
        stringResource(R.string.dial_account_checking) to TvColors.TextSecondary
    XtreamAccountStatus.Unreachable ->
        stringResource(R.string.dial_account_unreachable) to TvColors.Danger
    XtreamAccountStatus.Rejected ->
        stringResource(R.string.dial_account_rejected) to TvColors.Danger
    is XtreamAccountStatus.Ready -> when {
        !status.active -> (status.status ?: "") to TvColors.Danger
        status.trial -> stringResource(R.string.dial_account_status_trial) to TvColors.Positive
        else -> stringResource(R.string.dial_account_status_active) to TvColors.Positive
    }
}

@Composable
private fun expiryText(expiresAtMillis: Long?): String {
    if (expiresAtMillis == null) return stringResource(R.string.dial_account_no_expiry)
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expiresAtMillis))
    val remaining = expiresAtMillis - System.currentTimeMillis()
    val detail = if (remaining <= 0) {
        stringResource(R.string.dial_account_expired)
    } else {
        val days = TimeUnit.MILLISECONDS.toDays(remaining).toInt()
        pluralStringResource(R.plurals.dial_account_days_left, days, days)
    }
    return stringResource(R.string.dial_account_expires_value, date, detail)
}

private fun expiryColor(expiresAtMillis: Long?): Color {
    if (expiresAtMillis == null) return TvColors.TextPrimary
    val days = TimeUnit.MILLISECONDS.toDays(expiresAtMillis - System.currentTimeMillis())
    return when {
        days < 0 -> TvColors.Danger
        days <= 7 -> TvColors.Focus
        else -> TvColors.TextPrimary
    }
}

@Composable
private fun connectionsText(status: XtreamAccountStatus.Ready): String {
    val active = status.activeConnections
    val max = status.maxConnections
    return if (active != null && max != null) {
        stringResource(R.string.dial_account_connections_value, active, max)
    } else {
        stringResource(R.string.dial_account_not_reported)
    }
}
