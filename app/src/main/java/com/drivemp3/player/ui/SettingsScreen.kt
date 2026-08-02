package com.drivemp3.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.drivemp3.player.R
import com.drivemp3.player.model.CacheQuota

/**
 * Settings (spec §6): account, the cache quota (FR-3.2.2), a used/limit/free storage
 * breakdown, and Clear Cache.
 *
 * Stateless but for the local confirmation dialog: everything shown comes from
 * [SettingsUiState], every action is a callback. Sign Out and Clear Cache live here
 * rather than in a library overflow menu because §6 names this as their home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onQuotaSelected: (CacheQuota) -> Unit,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            AccountSection(email = state.email, onSignOut = onSignOut)

            HorizontalDivider()

            CacheLimitSection(selected = state.quota, onQuotaSelected = onQuotaSelected)

            HorizontalDivider()

            StorageSection(
                quota = state.quota,
                usedCacheBytes = state.usedCacheBytes,
                deviceFreeBytes = state.deviceFreeBytes,
                onClearCache = onClearCache,
            )
        }
    }
}

@Composable
private fun AccountSection(email: String?, onSignOut: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        SectionTitle(stringResource(R.string.account))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = email ?: stringResource(R.string.account_signed_in),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSignOut) {
                Text(stringResource(R.string.sign_out))
            }
        }
    }
}

@Composable
private fun CacheLimitSection(
    selected: CacheQuota,
    onQuotaSelected: (CacheQuota) -> Unit,
) {
    Column(Modifier.padding(vertical = 12.dp)) {
        SectionTitle(
            text = stringResource(R.string.cache_limit),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        CacheQuota.entries.forEach { quota ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The whole row toggles the option, so the radio button and its label
                    // share one target and one selected-state semantics for a11y.
                    .selectable(
                        selected = quota == selected,
                        onClick = { onQuotaSelected(quota) },
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = quota == selected, onClick = null)
                Text(
                    text = quotaLabel(quota),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StorageSection(
    quota: CacheQuota,
    usedCacheBytes: Long,
    deviceFreeBytes: Long,
    onClearCache: () -> Unit,
) {
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        SectionTitle(stringResource(R.string.storage_label))

        // The proportion bar is only meaningful against a finite ceiling; unlimited gets
        // the figures without a fill that would have no denominator.
        if (!quota.isUnlimited) {
            val fraction = if (quota.bytes <= 0L) 0f
            else (usedCacheBytes.toFloat() / quota.bytes).coerceIn(0f, 1f)
            StorageBar(fraction = fraction)
            Text(
                text = stringResource(
                    R.string.storage_used_of_limit,
                    formatSize(usedCacheBytes),
                    formatSize(quota.bytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.storage_used_unlimited, formatSize(usedCacheBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = stringResource(R.string.storage_device_free, formatSize(deviceFreeBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        OutlinedButton(
            onClick = { showClearConfirm = true },
            enabled = usedCacheBytes > 0L,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.clear_cache))
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_all_downloads_title)) },
            text = { Text(stringResource(R.string.clear_all_downloads_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClearCache()
                }) {
                    Text(stringResource(R.string.clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** A two-tone proportion bar: filled portion is [fraction] of the track. */
@Composable
private fun StorageBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun quotaLabel(quota: CacheQuota): String = stringResource(
    when (quota) {
        CacheQuota.Mb250 -> R.string.quota_250_mb
        CacheQuota.Mb500 -> R.string.quota_500_mb
        CacheQuota.Gb1 -> R.string.quota_1_gb
        CacheQuota.Gb5 -> R.string.quota_5_gb
        CacheQuota.Unlimited -> R.string.quota_unlimited
    }
)
