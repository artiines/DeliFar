package com.delifar.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.delifar.app.R
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.data.AppPreferences
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.PrefKeys
import com.delifar.app.data.SearchArea
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.EncryptedArchiveBackupManager
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.RoleBadge
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.syncStatusColors
import com.delifar.app.ui.components.syncStatusLabel
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: DeliFarViewModel,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenTeam: () -> Unit
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsState()
    val company by viewModel.activeCompany.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val resolvedSyncStatus by viewModel.resolvedSyncStatus.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsState()
    val failedSyncCount by viewModel.failedSyncCount.collectAsState()
    val latestSyncError by viewModel.latestSyncError.collectAsState()
    val failedSyncItems by viewModel.failedSyncItems.collectAsState()
    val importPreview by viewModel.importPreview.collectAsState()
    val pendingEncryptedFile by viewModel.pendingEncryptedArchiveFile.collectAsState()
    val archiveImportPreview by viewModel.archiveImportPreview.collectAsState()
    val pendingMigration by viewModel.pendingMigration.collectAsState()

    var showExportPassphraseDialog by remember { mutableStateOf(false) }
    var pendingExportPassphrase by remember { mutableStateOf("") }

    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPermission = granted
        if (granted) viewModel.rebuildReminders()
    }

    val exportEncryptedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = pendingExportPassphrase
        pendingExportPassphrase = ""
        if (uri != null && pass.length >= EncryptedArchiveBackupManager.MIN_PASSPHRASE_LENGTH) {
            viewModel.exportEncryptedArchiveToUri(uri, pass)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportBackupToUri(uri)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.inspectImportUri(uri)
    }

    val scope = rememberCoroutineScope()
    var summaryTimeInput by remember(prefs.summaryMinute) {
        mutableStateOf(BusinessRules.formatMinuteOfDay(prefs.summaryMinute))
    }
    var advanceInput by remember(prefs.advance) {
        mutableStateOf(prefs.advance.toString())
    }
    var geocodingUrlInput by remember(prefs.geocodingUrl) { mutableStateOf(prefs.geocodingUrl) }
    var routingUrlInput by remember(prefs.routingUrl) { mutableStateOf(prefs.routingUrl) }
    var mapStyleUrlInput by remember(prefs.mapStyleUrl) { mutableStateOf(prefs.mapStyleUrl) }
    var allowOsmFallback by remember(prefs.allowOsmFallback) { mutableStateOf(prefs.allowOsmFallback) }
    var tzDropdownExpanded by remember { mutableStateOf(false) }
    val zoneId = remember(prefs.companyTimezone) { BusinessRules.resolveZone(prefs.companyTimezone) }

    val summarySavedMsg = lStr(R.string.summary_time_saved)
    val summaryInvalidMsg = lStr(R.string.summary_time_invalid)
    val advanceSavedMsg = lStr(R.string.advance_minutes_saved)
    val advanceInvalidMsg = lStr(R.string.advance_minutes_invalid)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.settings), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = lStr(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Cloud Account, Company & Transparent Sync Diagnostics
            item {
                val (syncBg, syncFg) = syncStatusColors(resolvedSyncStatus)
                val lastSyncedTs = syncState.lastSyncedAt ?: prefs.lastSyncedAt
                val activeError = syncState.lastError ?: latestSyncError ?: prefs.lastSyncError
                SettingsCard(title = lStr(R.string.account_and_company)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = company?.name ?: lStr(R.string.sync_offline_mode),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = prefs.activeUserEmail.ifBlank { prefs.activeUserName.ifBlank { lStr(R.string.local_profile) } },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RoleBadge(role = prefs.activeUserRole)
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = lStr(R.string.sync_status_title),
                            style = MaterialTheme.typography.labelLarge
                        )
                        StatusTag(
                            text = syncStatusLabel(resolvedSyncStatus, pendingSyncCount, failedSyncCount),
                            bg = syncBg,
                            fg = syncFg
                        )
                    }

                    Text(
                        text = if (lastSyncedTs != null) {
                            lStr(R.string.sync_last_synced, BusinessRules.formatTimestamp(lastSyncedTs, zoneId))
                        } else {
                            lStr(R.string.sync_never_synced)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = lStr(R.string.sync_queue_counts, pendingSyncCount, failedSyncCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (failedSyncCount > 0) TerracottaError else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = lStr(
                            R.string.sync_env_diag,
                            if (FirebaseServices.isConfigured) lStr(R.string.sync_bundled) else lStr(R.string.sync_not_bundled),
                            if (FirebaseServices.isAuthenticated) lStr(R.string.sync_signed_in) else lStr(R.string.sync_not_signed_in),
                            if (FirebaseServices.appCheckInstalled) FirebaseServices.appCheckProviderName else lStr(R.string.sync_inactive)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!activeError.isNullOrBlank()) {
                        Text(
                            text = activeError,
                            style = MaterialTheme.typography.bodySmall,
                            color = TerracottaError
                        )
                    }
                    if (failedSyncItems.isNotEmpty()) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            ),
                            border = BorderStroke(1.dp, TerracottaError.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = lStr(R.string.failed_queue_details_title, failedSyncItems.size),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = TerracottaError,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { viewModel.triggerSyncNow() },
                                        modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                    ) {
                                        Text(lStr(R.string.sync_now), color = TerracottaError)
                                    }
                                }
                                failedSyncItems.forEachIndexed { idx, failedItem ->
                                    if (idx > 0) {
                                        HorizontalDivider(color = TerracottaError.copy(alpha = 0.25f))
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = lStr(
                                                R.string.failed_queue_item_header,
                                                failedItem.entityType,
                                                failedItem.operation,
                                                failedItem.retryCount
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = lStr(R.string.failed_queue_item_id, failedItem.entityId),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = lStr(
                                                R.string.failed_queue_item_error,
                                                failedItem.lastError?.ifBlank { null } ?: lStr(R.string.unknown_sync_error)
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TerracottaError
                                        )
                                    }
                                }
                            }
                        }
                    }
                    FirebaseServices.lastInitError?.let { initErr ->
                        Text(
                            text = initErr,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberAccent
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenAccount,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Business, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.account_and_company), maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = onOpenTeam,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Groups, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.team_management), maxLines = 1)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.triggerSyncNow() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.sync_now), maxLines = 1)
                        }
                        if (company != null && prefs.activeUserRole == EmployeeRole.OWNER) {
                            OutlinedButton(
                                onClick = { viewModel.checkUnmigratedLocalData() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(lStr(R.string.migrate_local_data), maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Company Timezone & Default Search Area
            item {
                SettingsCard(title = lStr(R.string.company_timezone_label)) {
                    ExposedDropdownMenuBox(
                        expanded = tzDropdownExpanded,
                        onExpandedChange = { tzDropdownExpanded = !tzDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = prefs.companyTimezone,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(lStr(R.string.company_timezone_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tzDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = tzDropdownExpanded,
                            onDismissRequest = { tzDropdownExpanded = false }
                        ) {
                            BusinessRules.SUPPORTED_TIMEZONES.forEach { tz ->
                                DropdownMenuItem(
                                    text = { Text(tz) },
                                    onClick = {
                                        viewModel.updatePreference(PrefKeys.COMPANY_TIMEZONE, tz)
                                        tzDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = lStr(R.string.settings_default_area),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.area == SearchArea.LA,
                            onClick = { viewModel.updatePreference(PrefKeys.AREA, SearchArea.LA) },
                            label = { Text(lStr(R.string.area_la)) }
                        )
                        FilterChip(
                            selected = prefs.area == SearchArea.SAC,
                            onClick = { viewModel.updatePreference(PrefKeys.AREA, SearchArea.SAC) },
                            label = { Text(lStr(R.string.area_sac)) }
                        )
                    }
                }
            }

            // Language selector
            item {
                SettingsCard(title = lStr(R.string.settings_language)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.language == "system",
                            onClick = { viewModel.updatePreference(PrefKeys.LANGUAGE, "system") },
                            label = { Text(lStr(R.string.lang_system)) }
                        )
                        FilterChip(
                            selected = prefs.language == "en",
                            onClick = { viewModel.updatePreference(PrefKeys.LANGUAGE, "en") },
                            label = { Text(lStr(R.string.lang_en)) }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = prefs.language == "ru",
                            onClick = { viewModel.updatePreference(PrefKeys.LANGUAGE, "ru") },
                            label = { Text(lStr(R.string.lang_ru)) }
                        )
                        FilterChip(
                            selected = prefs.language == "hy",
                            onClick = { viewModel.updatePreference(PrefKeys.LANGUAGE, "hy") },
                            label = { Text(lStr(R.string.lang_hy)) }
                        )
                    }
                }
            }

            // Reminders & Push Notification Preferences
            item {
                SettingsCard(title = lStr(R.string.settings_reminders)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(lStr(R.string.settings_notifications_permission), style = MaterialTheme.typography.bodyMedium)
                            if (hasNotifPermission) {
                                Text(
                                    lStr(R.string.permission_granted),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = SuccessGreen
                                )
                            } else {
                                OutlinedButton(
                                    onClick = { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                ) {
                                    Text(lStr(R.string.permission_request))
                                }
                            }
                        }
                    }

                    ToggleSettingRow(
                        label = lStr(R.string.settings_daily_summary),
                        checked = prefs.daily,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.DAILY, it.toString()) }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = summaryTimeInput,
                            onValueChange = { summaryTimeInput = it },
                            label = { Text(lStr(R.string.settings_summary_time)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val min = BusinessRules.parseMinuteOfDay(summaryTimeInput)
                                if (min != null) {
                                    viewModel.updatePreference(PrefKeys.SUMMARY_MINUTE, min.toString())
                                    viewModel.notifyMessage(summarySavedMsg)
                                } else {
                                    viewModel.notifyMessage(summaryInvalidMsg)
                                }
                            }
                        ) { Text(lStr(R.string.save)) }
                    }

                    ToggleSettingRow(
                        label = lStr(R.string.settings_job_reminders),
                        checked = prefs.jobReminders,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.JOB_REMINDERS, it.toString()) }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = advanceInput,
                            onValueChange = { advanceInput = it },
                            label = { Text(lStr(R.string.settings_advance_minutes)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val v = advanceInput.trim().toIntOrNull()
                                if (v != null && v in 1..10080) {
                                    viewModel.updatePreference(PrefKeys.ADVANCE, v.toString())
                                    viewModel.notifyMessage(advanceSavedMsg)
                                } else {
                                    viewModel.notifyMessage(advanceInvalidMsg)
                                }
                            }
                        ) { Text(lStr(R.string.save)) }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // 8 Granular Cloud Notification Switches
                    ToggleSettingRow(
                        label = lStr(R.string.notif_new_assignment),
                        checked = prefs.notifNewAssignment,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.NOTIF_NEW_ASSIGNMENT, it.toString()) }
                    )
                    ToggleSettingRow(
                        label = lStr(R.string.notif_upcoming),
                        checked = prefs.notifUpcoming,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.NOTIF_UPCOMING, it.toString()) }
                    )
                    ToggleSettingRow(
                        label = lStr(R.string.notif_changed_assignment),
                        checked = prefs.notifChangedAssignment,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.NOTIF_CHANGED_ASSIGNMENT, it.toString()) }
                    )
                    ToggleSettingRow(
                        label = lStr(R.string.notif_completed),
                        checked = prefs.notifCompleted,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.NOTIF_COMPLETED, it.toString()) }
                    )
                    ToggleSettingRow(
                        label = lStr(R.string.notif_signed_invoice),
                        checked = prefs.notifSignedInvoice,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.NOTIF_SIGNED_INVOICE, it.toString()) }
                    )
                    ToggleSettingRow(
                        label = lStr(R.string.notif_missing_signature),
                        checked = prefs.notifMissingSignature,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.NOTIF_MISSING_SIGNATURE, it.toString()) }
                    )
                    ToggleSettingRow(
                        label = lStr(R.string.notif_failed_upload),
                        checked = prefs.notifFailedUpload,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.NOTIF_FAILED_UPLOAD, it.toString()) }
                    )
                    ToggleSettingRow(
                        label = lStr(R.string.notif_employee_invite),
                        checked = prefs.notifEmployeeInvite,
                        onCheckedChange = { viewModel.updatePreference(PrefKeys.NOTIF_EMPLOYEE_INVITE, it.toString()) }
                    )
                }
            }

            // Backup & Import (Encrypted v2 Archive + Legacy v1 JSON)
            item {
                SettingsCard(title = lStr(R.string.settings_backup)) {
                    Text(
                        text = lStr(R.string.encrypted_backup_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { showExportPassphraseDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(lStr(R.string.export_encrypted_backup))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.import_backup_btn))
                        }
                        OutlinedButton(
                            onClick = { exportLauncher.launch("delifar-backup-${BusinessRules.todayIso(zoneId)}.json") },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 44.dp)
                        ) {
                            Text(lStr(R.string.legacy_json_btn))
                        }
                    }
                }
            }

            // Map, Geocoding & Routing Services (Free & Configurable)
            item {
                SettingsCard(title = lStr(R.string.settings_mapping_services)) {
                    Text(
                        text = lStr(R.string.mapping_fleet_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = mapStyleUrlInput == AppPreferences.DEFAULT_MAP_STYLE_URL,
                            onClick = { mapStyleUrlInput = AppPreferences.DEFAULT_MAP_STYLE_URL },
                            label = { Text(lStr(R.string.style_openfreemap_liberty)) }
                        )
                        FilterChip(
                            selected = mapStyleUrlInput == AppPreferences.OPENFREEMAP_POSITRON_STYLE_URL,
                            onClick = { mapStyleUrlInput = AppPreferences.OPENFREEMAP_POSITRON_STYLE_URL },
                            label = { Text(lStr(R.string.style_positron_light)) }
                        )
                    }

                    OutlinedTextField(
                        value = mapStyleUrlInput,
                        onValueChange = { mapStyleUrlInput = it },
                        label = { Text(lStr(R.string.settings_map_style_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    ToggleSettingRow(
                        label = lStr(R.string.settings_allow_osm_fallback),
                        checked = allowOsmFallback,
                        onCheckedChange = { allowOsmFallback = it }
                    )

                    OutlinedTextField(
                        value = geocodingUrlInput,
                        onValueChange = { geocodingUrlInput = it },
                        label = { Text(lStr(R.string.settings_geocoding_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = routingUrlInput,
                        onValueChange = { routingUrlInput = it },
                        label = { Text(lStr(R.string.settings_routing_url)) },
                        placeholder = { Text(lStr(R.string.routing_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val res = viewModel.updateMappingSettings(
                                        geocodingUrl = geocodingUrlInput,
                                        routingUrl = routingUrlInput,
                                        mapStyleUrl = mapStyleUrlInput,
                                        allowOsmFallback = allowOsmFallback
                                    )
                                    res.onFailure { viewModel.notifyMessage(it.message ?: "Invalid endpoint URL.") }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(lStr(R.string.save))
                        }
                        OutlinedButton(
                            onClick = {
                                geocodingUrlInput = AppPreferences.DEFAULT_GEOCODING_URL
                                routingUrlInput = AppPreferences.DEFAULT_ROUTING_URL
                                mapStyleUrlInput = AppPreferences.DEFAULT_MAP_STYLE_URL
                                allowOsmFallback = true
                                scope.launch {
                                    viewModel.updateMappingSettings(
                                        geocodingUrl = AppPreferences.DEFAULT_GEOCODING_URL,
                                        routingUrl = AppPreferences.DEFAULT_ROUTING_URL,
                                        mapStyleUrl = AppPreferences.DEFAULT_MAP_STYLE_URL,
                                        allowOsmFallback = true
                                    )
                                }
                            }
                        ) {
                            Text(lStr(R.string.reset_defaults))
                        }
                    }
                }
            }

            // About DeliFar 2.0 & Open Mapping Attribution
            item {
                SettingsCard(title = lStr(R.string.settings_about)) {
                    Text(
                        text = lStr(R.string.about_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val uri = Uri.parse("https://openfreemap.org")
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                        ) {
                            Text("OpenFreeMap")
                        }
                        TextButton(
                            onClick = {
                                val uri = Uri.parse("https://www.openstreetmap.org/copyright")
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                        ) {
                            Text("OpenStreetMap ©")
                        }
                        TextButton(
                            onClick = {
                                val uri = Uri.parse("https://maplibre.org")
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                        ) {
                            Text("MapLibre")
                        }
                    }
                }
            }
        }
    }

    // Owner-Confirmed Local-to-Cloud Migration Dialog
    pendingMigration?.let { summary ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissMigrationPrompt() },
            title = { Text(lStr(R.string.migrate_confirm_title)) },
            text = {
                Text(
                    lStr(
                        R.string.migrate_confirm_body,
                        summary.storesCount,
                        summary.jobsCount,
                        summary.invoicesCount,
                        company?.name ?: ""
                    )
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.confirmMigrateLocalData() }) {
                    Text(lStr(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissMigrationPrompt() }) {
                    Text(lStr(R.string.cancel))
                }
            }
        )
    }

    // Validated Backup Import Preview Dialog
    importPreview?.let { preview ->
        var replaceConflicts by remember { mutableStateOf(false) }
        var restorePrefs by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissImportPreview() },
            title = { Text(lStr(R.string.import_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Stores: ${preview.newStores} new, ${preview.identicalStores} identical, ${preview.conflictingStores} conflicting",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Jobs: ${preview.newJobs} new, ${preview.identicalJobs} identical, ${preview.conflictingJobs} conflicting",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (preview.conflictingStores > 0 || preview.conflictingJobs > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = replaceConflicts,
                                onCheckedChange = { replaceConflicts = it }
                            )
                            Text(lStr(R.string.import_replace_conflicts), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (preview.preferenceCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = restorePrefs,
                                onCheckedChange = { restorePrefs = it }
                            )
                            Text(lStr(R.string.import_restore_prefs), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.applyImportMerge(replaceConflicts, restorePrefs) }) {
                    Text(lStr(R.string.import_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissImportPreview() }) {
                    Text(lStr(R.string.cancel))
                }
            }
        )
    }

    // Encrypted Backup Export Passphrase Dialog
    if (showExportPassphraseDialog) {
        var pass1 by remember { mutableStateOf("") }
        var pass2 by remember { mutableStateOf("") }
        var passErr by remember { mutableStateOf<String?>(null) }
        val errTooShort = lStr(R.string.passphrase_too_short, EncryptedArchiveBackupManager.MIN_PASSPHRASE_LENGTH)
        val errMismatch = lStr(R.string.passphrase_mismatch)
        AlertDialog(
            onDismissRequest = { showExportPassphraseDialog = false },
            title = { Text(lStr(R.string.encrypt_backup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        lStr(R.string.encrypt_backup_body, EncryptedArchiveBackupManager.MIN_PASSPHRASE_LENGTH),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = pass1,
                        onValueChange = { pass1 = it; passErr = null },
                        label = { Text(lStr(R.string.passphrase_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pass2,
                        onValueChange = { pass2 = it; passErr = null },
                        label = { Text(lStr(R.string.confirm_passphrase_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    passErr?.let { err ->
                        Text(err, color = TerracottaError, style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            pass1.length < EncryptedArchiveBackupManager.MIN_PASSPHRASE_LENGTH ->
                                passErr = errTooShort
                            pass1 != pass2 ->
                                passErr = errMismatch
                            else -> {
                                pendingExportPassphrase = pass1
                                showExportPassphraseDialog = false
                                exportEncryptedLauncher.launch("delifar-full-backup-${BusinessRules.todayIso(zoneId)}.delifarbak")
                            }
                        }
                    }
                ) {
                    Text(lStr(R.string.continue_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportPassphraseDialog = false }) {
                    Text(lStr(R.string.cancel))
                }
            }
        )
    }

    // Encrypted Backup Unlock Dialog
    if (pendingEncryptedFile != null && archiveImportPreview == null) {
        var unlockPass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.dismissEncryptedImport() },
            title = { Text(lStr(R.string.unlock_backup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        lStr(R.string.unlock_backup_body),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = unlockPass,
                        onValueChange = { unlockPass = it },
                        label = { Text(lStr(R.string.passphrase_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = unlockPass.length >= EncryptedArchiveBackupManager.MIN_PASSPHRASE_LENGTH,
                    onClick = { viewModel.unlockEncryptedArchive(unlockPass) }
                ) {
                    Text(lStr(R.string.verify_and_inspect))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissEncryptedImport() }) {
                    Text(lStr(R.string.cancel))
                }
            }
        )
    }

    // Verified Encrypted Archive Restore Preview Dialog
    archiveImportPreview?.let { preview ->
        var replaceConflicts by remember { mutableStateOf(false) }
        var restorePrefs by remember { mutableStateOf(false) }
        var confirmCrossCompany by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewModel.dismissEncryptedImport() },
            title = { Text(lStr(R.string.restore_backup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        lStr(R.string.restore_backup_company, preview.backupCompanyName, preview.dbSchemaVersion),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        lStr(R.string.restore_backup_stores, preview.newStores, preview.identicalStores, preview.conflictingStores),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        lStr(R.string.restore_backup_jobs, preview.newJobs, preview.identicalJobs, preview.conflictingJobs),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        lStr(R.string.restore_backup_invoices, preview.newInvoices, preview.identicalInvoices, preview.conflictingInvoices, preview.attachmentCount),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (preview.isCrossCompany) {
                        Text(
                            lStr(R.string.restore_cross_company_warn, preview.backupCompanyName),
                            color = TerracottaError,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = confirmCrossCompany,
                                onCheckedChange = { confirmCrossCompany = it }
                            )
                            Text(lStr(R.string.restore_allow_cross_company), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (preview.conflictingStores > 0 || preview.conflictingJobs > 0 || preview.conflictingInvoices > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = replaceConflicts,
                                onCheckedChange = { replaceConflicts = it }
                            )
                            Text(lStr(R.string.import_replace_conflicts), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (preview.preferenceCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = restorePrefs,
                                onCheckedChange = { restorePrefs = it }
                            )
                            Text(lStr(R.string.import_restore_prefs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !preview.isCrossCompany || confirmCrossCompany,
                    onClick = {
                        viewModel.applyEncryptedArchiveRestore(
                            replaceConflicts = replaceConflicts,
                            restorePreferences = restorePrefs,
                            allowCrossCompany = confirmCrossCompany
                        )
                    }
                ) {
                    Text(lStr(R.string.import_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissEncryptedImport() }) {
                    Text(lStr(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ToggleSettingRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
