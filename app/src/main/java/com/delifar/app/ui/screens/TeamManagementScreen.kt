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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.EmployeeRole
import com.delifar.app.data.InviteStatus
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.RoleBadge
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.roleLabel
import com.delifar.app.ui.theme.SuccessContainer
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.launch

private enum class TeamTab { EMPLOYEES, INVITES, ACTIVITY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamManagementScreen(
    viewModel: DeliFarViewModel,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsState()
    val company by viewModel.activeCompany.collectAsState()
    val employees by viewModel.employees.collectAsState()
    val invitations by viewModel.invitations.collectAsState()
    val auditLogs by viewModel.auditLogs.collectAsState()
    val scope = rememberCoroutineScope()

    val isOwner = RolePermissions.canManageEmployees(prefs.activeUserRole)
    val zoneId = remember(prefs.companyTimezone) { BusinessRules.resolveZone(prefs.companyTimezone) }

    var selectedTab by remember { mutableStateOf(TeamTab.EMPLOYEES) }
    var showInviteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.team_management), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = lStr(R.string.back))
                    }
                },
                actions = {
                    if (isOwner && company != null) {
                        IconButton(onClick = { showInviteDialog = true }) {
                            Icon(Icons.Default.PersonAdd, contentDescription = lStr(R.string.invite_employee))
                        }
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
            if (company == null) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("No company linked yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Register your company as Owner or join an existing team with an invite code to unlock multi-user real-time sync.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onOpenAccount) {
                                Text(lStr(R.string.account_and_company))
                            }
                        }
                    }
                }
                return@LazyColumn
            }

            // Company & Storage Quota Header Card
            item {
                val c = company!!
                val usedMb = c.storageUsedBytes / (1024.0 * 1024.0)
                val quotaMb = (c.storageQuotaBytes / (1024.0 * 1024.0)).coerceAtLeast(1.0)
                val progress = (usedMb / quotaMb).toFloat().coerceIn(0f, 1f)
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(c.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Timezone: ${c.timezone} • ${employees.count { it.active }} active member(s)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RoleBadge(role = prefs.activeUserRole)
                        }
                        Text(
                            text = "Document Storage: %.1f MB / %.0f MB".format(usedMb, quotaMb),
                            style = MaterialTheme.typography.labelMedium
                        )
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isOwner) {
                                Button(onClick = { showInviteDialog = true }) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(lStr(R.string.invite_employee))
                                }
                                OutlinedButton(onClick = { viewModel.checkUnmigratedLocalData(); onOpenAccount() }) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(lStr(R.string.migrate_local_data))
                                }
                            }
                        }
                    }
                }
            }

            // Tab selector
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedTab == TeamTab.EMPLOYEES,
                        onClick = { selectedTab = TeamTab.EMPLOYEES },
                        label = { Text("${lStr(R.string.employees_tab)} (${employees.size})") }
                    )
                    FilterChip(
                        selected = selectedTab == TeamTab.INVITES,
                        onClick = { selectedTab = TeamTab.INVITES },
                        label = { Text("${lStr(R.string.invitations_tab)} (${invitations.size})") }
                    )
                    FilterChip(
                        selected = selectedTab == TeamTab.ACTIVITY,
                        onClick = { selectedTab = TeamTab.ACTIVITY },
                        label = { Text(lStr(R.string.activity_tab)) }
                    )
                }
            }

            when (selectedTab) {
                TeamTab.EMPLOYEES -> {
                    items(employees, key = { it.uid }) { emp ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(emp.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(emp.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        RoleBadge(role = emp.role)
                                        StatusTag(
                                            text = if (emp.active) lStr(R.string.active_status) else lStr(R.string.deactivated_status),
                                            bg = if (emp.active) SuccessContainer else TerracottaContainer,
                                            fg = if (emp.active) SuccessGreen else TerracottaError
                                        )
                                    }
                                }
                                if (isOwner && emp.uid != prefs.activeUserUid) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf(EmployeeRole.MANAGER, EmployeeRole.DRIVER, EmployeeRole.ACCOUNTANT).forEach { r ->
                                                FilterChip(
                                                    selected = emp.role == r,
                                                    onClick = { viewModel.updateEmployeeRole(emp.uid, r) },
                                                    label = { Text(roleLabel(r)) }
                                                )
                                            }
                                        }
                                        TextButton(onClick = { viewModel.setEmployeeActive(emp.uid, !emp.active) }) {
                                            Text(
                                                if (emp.active) lStr(R.string.deactivate) else lStr(R.string.activate),
                                                color = if (emp.active) TerracottaError else SuccessGreen
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                TeamTab.INVITES -> {
                    if (invitations.isEmpty()) {
                        item {
                            Text("No invitations sent yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(invitations, key = { it.id }) { inv ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(inv.email, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Code: ${inv.inviteCode} • ${roleLabel(inv.role)} • ${inv.status}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    if (isOwner && inv.status == InviteStatus.PENDING) {
                                        TextButton(onClick = { viewModel.revokeInvitation(inv.id) }) {
                                            Text(lStr(R.string.revoke), color = TerracottaError)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                TeamTab.ACTIVITY -> {
                    if (auditLogs.isEmpty()) {
                        item {
                            Text("No activity logged yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(auditLogs, key = { it.id }) { log ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(log.action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                        Text(
                                            BusinessRules.formatTimestamp(log.createdAt, zoneId),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(log.summary, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "By ${log.actorName} (${log.actorRole})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInviteDialog) {
        var inviteEmail by remember { mutableStateOf("") }
        var inviteRole by remember { mutableStateOf(EmployeeRole.DRIVER) }
        var err by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text(lStr(R.string.invite_employee)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inviteEmail,
                        onValueChange = { inviteEmail = it; err = null },
                        label = { Text(lStr(R.string.email_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(EmployeeRole.MANAGER, EmployeeRole.DRIVER, EmployeeRole.ACCOUNTANT).forEach { r ->
                            FilterChip(
                                selected = inviteRole == r,
                                onClick = { inviteRole = r },
                                label = { Text(roleLabel(r)) }
                            )
                        }
                    }
                    err?.let { Text(it, color = TerracottaError, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val res = viewModel.inviteEmployee(inviteEmail, inviteRole)
                            res.fold(
                                onSuccess = { showInviteDialog = false },
                                onFailure = { err = it.message ?: "Failed to create invite." }
                            )
                        }
                    }
                ) { Text(lStr(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showInviteDialog = false }) { Text(lStr(R.string.cancel)) }
            }
        )
    }
}
