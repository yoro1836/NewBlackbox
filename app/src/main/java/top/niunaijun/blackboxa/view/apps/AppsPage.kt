package top.niunaijun.blackboxa.view.apps

import android.app.Activity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddToHomeScreen
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.xposed.XposedManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.data.FrameGen
import top.niunaijun.blackboxa.ui.CenterProgress
import top.niunaijun.blackboxa.ui.ConfirmDialog
import top.niunaijun.blackboxa.ui.DrawableIcon
import top.niunaijun.blackboxa.ui.EmptyState
import top.niunaijun.blackboxa.ui.InfoDialog
import top.niunaijun.blackboxa.util.ContextUtil.openAppSystemSettings
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.ShortcutUtil
import top.niunaijun.blackboxa.util.toast

/** Installs an APK (package name, path or content URI) into the page's user. */
class AppsPageController {
    internal var install: ((String) -> Unit)? = null
    fun install(source: String) = install?.invoke(source)
}

private sealed interface AppAction {
    data class Uninstall(val app: AppInfo) : AppAction
    data class Clear(val app: AppInfo) : AppAction
    data class Stop(val app: AppInfo) : AppAction
    data class Shortcut(val app: AppInfo) : AppAction
    data class FrameGen(val app: AppInfo) : AppAction
    data object ShortcutHint : AppAction
}

/**
 * The app grid of one virtual user. Tap opens an app; long-press and drag reorders; a
 * long-press without moving opens the app's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsPage(
    userId: Int,
    controller: AppsPageController,
    contentPadding: PaddingValues,
    onBusy: (Boolean) -> Unit,
    onUsersChanged: () -> Unit,
) {
    val context = LocalContext.current
    val vm: AppsViewModel = viewModel(key = "apps_$userId", factory = InjectionUtil.getAppsFactory())
    val apps by vm.appsLiveData.observeAsState()
    val result by vm.resultLiveData.observeAsState()
    val launched by vm.launchLiveData.observeAsState()
    val items = remember { mutableStateListOf<AppInfo>() }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<AppAction?>(null) }
    var frameGenVersion by remember { mutableStateOf(0) }
    val haptics = LocalHapticFeedback.current

    controller.install = { source -> onBusy(true); vm.install(source, userId) }

    DisposableEffect(userId) {
        val callback = Runnable { vm.getInstalledAppsWithRetry(userId) }
        try {
            BlackBoxCore.get().addServiceAvailableCallback(callback)
        } catch (_: Exception) {
        }
        vm.getInstalledAppsWithRetry(userId)
        onDispose { }
    }
    LaunchedEffect(apps) {
        apps?.let { items.clear(); items.addAll(it) }
    }
    LaunchedEffect(result) {
        val r = result ?: return@LaunchedEffect
        onBusy(false)
        toast(r)
        vm.resultLiveData.value = null
        vm.getInstalledApps(userId)
        onUsersChanged()
    }
    LaunchedEffect(launched) {
        val ok = launched ?: return@LaunchedEffect
        onBusy(false)
        if (!ok) toast(R.string.start_fail)
        vm.launchLiveData.value = null
    }

    val gridState = rememberLazyGridState()
    var moved by remember { mutableStateOf(false) }
    // A long press starts a drag; the release that ends it must not also open the app.
    var suppressClick by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
        val f = items.indexOfFirst { it.packageName == from.key }
        val t = items.indexOfFirst { it.packageName == to.key }
        if (f >= 0 && t >= 0) {
            items.add(t, items.removeAt(f))
            moved = true
        }
    }

    when {
        apps == null -> CenterProgress(Modifier.padding(contentPadding))
        items.isEmpty() -> EmptyState(stringResource(R.string.apps_empty), Modifier.padding(contentPadding))
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 84.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.packageName }) { app ->
                ReorderableItem(reorder, key = app.packageName) { dragging ->
                    Box {
                        val fg = remember(app.packageName, frameGenVersion) {
                            FrameGen.get(context, app.packageName).on
                        }
                        AppTile(
                            app,
                            frameGen = fg,
                            modifier = Modifier
                                .scale(if (dragging) 1.08f else 1f)
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        moved = false
                                        suppressClick = true
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = {
                                        scope.launch { delay(300); suppressClick = false }
                                        if (moved) {
                                            vm.updateApkOrder(userId, items.toList())
                                        } else {
                                            menuFor = app.packageName
                                        }
                                    }
                                ),
                            onClick = {
                                if (suppressClick) {
                                    suppressClick = false
                                    return@AppTile
                                }
                                onBusy(true)
                                vm.launchApk(app.packageName, userId)
                            }
                        )
                        AppMenu(
                            app = app,
                            expanded = menuFor == app.packageName,
                            onDismiss = { menuFor = null },
                            onAction = { menuFor = null; action = it }
                        )
                    }
                }
            }
        }
    }

    when (val a = action) {
        is AppAction.Uninstall -> ConfirmDialog(
            title = stringResource(R.string.uninstall_app),
            text = stringResource(R.string.uninstall_app_hint, a.app.name),
            confirm = stringResource(R.string.app_remove),
            onConfirm = { onBusy(true); vm.unInstall(a.app.packageName, userId) },
            onDismiss = { action = null }
        )
        is AppAction.Clear -> ConfirmDialog(
            title = stringResource(R.string.app_clear),
            text = stringResource(R.string.app_clear_hint, a.app.name),
            onConfirm = { onBusy(true); vm.clearApkData(a.app.packageName, userId) },
            onDismiss = { action = null }
        )
        is AppAction.Stop -> ConfirmDialog(
            title = stringResource(R.string.app_stop),
            text = stringResource(R.string.app_stop_hint, a.app.name),
            onConfirm = {
                BlackBoxCore.get().stopPackage(a.app.packageName, userId)
                toast(context.getString(R.string.is_stop, a.app.name))
            },
            onDismiss = { action = null }
        )
        is AppAction.Shortcut -> {
            var label by remember(a) { mutableStateOf(a.app.name) }
            AlertDialog(
                onDismissRequest = { action = null },
                title = { Text(stringResource(R.string.app_shortcut)) },
                text = {
                    OutlinedTextField(
                        value = label, onValueChange = { label = it }, singleLine = true,
                        label = { Text(stringResource(R.string.shortcut_name)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (ShortcutUtil.pinShortcut(context, userId, a.app, label.ifBlank { a.app.name })) {
                            action = if (AppManager.mBlackBoxLoader.showShortcutPermissionDialog())
                                AppAction.ShortcutHint else null
                        } else {
                            action = null
                            toast(R.string.cannot_create_shortcut)
                        }
                    }) { Text(stringResource(R.string.done)) }
                },
                dismissButton = { TextButton(onClick = { action = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }
        AppAction.ShortcutHint -> AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(stringResource(R.string.try_add_shortcut)) },
            text = { Text(stringResource(R.string.add_shortcut_fail_msg)) },
            confirmButton = { TextButton(onClick = { action = null }) { Text(stringResource(R.string.done)) } },
            dismissButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        action = null
                        context.openAppSystemSettings()
                    }) { Text(stringResource(R.string.permission_setting)) }
                    TextButton(onClick = {
                        action = null
                        AppManager.mBlackBoxLoader.invalidShortcutPermissionDialog(false)
                    }) { Text(stringResource(R.string.no_reminders)) }
                }
            }
        )
        is AppAction.FrameGen -> FrameGenDialog(a.app) { action = null; frameGenVersion++ }
        null -> {}
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppInfo, frameGen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier.combinedClickable(onClick = onClick)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp)
        ) {
            Box {
                DrawableIcon(app.icon, size = 52.dp)
                if (frameGen) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(
                            Icons.Outlined.Speed, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(2.dp).height(14.dp)
                        )
                    }
                }
                if (app.isXpModule) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Icon(
                            Icons.Outlined.Extension, null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(2.dp).height(14.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                app.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AppMenu(app: AppInfo, expanded: Boolean, onDismiss: () -> Unit, onAction: (AppAction) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (app.isXpModule) {
            var enabled by remember(expanded) {
                mutableStateOf(XposedManager.loadEnabledModules().contains(app.packageName))
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.app_xposed)) },
                leadingIcon = { Icon(Icons.Outlined.Extension, null) },
                trailingIcon = { Checkbox(checked = enabled, onCheckedChange = null) },
                onClick = {
                    val set = XposedManager.loadEnabledModules().toMutableSet()
                    if (!set.add(app.packageName)) set.remove(app.packageName)
                    XposedManager.saveEnabledModules(set)
                    enabled = set.contains(app.packageName)
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.framegen)) },
            leadingIcon = { Icon(Icons.Outlined.Speed, null) },
            onClick = { onAction(AppAction.FrameGen(app)) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_shortcut)) },
            leadingIcon = { Icon(Icons.Outlined.AddToHomeScreen, null) },
            onClick = { onAction(AppAction.Shortcut(app)) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_stop)) },
            leadingIcon = { Icon(Icons.Outlined.StopCircle, null) },
            onClick = { onAction(AppAction.Stop(app)) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_clear)) },
            leadingIcon = { Icon(Icons.Outlined.CleaningServices, null) },
            onClick = { onAction(AppAction.Clear(app)) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_remove), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onAction(AppAction.Uninstall(app)) }
        )
    }
}
