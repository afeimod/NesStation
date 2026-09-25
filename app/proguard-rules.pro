# ─── Keep the Application class and its companion ───────────────────────────
-keep class com.nesstation.app.NesApp { *; }
-keep class com.nesstation.app.NesApp$Companion { *; }

# ─── JNI bridge: keep native methods + the object that declares them ──────
-keep class com.nesstation.app.core.jni.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ─── SDL bridge: libemucore_4k.so 的 JNI_OnLoad 通过 FindClass("org/libsdl/app/SDLActivity")
#     按名称查找 SDL 桥接类（R8 看不到这个原生引用）。不 keep 会在 release 构建被裁剪，
#     System.loadLibrary("emucore_4k") 时抛 ClassNotFoundException 崩溃。
-keep class org.libsdl.app.** { *; }

# ─── NativeApp JNI 回调方法: 核心在 initialize() 里用 GetStaticMethodID 按名字
#     查找 vmSetPaused/onPadRumble/playSound/openContentUri/createDirectoryPath/
#     createFilePath 这 6 个静态方法(native-lib.cpp:459-461, 2601-2664)，它们只被
#     原生字符串引用、无 Kotlin 调用方，R8 看不到引用会当作死代码裁剪。
#     不 keep 时 release 构建抛 NoSuchMethodError → ART SIGABRT（PS2 启动闪退）。
#     注意 -keepclasseswithmembernames 只保护 native 方法，保护不了这些普通静态方法。
-keep class kr.co.iefriends.pcsx2.NativeApp { *; }

# ─── Engine + its companion (singleton pattern) ───────────────────────────
-keep class com.nesstation.app.core.engine.NesEngine { *; }
-keep class com.nesstation.app.core.engine.NesEngine$Companion { *; }

# ─── Storage layer ─────────────────────────────────────────────────────────
-keep class com.nesstation.app.core.storage.** { *; }
# 闪退根因: R8 在 release 优化阶段生成了 ART 校验器不接受的字节码
# (java.lang.VerifyError: Verifier rejected class e1.b0: void e1.b0.invoke()
#  [0x69A] Rejecting invocation, expected 8 argument registers,
#  method signature has 9 or more)。
# 之前的修复只写了 -optimizations !method/inlining, 但 AGP 8.0+ release
# 默认开启 R8 full mode, 该模式下 -dontoptimize / -optimizations 等 ProGuard
# 优化开关会被 R8 忽略, 所以始终无法生效(反复闪退)。
# 处理: 已在 gradle.properties 关闭 full mode (android.enableR8.fullMode=false),
# 这里再全局禁用优化(保留裁剪与混淆), 彻底规避该 VerifyError。
-dontoptimize

# ─── J2ME-Loader: keep all emulator classes (prevent R8 stripping) ────────
-keep class javax.** { *; }
-keep class com.kddi.** { *; }
-keep class com.siemens.mp.** { *; }
-keep class com.samsung.util.** { *; }
-keep class com.sonyericsson.accelerometer.** { *; }
-keep class com.sprintpcs.media.** { *; }
-keep class com.mascotcapsule.micro3d.v3.** { *; }
-keep class com.jblend.graphics.j3d.* { *; }
-keep class com.motorola.** { *; }
-keep class com.nokia.mid.** { *; }
-keep class com.sun.midp.midlet.** { *; }
-keep class com.vodafone.** { *; }
-keep class mmpp.media.** { *; }
-keep class org.microemu.** { *; }
-keep class ru.playsoftware.j2meloader.** { *; }
-keep class ru.woesss.** { *; }
-keep class com.nesstation.app.BuildConfig { *; }
-keep class com.arthenica.mobileffmpeg.** { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class ru.playsoftware.j2meloader.crashes.models.* { *; }
# Keep J2ME data binding classes
-keep class com.nesstation.app.databinding.** { *; }
# Keep J2ME activities (also declared in manifest, but be explicit)
-keep class ru.playsoftware.j2meloader.config.ConfigActivity { *; }
-keep class ru.playsoftware.j2meloader.config.ProfilesActivity { *; }
-keep class ru.playsoftware.j2meloader.settings.SettingsActivity { *; }
-keep class ru.playsoftware.j2meloader.settings.KeyMapperActivity { *; }
-keep class javax.microedition.shell.MicroActivity { *; }
-keep class ru.playsoftware.j2meloader.filepicker.** { *; }
# Keep enum methods
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Room ──────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * implements androidx.room.Dao { *; }
-dontwarn androidx.room.paging.**

# ─── DataStore ─────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ─── Keep Kotlin metadata so reflection-based libs work ───────────────────
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# ─── Compose / Lifecycle (R8 sometimes over-strips) ───────────────────────
-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.**
# javax.annotation
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedOptions
-dontwarn javax.annotation.processing.Processor

# Gson
### The following rules are needed for R8 in "full mode" which only adheres to `-keepattribtues` if
### the corresponding class or field is matches by a `-keep` rule as well, see
### https://r8.googlesource.com/r8/+/refs/heads/master/compatibility-faq.md#r8-full-mode

# Keep class TypeToken (respectively its generic signature)
-keep class com.google.gson.reflect.TypeToken { *; }

# Keep any (anonymous) classes extending TypeToken
-keep class * extends com.google.gson.reflect.TypeToken

# Keep classes with @JsonAdapter annotation
-keep @com.google.gson.annotations.JsonAdapter class *

# Keep fields with @SerializedName annotation, but allow obfuscation of their names
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Keep fields with any other Gson annotation
-keepclassmembers class * {
  @com.google.gson.annotations.Expose <fields>;
  @com.google.gson.annotations.JsonAdapter <fields>;
  @com.google.gson.annotations.Since <fields>;
  @com.google.gson.annotations.Until <fields>;
}

# Keep no-args constructor of classes which can be used with @JsonAdapter
# By default their no-args constructor is invoked to create an adapter instance
-keep class * extends com.google.gson.TypeAdapter {
  <init>();
}
-keep class * implements com.google.gson.TypeAdapterFactory {
  <init>();
}
-keep class * implements com.google.gson.JsonSerializer {
  <init>();
}
-keep class * implements com.google.gson.JsonDeserializer {
  <init>();
}

# ─── DraStic（激烈）NDS 核心 JNI 契约 ─────────────────────────────────────
# libdrastic*.so 通过 JNI 符号名（Java_com_dsemu_drastic_DraSticJNI_*）与
# GetStaticMethodID/GetFieldID 字符串名（DraSticPathCache.open/remove/rename、
# NativePathHandle.filePath/fileFd/fileName）查找这些类 —— R8 看不到原生
# 引用，不 keep 会在 release 构建被裁剪/混淆，运行时抛 NoSuchMethodError /
# UnsatisfiedLinkError（NDS 选 DraStic 核心启动闪退）。整个包 keep。
-keep class com.dsemu.drastic.** { *; }

# DC (Dreamcast/NAOMI) 已改为 libretro Flycast 核心集成（libdccore.so +
# libflycast_libretro_android.so，进程内引擎 DcNative —— 与 PsxNative 等
# 同一 JNI 模式），无 com.flycast.emulator.* 宿主类，无需 keep。
# 历史注：旧版此处 keep 过 com.flycast.emulator.** /
# com.google.androidgamesdk.**（Flycast 独立核心的 swappy 胶水）与
# -dontwarn org.ietf.jgss.**（httpclient5 的 Kerberos 依赖），均已随独立
# 集成移除。

# ─── Azahar（3DS）核心 JNI 契约 ───────────────────────────────────────────
# libazahar.so（= 上游 libcitra-android.so，AzaharPlus APK 提取）通过 JNI 符号名
# （Java_org_citra_citra_emu_NativeLibrary_*）与 JNI_OnLoad 时的
# FindClass/GetStaticMethodID/GetFieldID 字符串查找 org.citra.citra_emu.** 下的
# 类与回调方法（onCoreError/exitEmulationActivity/createFile/...、Cheat.mPointer、
# GameInfo.pointer、DiskShaderCacheProgress.loadProgress、SoftwareKeyboard.execute、
# MiiSelector.execute、StillImageCameraHelper.openFilePicker 等）—— R8 看不到
# 这些原生引用，不 keep 会在 release 构建被裁剪/混淆，库加载即失败。
# 整个包 keep。
-keep class org.citra.citra_emu.** { *; }

# ─── Ishiiruka（NGC/WII）核心 JNI 契约 ────────────────────────────────────
# libishiiruka.so（= 上游 libmain.so，Ishiruka APK 提取）通过 JNI 符号名
# （Java_org_dolphinemu_dolphinemu_*）与 JNI_OnLoad / Run 懒初始化路径的
# FindClass/GetStaticMethodID/GetStaticFieldID/GetFieldID/GetMethodID 字符串
# 查找 org.dolphinemu.dolphinemu 下的契约类：
#   - NativeLibrary.displayAlertMsg (String,String,boolean)boolean /
#     rumble (int,double) / updateWindowSize (int,int)；
#   - model/IniFile.mPointer:J 字段；
#   - model/GameFile.mPointer:J 字段 + <init>(J)V 构造器；
#   - utils/Java_WiimoteAdapter.wiimote_payload:[[B 静态字段 + Input/Output；
#   - utils/Java_GCAdapter / utils/DirectoryInitialization native 方法。
# ⚠ 历史教训（启动闪退第二根因）：这些类/成员只被原生字符串引用，
#   R8 看不到任何 Java 调用方 —— 旧规则只 keep 了 org.dolphinemu.ishiiruka.**
#   门面包，dolphinemu 契约包完全没 keep，release 构建会被整体裁剪/混淆，
#   JNI_OnLoad 抛 NoSuchMethodError → pending exception → SIGABRT。
# 必须整个包 keep。
-keep class org.dolphinemu.dolphinemu.** { *; }

# vendored 门面/兼容层（org.dolphinemu.ishiiruka.**，含 NesStationHost 宿主桥、
# DirectoryInitializationService、ButtonType/TouchScreenDevice 常量）同样被
# 引擎层直接调用，保持 keep。
-keep class org.dolphinemu.ishiiruka.** { *; }
