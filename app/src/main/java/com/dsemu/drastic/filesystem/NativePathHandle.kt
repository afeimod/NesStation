@file:Suppress("unused")

package com.dsemu.drastic.filesystem

import androidx.annotation.Keep
import java.io.File

/**
 * DraStic 原生层的文件句柄。
 *
 * 字段名 / 类型与原版严格一致 —— libdrastic.so 通过 GetFieldID("filePath",
 * "Ljava/lang/String;") / ("fileFd", "I") / ("fileName", "Ljava/lang/String;")
 * 按名读取（.so 字符串已验证），任何改名都会在运行时抛 NoSuchFieldError。
 */
@Keep
class NativePathHandle @JvmOverloads constructor(
    @JvmField val filePath: String,
    @JvmField val fileFd: Int = -1,
    @JvmField val fileName: String = filePath.substring(filePath.lastIndexOf(File.separatorChar) + 1)
) {
    constructor(file: File) : this(file.absolutePath)

    /** 兼容原版 (String, String) 构造：path + 显式显示名。 */
    constructor(path: String, displayName: String) : this(path, -1, displayName)
}
