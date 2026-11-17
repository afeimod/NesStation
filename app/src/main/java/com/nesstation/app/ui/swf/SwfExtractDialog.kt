package com.nesstation.app.ui.swf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SWF 提取结果中的单个文件(SWF 或资源) */
data class SwfItem(
    val url: String,
    val title: String,
    val type: String = "swf",  // "swf" or "resource"
    val subDir: String = ""
)

/**
 * SWF 提取结果对话框:
 * - 多选 checkbox + 全选按钮
 * - 每个 SWF 行有"播放"按钮(可选,SwfPlayerScreen 不需要时可传空 lambda)
 * - 底部"下载选中"按钮触发 onDownload(选中项列表)
 *
 * 供 SwfPlayerScreen 和 WebGameScreen 共用。设计在 com.nesstation.app.ui.swf 包
 * (WebGameScreen import 时用 com.nesstation.app.ui.swf.SwfExtractDialog 即可)。
 */
@Composable
fun SwfExtractDialog(
    json: String,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    onDownload: (List<SwfItem>) -> Unit
) {
    val allItems = remember(json) {
        try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url", "")
                if (u.isEmpty()) null else SwfItem(
                    url = u,
                    title = o.optString("title", u.substringAfterLast('/')),
                    type = o.optString("type", "swf"),
                    subDir = o.optString("subDir", "")
                )
            }
        } catch (e: Exception) { emptyList() }
    }
    val swfList = allItems.filter { it.type == "swf" }
    val resList = allItems.filter { it.type == "resource" }
    val selected = remember(json) { mutableStateMapOf<String, Boolean>() }

    if (allItems.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("提取 SWF") },
            text = { Text("未在页面中发现 SWF 文件") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现 ${swfList.size} 个 SWF" + if (resList.isNotEmpty()) " + ${resList.size} 个资源" else "") },
            text = {
                LazyColumn {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val allUrls = allItems.map { it.url }
                                    val allSelected = allUrls.all { selected[it] == true }
                                    if (allSelected) {
                                        selected.clear()
                                    } else {
                                        allUrls.forEach { selected[it] = true }
                                    }
                                }
                            ) { Text(if (allItems.all { selected[it.url] == true }) "取消全选" else "全选") }
                        }
                    }
                    // SWF 文件列表
                    if (swfList.isNotEmpty()) {
                        item { Text("SWF 文件", color = Color(0xFFFFC107), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)) }
                    }
                    items(count = swfList.size) { idx ->
                        val it = swfList[idx]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected[it.url] == true,
                                onCheckedChange = { checked -> selected[it.url] = checked }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(it.title, color = Color.White, fontSize = 13.sp)
                                Text(it.url, color = Color.Gray, fontSize = 10.sp)
                            }
                            TextButton(onClick = { onPlay(it.url) }) {
                                Text("播放")
                            }
                        }
                    }
                    // 资源文件列表
                    if (resList.isNotEmpty()) {
                        item { Text("资源文件 (${resList.size})", color = Color(0xFF4FC3F7), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)) }
                    }
                    items(count = resList.size) { idx ->
                        val it = resList[idx]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected[it.url] == true,
                                onCheckedChange = { checked -> selected[it.url] = checked }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(it.title, color = Color.White, fontSize = 12.sp)
                                Text(
                                    if (it.subDir.isNotEmpty()) "${it.subDir}${it.title}" else it.url,
                                    color = Color.Gray, fontSize = 9.sp, maxLines = 1
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDownload = allItems.filter { selected[it.url] == true }
                        if (toDownload.isNotEmpty()) {
                            onDownload(toDownload)
                        }
                    }
                ) { Text("下载选中") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        )
    }
}
