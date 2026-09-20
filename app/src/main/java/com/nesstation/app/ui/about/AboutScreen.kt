package com.nesstation.app.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.ui.components.AppBackgroundState

// ---------------------------------------------------------------------------
// NesStation — dark, retro "About" screen.
// Palette is intentionally self-contained so the page keeps its polished
// look regardless of the app's Material color scheme.
// ---------------------------------------------------------------------------

private val BgColor = Color(0xFF0D1117)
private val CardColor = Color(0xFF1E2A3A)
private val PrimaryText = Color(0xFFE2E8F0)
private val SecondaryText = Color(0xFF8899AA)
private val Accent = Color(0xFF8A7BFF)
private val SectionHeader = Color(0xFFFFD66B)

/**
 * A full-screen "About" page for NesStation.
 *
 * @param onBack invoked when the user taps the back arrow in the top-left.
 * @param modifier optional modifier applied to the root of the screen.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (AppBackgroundState.active) Color.Transparent else BgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // ---- Top bar (back arrow + title) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = PrimaryText
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "关于",
                    color = PrimaryText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ---- Scrollable body ----
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))

                // ===== App logo / name section =====
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Accent, Color(0xFF5B4BFF))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "N",
                        color = Color.White,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "NesStation",
                    color = PrimaryText,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "为复古而生",
                    color = Accent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(14.dp))

                // Version pill: "版本 3.6.3"
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = 0.16f))
                        .border(
                            width = 1.dp,
                            color = Accent.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "版本 3.6.3",
                        color = PrimaryText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(28.dp))

                // ===== Features =====
                InfoCard(title = "功能特性") {
                    BulletItem("NES/Famicom 模拟 (FCEUmm core)")
                    BulletItem("FDS 磁盘系统支持")
                    BulletItem("SWF/Flash 游戏支持 (Ruffle)")
                    BulletItem("多种视频滤镜 (XBR, HQ2X, HQ4X, 扫描线, CRT)")
                    BulletItem("虚拟手柄自定义")
                    BulletItem("存档/读档")
                }

                Spacer(Modifier.height(16.dp))

                // ===== Credits =====
                InfoCard(title = "致谢") {
                    BulletItem("FCEUmm core by libretro")
                    BulletItem("Ruffle by ruffle-rs")
                    BulletItem("HQX by Maxim Stepin")
                    BulletItem("2xBR by Hyllian")
                }

                Spacer(Modifier.height(16.dp))

                // ===== Open source license notice =====
                InfoCard(title = "开源许可") {
                    Text(
                        text = "NesStation 为开源软件，基于 MIT 协议发布。" +
                            "所集成的第三方核心与组件（FCEUmm、Ruffle、HQX、2xBR 等）" +
                            "均保留其原始版权与许可声明，完整许可文本请参阅各项目源码仓库" +
                            "及应用内的 LICENSE 文件。",
                        color = SecondaryText,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }

                Spacer(Modifier.height(28.dp))

                // ===== Thank you =====
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Accent.copy(alpha = 0.6f))
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "感谢使用",
                    color = SectionHeader,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/**
 * A rounded dark card with a gold section header and arbitrary content below.
 */
@Composable
private fun InfoCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardColor)
            .padding(20.dp)
    ) {
        Text(
            text = title,
            color = SectionHeader,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * A single bulleted row: an accent dot followed by a line of text.
 */
@Composable
private fun BulletItem(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(Accent)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            color = PrimaryText,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}
