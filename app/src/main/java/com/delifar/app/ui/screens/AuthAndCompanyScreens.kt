package com.delifar.app.ui.screens

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
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.cloud.FirebaseServices
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.SearchArea
import com.delifar.app.domain.BusinessRules
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.RoleBadge
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.roleLabel
import com.delifar.app.ui.components.syncStatusColors
import com.delifar.app.ui.components.syncStatusLabel
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.launch

private enum class AuthTab { ACCOUNT, REGISTER_COMPANY, JOIN_INVITE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountAndCompanyScreen(
    viewModel: DeliFarViewModel,
    onBack: () -> Unit,
    onOpenTeam: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsState()
    val company by viewModel.activeCompany.collectAsState()
    val pendingMigration by viewModel.pendingMigration.collectAsState()
    val offlineCompanies by viewModel.offlineOwnedCompanies.collectAsState()
    val userCompanies by viewModel.userCompanies.collectAsState()
    val resolvedSyncStatus by viewModel.resolvedSyncStatus.collectAsState()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsState()
    val failedSyncCount by viewModel.failedSyncCount.collectAsState()
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(if (company == null) AuthTab.REGISTER_COMPANY else AuthTab.ACCOUNT) }

    // Account form
    var isCreateAccount by remember { mutableStateOf(false) }
    var fullName by remember { mutableStateOf(prefs.activeUserName) }
    var email by remember { mutableStateOf(prefs.activeUserEmail) }
    var password by remember { mutableStateOf("") }

    // Register company form
    var companyName by remember { mutableStateOf(company?.name.orEmpty()) }
    var timezone by remember { mutableStateOf(prefs.companyTimezone) }
    var tzDropdown by remember { mutableStateOf(false) }
    var defaultArea by remember { mutableStateOf(prefs.area) }

    // Join invite form
    var inviteCode by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.account_and_company), style = MaterialTheme.typography.titleLarge) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Current Session Summary
            item {
                val (syncBg, syncFg) = syncStatusColors(resolvedSyncStatus)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = prefs.activeUserName.ifBlank { "Offline Operator" },
                                style = MaterialTheme.typography.titleMedium
                            )
                            RoleBadge(role = prefs.activeUserRole)
                        }
                        if (prefs.activeUserEmail.isNotBlank()) {
                            Text(prefs.activeUserEmail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            text = if (company != null) "Company: ${company!!.name} (${company!!.timezone})"
                            else "No active company selected",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        StatusTag(
                            text = syncStatusLabel(resolvedSyncStatus, pendingSyncCount, failedSyncCount),
                            bg = syncBg,
                            fg = syncFg
                        )
                        Text(
                            text = when {
                                !FirebaseServices.isConfigured ->
                                    "Offline Mode: Firebase config (google-services.json) is not bundled in this build. All data is stored safely on this device."
                                !FirebaseServices.isAuthenticated ->
                                    "Firebase is configured (${FirebaseServices.appCheckProviderName}), but you are not signed in. Sign in below to sync with the cloud."
                                else ->
                                    "Signed in as ${FirebaseServices.currentAuthEmail ?: prefs.activeUserEmail} • App Check: ${FirebaseServices.appCheckProviderName}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (company == null) {
                            Text(
                                text = "Working Role (Offline / Setup)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                EmployeeRole.ALL.forEach { role ->
                                    FilterChip(
                                        selected = prefs.activeUserRole == role,
                                        onClick = { viewModel.selectOfflineRole(role) },
                                        label = { Text(roleLabel(role)) }
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (company != null) {
                                Button(onClick = onOpenTeam) {
                                    Text(lStr(R.string.team_management))
                                }
                            }
                            if (prefs.activeUserUid.isNotBlank() || FirebaseServices.isAuthenticated) {
                                OutlinedButton(onClick = { viewModel.signOut() }) {
                                    Text(lStr(R.string.sign_out))
                                }
                            }
                        }
                    }
                }
            }

            // Explicit Offline-to-Cloud Company Ownership Transfer Prompt (never silent)
            if (FirebaseServices.isAuthenticated && offlineCompanies.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberContainer),
                        border = BorderStroke(1.dp, AmberAccent.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = lStr(R.string.claim_offline_company_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "You have locally created company data on this device. Confirm below to assign ownership to your signed-in account (${FirebaseServices.currentAuthEmail ?: prefs.activeUserEmail}) without creating duplicate records.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            offlineCompanies.forEach { localComp ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(localComp.name, style = MaterialTheme.typography.titleSmall)
                                        Text(localComp.timezone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Button(
                                        enabled = !busy,
                                        onClick = {
                                            busy = true
                                            errorMsg = null
                                            scope.launch {
                                                val res = viewModel.claimLocalCompany(localComp.id)
                                                busy = false
                                                res.onFailure { errorMsg = it.message ?: "Could not claim offline company." }
                                            }
                                        }
                                    ) {
                                        Text(lStr(R.string.claim_offline_company_action))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Company Switcher when user belongs to multiple companies
            if (userCompanies.size > 1) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(lStr(R.string.switch_company_title), style = MaterialTheme.typography.titleMedium)
                            userCompanies.forEach { comp ->
                                val isCurrent = comp.id == company?.id
                                OutlinedButton(
                                    onClick = {
                                        if (!isCurrent) {
                                            scope.launch { viewModel.switchCompany(comp.id) }
                                        }
                                    },
                                    enabled = !isCurrent,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isCurrent) "${comp.name} (Active)" else comp.name)
                                }
                            }
                        }
                    }
                }
            }

            // Tab selector
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = activeTab == AuthTab.ACCOUNT,
                        onClick = { activeTab = AuthTab.ACCOUNT; errorMsg = null },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text(lStr(R.string.sign_in)) }
                    )
                    FilterChip(
                        selected = activeTab == AuthTab.REGISTER_COMPANY,
                        onClick = { activeTab = AuthTab.REGISTER_COMPANY; errorMsg = null },
                        leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) },
                        label = { Text(lStr(R.string.register_company)) }
                    )
                    FilterChip(
                        selected = activeTab == AuthTab.JOIN_INVITE,
                        onClick = { activeTab = AuthTab.JOIN_INVITE; errorMsg = null },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        label = { Text(lStr(R.string.join_with_invite)) }
                    )
                }
            }

            errorMsg?.let { err ->
                item {
                    Surface(
                        color = TerracottaContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(err, color = TerracottaError, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            when (activeTab) {
                AuthTab.ACCOUNT -> {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = !isCreateAccount,
                                        onClick = { isCreateAccount = false },
                                        label = { Text(lStr(R.string.sign_in)) }
                                    )
                                    FilterChip(
                                        selected = isCreateAccount,
                                        onClick = { isCreateAccount = true },
                                        label = { Text(lStr(R.string.create_account)) }
                                    )
                                }
                                if (isCreateAccount) {
                                    OutlinedTextField(
                                        value = fullName,
                                        onValueChange = { fullName = it },
                                        label = { Text(lStr(R.string.full_name_label)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                OutlinedTextField(
                                    value = email,
                                    onValueChange = { email = it },
                                    label = { Text(lStr(R.string.email_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text(lStr(R.string.password_label)) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        if (busy) return@Button
                                        busy = true
                                        errorMsg = null
                                        scope.launch {
                                            val res = if (isCreateAccount) {
                                                viewModel.registerAccountWithEmail(fullName, email, password)
                                            } else {
                                                viewModel.signInWithEmail(email, password)
                                            }
                                            busy = false
                                            res.fold(
                                                onSuccess = { viewModel.notifyMessage("Signed in successfully.") },
                                                onFailure = { errorMsg = it.message ?: "Authentication failed." }
                                            )
                                        }
                                    },
                                    enabled = !busy && email.contains("@") && password.length >= 6,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isCreateAccount) lStr(R.string.create_account) else lStr(R.string.sign_in))
                                }
                            }
                        }
                    }
                }

                AuthTab.REGISTER_COMPANY -> {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = companyName,
                                    onValueChange = { companyName = it },
                                    label = { Text(lStr(R.string.company_name_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = fullName,
                                    onValueChange = { fullName = it },
                                    label = { Text(lStr(R.string.full_name_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = email,
                                    onValueChange = { email = it },
                                    label = { Text(lStr(R.string.email_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                ExposedDropdownMenuBox(
                                    expanded = tzDropdown,
                                    onExpandedChange = { tzDropdown = it }
                                ) {
                                    OutlinedTextField(
                                        value = timezone,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(lStr(R.string.company_timezone_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tzDropdown) },
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = tzDropdown,
                                        onDismissRequest = { tzDropdown = false }
                                    ) {
                                        BusinessRules.SUPPORTED_TIMEZONES.forEach { tz ->
                                            DropdownMenuItem(
                                                text = { Text(tz) },
                                                onClick = { timezone = tz; tzDropdown = false }
                                            )
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = defaultArea == SearchArea.LA,
                                        onClick = { defaultArea = SearchArea.LA },
                                        label = { Text(lStr(R.string.area_la)) }
                                    )
                                    FilterChip(
                                        selected = defaultArea == SearchArea.SAC,
                                        onClick = { defaultArea = SearchArea.SAC },
                                        label = { Text(lStr(R.string.area_sac)) }
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (busy) return@Button
                                        busy = true
                                        errorMsg = null
                                        scope.launch {
                                            val res = viewModel.registerCompany(
                                                companyName = companyName,
                                                ownerName = fullName,
                                                ownerEmail = email,
                                                timezone = timezone,
                                                defaultArea = defaultArea
                                            )
                                            busy = false
                                            res.onFailure { errorMsg = it.message ?: "Could not register company." }
                                        }
                                    },
                                    enabled = !busy && companyName.trim().length >= 2,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(lStr(R.string.register_company))
                                }
                            }
                        }
                    }
                }

                AuthTab.JOIN_INVITE -> {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = inviteCode,
                                    onValueChange = { inviteCode = it.uppercase() },
                                    label = { Text(lStr(R.string.invite_code_label)) },
                                    placeholder = { Text("8-character code") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = fullName,
                                    onValueChange = { fullName = it },
                                    label = { Text(lStr(R.string.full_name_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = email,
                                    onValueChange = { email = it },
                                    label = { Text(lStr(R.string.email_label)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Button(
                                    onClick = {
                                        if (busy) return@Button
                                        busy = true
                                        errorMsg = null
                                        scope.launch {
                                            val res = viewModel.joinCompanyWithInvite(inviteCode, fullName, email)
                                            busy = false
                                            res.fold(
                                                onSuccess = { onOpenTeam() },
                                                onFailure = { errorMsg = it.message ?: "Invalid invite code." }
                                            )
                                        }
                                    },
                                    enabled = !busy && inviteCode.trim().length >= 6,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(lStr(R.string.join_with_invite))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Owner Confirmation Dialog for Local-to-Cloud Data Migration
    val migration = pendingMigration
    if (migration != null && company != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissMigrationPrompt() },
            title = { Text(lStr(R.string.migrate_confirm_title)) },
            text = {
                Text(
                    lStr(
                        R.string.migrate_confirm_body,
                        migration.storesCount,
                        migration.jobsCount,
                        migration.invoicesCount,
                        company!!.name
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
}
