package com.delifar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.data.AppPreferences
import com.delifar.app.data.HoursMode
import com.delifar.app.data.JobListRow
import com.delifar.app.data.SearchArea
import com.delifar.app.data.StoreHoursEntity
import com.delifar.app.data.StoreWithHours
import com.delifar.app.domain.BusinessRules
import com.delifar.app.domain.RolePermissions
import com.delifar.app.network.GeoPoint
import com.delifar.app.network.GeoValidation
import com.delifar.app.network.GeocodeCandidate
import com.delifar.app.network.GeocodeSource
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.JobCard
import com.delifar.app.ui.components.ReceivingWindowStatusTag
import com.delifar.app.ui.components.StatusTag
import com.delifar.app.ui.components.StorePinPickerMap
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.components.launchDialer
import com.delifar.app.ui.components.launchDirections
import com.delifar.app.ui.components.todayHoursSummary
import com.delifar.app.ui.components.weekdayShortLabel
import com.delifar.app.ui.theme.AmberAccent
import com.delifar.app.ui.theme.AmberContainer
import com.delifar.app.ui.theme.SuccessContainer
import com.delifar.app.ui.theme.SuccessGreen
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StoreEditorScreen(
    existingStoreId: String?,
    viewModel: DeliFarViewModel,
    onBack: () -> Unit,
    onSaved: (String) -> Unit
) {
    val prefs by viewModel.preferences.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val scope = rememberCoroutineScope()

    var area by remember { mutableStateOf(prefs.area) }
    var searchQuery by remember { mutableStateOf("") }

    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    // CRITICAL: New stores start with NULL coordinates (never silent default Los Angeles coordinates!)
    var pin by remember { mutableStateOf<GeoPoint?>(null) }
    var locationConfirmed by remember { mutableStateOf(false) }
    var geocodeSource by remember { mutableStateOf(GeocodeSource.MANUAL_PIN) }
    var providerId by remember { mutableStateOf<String?>(null) }

    var locatingAddress by remember { mutableStateOf(false) }
    var locateCandidates by remember { mutableStateOf<List<GeocodeCandidate>>(emptyList()) }
    var locateError by remember { mutableStateOf<String?>(null) }
    var cameraFocusNonce by remember { mutableIntStateOf(0) }

    var errorMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val dayModes = remember { mutableStateMapOf<Int, String>().apply { (1..7).forEach { this[it] = HoursMode.UNKNOWN } } }
    val dayWindows = remember { mutableStateMapOf<Int, String>().apply { (1..7).forEach { this[it] = "" } } }

    LaunchedEffect(existingStoreId) {
        if (existingStoreId != null) {
            val existing = viewModel.repository.getStoreWithHours(existingStoreId)
            if (existing != null) {
                name = existing.store.name
                address = existing.store.address
                phone = existing.store.phone
                if (GeoValidation.isValidCoordinate(existing.store.lat, existing.store.lon)) {
                    pin = GeoPoint(existing.store.lat, existing.store.lon)
                    locationConfirmed = existing.store.locationConfirmed
                    cameraFocusNonce++
                }
                geocodeSource = existing.store.geocodeSource
                providerId = existing.store.providerId
                (1..7).forEach { d ->
                    val h = existing.hours.find { it.day == d }
                    dayModes[d] = h?.mode ?: HoursMode.UNKNOWN
                    dayWindows[d] = h?.windows ?: ""
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.clearSearchState() }
    }

    val viewportCenter = remember(area) {
        if (area == SearchArea.SAC) GeoPoint(38.5816, -121.4944) else GeoPoint(34.0522, -118.2437)
    }

    val duplicateProviderWarn = lStr(R.string.duplicate_store_warning)
    val duplicateStoreWarn = lStr(R.string.duplicate_manual_store_warning)
    val unconfirmedWarn = lStr(R.string.confirm_location_required)
    val locateNotFoundMsg = lStr(R.string.locate_not_found)
    val copiedWeekdaysMsg = lStr(R.string.copied_weekdays_msg)

    fun applyCandidate(candidate: GeocodeCandidate, allowFillBlankName: Boolean) {
        // NEVER overwrite a custom store name the user already typed!
        if (allowFillBlankName && name.isBlank() && candidate.displayName.isNotBlank()) {
            name = candidate.displayName
        }
        if (candidate.formattedAddress.isNotBlank()) {
            // Only replace address if user came from directory search or pasted a link/coords
            if (allowFillBlankName || address.contains("http", ignoreCase = true) || address.trim().matches(Regex("^-?\\d+\\.\\d+.*"))) {
                address = candidate.formattedAddress
            }
        }
        if (candidate.phone.isNotBlank() && phone.isBlank()) {
            phone = candidate.phone
        }
        pin = GeoPoint(candidate.lat, candidate.lon)
        geocodeSource = candidate.source
        providerId = candidate.id.takeIf { it.startsWith("osm:") }
        // Require explicit user confirmation after reviewing pin on the interactive map
        locationConfirmed = false
        locateError = null
        errorMsg = null
        cameraFocusNonce++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (existingStoreId == null) lStr(R.string.add_store) else lStr(R.string.edit_store),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
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
            // 1. Custom Store Name & Full Street Address + "Locate Address" (Primary Workflow)
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = lStr(R.string.store_details_section),
                            style = MaterialTheme.typography.titleMedium
                        )

                        // Area bias chips (used only to bias geocoder & initial map viewport — NEVER sets store coords)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = area == SearchArea.LA,
                                onClick = { area = SearchArea.LA },
                                label = { Text(lStr(R.string.area_la)) }
                            )
                            FilterChip(
                                selected = area == SearchArea.SAC,
                                onClick = { area = SearchArea.SAC },
                                label = { Text(lStr(R.string.area_sac)) }
                            )
                        }

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; errorMsg = null },
                            label = { Text(lStr(R.string.store_name)) },
                            placeholder = { Text(lStr(R.string.store_name_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = address,
                            onValueChange = {
                                address = it
                                // Editing address invalidates previous confirmation so stale pins cannot be saved
                                locationConfirmed = false
                                locateError = null
                                errorMsg = null
                            },
                            label = { Text(lStr(R.string.store_address)) },
                            placeholder = { Text(lStr(R.string.store_address_placeholder)) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                if (locatingAddress) return@Button
                                locatingAddress = true
                                locateError = null
                                locateCandidates = emptyList()
                                errorMsg = null
                                scope.launch {
                                    val outcome = viewModel.locateStoreAddress(address, area)
                                    locatingAddress = false
                                    if (outcome.candidates.isNotEmpty()) {
                                        locateCandidates = outcome.candidates
                                        // Preview first candidate immediately while letting user pick another if multiple
                                        applyCandidate(outcome.candidates.first(), allowFillBlankName = false)
                                    } else {
                                        locateError = outcome.errorMessage ?: locateNotFoundMsg
                                    }
                                }
                            },
                            enabled = !locatingAddress && address.trim().length >= 3,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (locatingAddress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(lStr(R.string.locating_address))
                            } else {
                                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(lStr(R.string.locate_address))
                            }
                        }

                        locateError?.let { err ->
                            Surface(
                                color = TerracottaContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = err,
                                    color = TerracottaError,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        // Multi-candidate selector if more than 1 location matched
                        if (locateCandidates.size > 1) {
                            Text(
                                text = lStr(R.string.select_matching_location, locateCandidates.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            locateCandidates.forEach { cand ->
                                val isCurrent = pin != null &&
                                    kotlin.math.abs(pin!!.lat - cand.lat) < 0.00001 &&
                                    kotlin.math.abs(pin!!.lon - cand.lon) < 0.00001
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            applyCandidate(cand, allowFillBlankName = false)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(cand.formattedAddress, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${sourceDisplayLabel(cand.source)} • ${"%.5f, %.5f".format(Locale.US, cand.lat, cand.lon)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(lStr(R.string.store_phone)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 2. Interactive MapLibre Pin Verification & Manual Adjustment Card
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        width = if (locationConfirmed) 1.5.dp else 1.dp,
                        color = if (locationConfirmed) SuccessGreen.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = lStr(R.string.confirm_pin_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            StatusTag(
                                text = if (locationConfirmed) lStr(R.string.location_confirmed_badge)
                                else if (pin != null) lStr(R.string.location_unconfirmed_badge)
                                else lStr(R.string.location_missing_badge),
                                bg = if (locationConfirmed) SuccessContainer else AmberContainer,
                                fg = if (locationConfirmed) SuccessGreen else AmberAccent
                            )
                        }

                        Text(
                            text = lStr(R.string.tap_map_to_adjust_pin),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        StorePinPickerMap(
                            styleUrl = prefs.mapStyleUrl.ifBlank { AppPreferences.DEFAULT_MAP_STYLE_URL },
                            pin = pin,
                            confirmed = locationConfirmed,
                            fallbackCenter = viewportCenter,
                            cameraFocusNonce = cameraFocusNonce,
                            onPinMoved = { movedPoint ->
                                pin = movedPoint
                                geocodeSource = GeocodeSource.MANUAL_PIN
                                locationConfirmed = false
                                locateError = null
                                errorMsg = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )

                        val currentPin = pin
                        if (currentPin != null && currentPin.isValid()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${"%.5f".format(Locale.US, currentPin.lat)}, ${"%.5f".format(Locale.US, currentPin.lon)}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = sourceDisplayLabel(geocodeSource),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!locationConfirmed) {
                                    Button(
                                        onClick = {
                                            locationConfirmed = true
                                            errorMsg = null
                                        }
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.confirm_location))
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = SuccessGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = lStr(R.string.location_confirmed_badge),
                                            color = SuccessGreen,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(16.dp))
                                Text(
                                    text = lStr(R.string.no_pin_yet_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 3. Optional OpenStreetMap / Device Directory Search (secondary convenience)
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = lStr(R.string.optional_directory_search),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                label = { Text(lStr(R.string.places_search_hint)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { viewModel.searchPlaces(searchQuery, area) },
                                enabled = !searchState.loading && searchQuery.trim().length >= 2
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        }

                        if (searchState.loading) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(lStr(R.string.places_searching), style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        searchState.error?.let { err ->
                            Text(err, color = TerracottaError, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (searchState.searched && !searchState.loading && searchState.results.isEmpty() && searchState.error == null) {
                            Text(
                                lStr(R.string.places_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        searchState.results.forEach { place ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        applyCandidate(place, allowFillBlankName = true)
                                        locateCandidates = emptyList()
                                        viewModel.clearSearchState()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(place.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            place.formattedAddress,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = lStr(R.string.places_attribution),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Receiving Hours
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = lStr(R.string.receiving_hours),
                        style = MaterialTheme.typography.titleMedium
                    )
                    val monMode = dayModes[1] ?: HoursMode.UNKNOWN
                    if (monMode != HoursMode.UNKNOWN) {
                        OutlinedButton(
                            onClick = {
                                val monWin = dayWindows[1] ?: ""
                                (2..5).forEach { d ->
                                    dayModes[d] = monMode
                                    dayWindows[d] = monWin
                                }
                                viewModel.notifyMessage(copiedWeekdaysMsg)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                        ) {
                            Text(lStr(R.string.copy_mon_to_weekdays), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            items((1..7).toList()) { day ->
                val mode = dayModes[day] ?: HoursMode.UNKNOWN
                val win = dayWindows[day] ?: ""
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = weekdayShortLabel(day),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = mode == HoursMode.UNKNOWN,
                                onClick = { dayModes[day] = HoursMode.UNKNOWN; dayWindows[day] = "" },
                                label = { Text(lStr(R.string.hours_unknown)) }
                            )
                            FilterChip(
                                selected = mode == HoursMode.CLOSED,
                                onClick = { dayModes[day] = HoursMode.CLOSED; dayWindows[day] = "" },
                                label = { Text(lStr(R.string.hours_closed)) }
                            )
                            FilterChip(
                                selected = mode == HoursMode.OPEN,
                                onClick = {
                                    dayModes[day] = HoursMode.OPEN
                                    if ((dayWindows[day] ?: "").isBlank()) {
                                        dayWindows[day] = "08:00-16:00"
                                    }
                                },
                                label = { Text(lStr(R.string.hours_open)) }
                            )
                        }
                        if (mode == HoursMode.OPEN) {
                            OutlinedTextField(
                                value = win,
                                onValueChange = { dayWindows[day] = it; errorMsg = null },
                                placeholder = { Text(lStr(R.string.hours_windows_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            errorMsg?.let { err ->
                item {
                    Surface(
                        color = TerracottaContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = err,
                            color = TerracottaError,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        if (saving) return@Button
                        val currentPin = pin
                        if (currentPin == null || !currentPin.isValid() || !locationConfirmed) {
                            errorMsg = unconfirmedWarn
                            return@Button
                        }
                        saving = true
                        errorMsg = null
                        val hours = (1..7).map { d ->
                            StoreHoursEntity(
                                storeId = existingStoreId ?: "",
                                day = d,
                                mode = dayModes[d] ?: HoursMode.UNKNOWN,
                                windows = dayWindows[d] ?: ""
                            )
                        }
                        scope.launch {
                            val res = viewModel.saveStore(
                                existingId = existingStoreId,
                                name = name,
                                address = address,
                                lat = currentPin.lat,
                                lon = currentPin.lon,
                                providerId = providerId,
                                phone = phone,
                                hours = hours,
                                locationConfirmed = locationConfirmed,
                                geocodeSource = geocodeSource
                            )
                            saving = false
                            res.fold(
                                onSuccess = { onSaved(it.id) },
                                onFailure = { err ->
                                    errorMsg = when (err.message) {
                                        "DUPLICATE_PROVIDER" -> duplicateProviderWarn
                                        "DUPLICATE_STORE" -> duplicateStoreWarn
                                        else -> err.message ?: "Could not save store."
                                    }
                                }
                            )
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(lStr(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun sourceDisplayLabel(source: String): String = when (source) {
    GeocodeSource.ANDROID_GEOCODER -> lStr(R.string.source_android_geocoder)
    GeocodeSource.OSM_NOMINATIM -> lStr(R.string.source_osm_nominatim)
    GeocodeSource.PASTED_COORDS -> lStr(R.string.source_pasted_coords)
    GeocodeSource.GOOGLE_MAPS_LINK -> lStr(R.string.source_google_maps_link)
    GeocodeSource.MANUAL_PIN -> lStr(R.string.source_manual_pin)
    else -> lStr(R.string.source_saved_location)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreDetailScreen(
    storeId: String,
    viewModel: DeliFarViewModel,
    allJobRows: List<JobListRow>,
    onBack: () -> Unit,
    onEditStore: (String) -> Unit,
    onNewJobForStore: (String) -> Unit,
    onOpenJob: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs by viewModel.preferences.collectAsState()
    val zoneId = remember(prefs.companyTimezone) { BusinessRules.resolveZone(prefs.companyTimezone) }
    val canManage = RolePermissions.canManageStoresAndJobs(prefs.activeUserRole)
    val storeWithHours: StoreWithHours? by viewModel.observeStore(storeId).collectAsState(initial = null)
    val storeJobs = remember(allJobRows, storeId) {
        BusinessRules.sortJobsForDay(allJobRows.filter { it.job.storeId == storeId })
    }
    val archiveBlockedMsg = lStr(R.string.archive_blocked_active_jobs)
    val invalidCoordMsg = lStr(R.string.invalid_store_coordinates)
    val reviewPinMsg = lStr(R.string.map_review_pin_before_nav)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(storeWithHours?.store?.name ?: lStr(R.string.store_details), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = lStr(R.string.back))
                    }
                },
                actions = {
                    if (storeWithHours != null && canManage) {
                        IconButton(onClick = { onEditStore(storeId) }) {
                            Icon(Icons.Default.Edit, contentDescription = lStr(R.string.edit))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        val swh = storeWithHours
        if (swh == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(swh.store.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            swh.store.address,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (GeoValidation.isValidCoordinate(swh.store.lat, swh.store.lon)) {
                            Text(
                                text = "${"%.5f".format(Locale.US, swh.store.lat)}, ${"%.5f".format(Locale.US, swh.store.lon)} • ${sourceDisplayLabel(swh.store.geocodeSource)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (swh.store.phone.isNotBlank()) {
                            Text(
                                swh.store.phone,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        if (!swh.store.locationConfirmed) {
                            Surface(
                                color = AmberContainer,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = lStr(R.string.pin_review_required),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = AmberAccent,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = lStr(R.string.pin_review_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (canManage) {
                                        Button(
                                            onClick = { onEditStore(swh.store.id) },
                                            modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(lStr(R.string.review_confirm_pin))
                                        }
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (!swh.store.locationConfirmed) {
                                        viewModel.notifyMessage(reviewPinMsg)
                                        if (canManage) onEditStore(swh.store.id)
                                    } else {
                                        val ok = launchDirections(
                                            context,
                                            swh.store.lat,
                                            swh.store.lon,
                                            swh.store.name,
                                            swh.store.address
                                        )
                                        if (!ok) viewModel.notifyMessage(invalidCoordMsg)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 44.dp)
                            ) {
                                Icon(Icons.Default.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(lStr(R.string.directions))
                            }
                            if (swh.store.phone.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { launchDialer(context, swh.store.phone) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 44.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(lStr(R.string.call_store))
                                }
                            }
                        }

                        if (canManage) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!swh.store.archived) {
                                    Button(
                                        onClick = { onNewJobForStore(swh.store.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.new_job))
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.archiveStore(swh.store.id, archiveBlockedMsg) }
                                    ) {
                                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.archive_store))
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.restoreStore(swh.store.id) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(lStr(R.string.restore_store))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(lStr(R.string.receiving_hours), style = MaterialTheme.typography.titleMedium)
                            ReceivingWindowStatusTag(hours = swh.hours, zoneId = zoneId)
                        }
                        (1..7).forEach { day ->
                            val entry = swh.hours.find { it.day == day }
                            val text = when (entry?.mode) {
                                HoursMode.CLOSED -> lStr(R.string.hours_closed)
                                HoursMode.OPEN -> entry.windows.ifBlank { lStr(R.string.hours_open) }
                                else -> lStr(R.string.hours_unknown)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(weekdayShortLabel(day), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (day < 7) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        }
                    }
                }
            }

            item {
                Text(
                    text = lStr(R.string.map_jobs_at_store, storeJobs.size),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(storeJobs, key = { it.job.id }) { row ->
                JobCard(
                    row = row,
                    zoneId = zoneId,
                    onClick = { onOpenJob(row.job.id) },
                    receivingHoursToday = todayHoursSummary(swh.hours, zoneId),
                    storeHours = swh.hours
                )
            }
        }
    }
}
