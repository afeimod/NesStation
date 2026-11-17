package com.nesstation.app.ui.swf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.storage.DpadMode
import com.nesstation.app.core.storage.SwfButton
import com.nesstation.app.core.storage.SwfPadConfig
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------
// Colour palette
// ---------------------------------------------------------------------------
private val BtnBg = Color(0xFF1E2A3A).copy(alpha = 0.72f)
private val BtnActive = Color(0xFF8A7BFF).copy(alpha = 0.85f)
private val BtnText = Color.White
private val EditModeBg = Color(0xFF2A3A4A).copy(alpha = 0.6f)
private val AccentColor = Color(0xFF8A7BFF)
private val DeleteColor = Color(0xFFE74C3C)
private val DpadBg = Color.Black.copy(alpha = 0.35f)
private val DpadArm = Color.White.copy(alpha = 0.5f)
private val DpadArmPressed = Color(0xFFFF5722)
private val DpadCenter = Color(0xFFCCCCCC).copy(alpha = 0.5f)
private val JoystickRing = Color.White.copy(alpha = 0.5f)
private val JoystickInnerRing = Color.White.copy(alpha = 0.25f)
private val JoystickKnobIdle = Color(0xFFFFC107)
private val JoystickKnobActive = Color(0xFFFF6E40)
private val JoystickKnobHighlight = Color.White.copy(alpha = 0.3f)
private val JoystickTriangle = Color.White.copy(alpha = 0.35f)

// Multi-colour palette for action buttons (mirrors 3.3 ActionButtonView design)
private val ButtonColors = listOf(
    Color(0xFFE53935), // 红
    Color(0xFF1E88E5), // 蓝
    Color(0xFF43A047), // 绿
    Color(0xFFFFB300), // 琥珀
    Color(0xFF8E24AA), // 紫
    Color(0xFFFF6E40), // 橙
    Color(0xFF00BFC4), // 青绿
    Color(0xFFEC407A), // 粉
    Color(0xFF7E57C2), // 靛
    Color(0xFF66BB6A), // 浅绿
    Color(0xFFFFA726), // 橙黄
    Color(0xFF42A5F5)  // 浅蓝
)

// ---------------------------------------------------------------------------
// Main virtual keyboard
// ---------------------------------------------------------------------------

@Composable
fun VirtualKeyboard(
    config: SwfPadConfig,
    editMode: Boolean,
    onKeyPress: (String) -> Unit,
    onKeyRelease: (String) -> Unit,
    onConfigChange: (SwfPadConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    // Track which D-pad directions are currently pressed
    val pressedDirs = remember { mutableStateOf(setOf<String>()) }
    // Joystick knob offset (in dp relative to center)
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var joystickActive by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        // ---- D-pad / Joystick area (left side) ----
        // Find the D-pad buttons from config to get position
        val dpadUp = config.buttons.firstOrNull { it.id == "dpad_up" }
        if (dpadUp != null) {
            val dpadSize = 160f // dp
            val dpadSizePx = with(density) { dpadSize.dp.toPx() }
            // Use the position of dpad_up as the center of the D-pad area
            val cxPct = dpadUp.xPct
            val cyPct = dpadUp.yPct + 7.5f // adjust to center the dpad block
            val centerX = cxPct / 100f * maxW
            val centerY = cyPct / 100f * maxH

            val dirKeys = when (config.dpadMode) {
                DpadMode.DPAD -> mapOf(
                    "up" to "ArrowUp", "down" to "ArrowDown",
                    "left" to "ArrowLeft", "right" to "ArrowRight"
                )
                DpadMode.WASD, DpadMode.JOYSTICK -> mapOf(
                    "up" to "w", "down" to "s",
                    "left" to "a", "right" to "d"
                )
            }

            // Draw the D-pad or Joystick using Canvas
            Box(
                modifier = Modifier
                    .offset(
                        x = with(density) { ((centerX - dpadSizePx / 2)).toDp() },
                        y = with(density) { ((centerY - dpadSizePx / 2)).toDp() }
                    )
                    .size(with(density) { dpadSizePx.toDp() })
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(config.dpadMode, editMode) {
                            if (editMode) return@pointerInput
                            if (config.dpadMode == DpadMode.JOYSTICK) {
                                // Joystick touch handling
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        joystickActive = true
                                        updateJoystickDirection(
                                            offset, size.width.toFloat(), size.height.toFloat(),
                                            dirKeys, pressedDirs.value, onKeyPress, onKeyRelease
                                        ) { newDirs, newKnob ->
                                            pressedDirs.value = newDirs
                                            knobOffset = newKnob
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        updateJoystickDirection(
                                            change.position, size.width.toFloat(), size.height.toFloat(),
                                            dirKeys, pressedDirs.value, onKeyPress, onKeyRelease
                                        ) { newDirs, newKnob ->
                                            pressedDirs.value = newDirs
                                            knobOffset = newKnob
                                        }
                                    },
                                    onDragEnd = {
                                        releaseAllDirections(dirKeys, pressedDirs.value, onKeyRelease)
                                        pressedDirs.value = emptySet()
                                        joystickActive = false
                                        knobOffset = Offset.Zero
                                    },
                                    onDragCancel = {
                                        releaseAllDirections(dirKeys, pressedDirs.value, onKeyRelease)
                                        pressedDirs.value = emptySet()
                                        joystickActive = false
                                        knobOffset = Offset.Zero
                                    }
                                )
                            }
                        }
                        .pointerInput(config.dpadMode, editMode) {
                            if (editMode) return@pointerInput
                            if (config.dpadMode != DpadMode.JOYSTICK) {
                                // D-pad touch handling
                                detectTapGestures(
                                    onPress = { offset ->
                                        val dirs = computeDpadDirections(
                                            offset.x, offset.y,
                                            size.width.toFloat(), size.height.toFloat()
                                        )
                                        val newPressed = mutableSetOf<String>()
                                        for (d in dirs) {
                                            val key = dirKeys[d]
                                            if (key != null && key !in pressedDirs.value) {
                                                onKeyPress(key)
                                                newPressed.add(d)
                                            }
                                        }
                                        pressedDirs.value = pressedDirs.value + newPressed
                                        tryAwaitRelease()
                                        // Release all on release
                                        for (d in newPressed) {
                                            dirKeys[d]?.let { onKeyRelease(it) }
                                        }
                                        pressedDirs.value = pressedDirs.value - newPressed
                                    }
                                )
                            }
                        }
                ) {
                    val w = this.size.width
                    val h = this.size.height
                    val cx = w / 2f
                    val cy = h / 2f
                    val r = min(w, h) / 2f - 8f

                    when (config.dpadMode) {
                        DpadMode.DPAD, DpadMode.WASD -> {
                            drawCrossDpad(cx, cy, r, pressedDirs.value)
                        }
                        DpadMode.JOYSTICK -> {
                            drawJoystick(cx, cy, r, knobOffset, joystickActive)
                        }
                    }
                }
            }
        }

        // ---- Action buttons (right side) — excluding D-pad keys ----
        config.buttons.filter { it.id !in setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right") }
            .forEachIndexed { index, btn ->
                SwfKeyButton(
                    button = btn,
                    label = btn.label,
                    key = btn.key,
                    editMode = editMode,
                    isSelected = selectedId == btn.id,
                    buttonColor = ButtonColors[index % ButtonColors.size],
                    maxW = maxW,
                    maxH = maxH,
                    density = density,
                    onPress = onKeyPress,
                    onRelease = onKeyRelease,
                    onPositionChange = { newX, newY ->
                        onConfigChange(config.copy(
                            buttons = config.buttons.map {
                                if (it.id == btn.id) it.copy(xPct = newX, yPct = newY) else it
                            }
                        ))
                    },
                    onSelect = { selectedId = btn.id },
                    onDelete = {
                        if (btn.id !in SwfPadConfig.FIXED_IDS) {
                            onConfigChange(config.copy(
                                buttons = config.buttons.filter { it.id != btn.id }
                            ))
                            selectedId = null
                        }
                    }
                )
            }

        // ---- Edit-mode toolbar ----
        if (editMode) {
            EditToolbar(
                modifier = Modifier.align(Alignment.TopCenter),
                onAdd = { showAddDialog = true },
                onDelete = {
                    selectedId?.let { sid ->
                        if (sid !in SwfPadConfig.FIXED_IDS) {
                            onConfigChange(config.copy(
                                buttons = config.buttons.filter { it.id != sid }
                            ))
                            selectedId = null
                        }
                    }
                },
                onReset = {
                    onConfigChange(SwfPadConfig(dpadMode = config.dpadMode, showPad = config.showPad))
                    selectedId = null
                },
                hasSelection = selectedId != null && selectedId !in SwfPadConfig.FIXED_IDS
            )

            selectedId?.let { sid ->
                val selected = config.buttons.firstOrNull { it.id == sid }
                if (selected != null) {
                    SizeSlider(
                        size = selected.sizeDp,
                        onSizeChange = { newSize ->
                            onConfigChange(config.copy(
                                buttons = config.buttons.map {
                                    if (it.id == sid) it.copy(sizeDp = newSize) else it
                                }
                            ))
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .fillMaxWidth(0.6f)
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddButtonDialog(
            onAdd = { label, key ->
                onConfigChange(config.copy(
                    buttons = config.buttons + SwfPadConfig.newButton(label, key)
                ))
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------
// D-pad / Joystick drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawCrossDpad(
    cx: Float, cy: Float, r: Float, pressed: Set<String>
) {
    // Background circle
    drawCircle(color = DpadBg, radius = r, center = Offset(cx, cy))

    val armW = r * 0.42f
    val armL = r * 0.95f

    // Up arm
    drawRoundRect(
        color = if ("up" in pressed) DpadArmPressed else DpadArm,
        topLeft = Offset(cx - armW, cy - armL),
        size = androidx.compose.ui.geometry.Size(armW * 2, armL),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(armW * 0.5f, armW * 0.5f)
    )
    // Down arm
    drawRoundRect(
        color = if ("down" in pressed) DpadArmPressed else DpadArm,
        topLeft = Offset(cx - armW, cy),
        size = androidx.compose.ui.geometry.Size(armW * 2, armL),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(armW * 0.5f, armW * 0.5f)
    )
    // Left arm
    drawRoundRect(
        color = if ("left" in pressed) DpadArmPressed else DpadArm,
        topLeft = Offset(cx - armL, cy - armW),
        size = androidx.compose.ui.geometry.Size(armL, armW * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(armW * 0.5f, armW * 0.5f)
    )
    // Right arm
    drawRoundRect(
        color = if ("right" in pressed) DpadArmPressed else DpadArm,
        topLeft = Offset(cx, cy - armW),
        size = androidx.compose.ui.geometry.Size(armL, armW * 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(armW * 0.5f, armW * 0.5f)
    )

    // Center circle
    drawCircle(color = DpadCenter, radius = armW * 0.6f, center = Offset(cx, cy))
}

private fun DrawScope.drawJoystick(
    cx: Float, cy: Float, r: Float, knobOffset: Offset, active: Boolean
) {
    // Outer ring background
    drawCircle(color = DpadBg, radius = r, center = Offset(cx, cy))

    // Outer ring border
    drawCircle(
        color = JoystickRing,
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = r * 0.06f)
    )

    // Inner scale ring
    drawCircle(
        color = JoystickInnerRing,
        radius = r * 0.55f,
        center = Offset(cx, cy),
        style = Stroke(width = r * 0.03f)
    )

    // Direction triangles
    val tri = r * 0.12f
    val triOff = r * 0.80f
    drawTriangle(Offset(cx, cy - triOff), tri, 0)
    drawTriangle(Offset(cx, cy + triOff), tri, 2)
    drawTriangle(Offset(cx - triOff, cy), tri, 3)
    drawTriangle(Offset(cx + triOff, cy), tri, 1)

    // Knob
    val knobR = r * 0.42f
    val kx = cx + knobOffset.x
    val ky = cy + knobOffset.y
    drawCircle(
        color = if (active) JoystickKnobActive else JoystickKnobIdle,
        radius = knobR,
        center = Offset(kx, ky)
    )
    // Knob highlight ring
    drawCircle(
        color = JoystickKnobHighlight,
        radius = knobR * 0.7f,
        center = Offset(kx, ky),
        style = Stroke(width = knobR * 0.14f)
    )
}

private fun DrawScope.drawTriangle(center: Offset, size: Float, dir: Int) {
    val p = Path()
    when (dir) {
        0 -> { // up
            p.moveTo(center.x, center.y - size)
            p.lineTo(center.x - size, center.y + size)
            p.lineTo(center.x + size, center.y + size)
        }
        1 -> { // right
            p.moveTo(center.x + size, center.y)
            p.lineTo(center.x - size, center.y - size)
            p.lineTo(center.x - size, center.y + size)
        }
        2 -> { // down
            p.moveTo(center.x, center.y + size)
            p.lineTo(center.x - size, center.y - size)
            p.lineTo(center.x + size, center.y - size)
        }
        3 -> { // left
            p.moveTo(center.x - size, center.y)
            p.lineTo(center.x + size, center.y - size)
            p.lineTo(center.x + size, center.y + size)
        }
    }
    p.close()
    drawPath(p, color = JoystickTriangle)
}

// ---------------------------------------------------------------------------
// Touch direction computation
// ---------------------------------------------------------------------------

private fun computeDpadDirections(x: Float, y: Float, w: Float, h: Float): Set<String> {
    val cx = w / 2f
    val cy = h / 2f
    val r = max(min(w, h) / 2f, 1f)
    val dx = (x - cx) / r
    val dy = (y - cy) / r
    val deadZone = 0.25f
    if (abs(dx) < deadZone && abs(dy) < deadZone) return emptySet()
    val set = mutableSetOf<String>()
    if (abs(dx) > abs(dy)) {
        set.add(if (dx > 0) "right" else "left")
    } else {
        set.add(if (dy > 0) "down" else "up")
    }
    return set
}

private fun updateJoystickDirection(
    position: Offset,
    w: Float,
    h: Float,
    dirKeys: Map<String, String>,
    currentPressed: Set<String>,
    onKeyPress: (String) -> Unit,
    onKeyRelease: (String) -> Unit,
    onUpdate: (Set<String>, Offset) -> Unit
) {
    val cx = w / 2f
    val cy = h / 2f
    val outerR = max(min(w, h) / 2f - 8f, 1f)
    var dx = position.x - cx
    var dy = position.y - cy
    val dist = hypot(dx, dy)
    val maxDist = outerR * 0.85f
    if (dist > maxDist) {
        val ratio = maxDist / dist
        dx *= ratio
        dy *= ratio
    }

    val knobOffset = Offset(dx, dy)

    // 8-direction detection
    val active = mutableSetOf<String>()
    val norm = dist / outerR
    val deadZone = 0.25f
    if (norm > deadZone) {
        val ax = abs(dx)
        val ay = abs(dy)
        val diagRatio = 0.35f
        if (ax > outerR * diagRatio) {
            active.add(if (dx > 0) "right" else "left")
        }
        if (ay > outerR * diagRatio) {
            active.add(if (dy > 0) "down" else "up")
        }
        if (active.isEmpty()) {
            if (ax > ay) {
                active.add(if (dx > 0) "right" else "left")
            } else {
                active.add(if (dy > 0) "down" else "up")
            }
        }
    }

    // Press new directions
    for (d in active) {
        val key = dirKeys[d]
        if (key != null && key !in currentPressed.map { dirKeys[it] }.filterNotNull()) {
            onKeyPress(key)
        }
    }
    // Release old directions not in active
    for (d in currentPressed) {
        if (d !in active) {
            dirKeys[d]?.let { onKeyRelease(it) }
        }
    }

    onUpdate(active, knobOffset)
}

private fun releaseAllDirections(
    dirKeys: Map<String, String>,
    pressed: Set<String>,
    onKeyRelease: (String) -> Unit
) {
    for (d in pressed) {
        dirKeys[d]?.let { onKeyRelease(it) }
    }
}

// ---------------------------------------------------------------------------
// Individual key button (action buttons)
// ---------------------------------------------------------------------------

@Composable
private fun SwfKeyButton(
    button: SwfButton,
    label: String,
    key: String,
    editMode: Boolean,
    isSelected: Boolean,
    buttonColor: Color,
    maxW: Float,
    maxH: Float,
    density: androidx.compose.ui.unit.Density,
    onPress: (String) -> Unit,
    onRelease: (String) -> Unit,
    onPositionChange: (Float, Float) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val sizeDp = button.sizeDp.dp

    val xOffset = with(density) { (button.xPct / 100f * maxW).toDp() } - sizeDp / 2
    val yOffset = with(density) { (button.yPct / 100f * maxH).toDp() } - sizeDp / 2

    Box(
        modifier = Modifier
            .offset(x = xOffset, y = yOffset)
            .size(sizeDp)
            .clip(CircleShape)
            .background(
                when {
                    editMode && isSelected -> AccentColor.copy(alpha = 0.4f)
                    editMode -> EditModeBg
                    pressed -> Color.White.copy(alpha = 0.85f)
                    else -> buttonColor.copy(alpha = 0.72f)
                }
            )
            .pointerInput(button.id, editMode) {
                if (editMode) {
                    detectDragGestures(
                        onDragStart = { onSelect() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val newX = button.xPct + (dragAmount.x / maxW * 100f)
                            val newY = button.yPct + (dragAmount.y / maxH * 100f)
                            onPositionChange(
                                newX.coerceIn(2f, 98f),
                                newY.coerceIn(5f, 95f)
                            )
                        }
                    )
                }
            }
            .pointerInput(button.id, editMode) {
                if (!editMode) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            onPress(key)
                            tryAwaitRelease()
                            pressed = false
                            onRelease(key)
                        }
                    )
                }
            }
            .clickable(enabled = editMode) { onSelect() },
        contentAlignment = Alignment.Center
    ) {
        if (editMode && button.id !in SwfPadConfig.FIXED_IDS) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(DeleteColor)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Inner highlight ring (mirrors 3.3 ActionButtonView inner glow)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f
            drawCircle(
                color = Color.White.copy(alpha = 0.3f),
                radius = r * 0.82f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = r * 0.12f)
            )
        }

        val fontSize = (button.sizeDp / 3.5f).coerceIn(8f, 20f)
        Text(
            text = label,
            color = BtnText,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Edit-mode toolbar
// ---------------------------------------------------------------------------

@Composable
private fun EditToolbar(
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    hasSelection: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(top = 56.dp, start = 16.dp, end = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1E2A3A).copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(Icons.Rounded.Add, "添加", AccentColor, onAdd)
        ToolButton(
            Icons.Rounded.Delete, "删除",
            if (hasSelection) DeleteColor else Color.Gray,
            onDelete
        )
        ToolButton(Icons.Rounded.Refresh, "重置", Color(0xFFFFD66B), onReset)
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------
// Size slider
// ---------------------------------------------------------------------------

@Composable
private fun SizeSlider(
    size: Float,
    onSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2A3A).copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("大小", color = Color(0xFF8899AA), fontSize = 11.sp)
        Spacer(Modifier.size(8.dp))
        Slider(
            value = size,
            onValueChange = onSizeChange,
            valueRange = 28f..80f,
            colors = SliderDefaults.colors(
                thumbColor = AccentColor,
                activeTrackColor = AccentColor.copy(alpha = 0.7f)
            ),
            modifier = Modifier.weight(1f)
        )
        Text("${size.toInt()}dp", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(36.dp))
    }
}

// ---------------------------------------------------------------------------
// Add-button dialog
// ---------------------------------------------------------------------------

@Composable
private fun AddButtonDialog(
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加按键") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.lowercase() },
                    label = { Text("按键 (如 a, b, 1, shift)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val l = if (label.isBlank()) key.uppercase() else label
                    val k = if (key.isBlank()) label.lowercase() else key
                    if (k.isNotBlank()) onAdd(l, k)
                }
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
