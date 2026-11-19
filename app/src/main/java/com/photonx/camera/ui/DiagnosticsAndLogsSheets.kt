package com.photonx.camera.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photonx.camera.BuildConfig
import com.photonx.camera.domain.CameraDiagnosticsReport
import com.photonx.camera.domain.LogEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsDialogSheet(
    report: CameraDiagnosticsReport?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val plainText = report?.formatPlainText() ?: "Loading Camera2 & Extensions capabilities..."

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161618)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Hardware Diagnostics",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Build v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
                        color = Color.Cyan,
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(plainText))
                            Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD60A))
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFFD60A))
                }
            } else {
                Surface(
                    color = Color(0xFF0D0D0F),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = plainText,
                        color = Color(0xFFE5E5EA),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsDialogSheet(
    logs: List<LogEntry>,
    plainTextLogs: String,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161618)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Engine & Error Logs (${logs.size})",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Build v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
                        color = Color.Cyan,
                        fontSize = 11.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Logs", tint = Color.LightGray)
                    }
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(plainTextLogs))
                            Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD60A))
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = Color(0xFF0D0D0F),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            "No events or errors logged yet.",
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    } else {
                        logs.asReversed().forEach { entry ->
                            val lineColor = when (entry.level) {
                                "ERROR" -> Color(0xFFFF453A)
                                "WARN" -> Color(0xFFFFD60A)
                                else -> Color(0xFF32D74B)
                            }
                            Text(
                                text = entry.formatPlainText(),
                                color = lineColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
