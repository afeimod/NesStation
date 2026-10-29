package org.libsdl.app

/*
 * ★★ v57 空壳占位（原 v55 afbn 桥已整体合并入 SDLActivity.java）★★
 *
 * 本文件原是 v55 为 afbn 引擎（AfbnEngine.kt，核心 libarcade.so）新增的
 * Kotlin 桥类 org.libsdl.app.SDLActivity —— 与 v5 vendored 的 SDL3 java 桥
 * app/src/main/java/org/libsdl/app/SDLActivity.java（Eden/Switch 引擎
 * SDL.setupJNI 的静态面）同包同类名，kapt 注解处理生成 stub 时 javac 报
 * "duplicate class: org.libsdl.app.SDLActivity"（app 构建失败）。
 *
 * v57 起该桥面（静态路径/游戏字段、getter、AudioTrack 管理、native 声明、
 * nativePause(boolean) 便捷包装）已 1:1 合并入 SDLActivity.java 末部的
 * 「v57 afbn 桥区段」，语义零变化 —— 本文件降级为纯注释占位。
 *
 * 用本包覆盖仓库同名文件即消除重复类；如愿意，也可整体删除本文件
 * （效果相同，本文件不参与任何编译产物）。
 */
