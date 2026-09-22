# Keep J2ME classes that are loaded via reflection/DexClassLoader
-keep class javax.microedition.** { *; }
-keep class com.nokia.mid.** { *; }
-keep class com.motorola.** { *; }
-keep class com.samsung.** { *; }
-keep class com.sonyericsson.** { *; }
-keep class com.siemens.** { *; }
-keep class com.kddi.** { *; }
-keep class com.vodafone.** { *; }
-keep class com.sprintpcs.** { *; }
-keep class com.mascotcapsule.** { *; }
-keep class ru.woesss.j2me.** { *; }
-keep class ru.playsoftware.j2meloader.** { *; }
-keep class com.android.dx.** { *; }

# Keep BuildConfig
-keep class com.nesstation.app.BuildConfig { *; }

# DC (Dreamcast/NAOMI)：已改为 libretro Flycast 核心集成（DcNative），
# 无按名绑定的 com.flycast.emulator.* 宿主类，无需 keep。
