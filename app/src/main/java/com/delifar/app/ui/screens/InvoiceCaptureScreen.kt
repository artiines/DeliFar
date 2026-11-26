package com.delifar.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.delifar.app.R
import com.delifar.app.data.InvoicePageEntity
import com.delifar.app.data.StoreWithHours
import com.delifar.app.documents.DocumentProcessor
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InvoiceCaptureScreen(
    jobId: String?,
    storeIdArg: String?,
    stores: List<StoreWithHours>,
    viewModel: DeliFarViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onSavedAndSign: (invoiceId: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val draftInvoiceId = remember { UUID.randomUUID().toString() }
    val activeStores = remember(stores) { stores.filter { !it.store.archived } }
    var selectedStoreId by remember(storeIdArg) {
        mutableStateOf(storeIdArg ?: activeStores.firstOrNull()?.store?.id.orEmpty())
    }
    LaunchedEffect(activeStores) {
        if (selectedStoreId.isBlank() && activeStores.isNotEmpty()) {
            selectedStoreId = storeIdArg ?: activeStores.first().store.id
        }
    }
    val selectedStore = remember(stores, selectedStoreId) {
        stores.find { it.store.id == selectedStoreId } ?: activeStores.firstOrNull()
    }
    var storeDropdownExpanded by remember { mutableStateOf(false) }

    var invoiceNumber by remember { mutableStateOf("INV-${(System.currentTimeMillis() % 100000)}") }
    var notes by remember { mutableStateOf("") }
    val pages = remember { mutableStateListOf<InvoicePageEntity>() }
    var selectedPageIdx by remember { mutableIntStateOf(0) }
    var previewVersion by remember { mutableIntStateOf(0) }
    var processing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    var retakeReplaceIndex by remember { mutableStateOf<Int?>(null) }

    val cameraPermMsg = lStr(R.string.camera_permission_required)
    val selectStoreMsg = lStr(R.string.select_or_create_store_first)

    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = pendingCameraFile
        val replaceIdx = retakeReplaceIndex
        pendingCameraFile = null
        retakeReplaceIndex = null
        if (success && file != null && file.exists()) {
            processing = true
            scope.launch {
                val targetIndex = replaceIdx ?: pages.size
                val res = DocumentProcessor.importCapturedFile(context, draftInvoiceId, targetIndex, file)
                processing = false
                res.fold(
                    onSuccess = { page ->
                        if (replaceIdx != null && replaceIdx in pages.indices) {
                            pages[replaceIdx] = page.copy(pageIndex = replaceIdx)
                            selectedPageIdx = replaceIdx
                        } else {
                            pages.add(page)
                            selectedPageIdx = pages.lastIndex
                        }
                        previewVersion++
                    },
                    onFailure = { errorMsg = it.message ?: "Failed to process photo." }
                )
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val (file, uri) = DocumentProcessor.createCameraCaptureFile(context)
            pendingCameraFile = file
            takePictureLauncher.launch(uri)
        } else {
            retakeReplaceIndex = null
            errorMsg = cameraPermMsg
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            processing = true
            scope.launch {
                uris.forEach { uri ->
                    DocumentProcessor.importImageUri(context, draftInvoiceId, pages.size, uri)
                        .onSuccess { pages.add(it) }
                        .onFailure { errorMsg = it.message }
                }
                if (pages.isNotEmpty()) selectedPageIdx = pages.lastIndex
                previewVersion++
                processing = false
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            processing = true
            scope.launch {
                val res = DocumentProcessor.importPdfUri(context, draftInvoiceId, pages.size, uri)
                processing = false
                res.fold(
                    onSuccess = { imported ->
                        pages.addAll(imported)
                        if (pages.isNotEmpty()) selectedPageIdx = pages.lastIndex
                        previewVersion++
                    },
                    onFailure = { errorMsg = it.message ?: "Could not import PDF." }
                )
            }
        }
    }

    fun launchCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val (file, uri) = DocumentProcessor.createCameraCaptureFile(context)
            pendingCameraFile = file
            takePictureLauncher.launch(uri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val currentPage = pages.getOrNull(selectedPageIdx)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.add_invoice), style = MaterialTheme.typography.titleLarge) },
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
            // Store selector (editable when opened from Invoices tab)
            if (activeStores.isNotEmpty()) {
                item {
                    if (storeIdArg != null && selectedStore != null) {
                        OutlinedTextField(
                            value = selectedStore.store.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(lStr(R.string.select_store)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        ExposedDropdownMenuBox(
                            expanded = storeDropdownExpanded,
                            onExpandedChange = { storeDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedStore?.store?.name.orEmpty(),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(lStr(R.string.select_store)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = storeDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = storeDropdownExpanded,
                                onDismissRequest = { storeDropdownExpanded = false }
                            ) {
                                activeStores.forEach { swh ->
                                    DropdownMenuItem(
                                        text = { Text(swh.store.name) },
                                        onClick = {
                                            selectedStoreId = swh.store.id
                                            storeDropdownExpanded = false
                                            errorMsg = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Capture source buttons
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { retakeReplaceIndex = null; launchCamera() },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(lStr(R.string.capture_photo), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(lStr(R.string.pick_gallery), maxLines = 1)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pdfLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 44.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(lStr(R.string.import_pdf))
                }
            }

            if (processing) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(lStr(R.string.invoice_processing_page), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Document Preview & Non-Destructive Adjustment Controls
            if (currentPage != null) {
                item {
                    val bmp by produceState<ImageBitmap?>(
                        initialValue = null,
                        key1 = currentPage.editedLocalPath,
                        key2 = previewVersion
                    ) {
                        value = withContext(Dispatchers.IO) {
                            DocumentProcessor.decodeScaledBitmap(currentPage.editedLocalPath, 1200)?.asImageBitmap()
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                lStr(R.string.invoice_page_of, selectedPageIdx + 1, pages.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp!!,
                                    contentDescription = "Invoice page preview",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(320.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                FilterChip(
                                    selected = currentPage.rotationDegrees != 0,
                                    onClick = {
                                        processing = true
                                        scope.launch {
                                            DocumentProcessor.updatePageAdjustments(
                                                page = currentPage,
                                                rotationDegrees = currentPage.rotationDegrees + 90,
                                                contrastBoost = currentPage.contrastBoost,
                                                cropInsetPercent = currentPage.cropInsetPercent
                                            ).onSuccess { updated ->
                                                pages[selectedPageIdx] = updated
                                                previewVersion++
                                            }
                                            processing = false
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text(lStr(R.string.rotate_90)) }
                                )
                                FilterChip(
                                    selected = currentPage.contrastBoost,
                                    onClick = {
                                        processing = true
                                        scope.launch {
                                            DocumentProcessor.updatePageAdjustments(
                                                page = currentPage,
                                                rotationDegrees = currentPage.rotationDegrees,
                                                contrastBoost = !currentPage.contrastBoost,
                                                cropInsetPercent = currentPage.cropInsetPercent
                                            ).onSuccess { updated ->
                                                pages[selectedPageIdx] = updated
                                                previewVersion++
                                            }
                                            processing = false
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Contrast, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text(lStr(R.string.enhance_contrast)) }
                                )
                                FilterChip(
                                    selected = currentPage.cropInsetPercent > 0,
                                    onClick = {
                                        val nextCrop = when (currentPage.cropInsetPercent) {
                                            0 -> 6
                                            6 -> 12
                                            else -> 0
                                        }
                                        processing = true
                                        scope.launch {
                                            DocumentProcessor.updatePageAdjustments(
                                                page = currentPage,
                                                rotationDegrees = currentPage.rotationDegrees,
                                                contrastBoost = currentPage.contrastBoost,
                                                cropInsetPercent = nextCrop
                                            ).onSuccess { updated ->
                                                pages[selectedPageIdx] = updated
                                                previewVersion++
                                            }
                                            processing = false
                                        }
                                    },
                                    leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text("${lStr(R.string.crop_edges)} ${currentPage.cropInsetPercent}%") }
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        retakeReplaceIndex = selectedPageIdx
                                        launchCamera()
                                    },
                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                ) {
                                    Text(lStr(R.string.retake))
                                }
                                OutlinedButton(
                                    onClick = {
                                        pages.removeAt(selectedPageIdx)
                                        selectedPageIdx = selectedPageIdx.coerceAtMost((pages.size - 1).coerceAtLeast(0))
                                    },
                                    modifier = Modifier.defaultMinSize(minHeight = 44.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = TerracottaError, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(lStr(R.string.delete), color = TerracottaError)
                                }
                            }
                        }
                    }
                }
            }

            // Multi-page thumbnail selector strip
            if (pages.size > 1) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(pages) { idx, _ ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (idx == selectedPageIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 44.dp)
                                    .clickable { selectedPageIdx = idx }
                            ) {
                                Text(
                                    text = lStr(R.string.invoice_page_chip, idx + 1),
                                    color = if (idx == selectedPageIdx) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Metadata Fields
            item {
                OutlinedTextField(
                    value = invoiceNumber,
                    onValueChange = { invoiceNumber = it; errorMsg = null },
                    label = { Text(lStr(R.string.invoice_number_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(lStr(R.string.invoice_notes_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            errorMsg?.let { err ->
                item {
                    Surface(color = TerracottaContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(err, color = TerracottaError, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            // Save & Save + Collect Signature Buttons
            item {
                val store = selectedStore
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (store == null) {
                                errorMsg = selectStoreMsg
                                return@Button
                            }
                            scope.launch {
                                val res = viewModel.saveInvoice(
                                    jobId = jobId,
                                    storeId = store.store.id,
                                    storeName = store.store.name,
                                    invoiceNumber = invoiceNumber,
                                    notes = notes,
                                    pages = pages.toList()
                                )
                                res.fold(
                                    onSuccess = { onSavedAndSign(it.invoice.id) },
                                    onFailure = { errorMsg = it.message ?: "Could not save invoice." }
                                )
                            }
                        },
                        enabled = pages.isNotEmpty() && !processing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.Draw, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(lStr(R.string.save_and_sign))
                    }
                    OutlinedButton(
                        onClick = {
                            if (store == null) {
                                errorMsg = selectStoreMsg
                                return@OutlinedButton
                            }
                            scope.launch {
                                val res = viewModel.saveInvoice(
                                    jobId = jobId,
                                    storeId = store.store.id,
                                    storeName = store.store.name,
                                    invoiceNumber = invoiceNumber,
                                    notes = notes,
                                    pages = pages.toList()
                                )
                                res.fold(
                                    onSuccess = { onSaved() },
                                    onFailure = { errorMsg = it.message ?: "Could not save invoice." }
                                )
                            }
                        },
                        enabled = pages.isNotEmpty() && !processing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                    ) {
                        Text(lStr(R.string.save_invoice))
                    }
                }
            }
        }
    }
}
