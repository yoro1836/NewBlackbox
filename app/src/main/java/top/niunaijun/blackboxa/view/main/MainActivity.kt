package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.ui.AppScaffold
import top.niunaijun.blackboxa.ui.LoadingDialog
import top.niunaijun.blackboxa.ui.setThemedContent
import top.niunaijun.blackboxa.view.apps.AppsPage
import top.niunaijun.blackboxa.view.apps.AppsPageController
import top.niunaijun.blackboxa.view.fake.FakeManagerActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"

        fun start(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java))
        }
    }

    /** Virtual user ids, plus one more id for the empty "new user" page. */
    private var pageUsers by mutableStateOf(listOf(0))
    private val controllers = HashMap<Int, AppsPageController>()
    private var pendingInstallUser = 0
    private var showStorageDialog by mutableStateOf(false)
    private var remarkVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            BlackBoxCore.get().onBeforeMainActivityOnCreate(this)
        } catch (e: Exception) {
            Log.e(TAG, "onBeforeMainActivityOnCreate: ${e.message}")
        }
        scanUsers()
        setThemedContent { MainScreen() }
        checkStoragePermission()
        checkVpnPermission()
        try {
            BlackBoxCore.get().onAfterMainActivityOnCreate(this)
        } catch (e: Exception) {
            Log.e(TAG, "onAfterMainActivityOnCreate: ${e.message}")
        }
    }

    private fun scanUsers() {
        val ids = try {
            BlackBoxCore.get().users.map { it.id }.sorted()
        } catch (e: Exception) {
            emptyList()
        }
        val next = (ids.maxOrNull() ?: -1) + 1
        pageUsers = ids + next
    }

    private fun remark(userId: Int): String {
        val r = AppManager.mRemarkSharedPreferences.getString("Remark$userId", null)
        return if (r.isNullOrEmpty()) getString(R.string.user_n, userId.toString()) else r
    }

    @Composable
    private fun MainScreen() {
        val users = pageUsers
        val pager = rememberPagerState { users.size }
        val currentUser = users.getOrElse(pager.currentPage) { 0 }
        var busy by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var renaming by remember { mutableStateOf(false) }
        @Suppress("UNUSED_EXPRESSION") remarkVersion

        AppScaffold(
            title = stringResource(R.string.app_name),
            subtitle = remark(currentUser),
            onTitleClick = { renaming = true },
            actions = {
                IconButton(onClick = {
                    startActivity(Intent(this@MainActivity, FakeManagerActivity::class.java).putExtra("userID", currentUser))
                }) { Icon(Icons.Outlined.LocationOn, contentDescription = stringResource(R.string.fake_location)) }
                IconButton(onClick = { SettingActivity.start(this@MainActivity) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.setting))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.open_source_path)) },
                            leadingIcon = { Icon(Icons.Outlined.Code, null) },
                            onClick = { menu = false; openUrl("https://github.com/ALEX5402/NewBlackbox") }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tg_group)) },
                            leadingIcon = { Icon(Icons.Outlined.Send, null) },
                            onClick = { menu = false; openUrl("https://t.me/newblackboxa") }
                        )
                    }
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        pendingInstallUser = currentUser
                        listResult.launch(Intent(this, ListActivity::class.java).putExtra("userID", currentUser))
                    },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text(stringResource(R.string.add_app)) }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state = pager, key = { users[it] }, modifier = Modifier.fillMaxSize()) { page ->
                    val userId = users[page]
                    val controller = controllers.getOrPut(userId) { AppsPageController() }
                    AppsPage(
                        userId = userId,
                        controller = controller,
                        contentPadding = padding,
                        onBusy = { busy = it },
                        onUsersChanged = { scanUsers() }
                    )
                }
                if (users.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 24.dp, bottom = padding.calculateBottomPadding() + 34.dp)
                    ) {
                        users.indices.forEach { i ->
                            val selected = i == pager.currentPage
                            Box(
                                Modifier
                                    .size(if (selected) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant
                                    )
                            )
                        }
                    }
                }
            }
        }

        if (busy) LoadingDialog()

        if (renaming) {
            var text by remember { mutableStateOf(remark(currentUser)) }
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text(stringResource(R.string.userRemark)) },
                text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        AppManager.mRemarkSharedPreferences.edit { putString("Remark$currentUser", text.trim()) }
                        remarkVersion++
                        renaming = false
                    }) { Text(stringResource(R.string.done)) }
                },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } }
            )
        }

        if (showStorageDialog) {
            AlertDialog(
                onDismissRequest = { showStorageDialog = false },
                title = { Text(stringResource(R.string.storage_permission_title)) },
                text = { Text(stringResource(R.string.storage_permission_text)) },
                confirmButton = {
                    TextButton(onClick = { showStorageDialog = false; openAllFilesAccessSettings() }) {
                        Text(stringResource(R.string.storage_permission_grant))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showStorageDialog = false }) { Text(stringResource(R.string.later)) }
                }
            )
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
        }
    }

    private val listResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            val data = it.data ?: return@registerForActivityResult
            val userId = data.getIntExtra("userID", pendingInstallUser)
            val source = data.getStringExtra("source") ?: return@registerForActivityResult
            controllers[userId]?.install(source)
        }
    }

    private fun checkStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) showStorageDialog = true
            } else {
                val perms = arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                if (perms.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                    requestPermissions(perms, 1001)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkStoragePermission: ${e.message}")
        }
    }

    private val storageResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            storageResult.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                storageResult.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }

    private val vpnResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    private fun checkVpnPermission() {
        try {
            VpnService.prepare(this)?.let { vpnResult.launch(it) }
        } catch (e: Exception) {
            Log.e(TAG, "checkVpnPermission: ${e.message}")
        }
    }
}
