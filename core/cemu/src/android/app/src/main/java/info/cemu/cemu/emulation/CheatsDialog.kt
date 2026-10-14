package info.cemu.cemu.emulation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import info.cemu.cemu.nativeinterface.NativeCheats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private fun cheatFolder(): File =
    File(NativeActiveSettings.getUserDataPath()).resolve("cheatCodes")

@Composable
fun CheatsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var cheatCount by remember { mutableIntStateOf(0) }
    var enabledStates by remember { mutableStateOf(listOf<Boolean>()) }
    var reloadTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadTick) {
        withContext(Dispatchers.IO) {
            cheatCount = NativeCheats.reloadCheats()
            enabledStates = (0 until cheatCount).map { NativeCheats.isCheatEnabled(it) }
        }
    }

    val importFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val titleId = NativeCheats.getCurrentTitleId()
            val targetName = String.format(
                Locale.ROOT, "%016x_%s.txt", titleId,
                (uri.lastPathSegment ?: "imported").substringAfterLast('/').take(64),
            )
            try {
                val folder = cheatFolder()
                if (!folder.exists()) folder.mkdirs()
                val target = File(folder, targetName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // import failure is non-fatal, the dialog simply keeps the old list
            }
            reloadTick++
        }
    }

    AlertDialog(
        title = { Text(tr("Cheats")) },
        text = {
            Column {
                if (cheatCount == 0) {
                    Text(
                        text = tr("No cheats found for this game. Place cheat files (txt) in the cheatCodes folder or import one below."),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        (0 until cheatCount).forEach { index ->
                            val name = NativeCheats.getCheatName(index) ?: ""
                            val comment = NativeCheats.getCheatComment(index) ?: ""
                            val checked = enabledStates.getOrNull(index) ?: false
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newValue = !checked
                                        enabledStates = enabledStates.toMutableList().also { it[index] = newValue }
                                        NativeCheats.setCheatEnabled(index, newValue)
                                    }
                                    .padding(vertical = 2.dp),
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = name,
                                        fontSize = 15.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (comment.isNotBlank()) {
                                        Text(
                                            text = comment,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                TextButton(
                    onClick = {
                        importFilePicker.launch(arrayOf("text/*", "text/plain", "*/*"))
                    },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(tr("Import cheat file (txt)"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
        onDismissRequest = onDismiss,
    )
}
