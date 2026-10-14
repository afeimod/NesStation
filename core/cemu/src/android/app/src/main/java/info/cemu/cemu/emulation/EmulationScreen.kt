package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.settings.AppSettingsStore
import info.cemu.cemu.common.settings.EmulationSettings
import info.cemu.cemu.common.settings.GamePadPosition
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.ui.components.Slider
import info.cemu.cemu.common.ui.extensions.showMessage
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.emulation.emulatedusbdevices.EmulatedUSBDevicesDialog
import info.cemu.cemu.emulation.input.HotkeyManager
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurface
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.DEFAULT
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_POSITION
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_SIZE
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun EmulationScreen(
    gamePath: String,
    setMotionSensorEnabled: (Boolean) -> Unit,
    setInputListeningEnabled: (Boolean) -> Unit,
    onQuit: () -> Unit,
    viewModel: EmulationViewModel = viewModel(
        factory = EmulationViewModel.Factory, extras = MutableCreationExtras().apply {
            set(EmulationViewModel.LAUNCH_PATH_KEY, gamePath)
        }),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showQuitConfirmationDialog by remember { mutableStateOf(false) }
    var inputOverlayInputMode by rememberSaveable { mutableStateOf(DEFAULT) }
    var showEmulatedUSBDevices by remember { mutableStateOf(false) }
    var showQuickSaveDialog by remember { mutableStateOf(false) }
    var showQuickLoadDialog by remember { mutableStateOf(false) }
    var showCheatsDialog by remember { mutableStateOf(false) }

    val emulationError by viewModel.emulationError.collectAsState()
    val isEmulationInitialized by viewModel.isEmulationInitialized.collectAsState()
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val gamePadPosition by viewModel.gamePadPosition.collectAsState()
    val emulationSettings by viewModel.emulationSettings.collectAsState()
    val isInputOverlayVisible by viewModel.isInputOverlayVisible.collectAsState()
    val inputOverlaySettings by viewModel.inputOverlaySettings.collectAsState()


    fun closeDrawer() {
        scope.launch {
            drawerState.close()
        }
    }

    // Relaunches the current title inside the running process (no app restart).
    // Falls back to the process-restart path if preparing the title fails.
    fun relaunchGameInProcess() {
        scope.launch {
            snackbarHostState.showMessage(scope, tr("Restarting game..."))
        }
        scope.launch(Dispatchers.IO) {
            try {
                val prepareResult = NativeEmulation.prepareTitle(gamePath)
                if (prepareResult == NativeEmulation.PrepareTitleResult.SUCCESSFUL) {
                    NativeEmulation.initializeSystems()
                    NativeEmulation.launchTitle()
                } else {
                    restartEmulationActivity(context, gamePath)
                }
            } catch (_: Exception) {
                restartEmulationActivity(context, gamePath)
            }
        }
    }

    suspend fun toggleMenu() {
        drawerState.apply {
            if (isClosed) {
                open()
            } else {
                close()
            }
        }
    }

    BackHandler {
        if (drawerState.isAnimationRunning) {
            return@BackHandler
        }

        scope.launch {
            toggleMenu()
        }
    }

    LaunchedEffect(drawerState.isClosed) {
        setInputListeningEnabled(drawerState.isClosed)
    }

    LaunchedEffect(Unit) {
        // apply the persisted game speed for this session as early as possible
        // (the side menu only applies it when opened)
        val storedSettings = AppSettingsStore.dataStore.data.first().emulationSettings
        NativeSettings.setGameSpeed(storedSettings.gameSpeedMode)
        // the pad opacity is applied per-pixel by the native pad view output
        // shader; push it before emulation starts (only for the floating overlay)
        NativeSettings.setPadOverlayOpacity(
            if (storedSettings.gamePadPosition.isOverlay()) storedSettings.padOverlayOpacity else 100
        )
    }

    LaunchedEffect(emulationSettings) {
        // re-push the per-pixel pad overlay opacity whenever it or the pad
        // position changes; takes effect on the next rendered frame
        val settings = emulationSettings ?: return@LaunchedEffect
        NativeSettings.setPadOverlayOpacity(
            if (settings.gamePadPosition.isOverlay()) settings.padOverlayOpacity else 100
        )
    }

    LaunchedEffect(Unit) {
        HotkeyManager.actions.collect { action ->
            when (action) {
                HotkeyAction.QUIT -> showQuitConfirmationDialog = true
                HotkeyAction.TOGGLE_MENU -> toggleMenu()
                HotkeyAction.SHOW_EMULATED_USB_DEVICES_DIALOG -> showEmulatedUSBDevices = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState())
                ) {
                    EmulationSideMenuContent(
                        sideMenuState = sideMenuState,
                        updateState = {
                            viewModel.updateSideMenuState(it)
                            setMotionSensorEnabled(it.isMotionEnabled)
                            NativeEmulation.setReplaceTVWithPadView(it.isTVReplacedWithPad)
                            closeDrawer()
                        },
                        onEditInputOverlay = {
                            snackbarHostState.showMessage(scope, tr("Edit input positions"))
                            inputOverlayInputMode = EDIT_POSITION
                            closeDrawer()
                        },
                        onResetInputOverlay = {
                            viewModel.resetInputOverlayLayout()
                            closeDrawer()
                        },
                        onQuit = {
                            showQuitConfirmationDialog = true
                            closeDrawer()
                        },
                        onShowEmulatedUSBDevices = {
                            showEmulatedUSBDevices = true
                            closeDrawer()
                        },
                        onQuickSave = {
                            showQuickSaveDialog = true
                            closeDrawer()
                        },
                        onQuickLoad = {
                            showQuickLoadDialog = true
                            closeDrawer()
                        },
                        onShowCheats = {
                            showCheatsDialog = true
                            closeDrawer()
                        },
                        emulationSettings = emulationSettings,
                        onUpdateEmulationSettings = viewModel::updateEmulationSettings,
                    )
                }
            }
        },
    ) {
        EmulationSurfaces(
            sideMenuState = sideMenuState,
            gamePadPosition = gamePadPosition,
            emulationSettings = emulationSettings,
            mainHolderCallback = viewModel.mainHolderCallback,
            padHolderCallback = viewModel.padHolderCallback,
            onInitializeEmulation = viewModel::initializeEmulation,
        )

        InputOverlaySurface(
            isVisible = isInputOverlayVisible,
            inputOverlaySettings = inputOverlaySettings,
            inputMode = inputOverlayInputMode,
            onEditFinished = { viewModel.saveInputOverlayRectangles(it) },
        )

        if (inputOverlayInputMode != DEFAULT) {
            EditInputsLayout(
                inputMode = inputOverlayInputMode,
                onFinishClick = {
                    snackbarHostState.showMessage(scope, tr("Exited input edit mode"))
                    inputOverlayInputMode = DEFAULT
                },
                onMoveClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input positions"))
                    inputOverlayInputMode = EDIT_POSITION
                },
                onResizeClick = {
                    snackbarHostState.showMessage(scope, tr("Edit input size"))
                    inputOverlayInputMode = EDIT_SIZE
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    emulationError?.let {
        EmulationErrorDialog(it, onQuit)
    }

    if (!isEmulationInitialized) {
        EmulationLoadingDialog()
    }

    if (showQuitConfirmationDialog) {
        EmulationQuitConfirmationDialog(
            onQuit = onQuit,
            onDismiss = { showQuitConfirmationDialog = false },
        )
    }

    if (showEmulatedUSBDevices) {
        EmulatedUSBDevicesDialog(
            onDismiss = { showEmulatedUSBDevices = false },
        )
    }

    if (showQuickSaveDialog) {
        QuickSaveSlotsDialog(
            mode = QuickSaveDialogMode.SAVE,
            onDismiss = { showQuickSaveDialog = false },
            onRelaunchGame = { relaunchGameInProcess() },
        )
    }

    if (showQuickLoadDialog) {
        QuickSaveSlotsDialog(
            mode = QuickSaveDialogMode.LOAD,
            onDismiss = { showQuickLoadDialog = false },
            onRelaunchGame = { relaunchGameInProcess() },
        )
    }

    if (showCheatsDialog) {
        CheatsDialog(
            onDismiss = { showCheatsDialog = false },
        )
    }

    EmulationTextInputDialog()
}

private fun restartEmulationActivity(context: Context, gamePath: String) {
    val intent = Intent(context, EmulationActivity::class.java).apply {
        putExtra(EmulationActivity.EXTRA_LAUNCH_PATH, gamePath)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

@Composable
private fun EditInputsLayout(
    inputMode: InputOverlaySurfaceView.InputMode,
    onFinishClick: () -> Unit,
    onMoveClick: () -> Unit,
    onResizeClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onFinishClick) { Text(tr("Done")) }

            Row(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(enabled = inputMode != EDIT_POSITION, onClick = onMoveClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_move),
                        contentDescription = tr("Move")
                    )
                }

                FilledIconButton(enabled = inputMode != EDIT_SIZE, onClick = onResizeClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_resize),
                        contentDescription = tr("Resize"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmulationSideMenuContent(
    sideMenuState: SideMenuState,
    updateState: (SideMenuState) -> Unit,
    onShowEmulatedUSBDevices: () -> Unit,
    onEditInputOverlay: () -> Unit,
    onResetInputOverlay: () -> Unit,
    onQuickSave: () -> Unit,
    onQuickLoad: () -> Unit,
    onShowCheats: () -> Unit,
    emulationSettings: EmulationSettings?,
    onUpdateEmulationSettings: ((EmulationSettings) -> EmulationSettings) -> Unit,
    onQuit: () -> Unit,
) {
    var isTitlePaused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isTitlePaused = NativeEmulation.isTitlePaused() }

    TextButtonItem(
        label = tr(if (isTitlePaused) "Resume emulation" else "Pause emulation"),
        onClick = {
            if (isTitlePaused) {
                NativeEmulation.resumeTitle()
                isTitlePaused = false
            } else {
                NativeEmulation.pauseTitle()
                isTitlePaused = true
            }
        },
    )

    TextButtonItem(
        label = tr("Quick save"),
        onClick = onQuickSave,
    )

    TextButtonItem(
        label = tr("Quick load"),
        onClick = onQuickLoad,
    )

    TextButtonItem(
        label = tr("Cheats"),
        onClick = onShowCheats,
    )

    GameSpeedItem()

    CheckboxItem(
        label = tr("Enable motion"),
        checked = sideMenuState.isMotionEnabled,
        onCheckedChange = { updateState(sideMenuState.copy(isMotionEnabled = it)) },
    )

    CheckboxItem(
        label = tr("Replace TV with PAD"),
        checked = sideMenuState.isTVReplacedWithPad,
        onCheckedChange = { updateState(sideMenuState.copy(isTVReplacedWithPad = it)) },
    )

    CheckboxItem(
        label = tr("Show PAD"),
        checked = sideMenuState.isPadVisible,
        onCheckedChange = { updateState(sideMenuState.copy(isPadVisible = it)) },
    )

    TextButtonItem(
        label = tr("Emulated USB Devices"),
        onClick = onShowEmulatedUSBDevices,
    )

    CheckboxItem(
        label = tr("Show input overlay"),
        checked = sideMenuState.isInputOverlayVisible,
        onCheckedChange = { updateState(sideMenuState.copy(isInputOverlayVisible = it)) },
    )

    if (emulationSettings != null && emulationSettings.gamePadPosition.isOverlay()) {
        PadOverlaySettingsSection(
            emulationSettings = emulationSettings,
            onUpdateEmulationSettings = onUpdateEmulationSettings,
        )
    }

    TextButtonItem(
        label = tr("Edit inputs"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onEditInputOverlay,
    )

    TextButtonItem(
        label = tr("Reset input overlay"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onResetInputOverlay,
    )

    TextButtonItem(
        label = tr("Exit"),
        onClick = onQuit,
    )
}

@Composable
private fun GameSpeedItem() {
    val scope = rememberCoroutineScope()
    var gameSpeed by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        // restore the persisted speed for this session and push it into the
        // emulator (the native side does not persist the speed itself)
        val storedSpeed = AppSettingsStore.dataStore.data.first().emulationSettings.gameSpeedMode
        NativeSettings.setGameSpeed(storedSpeed)
        gameSpeed = storedSpeed
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Text(
            text = tr("Game speed"),
            fontSize = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("0.5x" to 0, "1x" to 1, "2x" to 2, "4x" to 3, "8x" to 4).forEach { (label, mode) ->
                Text(
                    text = label,
                    fontSize = 14.sp,
                    color = if (gameSpeed == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            gameSpeed = mode
                            NativeSettings.setGameSpeed(mode)
                            scope.launch {
                                AppSettingsStore.dataStore.updateData {
                                    it.copy(emulationSettings = it.emulationSettings.copy(gameSpeedMode = mode))
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled) { onCheckedChange(!checked) }
            .padding(8.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(1f),
            fontSize = 16.sp,
        )

        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun TextButtonItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .heightIn(min = 48.dp)
            .wrapContentHeight(align = Alignment.CenterVertically),
        fontSize = 16.sp,
    )
}

@Composable
private fun EmulationSurfaces(
    sideMenuState: SideMenuState,
    gamePadPosition: GamePadPosition?,
    emulationSettings: EmulationSettings?,
    mainHolderCallback: SurfaceHolder.Callback,
    padHolderCallback: SurfaceHolder.Callback,
    onInitializeEmulation: () -> Unit
) {
    if (gamePadPosition == null) {
        return
    }

    val isPadVisible = sideMenuState.isPadVisible

    @Composable
    fun MainSurface(modifier: Modifier) {
        EmulationSurface(
            modifier = modifier,
            isTV = true,
            holderCallback = mainHolderCallback,
            afterInit = { onInitializeEmulation() },
        )
    }

    @Composable
    fun PadSurface(modifier: Modifier) {
        if (isPadVisible) {
            EmulationSurface(
                modifier = modifier,
                isTV = false,
                holderCallback = padHolderCallback,
            )
        }
    }

    if (gamePadPosition.isOverlay()) {
        // floating GamePad screen in a corner of the unchanged, full-size TV screen
        Box(modifier = Modifier.fillMaxSize()) {
            MainSurface(Modifier.fillMaxSize())
            if (isPadVisible && emulationSettings != null) {
                PadOverlaySurface(
                    settings = emulationSettings,
                    padHolderCallback = padHolderCallback,
                )
            }
        }
        return
    }

    val isVertical = gamePadPosition.isVertical()
    val appearsAfterTV = gamePadPosition.appearsAfterTV()

    @Composable
    fun SurfacesInOrder(itemModifier: Modifier) {
        if (appearsAfterTV) {
            MainSurface(itemModifier)
            PadSurface(itemModifier)
        } else {
            PadSurface(itemModifier)
            MainSurface(itemModifier)
        }
    }

    LinearLayout(isVertical) { itemModifier ->
        SurfacesInOrder(itemModifier)
    }
}

@Composable
private fun PadOverlaySurface(
    settings: EmulationSettings,
    padHolderCallback: SurfaceHolder.Callback,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val scale = settings.padOverlayScale.coerceIn(0.1f, 0.6f)
        val padWidth = maxWidth * scale
        // Wii U GamePad display is 854x480 (16:9)
        val padHeight = padWidth * (480f / 854f)
        val corner = settings.padOverlayCorner.coerceIn(0, 3)
        var padModifier = when (corner) {
            1 -> Modifier.align(Alignment.BottomEnd)
            2 -> Modifier.align(Alignment.TopStart)
            3 -> Modifier.align(Alignment.TopEnd)
            else -> Modifier.align(Alignment.BottomStart) // default: bottom-left corner
        }
            .padding(8.dp)
            .width(padWidth)
            .height(padHeight)

        // NOTE: SurfaceView content lives on its own SurfaceFlinger layer,
        // so Compose layer alpha/blur on this modifier cannot tint it.
        // The pad opacity is applied per-pixel inside the native pad view
        // output shader (premultiplied alpha), and the surface is created
        // with TRANSLUCENT format (see EmulationSurface) so the compositor
        // actually blends it. View.setAlpha()/SurfaceControl layer alpha is
        // deliberately not used.
        if (settings.padOverlayBlur > 0 && Build.VERSION.SDK_INT >= 31) {
            padModifier = padModifier.blur(settings.padOverlayBlur.coerceIn(1, 24).dp)
        }

        EmulationSurface(
            modifier = padModifier,
            isTV = false,
            holderCallback = padHolderCallback,
            translucent = true,
        )
    }
}

@Composable
private fun PadOverlaySettingsSection(
    emulationSettings: EmulationSettings,
    onUpdateEmulationSettings: ((EmulationSettings) -> EmulationSettings) -> Unit,
) {
    Column {
        TextButtonItem(
            label = tr("Floating GamePad screen"),
            enabled = false,
            onClick = {},
        )

        Slider(
            label = tr("PAD screen size"),
            value = (emulationSettings.padOverlayScale * 100f).roundToInt().coerceIn(10, 60),
            valueFrom = 10,
            valueTo = 60,
            labelFormatter = { "$it%" },
            onValueChange = { percent ->
                onUpdateEmulationSettings { it.copy(padOverlayScale = percent / 100f) }
            },
        )

        Slider(
            label = tr("PAD screen opacity"),
            value = emulationSettings.padOverlayOpacity.coerceIn(20, 100),
            valueFrom = 20,
            valueTo = 100,
            labelFormatter = { "$it%" },
            onValueChange = { percent ->
                onUpdateEmulationSettings { it.copy(padOverlayOpacity = percent) }
            },
        )

        Slider(
            label = tr("PAD screen blur"),
            value = emulationSettings.padOverlayBlur.coerceIn(0, 24),
            valueFrom = 0,
            valueTo = 24,
            labelFormatter = { if (it == 0) tr("Off") else "$it dp" },
            onValueChange = { blur ->
                onUpdateEmulationSettings { it.copy(padOverlayBlur = blur) }
            },
        )

        Text(
            text = tr("PAD screen corner"),
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                0 to tr("Bottom left"),
                1 to tr("Bottom right"),
                2 to tr("Top left"),
                3 to tr("Top right"),
            ).forEach { (cornerId, label) ->
                Text(
                    text = label,
                    fontSize = 13.sp,
                    color = if (emulationSettings.padOverlayCorner.coerceIn(0, 3) == cornerId)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onUpdateEmulationSettings { it.copy(padOverlayCorner = cornerId) }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun LinearLayout(
    isVertical: Boolean,
    content: @Composable (Modifier) -> Unit,
) {
    if (isVertical) {
        Column(modifier = Modifier.fillMaxSize()) {
            content(Modifier.weight(1f))
        }
    } else {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(modifier = Modifier.fillMaxSize()) {
                content(Modifier.weight(1f))
            }
        }
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility")
private fun EmulationSurface(
    modifier: Modifier,
    isTV: Boolean,
    holderCallback: SurfaceHolder.Callback,
    afterInit: () -> Unit = {},
    // requests a blending (per-pixel alpha) surface. SurfaceViews are OPAQUE
    // by default, in which case the compositor skips blending entirely and
    // ignores any alpha written by the renderer. The opacity itself is applied
    // per-pixel by the native pad view output shader (premultiplied alpha);
    // View.setAlpha()/SurfaceControl layer alpha is deliberately not used.
    translucent: Boolean = false
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                var firstChange = true

                if (translucent) {
                    holder.setFormat(PixelFormat.TRANSLUCENT)
                }

                setOnTouchListener(CanvasOnTouchListener(isTV))

                holder.addCallback(holderCallback)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        if (firstChange) {
                            afterInit()
                            firstChange = false
                        }
                    }

                    override fun surfaceCreated(holder: SurfaceHolder) {}

                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        })
}

@Composable
private fun EmulationQuitConfirmationDialog(onQuit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        title = { Text(tr("Exit confirmation")) },
        text = { Text(tr("Are you sure you want to exit?")) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Yes")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("No")) } },
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun EmulationLoadingDialog() {
    AlertDialog(
        title = { Text(tr("Initializing emulation")) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
        onDismissRequest = {},
    )
}

@Composable
private fun EmulationErrorDialog(error: NativeError, onQuit: () -> Unit) {
    val errorMessage = remember(error) {
        when (error) {
            is NativeError.SystemInitializationError -> tr("Failed to initialize")

            is NativeError.RendererInitializationError ->
                tr("Failed creating renderer: {0}", error.message)

            is NativeError.SurfaceCreationError -> tr("Failed creating surface: {0}", error.message)

            NativeError.GameFilesNotFoundError -> tr("Unable to launch game because the base files were not found.")
            NativeError.NoDiscKeysError -> tr("Could not decrypt title. Make sure that keys.txt contains the correct disc key for this title.")
            NativeError.NoTitleTikError -> tr("Could not decrypt title because title.tik is missing.")
            is NativeError.UnknownTilePrepareError ->
                tr("Unable to launch game\nPath: {0}", error.launchPath)

            NativeError.LaunchingTitleError -> tr("Failed to launch title")
        }
    }
    AlertDialog(
        title = { Text(tr("Error")) },
        text = { Text(errorMessage) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Quit")) } },
        onDismissRequest = {},
    )
}
