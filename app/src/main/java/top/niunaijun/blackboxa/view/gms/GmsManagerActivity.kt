package top.niunaijun.blackboxa.view.gms

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.data.MicroGDownloader
import top.niunaijun.blackboxa.ui.AppScaffold
import top.niunaijun.blackboxa.ui.CenterProgress
import top.niunaijun.blackboxa.ui.ConfirmDialog
import top.niunaijun.blackboxa.ui.InfoDialog
import top.niunaijun.blackboxa.ui.LoadingDialog
import top.niunaijun.blackboxa.ui.setThemedContent
import top.niunaijun.blackboxa.util.InjectionUtil
import top.niunaijun.blackboxa.util.toast

/** Per-user Google services: microG or a copy of the device GMS, plus account sign-out. */
class GmsManagerActivity : ComponentActivity() {

    private lateinit var viewModel: GmsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, InjectionUtil.getGmsFactory())[GmsViewModel::class.java]
        viewModel.getInstalledUser()
        setThemedContent { GmsScreen() }
    }

    private sealed interface DialogState {
        data class Choose(val userId: Int) : DialogState
        data class ConfirmHost(val userId: Int) : DialogState
        data class ConfirmOff(val userId: Int) : DialogState
        data class Accounts(val userId: Int, val accounts: List<Account>) : DialogState
        data class ConfirmSignOut(val userId: Int, val account: Account) : DialogState
        data class Error(val text: String) : DialogState
    }

    @Composable
    private fun GmsScreen() {
        val users by viewModel.mInstalledLiveData.observeAsState()
        val result by viewModel.mUpdateInstalledLiveData.observeAsState()
        val progress by viewModel.mProgressLiveData.observeAsState()
        var dialog by remember { mutableStateOf<DialogState?>(null) }
        var busy by remember { mutableStateOf(false) }
        var downloading by remember { mutableStateOf(false) }

        LaunchedEffect(result) {
            val r = result ?: return@LaunchedEffect
            busy = false
            downloading = false
            if (r.success) toast(r.msg) else dialog = DialogState.Error(r.msg)
            viewModel.mUpdateInstalledLiveData.value = null
            viewModel.getInstalledUser()
        }

        AppScaffold(title = stringResource(R.string.gms_manager), onBack = { finish() }) { padding ->
            val list = users
            if (list == null) {
                CenterProgress(Modifier.padding(padding))
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            stringResource(R.string.gms_manager_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                    items(list, key = { it.userID }) { bean ->
                        UserCard(
                            bean,
                            onToggle = {
                                dialog = if (bean.isInstalledGms) DialogState.ConfirmOff(bean.userID)
                                else DialogState.Choose(bean.userID)
                            },
                            onSettings = { openMicroGSettings(bean.userID) },
                            onSignOut = {
                                val accounts = try {
                                    BlackBoxCore.get().getGoogleAccounts(bean.userID).toList()
                                } catch (e: Throwable) {
                                    emptyList()
                                }
                                if (accounts.isEmpty()) toast(getString(R.string.google_no_accounts))
                                else dialog = DialogState.Accounts(bean.userID, accounts)
                            }
                        )
                    }
                }
            }
        }

        when (val d = dialog) {
            is DialogState.Choose -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.gms_choose)) },
                text = {
                    Column {
                        ChoiceRow(
                            stringResource(R.string.gms_choose_microg, MicroGDownloader.VERSION),
                            stringResource(R.string.gms_choose_microg_summary)
                        ) {
                            dialog = null
                            downloading = true
                            busy = true
                            viewModel.installMicroG(d.userId, cacheDir)
                        }
                        ChoiceRow(
                            stringResource(R.string.gms_choose_host),
                            stringResource(R.string.enable_gms_hint)
                        ) { dialog = DialogState.ConfirmHost(d.userId) }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } }
            )
            is DialogState.ConfirmHost -> ConfirmDialog(
                title = stringResource(R.string.enable_gms),
                text = stringResource(R.string.enable_gms_hint),
                onConfirm = { busy = true; viewModel.installGms(d.userId) },
                onDismiss = { dialog = null }
            )
            is DialogState.ConfirmOff -> ConfirmDialog(
                title = stringResource(R.string.disable_gms),
                text = stringResource(R.string.disable_gms_hint),
                onConfirm = { busy = true; viewModel.uninstallGms(d.userId) },
                onDismiss = { dialog = null }
            )
            is DialogState.Accounts -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.google_sign_out)) },
                text = {
                    Column {
                        d.accounts.forEach { account ->
                            ListItem(
                                headlineContent = { Text(account.name) },
                                leadingContent = { Icon(Icons.Outlined.AccountCircle, null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    dialog = DialogState.ConfirmSignOut(d.userId, account)
                                }
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.cancel)) } }
            )
            is DialogState.ConfirmSignOut -> ConfirmDialog(
                title = stringResource(R.string.google_sign_out),
                text = stringResource(R.string.google_sign_out_confirm, d.account.name),
                confirm = stringResource(R.string.google_sign_out),
                onConfirm = {
                    val ok = try {
                        BlackBoxCore.get().removeGoogleAccount(d.account, d.userId)
                    } catch (e: Throwable) {
                        false
                    }
                    toast(getString(if (ok) R.string.google_signed_out else R.string.google_sign_out_failed))
                },
                onDismiss = { dialog = null }
            )
            is DialogState.Error -> InfoDialog(
                title = stringResource(R.string.gms_manager), text = d.text, onDismiss = { dialog = null }
            )
            null -> {}
        }

        if (downloading) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.microg_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.microg_hint))
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(progress ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {}
            )
        } else if (busy) {
            LoadingDialog()
        }
    }

    @Composable
    private fun ChoiceRow(title: String, summary: String, onClick: () -> Unit) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary, maxLines = 3) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onClick)
        )
    }

    @Composable
    private fun UserCard(bean: GmsBean, onToggle: () -> Unit, onSettings: () -> Unit, onSignOut: () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bean.userName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                !bean.isInstalledGms -> stringResource(R.string.gms_off)
                                bean.isMicroG -> stringResource(R.string.gms_kind_microg)
                                else -> stringResource(R.string.gms_kind_host)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = bean.isInstalledGms, onCheckedChange = { onToggle() })
                }
                if (bean.isInstalledGms) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (bean.isMicroG) {
                            FilledTonalButton(onClick = onSettings) {
                                Icon(Icons.Outlined.Settings, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.microg_settings))
                            }
                        }
                        OutlinedButton(onClick = onSignOut) {
                            Icon(Icons.AutoMirrored.Outlined.Logout, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.google_sign_out))
                        }
                    }
                }
            }
        }
    }

    private fun openMicroGSettings(userID: Int) {
        val ok = try {
            BlackBoxCore.get().launchApk("com.google.android.gms", userID)
        } catch (e: Throwable) {
            false
        }
        if (!ok) toast(getString(R.string.microg_settings_failed))
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, GmsManagerActivity::class.java))
        }
    }
}
