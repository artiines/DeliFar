package com.delifar.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.delifar.app.R
import com.delifar.app.documents.DocumentProcessor
import com.delifar.app.documents.SignatureStrokePoint
import com.delifar.app.ui.DeliFarViewModel
import com.delifar.app.ui.components.lStr
import com.delifar.app.ui.theme.TerracottaContainer
import com.delifar.app.ui.theme.TerracottaError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SignatureCaptureScreen(
    invoiceId: String,
    viewModel: DeliFarViewModel,
    onBack: () -> Unit,
    onSignedComplete: () -> Unit
) {
    val invoiceWithPages by viewModel.observeInvoice(invoiceId).collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var selectedPageIdx by remember { mutableIntStateOf(0) }
    var sigPosX by remember { mutableFloatStateOf(0.5f) }
    var sigPosY by remember { mutableFloatStateOf(0.82f) }

    // Zoom & pan state for full invoice preview
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        zoomScale = (zoomScale * zoomChange).coerceIn(1f, 3.5f)
        panOffset = if (zoomScale > 1.05f) panOffset + offsetChange else Offset.Zero
    }

    // Handwritten signature strokes with Undo / Redo
    val strokes = remember { mutableStateListOf<List<SignatureStrokePoint>>() }
    val redoStack = remember { mutableStateListOf<List<SignatureStrokePoint>>() }
    var activeStroke by remember { mutableStateOf<List<SignatureStrokePoint>>(emptyList()) }
    var padWidth by remember { mutableFloatStateOf(600f) }
    var padHeight by remember { mutableFloatStateOf(220f) }

    var recipientName by remember { mutableStateOf("") }
    var recipientTitle by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var showFinalConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val errDrawFirst = lStr(R.string.sig_err_draw_first)
    val errRecipientName = lStr(R.string.sig_err_recipient_name)
    val errAckRequired = lStr(R.string.sig_err_ack_required)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(lStr(R.string.signature_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = lStr(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { innerPadding ->
        val iwp = invoiceWithPages
        if (iwp == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val pages = iwp.sortedPages
        val currentPage = pages.getOrNull(selectedPageIdx.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Invoice #${iwp.invoice.invoiceNumber} (v${iwp.invoice.version}) • ${iwp.invoice.storeName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = lStr(R.string.signature_position_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (pages.size > 1) {
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        pages.forEachIndexed { idx, _ ->
                            FilterChip(
                                selected = idx == selectedPageIdx,
                                onClick = { selectedPageIdx = idx },
                                label = { Text(lStr(R.string.invoice_page_chip, idx + 1)) }
                            )
                        }
                    }
                }
            }

            // Zoomable, scrollable invoice preview with tappable signature placement box
            if (currentPage != null) {
                item {
                    val bmp by produceState<ImageBitmap?>(
                        initialValue = null,
                        key1 = currentPage.editedLocalPath
                    ) {
                        value = withContext(Dispatchers.IO) {
                            DocumentProcessor.decodeScaledBitmap(currentPage.editedLocalPath, 1200)?.asImageBitmap()
                        }
                    }
                    var boxW by remember { mutableFloatStateOf(1f) }
                    var boxH by remember { mutableFloatStateOf(1f) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(290.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFE2E8F0))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                            .onSizeChanged {
                                boxW = it.width.toFloat().coerceAtLeast(1f)
                                boxH = it.height.toFloat().coerceAtLeast(1f)
                            }
                            .transformable(state = transformState)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    sigPosX = (offset.x / boxW).coerceIn(0.15f, 0.85f)
                                    sigPosY = (offset.y / boxH).coerceIn(0.12f, 0.88f)
                                }
                            }
                    ) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp!!,
                                contentDescription = "Invoice preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = zoomScale,
                                        scaleY = zoomScale,
                                        translationX = panOffset.x,
                                        translationY = panOffset.y
                                    )
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(28.dp)
                                    .align(Alignment.Center)
                            )
                        }
                        // Signature placement indicator
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = size.width * sigPosX
                            val cy = size.height * sigPosY
                            val rectW = size.width * 0.34f
                            val rectH = 44.dp.toPx()
                            drawRect(
                                color = Color(0x55183A37),
                                topLeft = Offset(cx - rectW / 2f, cy - rectH / 2f),
                                size = androidx.compose.ui.geometry.Size(rectW, rectH)
                            )
                            drawRect(
                                color = Color(0xFF183A37),
                                topLeft = Offset(cx - rectW / 2f, cy - rectH / 2f),
                                size = androidx.compose.ui.geometry.Size(rectW, rectH),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
            }

            // Handwritten Signature Pad (finger / stylus) with Undo, Redo, Clear
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(lStr(R.string.signature_pad_hint), style = MaterialTheme.typography.titleMedium)
                            Row {
                                IconButton(
                                    onClick = {
                                        if (strokes.isNotEmpty()) {
                                            redoStack.add(strokes.removeAt(strokes.lastIndex))
                                        }
                                    },
                                    enabled = strokes.isNotEmpty()
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = lStr(R.string.signature_undo))
                                }
                                IconButton(
                                    onClick = {
                                        if (redoStack.isNotEmpty()) {
                                            strokes.add(redoStack.removeAt(redoStack.lastIndex))
                                        }
                                    },
                                    enabled = redoStack.isNotEmpty()
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = lStr(R.string.signature_redo))
                                }
                                IconButton(
                                    onClick = {
                                        strokes.clear()
                                        redoStack.clear()
                                        activeStroke = emptyList()
                                    },
                                    enabled = strokes.isNotEmpty()
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = lStr(R.string.clear))
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .border(1.5.dp, Color(0xFF94A3B8), RoundedCornerShape(10.dp))
                                .onSizeChanged {
                                    padWidth = it.width.toFloat().coerceAtLeast(1f)
                                    padHeight = it.height.toFloat().coerceAtLeast(1f)
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            redoStack.clear()
                                            activeStroke = listOf(SignatureStrokePoint(start.x, start.y))
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val pos = change.position
                                            activeStroke = activeStroke + SignatureStrokePoint(
                                                pos.x.coerceIn(0f, padWidth),
                                                pos.y.coerceIn(0f, padHeight)
                                            )
                                        },
                                        onDragEnd = {
                                            if (activeStroke.isNotEmpty()) {
                                                strokes.add(activeStroke)
                                                activeStroke = emptyList()
                                            }
                                        },
                                        onDragCancel = {
                                            activeStroke = emptyList()
                                        }
                                    )
                                }
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val allStrokes = if (activeStroke.isNotEmpty()) strokes + listOf(activeStroke) else strokes.toList()
                                allStrokes.forEach { stroke ->
                                    if (stroke.size == 1) {
                                        drawCircle(
                                            color = Color(0xFF0F172A),
                                            radius = 3.dp.toPx(),
                                            center = Offset(stroke[0].x, stroke[0].y)
                                        )
                                    } else if (stroke.size > 1) {
                                        val path = Path().apply {
                                            moveTo(stroke[0].x, stroke[0].y)
                                            for (i in 1 until stroke.size) {
                                                lineTo(stroke[i].x, stroke[i].y)
                                            }
                                        }
                                        drawPath(
                                            path = path,
                                            color = Color(0xFF0F172A),
                                            style = Stroke(
                                                width = 3.5.dp.toPx(),
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Recipient details & Acknowledgment checkbox
            item {
                OutlinedTextField(
                    value = recipientName,
                    onValueChange = { recipientName = it; errorMsg = null },
                    label = { Text(lStr(R.string.recipient_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = recipientTitle,
                    onValueChange = { recipientTitle = it },
                    label = { Text(lStr(R.string.recipient_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it; errorMsg = null })
                    Text(lStr(R.string.delivery_ack_label), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = lStr(R.string.signature_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                )
            }

            errorMsg?.let { err ->
                item {
                    Surface(color = TerracottaContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(err, color = TerracottaError, modifier = Modifier.padding(12.dp))
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        when {
                            strokes.isEmpty() -> errorMsg = errDrawFirst
                            recipientName.trim().length < 2 -> errorMsg = errRecipientName
                            !acknowledged -> errorMsg = errAckRequired
                            else -> showFinalConfirm = true
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(lStr(R.string.confirm))
                }
            }
        }

        if (showFinalConfirm) {
            AlertDialog(
                onDismissRequest = { if (!saving) showFinalConfirm = false },
                title = { Text(lStr(R.string.signature_confirm_title)) },
                text = { Text(lStr(R.string.signature_confirm_body)) },
                confirmButton = {
                    Button(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                val res = viewModel.completeElectronicSignature(
                                    invoiceWithPages = iwp,
                                    strokes = strokes.toList(),
                                    canvasWidth = padWidth,
                                    canvasHeight = padHeight,
                                    signaturePageIndex = selectedPageIdx,
                                    signaturePosX = sigPosX,
                                    signaturePosY = sigPosY,
                                    recipientName = recipientName,
                                    recipientTitle = recipientTitle,
                                    acknowledgmentConfirmed = acknowledged
                                )
                                saving = false
                                showFinalConfirm = false
                                res.fold(
                                    onSuccess = { onSignedComplete() },
                                    onFailure = { errorMsg = it.message ?: "Could not finalize signature." }
                                )
                            }
                        }
                    ) {
                        Text(if (saving) lStr(R.string.generating_pdf) else lStr(R.string.confirm))
                    }
                },
                dismissButton = {
                    TextButton(enabled = !saving, onClick = { showFinalConfirm = false }) {
                        Text(lStr(R.string.cancel))
                    }
                }
            )
        }
    }
}
