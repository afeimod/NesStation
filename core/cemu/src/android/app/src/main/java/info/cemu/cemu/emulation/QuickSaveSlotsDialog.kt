package info.cemu.cemu.emulation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.nativeinterface.NativeException
import info.cemu.cemu.nativeinterface.NativeQuickSave
import info.cemu.cemu.nativeinterface.NativeQuickSave.QuickSaveSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuickSaveDialogMode {
    SAVE,
    LOAD,
}

@Composable
private fun SlotThumbnail(bitmap: Bitmap?) {
    val thumbnailModifier = Modifier
        .size(width = 96.dp, height = 54.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = thumbnailModifier,
        )
    } else {
        Box(modifier = thumbnailModifier, contentAlignment = Alignment.Center) {
            Text(
                text = tr("No preview"),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SlotCard(
    slot: QuickSaveSlot?,
    label: String,
    bitmap: Bitmap?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val exists = slot != null && slot.exists
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled) { onClick() }
            .padding(4.dp),
    ) {
        SlotThumbnail(bitmap = if (exists) bitmap else null)
        Text(
            text = label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (exists) slot?.timestamp?.takeIf { it.isNotBlank() } ?: tr("Saved") else tr("Empty"),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun QuickSaveSlotsDialog(
    mode: QuickSaveDialogMode,
    onDismiss: () -> Unit,
    onRelaunchGame: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<NativeQuickSave.QuickSaveInfo?>(null) }
    var working by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmSaveSlot by remember { mutableStateOf<QuickSaveSlot?>(null) }
    var confirmRestoreSlot by remember { mutableStateOf<QuickSaveSlot?>(null) }
    var failedSlot by remember { mutableStateOf<Int?>(null) }
    val thumbnails = remember { mutableStateMapOf<Int, Bitmap?>() }
    var autoBackupSlot by remember { mutableStateOf<QuickSaveSlot?>(null) }

    fun refreshInfo() {
        scope.launch {
            val parsedInfo = withContext(Dispatchers.IO) {
                try {
                    NativeQuickSave.parseQuickSaveInfo(NativeQuickSave.getQuickSaveInfo())
                } catch (_: NativeException) {
                    null
                }
            } ?: return@launch
            info = parsedInfo

            autoBackupSlot = null
            val autoBackup = parsedInfo.autoBackup
            if (mode == QuickSaveDialogMode.LOAD && autoBackup?.exists == true) {
                autoBackupSlot = QuickSaveSlot(slot = -1, exists = true, timestamp = autoBackup.timestamp, sizeBytes = 0, thumb = autoBackup.thumb)
                if (autoBackup.thumb && !thumbnails.containsKey(-1)) {
                    thumbnails[-1] = withContext(Dispatchers.IO) {
                        try {
                            NativeQuickSave.getQuickSaveThumbnail(-1)
                        } catch (_: NativeException) {
                            null
                        }
                    }
                }
            }

            for (slot in parsedInfo.slots) {
                if (slot.exists && slot.thumb && !thumbnails.containsKey(slot.slot)) {
                    thumbnails[slot.slot] = withContext(Dispatchers.IO) {
                        try {
                            NativeQuickSave.getQuickSaveThumbnail(slot.slot)
                        } catch (_: NativeException) {
                            null
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { refreshInfo() }

    fun resultToMessage(result: Int): String? = when (result) {
        NativeQuickSave.RESULT_OK, NativeQuickSave.RESULT_OK_RESTORED_IN_PLACE -> null
        NativeQuickSave.RESULT_ERROR_NO_SAVE_DATA -> tr("No save data found. Save your progress in the game first.")
        NativeQuickSave.RESULT_ERROR_SLOT_EMPTY -> tr("This slot is empty.")
        NativeQuickSave.RESULT_ERROR_STATE_RESTORE_FAILED -> tr("Loading the savestate failed. The game is still running.")
        NativeQuickSave.RESULT_ERROR_IO, NativeQuickSave.RESULT_ERROR_NOT_RUNNING -> tr("A file error occurred. Please try again.")
        else -> tr("A file error occurred. Please try again.")
    }

    fun saveToSlot(slotIndex: Int) {
        scope.launch {
            working = true
            errorMessage = null
            val result = withContext(Dispatchers.IO) {
                try {
                    NativeQuickSave.quickSaveSlot(slotIndex)
                } catch (_: NativeException) {
                    NativeQuickSave.RESULT_ERROR_IO
                }
            }
            working = false
            resultToMessage(result)?.let { errorMessage = it } ?: refreshInfo()
        }
    }

    fun restoreFromSlot(slotIndex: Int, allowLegacy: Boolean = false) {
        scope.launch {
            working = true
            errorMessage = null
            val result = withContext(Dispatchers.IO) {
                try {
                    NativeQuickSave.quickRestoreSlot(slotIndex, if (allowLegacy) NativeQuickSave.RESTORE_FLAG_ALLOW_LEGACY else 0)
                } catch (_: NativeException) {
                    NativeQuickSave.RESULT_ERROR_IO
                }
            }
            working = false
            when (result) {
                // the state was restored in-place, the game continues without
                // any restart
                NativeQuickSave.RESULT_OK_RESTORED_IN_PLACE -> {
                    onDismiss()
                }
                // the title has been shut down during the legacy save data
                // restore, relaunch it inside the running process without
                // killing the app
                NativeQuickSave.RESULT_OK, NativeQuickSave.RESULT_ERROR_IO -> {
                    onRelaunchGame()
                    onDismiss()
                }
                else -> {
                    errorMessage = resultToMessage(result)
                    if (result == NativeQuickSave.RESULT_ERROR_STATE_RESTORE_FAILED && !allowLegacy) {
                        // offer the reliable restart-based fallback (safe mode)
                        failedSlot = slotIndex
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = {
            Column {
                Text(tr(if (mode == QuickSaveDialogMode.SAVE) "Quick save" else "Quick load"))
                info?.titleName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column {
                if (working) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val slots = info?.slots ?: emptyList()
                for (row in slots.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (slot in row) {
                            SlotCard(
                                slot = slot,
                                label = tr("Slot {0}", slot.slot),
                                bitmap = thumbnails[slot.slot],
                                enabled = !working && (mode == QuickSaveDialogMode.SAVE || slot.exists),
                                onClick = {
                                    if (mode == QuickSaveDialogMode.SAVE) {
                                        if (slot.exists) confirmSaveSlot = slot else saveToSlot(slot.slot)
                                    } else {
                                        confirmRestoreSlot = slot
                                    }
                                },
                            )
                        }
                    }
                }
                if (mode == QuickSaveDialogMode.LOAD && autoBackupSlot != null) {
                    SlotCard(
                        slot = autoBackupSlot,
                        label = tr("Auto backup"),
                        bitmap = thumbnails[-1],
                        enabled = !working,
                        onClick = { confirmRestoreSlot = autoBackupSlot },
                    )
                }
                errorMessage?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                failedSlot?.let { failed ->
                    TextButton(onClick = {
                        val slotIndex = failed
                        failedSlot = null
                        restoreFromSlot(slotIndex, allowLegacy = true)
                    }) {
                        Text(tr("Use safe mode"))
                    }
                }
                if (mode == QuickSaveDialogMode.LOAD) {
                    Text(
                        text = tr("Slots with a state file are restored instantly without restarting the game."),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = onDismiss) { Text(tr("Close")) }
        },
    )

    confirmSaveSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { confirmSaveSlot = null },
            title = { Text(tr("Overwrite this slot?")) },
            text = { Text(tr("Slot {0} already contains a save from {1}", slot.slot, slot.timestamp)) },
            confirmButton = {
                TextButton(onClick = {
                    val slotIndex = slot.slot
                    confirmSaveSlot = null
                    saveToSlot(slotIndex)
                }) { Text(tr("Overwrite")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSaveSlot = null }) { Text(tr("Cancel")) }
            },
        )
    }

    confirmRestoreSlot?.let { slot ->
        var safeMode by remember(slot) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!working) confirmRestoreSlot = null },
            title = { Text(tr("Restore this save?")) },
            text = {
                Column {
                    Text(
                        when {
                            slot.hasState -> tr("The game will be restored to the exact state from when this snapshot was taken.")
                            slot.slot == -1 -> tr("The current save progress will be restored from the auto backup. The game will restart.")
                            else -> tr("The current save progress will be replaced with slot {0}. The game will restart.", slot.slot)
                        }
                    )
                    if (slot.hasState) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { safeMode = !safeMode }
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = safeMode, onCheckedChange = { safeMode = it })
                            Text(
                                text = tr("Safe mode: restart the game and load the saved data instead"),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    val slotIndex = slot.slot
                    confirmRestoreSlot = null
                    restoreFromSlot(slotIndex, allowLegacy = safeMode)
                }) { Text(tr("Restore")) }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { confirmRestoreSlot = null }) { Text(tr("Cancel")) }
            },
        )
    }
}
