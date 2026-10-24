package com.pixivdownloader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WinDark = darkColorScheme(
    primary = Color(0xFF0078D4),
    onPrimary = Color.White,
    background = Color(0xFF202020),
    onBackground = Color.White,
    surface = Color(0xFF2D2D2D),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2D2D2D),
    outline = Color(0xFF3A3A3A),
)

private val Gray = Color(0xFFA0A0A0)

@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenFolder: () -> Unit,
    onShareLog: () -> Unit,
) {
    MaterialTheme(colorScheme = WinDark) {
        val token by vm.token.collectAsState()
        val tokenStatus by vm.tokenStatus.collectAsState()
        val url by vm.url.collectAsState()
        val type by vm.contentType.collectAsState()
        val format by vm.outFormat.collectAsState()
        val quality by vm.quality.collectAsState()
        val resize by vm.resizeMode.collectAsState()
        val width by vm.width.collectAsState()
        val height by vm.height.collectAsState()
        val speed by vm.speedIndex.collectAsState()
        val progress by vm.progress.collectAsState()
        val log by vm.logText.collectAsState()
        val busy by vm.busy.collectAsState()
        val message by vm.message.collectAsState()

        val imageGroupOn = type != ContentType.UGOIRA
        val ugoiraGroupOn = type != ContentType.IMAGE
        val processingOn = imageGroupOn && !busy && format != OutFormat.ORIGINAL
        val qualityOn = processingOn && (format == OutFormat.JPEG || format == OutFormat.WEBP)
        val widthOn = processingOn && (resize == ResizeMode.AUTO || resize == ResizeMode.WIDTH || resize == ResizeMode.BOTH)
        val heightOn = processingOn && (resize == ResizeMode.HEIGHT || resize == ResizeMode.BOTH)

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // PHPSESSID
                    var showToken by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = token,
                            onValueChange = vm::setToken,
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("PHPSESSID") },
                            placeholder = { Text("Paste your Pixiv PHPSESSID here") },
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                TextButton(onClick = { showToken = !showToken }) {
                                    Text(if (showToken) "Hide" else "Show", fontSize = 12.sp)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = vm::saveToken, enabled = !busy) { Text("Save") }
                    }
                    Text(tokenStatus, fontSize = 11.sp, color = Gray)

                    OutlinedTextField(
                        value = url,
                        onValueChange = vm::setUrl,
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("Pixiv URL") },
                        placeholder = { Text("Paste Pixiv artwork or image URL here") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Picker(
                        label = "Content Type",
                        items = ContentType.entries,
                        selected = type.ordinal,
                        enabled = !busy,
                        text = { it.label },
                        onSelect = { vm.setContentType(ContentType.entries[it]) },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Group("Image Processing Options", imageGroupOn) {
                        Picker(
                            label = "Output Format",
                            items = OutFormat.entries,
                            selected = format.ordinal,
                            enabled = imageGroupOn && !busy,
                            text = { it.label },
                            onSelect = { vm.setOutFormat(OutFormat.entries[it]) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Picker(
                            label = "Quality (JPEG/WEBP)",
                            items = Presets.QUALITIES,
                            selected = Presets.QUALITIES.indexOf(quality).coerceAtLeast(0),
                            enabled = qualityOn,
                            text = { it.toString() },
                            onSelect = { vm.setQuality(Presets.QUALITIES[it]) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Picker(
                            label = "Resize",
                            items = ResizeMode.entries,
                            selected = resize.ordinal,
                            enabled = processingOn,
                            text = { it.label },
                            onSelect = { vm.setResizeMode(ResizeMode.entries[it]) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = width,
                                onValueChange = { v -> vm.setWidth(v.filter { c -> c.isDigit() }) },
                                enabled = widthOn,
                                singleLine = true,
                                label = { Text(if (resize == ResizeMode.AUTO) "Size (px)" else "Width (px)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            if (resize != ResizeMode.AUTO) {
                                OutlinedTextField(
                                    value = height,
                                    onValueChange = { v -> vm.setHeight(v.filter { c -> c.isDigit() }) },
                                    enabled = heightOn,
                                    singleLine = true,
                                    label = { Text("Height (px)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    Group("Ugoira Video Options (Output is always 60 FPS)", ugoiraGroupOn) {
                        Picker(
                            label = "Animation Speed",
                            items = Presets.SPEEDS,
                            selected = speed.coerceIn(0, Presets.SPEEDS.lastIndex),
                            enabled = ugoiraGroupOn && !busy,
                            text = { it.label },
                            onSelect = vm::setSpeedIndex,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Progress
                    val fraction = if (progress.total > 0) (progress.current.toFloat() / progress.total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (progress.total > 0) "${progress.text} - ${(fraction * 100).toInt()}%" else progress.text,
                        fontSize = 11.sp,
                        color = Gray,
                    )

                    // Log
                    val scroll = rememberScrollState()
                    LaunchedEffect(log) { scroll.scrollTo(scroll.maxValue) }
                    Box(
                        Modifier.fillMaxWidth().height(200.dp)
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFFE0E0E0),
                            modifier = Modifier.fillMaxSize().verticalScroll(scroll),
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::onDownloadClicked, modifier = Modifier.weight(1f)) {
                        Text(if (busy) "Cancel" else "Download")
                    }
                    OutlinedButton(onClick = onOpenFolder, modifier = Modifier.weight(1f)) { Text("Open Folder") }
                }
                TextButton(onClick = onShareLog, modifier = Modifier.fillMaxWidth()) { Text("Share history log") }
            }
        }

        message?.let { m ->
            AlertDialog(
                onDismissRequest = vm::dismissMessage,
                title = { Text(m.title) },
                text = { Text(m.text) },
                confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("OK") } },
                containerColor = Color(0xFF202020),
            )
        }
    }
}

@Composable
private fun Group(title: String, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(4.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 13.sp, color = Gray)
        content()
    }
}

@Composable
private fun <T> Picker(
    label: String,
    items: List<T>,
    selected: Int,
    enabled: Boolean,
    text: (T) -> String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(
                "$label: ${items.getOrNull(selected)?.let(text) ?: ""}",
                maxLines = 1,
                fontSize = 13.sp,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEachIndexed { i, item ->
                DropdownMenuItem(text = { Text(text(item), fontSize = 13.sp) }, onClick = { onSelect(i); open = false })
            }
        }
    }
}
