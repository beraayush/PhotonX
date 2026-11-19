package com.photonx.camera.ui

import android.content.Intent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.photonx.camera.BuildConfig
import com.photonx.camera.camera.PhotonXCameraManager
import com.photonx.camera.domain.CameraMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val logs by viewModel.logger.logs.collectAsState()

    val cameraManager = remember { PhotonXCameraManager(context, viewModel.logger) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showDiagnosticsSheet by remember { mutableStateOf(false) }
    var showLogsSheet by remember { mutableStateOf(false) }
    var focusTapPoint by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(uiState.selectedMode, settings.useVivoExtensions) {
        cameraManager.bindCamera(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            mode = uiState.selectedMode,
            useVivoExtensions = settings.useVivoExtensions
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        focusTapPoint = offset
                        cameraManager.tapToFocus(previewView, offset.x, offset.y)
                    }
                }
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (settings.showGrid) {
                val thirdW = size.width / 3f
                val thirdH = size.height / 3f
                val stroke = 1.dp.toPx()
                val gridColor = Color.White.copy(alpha = 0.25f)

                drawLine(gridColor, Offset(thirdW, 0f), Offset(thirdW, size.height), stroke)
                drawLine(gridColor, Offset(thirdW * 2, 0f), Offset(thirdW * 2, size.height), stroke)
                drawLine(gridColor, Offset(0f, thirdH), Offset(size.width, thirdH), stroke)
                drawLine(gridColor, Offset(0f, thirdH * 2), Offset(size.width, thirdH * 2), stroke)
            }

            focusTapPoint?.let { pt ->
                drawCircle(
                    color = Color(0xFFFFD60A),
                    radius = 28.dp.toPx(),
                    center = pt,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "PhotonX • ${uiState.statusMessage}",
                            color = Color(0xFFFFD60A),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (uiState.pendingJobsCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            CircularProgressIndicator(
                                color = Color.Cyan,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { showSettingsSheet = true },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), CircleShape)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            if (settings.showBenchmarks && uiState.lastCapture != null) {
                val m = uiState.lastCapture!!.metrics
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color.Black.copy(alpha = 0.68f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "${m.pipelineNote} • ${m.resolution}",
                            color = Color.Cyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Capture: ${m.captureTimeMs}ms | DSP: ${m.processingTimeMs}ms | Total: ${m.totalTimeMs}ms",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.72f))
                .navigationBarsPadding()
                .padding(vertical = 14.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!uiState.isFrontCamera) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(1.0f, 2.0f).forEach { z ->
                        val selected = uiState.zoomRatio == z
                        Text(
                            text = "${z.toInt()}x",
                            color = if (selected) Color.Black else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (selected) Color(0xFFFFD60A) else Color.DarkGray)
                                .clickable { viewModel.setZoom(z, cameraManager) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Text("EV ${uiState.exposureIndex}", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                Slider(
                    value = uiState.exposureIndex.toFloat(),
                    onValueChange = { viewModel.setExposure(it.toInt(), cameraManager) },
                    valueRange = -6f..6f,
                    steps = 11,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CameraMode.entries.forEach { mode ->
                    val isSelected = uiState.selectedMode == mode
                    Text(
                        text = mode.title.uppercase(),
                        color = if (isSelected) Color(0xFFFFD60A) else Color.White.copy(alpha = 0.7f),
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { viewModel.selectMode(mode) }
                            .padding(8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                        .clickable {
                            uiState.lastCapture?.savedUri?.let { uri ->
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "image/jpeg")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    viewModel.logger.error("Gallery", "Failed opening gallery intent", e)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val lastBmp = uiState.lastCapture?.previewBitmap
                    if (lastBmp != null && !lastBmp.isRecycled) {
                        Image(
                            bitmap = lastBmp.asImageBitmap(),
                            contentDescription = "Last Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("GAL", color = Color.Gray, fontSize = 10.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (uiState.isCapturingBurst) Color.Gray else Color.White)
                        .clickable(enabled = !uiState.isCapturingBurst) {
                            viewModel.capturePhoto(cameraManager)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.isCapturingBurst) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                IconButton(
                    onClick = {
                        cameraManager.switchCamera(
                            lifecycleOwner = lifecycleOwner,
                            previewView = previewView,
                            mode = uiState.selectedMode,
                            useVivoExtensions = settings.useVivoExtensions
                        )
                        viewModel.onCameraSwitched(cameraManager.isFrontCamera())
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .background(Color.DarkGray.copy(alpha = 0.7f), CircleShape)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White)
                }
            }
        }

        if (showSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
                containerColor = Color(0xFF1C1C1E)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PhotonX Pro Settings", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
                            color = Color.Cyan,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showSettingsSheet = false
                                viewModel.loadDiagnostics()
                                showDiagnosticsSheet = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Diagnostics", color = Color.White)
                        }

                        OutlinedButton(
                            onClick = {
                                showSettingsSheet = false
                                showLogsSheet = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = Color(0xFFFFD60A), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Logs (${logs.size})", color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingToggleRow(
                        title = "Natural iPhone-Like Color Science",
                        checked = settings.naturalColorScience,
                        onCheckedChange = viewModel::toggleNaturalColor
                    )
                    SettingToggleRow(
                        title = "Vivo Hardware Engine (Extensions if supported)",
                        checked = settings.useVivoExtensions,
                        onCheckedChange = viewModel::toggleVivoExtensions
                    )
                    SettingToggleRow(
                        title = "Rule-of-Thirds Grid",
                        checked = settings.showGrid,
                        onCheckedChange = viewModel::toggleGrid
                    )
                    SettingToggleRow(
                        title = "Show Telemetry HUD",
                        checked = settings.showBenchmarks,
                        onCheckedChange = viewModel::toggleBenchmarks
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Computational Tuning", color = Color(0xFFFFD60A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    TuningSliderRow(
                        label = "HDR+ Fusion Strength",
                        valueText = "${(settings.hdrStrength * 100).toInt()}%",
                        value = settings.hdrStrength,
                        range = 0f..1f,
                        onValueChange = viewModel::setHdrStrength
                    )

                    TuningSliderRow(
                        label = "Portrait Bokeh Blur",
                        valueText = "${(settings.portraitBlurStrength * 100).toInt()}%",
                        value = settings.portraitBlurStrength,
                        range = 0.1f..1f,
                        onValueChange = viewModel::setPortraitBlurStrength
                    )

                    TuningSliderRow(
                        label = "Food / Macro Micro-Contrast",
                        valueText = "${(settings.macroClarityStrength * 100).toInt()}%",
                        value = settings.macroClarityStrength,
                        range = 0f..1f,
                        onValueChange = viewModel::setMacroClarityStrength
                    )

                    TuningSliderRow(
                        label = "Golden Warmth Tint",
                        valueText = "${"%.2f".format(settings.colorWarmth)}",
                        value = settings.colorWarmth,
                        range = -0.5f..0.5f,
                        onValueChange = viewModel::setColorWarmth
                    )

                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }

        if (showDiagnosticsSheet) {
            DiagnosticsDialogSheet(
                report = uiState.diagnosticsReport,
                isLoading = uiState.isLoadingDiagnostics,
                onRefresh = viewModel::loadDiagnostics,
                onDismiss = { showDiagnosticsSheet = false }
            )
        }

        if (showLogsSheet) {
            LogsDialogSheet(
                logs = logs,
                plainTextLogs = viewModel.logger.exportPlainText(),
                onClear = viewModel.logger::clear,
                onDismiss = { showLogsSheet = false }
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TuningSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.LightGray, fontSize = 13.sp)
            Text(valueText, color = Color.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}
