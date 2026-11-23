package com.delifar.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.delifar.app.R
import com.delifar.app.data.PrefKeys
import com.delifar.app.data.SearchArea
import com.delifar.app.domain.BusinessRules
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DeliFarViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsState()
    val importPreview by viewModel.importPreview.collectAsState()

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

    var summaryTimeInput by remember(prefs.summaryMinute) {
        mutableStateOf(BusinessRules.formatMinuteOfDay(prefs.summaryMinute))
    }
    var advanceInput by remember(prefs.advance) {
        mutableStateOf(prefs.advance.toString())
    }
    var searchUrlInput by remember(prefs.searchUrl) { mutableStateOf(prefs.searchUrl) }
    var tileUrlInput by remember(prefs.tileUrl) { mutableStateOf(prefs.tileUrl) }

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

            // Default Area
            item {
                SettingsCard(title = lStr(R.string.settings_default_area)) {
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

            // Reminders
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(lStr(R.string.settings_daily_summary), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = prefs.daily,
                            onCheckedChange = { viewModel.updatePreference(PrefKeys.DAILY, it.toString()) }
                        )
                    }

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
                                    viewModel.notifyMessage("Summary time saved.")
                                } else {
                                    viewModel.notifyMessage("Use 24-hour HH:mm format.")
                                }
                            }
                        ) { Text(lStr(R.string.save)) }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(lStr(R.string.settings_job_reminders), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = prefs.jobReminders,
                            onCheckedChange = { viewModel.updatePreference(PrefKeys.JOB_REMINDERS, it.toString()) }
                        )
                    }

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
                                    viewModel.notifyMessage("Advance minutes saved.")
                                } else {
                                    viewModel.notifyMessage("Enter 1–10080 minutes.")
                                }
                            }
                        ) { Text(lStr(R.string.save)) }
                    }
                }
            }

            // Map & Search Services
            item {
                SettingsCard(title = lStr(R.string.settings_map_services)) {
                    OutlinedTextField(
                        value = searchUrlInput,
                        onValueChange = { searchUrlInput = it },
                        label = { Text(lStr(R.string.settings_search_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tileUrlInput,
                        onValueChange = { tileUrlInput = it },
                        label = { Text(lStr(R.string.settings_tile_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.updateServiceUrls(searchUrlInput, tileUrlInput) },
                            modifier = Modifier.weight(1f)
                        ) { Text(lStr(R.string.save)) }
                        OutlinedButton(
                            onClick = { viewModel.resetServiceUrls() },
                            modifier = Modifier.weight(1f)
                        ) { Text(lStr(R.string.settings_reset_defaults)) }
                    }
                }
            }

            // Backup & Import
            item {
                SettingsCard(title = lStr(R.string.settings_backup)) {
                    Text(
                        text = lStr(R.string.backup_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { exportLauncher.launch("delifar-backup-${BusinessRules.todayIso()}.json") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.export_json))
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(lStr(R.string.import_json))
                        }
                    }
                }
            }

            // About & Attribution
            item {
                SettingsCard(title = lStr(R.string.settings_about)) {
                    Text(
                        text = lStr(R.string.about_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
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
