package top.niunaijun.blackboxa.view.gpu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.niunaijun.blackbox.core.gpu.VulkanDriverManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.ui.AppScaffold
import top.niunaijun.blackboxa.ui.CenterProgress
import top.niunaijun.blackboxa.ui.ConfirmDialog
import top.niunaijun.blackboxa.ui.EmptyState
import top.niunaijun.blackboxa.ui.setThemedContent
import top.niunaijun.blackboxa.util.toast
import kotlin.concurrent.thread

/** Lists imported Vulkan drivers, imports new packages, and opens per-driver scope. */
class GpuDriverActivity : ComponentActivity() {
    private var drivers by mutableStateOf<List<VulkanDriverManager.DriverInfo>?>(null)
    private var version by mutableIntStateOf(0)

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VulkanDriverManager.isSupported()) toast(R.string.gpu_unsupported)
        setThemedContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Screen() {
        var deleting by remember { mutableStateOf<VulkanDriverManager.DriverInfo?>(null) }
        AppScaffold(
            title = stringResource(R.string.gpu_manager),
            onBack = { finish() },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    // Driver packages are zips; many are served as octet-stream, so accept any file.
                    onClick = { importLauncher.launch("*/*") },
                    icon = { Icon(Icons.Outlined.FileOpen, null) },
                    text = { Text(stringResource(R.string.gpu_import)) }
                )
            }
        ) { padding ->
            val list = drivers
            @Suppress("UNUSED_VARIABLE") val v = version
            when {
                list == null -> CenterProgress(Modifier.padding(padding))
                list.isEmpty() -> EmptyState(stringResource(R.string.gpu_no_drivers), Modifier.padding(padding))
                else -> LazyColumn(contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp
                )) {
                    items(list, key = { it.id }) { driver ->
                        val meta = listOf(driver.vendor, driver.driverVersion).filter { it.isNotEmpty() }.joinToString(" · ")
                        val count = VulkanDriverManager.appsForDriver(driver.id).size
                        ListItem(
                            headlineContent = { Text(driver.name) },
                            overlineContent = if (meta.isNotEmpty()) ({ Text(meta) }) else null,
                            supportingContent = {
                                androidx.compose.foundation.layout.Column {
                                    if (driver.description.isNotEmpty()) Text(driver.description, maxLines = 3)
                                    Text(
                                        if (count == 0) stringResource(R.string.gpu_applies_none)
                                        else stringResource(R.string.gpu_applies_count, count),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            leadingContent = { Icon(Icons.Outlined.Memory, null) },
                            trailingContent = {
                                IconButton(onClick = { deleting = driver }) { Icon(Icons.Outlined.Delete, null) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.combinedClickable(
                                onClick = { GpuDriverScopeActivity.start(this@GpuDriverActivity, driver.id, driver.name) },
                                onLongClick = { deleting = driver }
                            )
                        )
                    }
                }
            }
        }
        deleting?.let { driver ->
            ConfirmDialog(
                title = driver.name,
                text = stringResource(R.string.gpu_delete_confirm),
                confirm = stringResource(R.string.gpu_delete),
                onConfirm = {
                    thread {
                        VulkanDriverManager.deleteDriver(driver.id)
                        runOnUiThread { toast(R.string.gpu_driver_deleted); refresh() }
                    }
                },
                onDismiss = { deleting = null }
            )
        }
    }

    private fun refresh() {
        thread {
            val list = VulkanDriverManager.listDrivers()
            runOnUiThread { drivers = list; version++ }
        }
    }

    private fun importFrom(uri: Uri) {
        thread {
            val result = try {
                contentResolver.openInputStream(uri)?.use { VulkanDriverManager.importDriver(it) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (result != null) {
                    toast(getString(R.string.gpu_import_success, result.name))
                    refresh()
                } else {
                    toast(R.string.gpu_import_failed)
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, GpuDriverActivity::class.java))
        }
    }
}
