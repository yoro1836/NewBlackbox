package top.niunaijun.blackboxa.view.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shop
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.spoof.SpoofManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.ui.AppScaffold
import top.niunaijun.blackboxa.ui.SectionCard
import top.niunaijun.blackboxa.ui.SectionHeader
import top.niunaijun.blackboxa.ui.setThemedContent
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.gms.GmsManagerActivity
import top.niunaijun.blackboxa.view.gpu.GpuDriverActivity
import top.niunaijun.blackboxa.view.xposed.XposedModulesActivity

class SettingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent { SettingsScreen() }
    }

    private val spoofKeys = listOf(
        "ro.product.model" to R.string.spoof_model,
        "ro.product.brand" to R.string.spoof_brand,
        "ro.product.manufacturer" to R.string.spoof_manufacturer,
        "ro.product.device" to R.string.spoof_device,
        "ro.build.fingerprint" to R.string.spoof_fingerprint,
    )

    @Composable
    private fun SettingsScreen() {
        val loader = AppManager.mBlackBoxLoader
        var hideRoot by remember { mutableStateOf(loader.hideRoot()) }
        var daemon by remember { mutableStateOf(loader.daemonEnable()) }
        var vpn by remember { mutableStateOf(loader.useVpnNetwork()) }
        var flagSecure by remember { mutableStateOf(loader.disableFlagSecure()) }
        val spoof = remember {
            mutableStateMapOf<String, String>().apply {
                try {
                    putAll(SpoofManager.loadProfile())
                } catch (_: Exception) {
                }
            }
        }
        var editing by remember { mutableStateOf<Pair<String, Int>?>(null) }
        var sendingLogs by remember { mutableStateOf(false) }

        AppScaffold(title = stringResource(R.string.setting), onBack = { finish() }) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                SectionHeader(stringResource(R.string.settings_features))
                SectionCard {
                    NavRow(Icons.Outlined.Shop, R.string.gms_manager, R.string.gms_manager_summary) {
                        GmsManagerActivity.start(this@SettingActivity)
                    }
                    Divider()
                    NavRow(Icons.Outlined.Extension, R.string.xposed_manager, R.string.xposed_manager_summary) {
                        XposedModulesActivity.start(this@SettingActivity)
                    }
                    Divider()
                    NavRow(Icons.Outlined.Memory, R.string.gpu_manager, R.string.gpu_manager_summary) {
                        GpuDriverActivity.start(this@SettingActivity)
                    }
                }

                SectionHeader(stringResource(R.string.settings_environment))
                SectionCard {
                    SwitchRow(Icons.Outlined.Security, R.string.hide_root, null, hideRoot) {
                        hideRoot = it; loader.invalidHideRoot(it); toast(R.string.restart_module)
                    }
                    Divider()
                    SwitchRow(Icons.Outlined.SettingsBackupRestore, R.string.daemon_enable, null, daemon) {
                        daemon = it; loader.invalidDaemonEnable(it); toast(R.string.restart_module)
                    }
                    Divider()
                    SwitchRow(Icons.Outlined.VpnLock, R.string.use_vpn_network, R.string.use_vpn_network_summary, vpn) {
                        vpn = it; loader.invalidUseVpnNetwork(it); toast(R.string.restart_module)
                    }
                    Divider()
                    SwitchRow(Icons.Outlined.Screenshot, R.string.disable_flag_secure, R.string.disable_flag_secure_summary, flagSecure) {
                        flagSecure = it; loader.invalidDisableFlagSecure(it); toast(R.string.restart_module)
                    }
                }

                SectionHeader(stringResource(R.string.spoof_category))
                SectionCard {
                    spoofKeys.forEachIndexed { i, (prop, label) ->
                        if (i > 0) Divider()
                        ListItem(
                            headlineContent = { Text(stringResource(label)) },
                            supportingContent = {
                                Text(spoof[prop]?.ifEmpty { null } ?: stringResource(R.string.spoof_not_set), maxLines = 2)
                            },
                            leadingContent = {
                                Icon(if (i == 4) Icons.Outlined.Fingerprint else Icons.Outlined.PhoneAndroid, null)
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { editing = prop to label }
                        )
                    }
                }

                SectionHeader(stringResource(R.string.settings_debug))
                SectionCard {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.send_logs)) },
                        supportingContent = { Text(stringResource(R.string.send_logs_summary)) },
                        leadingContent = { Icon(Icons.Outlined.BugReport, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = !sendingLogs) {
                            sendingLogs = true
                            BlackBoxCore.get().sendLogs("Manual Log Upload from Settings", true,
                                object : BlackBoxCore.LogSendListener {
                                    override fun onSuccess() = runOnUiThread { sendingLogs = false }
                                    override fun onFailure(error: String?) = runOnUiThread { sendingLogs = false }
                                })
                            toast(R.string.send_logs_started)
                        }
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        editing?.let { (prop, label) ->
            var text by remember(prop) { mutableStateOf(spoof[prop].orEmpty()) }
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text(stringResource(label)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.spoof_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = prop != "ro.build.fingerprint",
                            modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        saveSpoof(prop, text.trim())
                        if (text.isBlank()) spoof.remove(prop) else spoof[prop] = text.trim()
                        editing = null
                    }) { Text(stringResource(R.string.done)) }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } }
            )
        }
    }

    private fun saveSpoof(prop: String, value: String) {
        try {
            val updated = SpoofManager.loadProfile().toMutableMap()
            if (value.isEmpty()) updated.remove(prop) else updated[prop] = value
            SpoofManager.saveProfile(updated)
        } catch (e: Exception) {
            android.util.Log.e("SettingActivity", "spoof save failed: ${e.message}")
        }
    }

    @Composable
    private fun Divider() {
        HorizontalDivider(
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }

    @Composable
    private fun NavRow(icon: ImageVector, title: Int, summary: Int, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(summary)) },
            leadingContent = { Icon(icon, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick)
        )
    }

    @Composable
    private fun SwitchRow(icon: ImageVector, title: Int, summary: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
        ListItem(
            headlineContent = { Text(stringResource(title)) },
            supportingContent = summary?.let { { Text(stringResource(it)) } },
            leadingContent = { Icon(icon, null) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { onChange(!checked) }
        )
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingActivity::class.java))
        }
    }
}
