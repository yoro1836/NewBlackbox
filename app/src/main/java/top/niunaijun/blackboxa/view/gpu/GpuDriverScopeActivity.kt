package top.niunaijun.blackboxa.view.gpu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.niunaijun.blackbox.core.gpu.VulkanDriverManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.ui.ScopeScreen
import top.niunaijun.blackboxa.ui.setThemedContent
import top.niunaijun.blackboxa.view.xposed.VirtualApp
import top.niunaijun.blackboxa.view.xposed.XposedRepo
import kotlin.concurrent.thread

/**
 * Chooses which virtual apps use a given Vulkan driver. Assignment is exclusive:
 * checking an app here moves it off any other driver. Saved immediately.
 */
class GpuDriverScopeActivity : ComponentActivity() {
    private lateinit var driverId: String
    private var apps by mutableStateOf<List<VirtualApp>?>(null)
    private val selected = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        driverId = intent.getStringExtra(EXTRA_DRIVER) ?: run { finish(); return }
        val label = intent.getStringExtra(EXTRA_LABEL)
        thread {
            val all = XposedRepo.installedApps()
            runOnUiThread {
                selected.addAll(VulkanDriverManager.appsForDriver(driverId))
                apps = all
            }
        }
        setThemedContent {
            ScopeScreen(
                title = getString(R.string.gpu_scope),
                subtitle = label,
                hint = getString(R.string.gpu_scope_hint),
                apps = apps,
                isSelected = { it in selected },
                onToggle = { pkg ->
                    if (selected.remove(pkg)) {
                        VulkanDriverManager.setDriverForApp(pkg, null)
                    } else {
                        selected.add(pkg)
                        // Exclusive: assigning this driver replaces any previous one for the app.
                        VulkanDriverManager.setDriverForApp(pkg, driverId)
                    }
                },
                menu = listOf(
                    getString(R.string.gpu_scope_none) to {
                        selected.toList().forEach { VulkanDriverManager.setDriverForApp(it, null) }
                        selected.clear()
                    }
                ),
                onBack = { finish() }
            )
        }
    }

    companion object {
        private const val EXTRA_DRIVER = "driver"
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, driverId: String, label: String) {
            context.startActivity(
                Intent(context, GpuDriverScopeActivity::class.java)
                    .putExtra(EXTRA_DRIVER, driverId)
                    .putExtra(EXTRA_LABEL, label)
            )
        }
    }
}
