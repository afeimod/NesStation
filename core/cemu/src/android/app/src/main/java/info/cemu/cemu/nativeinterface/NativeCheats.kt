package info.cemu.cemu.nativeinterface

object NativeCheats {
    @JvmStatic
    external fun reloadCheats(): Int

    @JvmStatic
    external fun getCheatCount(): Int

    @JvmStatic
    external fun getCheatName(index: Int): String?

    @JvmStatic
    external fun getCheatComment(index: Int): String?

    @JvmStatic
    external fun isCheatEnabled(index: Int): Boolean

    @JvmStatic
    external fun setCheatEnabled(index: Int, enabled: Boolean)

    @JvmStatic
    external fun getCurrentTitleId(): Long
}
