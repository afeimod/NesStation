package com.nesstation.app.ui.home

import androidx.compose.ui.graphics.Color
import com.nesstation.app.core.model.GameEntry

object HomeSamples {
    val recents: List<GameEntry> = listOf(
        GameEntry("1", "超级马里奥", "Super Mario Bros", Color(0xFFE74C3C)),
        GameEntry("2", "魂斗罗", "Contra", Color(0xFF27AE60)),
        GameEntry("3", "冒险岛", "Adventure Island", Color(0xFF3498DB)),
        GameEntry("4", "恶魔城", "Castlevania", Color(0xFF8E44AD)),
        GameEntry("5", "忍者神龟", "TMNT", Color(0xFFE67E22)),
        GameEntry("6", "最终幻想", "Final Fantasy", Color(0xFF1ABC9C))
    )
    val featured: List<GameEntry> = listOf(
        GameEntry("f1", "塞尔达传说", "Zelda", Color(0xFF2ECC71)),
        GameEntry("f2", "银河战士", "Metroid", Color(0xFF9B59B6)),
        GameEntry("f3", "洛克人", "Mega Man", Color(0xFF3498DB)),
        GameEntry("f4", "双截龙", "Double Dragon", Color(0xFFE74C3C)),
        GameEntry("f5", "热血系列", "Kunio-kun", Color(0xFFE67E22)),
        GameEntry("f6", "吃豆人", "Pac-Man", Color(0xFFF1C40F))
    )
}
