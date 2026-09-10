package com.nesstation.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyColumn
import com.nesstation.app.core.model.GamePlatform
import java.io.File

/**
 * Frosted-glass tile that highlights on focus (D-pad / touch).
 * Used both on phone home and Android TV launcher.
 */
@Composable
fun GlassCard(
    title: String,
    subtitle: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    icon: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "scale")
    val borderAlpha by animateFloatAsState(if (focused) 0.95f else 0.4f, label = "border")

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.30f)
                    )
                )
            )
            .border(
                width = 2.dp,
                color = accent.copy(alpha = borderAlpha),
                shape = RoundedCornerShape(22.dp)
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            if (icon != null) icon()
            Text(
                text = title,
                color = Color(0xFF1E2A3A),
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color(0xFF4A5568),
                    fontSize = 13.sp
                )
            }
        }
        if (focused) {
            // glow
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { this.shadowElevation = 24f }
                    .border(2.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
            )
        }
        @Suppress("UNUSED_EXPRESSION") pressed
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameCard(
    title: String,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    coverPath: String? = null,
    platform: GamePlatform = GamePlatform.NES,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "card-scale")

    // Load cover bitmap:
    // 1. Try the provided coverPath (custom icon, Java icon.png, etc.)
    // 2. If that fails, generate a title-based fallback cover with the game's
    //    accent color and initials. This makes the library look much nicer
    //    than a generic gamepad icon for every game without a custom cover.
    //    Implements the user's request to show "built-in" icons — for Java
    //    games this is the MIDlet-Icon, for other platforms we generate a
    //    title-based cover as a visual placeholder.
    val coverBitmap = remember(coverPath, title, accent) {
        var bmp: android.graphics.Bitmap? = null
        if (coverPath != null) {
            val file = File(coverPath)
            if (file.exists()) {
                try {
                    bmp = BitmapFactory.decodeFile(coverPath)
                } catch (_: Exception) {
                    bmp = null
                }
            }
        }
        // Fallback: generate a title-based cover if no icon available.
        // This produces a colored tile with the game's initials, which is
        // much more visually appealing than a generic gamepad icon.
        if (bmp == null) {
            try {
                val entry = com.nesstation.app.core.model.GameEntry(
                    id = title,
                    title = title,
                    accent = accent,
                    platform = platform
                )
                bmp = com.nesstation.app.core.storage.GameIconExtractor
                    .generateFallbackCover(entry)
            } catch (_: Exception) {
                bmp = null
            }
        }
        bmp
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.75f))
            .border(
                width = if (focused) 3.dp else 1.5.dp,
                color = if (focused) accent else accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp)
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(85.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(
                                accent.copy(alpha = 0.85f),
                                accent.copy(alpha = 0.55f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.SportsEsports,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                // Engine watermark badge — shows platform identifier (FC, SFC,
                // GBA, GB, Java) at the top-left corner of the card, ON TOP of
                // the custom icon so it's always visible. Each engine has its
                // own colour for quick visual identification.
                PlatformBadge(
                    platform,
                    modifier = Modifier.align(Alignment.TopStart)
                )
            }
            Text(
                text = title,
                color = Color(0xFF1E2A3A),
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 2.dp, end = 2.dp)
            )
        }
    }
}

/**
 * Engine watermark badge for a game platform.
 * Shows a short label (FC, SFC, GBA, GB, Java) with a distinctive
 * background colour, positioned at the top-left of the card cover.
 * Drawn ON TOP of the custom icon so it's always visible.
 */
@Composable
private fun PlatformBadge(platform: GamePlatform, modifier: Modifier = Modifier) {
    val (label, bgColor) = when (platform) {
        GamePlatform.NES    -> "FC"   to Color(0xFFD32F2F)   // red — Famicom
        GamePlatform.SFC    -> "SFC"  to Color(0xFF1565C0)   // blue — Super Famicom
        GamePlatform.GB     -> "GB"   to Color(0xFF558B2F)   // green — Game Boy/GBC
        GamePlatform.GBA    -> "GBA"  to Color(0xFF6A1B9A)   // purple — GBA
        GamePlatform.DOS    -> "DOS"  to Color(0xFF37474F)   // blue-grey — DOS/PC
        GamePlatform.ARCADE -> "ARC"  to Color(0xFFF57C00)   // amber — Arcade (FBNeo)
        GamePlatform.MD     -> "MD"   to Color(0xFF00695C)   // teal — Mega Drive / SEGA
        GamePlatform.PCE    -> "PCE"  to Color(0xFFAD1457)   // pink — PC-Engine / TurboGrafx-16
        GamePlatform.NDS    -> "NDS"  to Color(0xFF4527A0)   // deep purple — Nintendo DS
        GamePlatform.PSX    -> "PSX"  to Color(0xFF5D4037)   // brown — PlayStation 1
        GamePlatform.PS2    -> "PS2"  to Color(0xFF37474F)   // blue-grey — PlayStation 2 (PCEE2/PCSX2)
        GamePlatform.DC     -> "DC"   to Color(0xFFE65100)   // deep orange — Sega Dreamcast (Flycast)
        GamePlatform.JAVA   -> "Java" to Color(0xFF4E342E)   // brown — J2ME
    }
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor.copy(alpha = 0.88f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
