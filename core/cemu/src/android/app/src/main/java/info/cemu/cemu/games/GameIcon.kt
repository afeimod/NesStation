package info.cemu.cemu.games

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import info.cemu.cemu.R
import info.cemu.cemu.nativeinterface.NativeGameTitles

private fun Modifier.iconBorder(borderColor: Color) =
    border(
        width = Dp.Hairline,
        color = borderColor,
        shape = RoundedCornerShape(8.dp)
    ).clip(RoundedCornerShape(8.dp))

@Composable
fun GameIcon(
    game: NativeGameTitles.Game,
    modifier: Modifier,
    coverVersion: Int = 0,
) {
    val borderColor = MaterialTheme.colorScheme.onSurface

    // custom cover images set by the user take precedence over the low-res
    // title icon; coverVersion is bumped whenever a cover is imported/removed
    // so all icons re-compose with fresh data
    val customCover = remember(game.titleId, coverVersion) {
        GameCoverStore.loadCover(game.titleId)
    }

    if (customCover != null) {
        Image(
            modifier = modifier.iconBorder(borderColor),
            bitmap = customCover,
            contentDescription = null
        )
    } else if (game.icon != null) {
        Image(
            modifier = modifier.iconBorder(borderColor),
            bitmap = game.icon!!,
            contentDescription = null
        )
    } else {
        Icon(
            modifier = modifier.iconBorder(borderColor),
            painter = painterResource(R.drawable.ic_question_mark),
            contentDescription = null
        )
    }
}
