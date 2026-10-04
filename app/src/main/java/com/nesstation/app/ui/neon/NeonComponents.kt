package com.nesstation.app.ui.neon

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalPlay
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MobileFriendly
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform

/** 平台 → 专属图标（与 FSD 主页同图标语义，保证跨风格辨识一致）。 */
fun neonPlatformIcon(p: GamePlatform): ImageVector = when (p) {
    GamePlatform.NES -> Icons.Rounded.Gamepad
    GamePlatform.SFC -> Icons.Rounded.VideogameAsset
    GamePlatform.GB -> Icons.Rounded.Smartphone
    GamePlatform.GBA -> Icons.Rounded.MobileFriendly
    GamePlatform.MD -> Icons.Rounded.Computer
    GamePlatform.PCE -> Icons.Rounded.Radio
    GamePlatform.PSX -> Icons.Rounded.Album
    GamePlatform.PS2 -> Icons.Rounded.Memory
    GamePlatform.NDS -> Icons.Rounded.MenuBook
    GamePlatform.ARCADE -> Icons.Rounded.LocalPlay
    GamePlatform.DOS -> Icons.Rounded.Terminal
    GamePlatform.JAVA -> Icons.Rounded.LocalCafe
    GamePlatform.DC -> Icons.Rounded.SportsEsports
    GamePlatform.N3DS -> Icons.Rounded.TabletAndroid
    GamePlatform.NGCWII -> Icons.Rounded.Tv
}

/** 手柄按键提示胶囊（底部提示栏单元）：圆形字母 + 说明文字。 */
@Composable
fun NeonHintPill(letter: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color.copy(alpha = 0.16f), CircleShape)
                .border(1.dp, color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(letter, color = color, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Text(label, color = Neon.Text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** 底部手柄按键提示栏（图3 参考稿：胶囊状 A/B/X/Y 映射提示）。 */
@Composable
fun NeonHintsBar(
    hints: List<Triple<String, String, Color>>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color(0xCC05070E))
            .border(1.dp, Neon.Line, neonChamfer(0.5f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        hints.forEach { (letter, label, color) ->
            NeonHintPill(letter, label, color)
        }
    }
}

/**
 * Neon 封面图 —— 与 FsdGameCover 同源的位图解析链
 * （customIconPath → coverPath → GameIconExtractor 回退生成），
 * 共享 LruCache 防重复解码；仅渲染图片本体，外框由调用方按风格自绘。
 */
@Composable
fun NeonCoverImage(
    game: GameEntry,
    cache: android.util.LruCache<String, android.graphics.Bitmap>
) {
    val context = LocalContext.current
    val cacheKey = "${game.id}|${game.customIconPath ?: ""}|${game.coverPath ?: ""}"
    val coverStamp = game.coverPath?.let {
        try { java.io.File(it).lastModified() } catch (_: Throwable) { 0L }
    } ?: 0L
    val fullKey = "$cacheKey|$coverStamp"
    val bmp = remember(fullKey) {
        cache.get(fullKey) ?: run {
            var b: android.graphics.Bitmap? = null
            val path = try {
                com.nesstation.app.core.storage.GameIconExtractor.resolveIconPath(context, game)
            } catch (_: Exception) { null }
            if (path != null) {
                try {
                    b = android.graphics.BitmapFactory.decodeFile(path)
                } catch (_: Exception) { b = null }
            }
            if (b == null) {
                try {
                    b = com.nesstation.app.core.storage.GameIconExtractor
                        .generateFallbackCover(game, 320, 420)
                } catch (_: Exception) {
                    b = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
                }
            }
            cache.put(fullKey, b!!)
            b!!
        }
    }
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = game.customTitle?.takeIf { it.isNotBlank() } ?: game.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}
