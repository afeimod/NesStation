package com.nesstation.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.sin

/**
 * Pixel-art landscape backdrop à la Pico-8 — soft sky, drifting clouds,
 * pixel grass and floating triangle/square sparkles. Used as the wallpaper
 * behind the home screen tiles.
 */
@Composable
fun PixelBackdrop(
    modifier: Modifier = Modifier,
    timeMs: Long = 0L
) {
    val state = remember { BackdropState.seed() }
    Canvas(modifier = modifier.fillMaxSize()) {
        drawSky()
        drawClouds(state, timeMs)
        drawSparkles(state, timeMs)
        drawSea()
        drawGrass()
    }
}

private data class Cloud(val x: Float, val y: Float, val scale: Float, val drift: Float)
private data class Sparkle(val x: Float, val y: Float, val size: Float, val phase: Float)

private class BackdropState(val clouds: List<Cloud>, val sparkles: List<Sparkle>) {
    companion object {
        fun seed(): BackdropState = BackdropState(
            clouds = listOf(
                Cloud(0.08f, 0.18f, 1.4f, 0.000_020f),
                Cloud(0.32f, 0.10f, 1.0f, 0.000_014f),
                Cloud(0.62f, 0.22f, 1.7f, 0.000_010f),
                Cloud(0.86f, 0.08f, 1.2f, 0.000_018f)
            ),
            sparkles = listOf(
                Sparkle(0.10f, 0.12f, 14f, 0.6f),
                Sparkle(0.25f, 0.06f, 18f, 0.4f),
                Sparkle(0.45f, 0.14f, 12f, 0.8f),
                Sparkle(0.78f, 0.05f, 16f, 0.5f),
                Sparkle(0.92f, 0.16f, 14f, 0.7f)
            )
        )
    }
}

private val SkyTop = Color(0xFFB7D7F2)
private val SkyBot = Color(0xFFC8E2F6)
private val CloudColor = Color(0xFFF5F7FB)
private val CloudShadow = Color(0xFFCAD3DF)
private val SeaTop = Color(0xFF4F8AC4)
private val SeaBot = Color(0xFF2F5C8C)
private val GrassTop = Color(0xFF7BB36A)
private val GrassDeep = Color(0xFF4F8C4A)
private val GrassSpike = Color(0xFF6BA45A)
private val SparkleColor = Color(0x80FFFFFF)

private fun DrawScope.drawSky() {
    drawRect(
        brush = Brush.verticalGradient(listOf(SkyTop, SkyBot))
    )
}

private fun DrawScope.drawClouds(s: BackdropState, timeMs: Long) {
    val w = size.width
    s.clouds.forEach { c ->
        val dx = (timeMs * c.drift) % w
        drawCloud(c.x * w + dx, c.y * size.height, c.scale)
    }
}

private fun DrawScope.drawCloud(cx: Float, cy: Float, scale: Float) {
    val s = scale
    val unit = 18f * s
    // 4x3 pixel art cloud (8-bit style).
    val shape = arrayOf(
        "  ####  ",
        " ###### ",
        "########",
        " ###### ",
        "  ####  "
    )
    shape.forEachIndexed { row, line ->
        line.forEachIndexed { col, ch ->
            if (ch == '#') {
                val px = cx + (col - 4) * unit
                val py = cy + (row - 2) * unit
                drawRect(
                    color = if (row >= 3) CloudShadow else CloudColor,
                    topLeft = Offset(px, py),
                    size = Size(unit, unit)
                )
            }
        }
    }
}

private fun DrawScope.drawSparkles(s: BackdropState, timeMs: Long) {
    val w = size.width
    val h = size.height
    s.sparkles.forEach { sp ->
        val blink = (sin((timeMs / 1000.0 + sp.phase) * 2.0).toFloat() + 1f) / 2f
        val alpha = (60 + (blink * 195).toInt()).coerceIn(60, 255) / 255f
        val cx = sp.x * w
        val cy = sp.y * h
        val sz = sp.size
        // hollow circle
        drawCircle(
            color = SparkleColor.copy(alpha = alpha),
            radius = sz / 2,
            center = Offset(cx, cy),
            style = Stroke(width = 2f)
        )
        // cross
        drawLine(SparkleColor.copy(alpha = alpha), Offset(cx - sz, cy), Offset(cx + sz, cy), 2f)
        drawLine(SparkleColor.copy(alpha = alpha), Offset(cx, cy - sz), Offset(cx, cy + sz), 2f)
    }
}

private fun DrawScope.drawSea() {
    val seaTop = size.height * 0.74f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(SeaTop, SeaBot),
            startY = seaTop,
            endY = size.height
        ),
        topLeft = Offset(0f, seaTop),
        size = Size(size.width, size.height - seaTop)
    )
    // pixel wave band
    val bandY = seaTop + 4f
    val step = 12f
    var x = 0f
    while (x < size.width) {
        drawRect(
            color = Color(0x66FFFFFF),
            topLeft = Offset(x, bandY),
            size = Size(step, 2f)
        )
        x += step * 2
    }
}

private fun DrawScope.drawGrass() {
    val baseY = size.height * 0.84f
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(GrassTop, GrassDeep),
            startY = baseY,
            endY = size.height
        ),
        topLeft = Offset(0f, baseY),
        size = Size(size.width, size.height - baseY)
    )
    // spikes
    val step = 10f
    var x = 0f
    while (x < size.width) {
        drawRect(
            color = GrassSpike,
            topLeft = Offset(x, baseY - 3f),
            size = Size(2f, 6f)
        )
        x += step
    }
}
