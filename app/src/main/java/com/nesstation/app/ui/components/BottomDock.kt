package com.nesstation.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact bottom dock — small centered pill, matches reference design.
 * Selected item gets a gradient circle highlight. Minimal footprint
 * so it doesn't block game content above.
 */
@Composable
fun BottomDock(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        DockItem("游戏库", Icons.Rounded.GridView),
        DockItem("对战平台", Icons.Rounded.SportsEsports),
        DockItem("浏览器", Icons.Rounded.Public),
        DockItem("SWF", Icons.Rounded.PlayArrow),
        DockItem("设置", Icons.Rounded.Settings),
        DockItem("关于", Icons.AutoMirrored.Rounded.HelpOutline),
        DockItem("退出", Icons.AutoMirrored.Rounded.Logout)
    )

    var selected by rememberSaveable { mutableIntStateOf(selectedIndex) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.30f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { idx, item ->
            DockItemView(
                item = item,
                selected = idx == selected,
                highlight = item.label == "对战平台",
                onClick = {
                    selected = idx
                    onSelect(idx)
                }
            )
        }
    }
}

/**
 * Vertical dock for portrait mode — displays dock items vertically on the left side.
 * Same items as BottomDock but arranged in a Column.
 */
@Composable
fun VerticalDock(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        DockItem("游戏库", Icons.Rounded.GridView),
        DockItem("对战平台", Icons.Rounded.SportsEsports),
        DockItem("浏览器", Icons.Rounded.Public),
        DockItem("SWF", Icons.Rounded.PlayArrow),
        DockItem("设置", Icons.Rounded.Settings),
        DockItem("说明", Icons.AutoMirrored.Rounded.HelpOutline),
        DockItem("退出", Icons.AutoMirrored.Rounded.Logout)
    )

    var selected by rememberSaveable { mutableIntStateOf(selectedIndex) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.30f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items.forEachIndexed { idx, item ->
            VerticalDockItemView(
                item = item,
                selected = idx == selected,
                highlight = item.label == "对战平台",
                onClick = {
                    selected = idx
                    onSelect(idx)
                }
            )
        }
    }
}

private data class DockItem(val label: String, val icon: ImageVector)

@Composable
private fun DockItemView(item: DockItem, selected: Boolean, highlight: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = selected || pressed
    val scale by animateFloatAsState(if (active) 1.1f else 1f, label = "dock-scale")

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (highlight) 32.dp else 28.dp)
                .clip(CircleShape)
                .background(
                    when {
                        active -> Brush.radialGradient(
                            listOf(Color(0xFF8A7BFF), Color(0xFF4F8AC4))
                        )
                        highlight -> Brush.radialGradient(
                            listOf(Color(0xFF8A7BFF).copy(alpha = 0.45f), Color(0xFF4F8AC4).copy(alpha = 0.20f))
                        )
                        else -> Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                        )
                    }
                )
                .border(
                    width = if (active || highlight) 1.5.dp else 0.dp,
                    color = if (active) Color(0xFF8A7BFF).copy(alpha = 0.7f)
                        else if (highlight) Color(0xFF8A7BFF).copy(alpha = 0.5f)
                        else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = if (active) Color.White else if (highlight) Color(0xFF6F5FE0) else Color(0xFF3A4A5C),
                modifier = Modifier.size(if (highlight) 18.dp else 16.dp)
            )
        }
    }
}

@Composable
private fun VerticalDockItemView(item: DockItem, selected: Boolean, highlight: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val active = selected || pressed
    val scale by animateFloatAsState(if (active) 1.08f else 1f, label = "vdock-scale")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(if (highlight) 30.dp else 26.dp)
                .clip(CircleShape)
                .background(
                    when {
                        active -> Brush.radialGradient(
                            listOf(Color(0xFF8A7BFF), Color(0xFF4F8AC4))
                        )
                        highlight -> Brush.radialGradient(
                            listOf(Color(0xFF8A7BFF).copy(alpha = 0.45f), Color(0xFF4F8AC4).copy(alpha = 0.20f))
                        )
                        else -> Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.15f), Color.Transparent)
                        )
                    }
                )
                .border(
                    width = if (active || highlight) 1.5.dp else 0.dp,
                    color = if (active) Color(0xFF8A7BFF).copy(alpha = 0.7f)
                        else if (highlight) Color(0xFF8A7BFF).copy(alpha = 0.5f)
                        else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = if (active) Color.White else if (highlight) Color(0xFF6F5FE0) else Color(0xFF3A4A5C),
                modifier = Modifier.size(if (highlight) 16.dp else 14.dp)
            )
        }
        Text(
            text = item.label,
            color = if (active) Color(0xFF8A7BFF) else if (highlight) Color(0xFF6F5FE0) else Color(0xFF3A4A5C),
            fontSize = 8.sp,
            maxLines = 1
        )
    }
}
