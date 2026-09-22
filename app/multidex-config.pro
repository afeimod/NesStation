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

# ─── Flycast (Dreamcast/NAOMI core)：JNI 按名绑定，multidex 调试构建同样 keep ───
-keep class com.flycast.emulator.** { *; }
-keep class com.google.androidgamesdk.** { *; }
