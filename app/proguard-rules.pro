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

# ─── Flycast (Dreamcast/NAOMI core) ────────────────────────────────────────
# libflycast.so 通过 JNI 符号 (Java_com_flycast_emulator_*) 与 GetMethodID 按名
# 查找宿主 Java 类/方法/字段（静态绑定包名+类名+方法名）。release 构建 R8 混淆
# 会改名导致 UnsatisfiedLinkError / NoSuchMethodError，因此整包 keep：
#   · native 方法声明（JNIdc/AudioBackend/HttpClient/VGamepad/InputDeviceManager/
#     AndroidStorage/BaseGLActivity.register 等）
#   · native → Java 回调（Emulator.getAppContext/getCurrentActivity、
#     AudioBackend.init/term/writeBuffer、HttpClient.init/openUrl/post、
#     InputDeviceManager.rumble、BaseGLActivity.onGameStateChange/
#     showScreenKeyboard/getNativeLibDir、AndroidStorage.openFile/listContent/
#     getFileInfo、FileInfo getter/setter、SipEmulator、LocaleUtils 等）
-keep class com.flycast.emulator.** { *; }
-keepclassmembers class com.flycast.emulator.** { *; }
# libflycast.so 内置 swappy 通过 FindClass 访问的 Java 胶水类
-keep class com.google.androidgamesdk.** { *; }
# httpclient5/slf4j 由 R8 处理依赖即可，flycast 的 HttpClient 经 JNI 调用已被上面 keep 覆盖

# ─── httpclient5 引用的 JDK 专属 GSS-API/Kerberos 类 ───────────────────────
# org.apache.hc.client5.http.impl.auth.GGSSchemeBase / KerberosCredentials
# (SPNEGO/Negotiate 认证) 引用 org.ietf.jgss.*，这些类只在 JDK 里存在、
# Android 运行时没有，且 App 在 Android 上从不使用 Kerberos 认证。
# 不加此规则 R8 报 "Missing class org.ietf.jgss.GSS*" → minifyReleaseWithR8
# 构建失败（即 R8 自动生成的 missing_rules.txt 内容，通配符覆盖全部 6 个类）。
-dontwarn org.ietf.jgss.**
