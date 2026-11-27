package top.niunaijun.blackboxa.view.xposed

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import android.os.Bundle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import top.niunaijun.blackbox.core.xposed.XposedManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.ui.AppRow
import top.niunaijun.blackboxa.ui.AppScaffold
import top.niunaijun.blackboxa.ui.CenterProgress
import top.niunaijun.blackboxa.ui.EmptyState
import top.niunaijun.blackboxa.ui.setThemedContent
import kotlin.concurrent.thread

/** LSPosed-style module list: enable/disable modules and open their scope. */
class XposedModulesActivity : ComponentActivity() {
    private var modules by mutableStateOf<List<XposedModule>?>(null)
    private var installedPackages: Set<String> = emptySet()
    private var version by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        thread {
            val apps = XposedRepo.installedApps()
            val list = XposedRepo.modules(apps)
            runOnUiThread {
                installedPackages = apps.map { it.packageName }.toSet()
                modules = list
                version++
            }
        }
    }

    @Composable
    private fun Screen() {
        AppScaffold(title = stringResource(R.string.xposed_manager), onBack = { finish() }) { padding ->
            val list = modules
            @Suppress("UNUSED_VARIABLE") val v = version
            when {
                list == null -> CenterProgress(Modifier.padding(padding))
                list.isEmpty() -> EmptyState(stringResource(R.string.xposed_no_modules), Modifier.padding(padding))
                else -> LazyColumn(contentPadding = padding) {
                    items(list, key = { it.app.packageName }) { module ->
                        val pkg = module.app.packageName
                        val enabled = pkg in XposedManager.loadEnabledModules()
                        val scope = XposedManager.loadScope(pkg)
                        val scopeText = when {
                            !enabled -> stringResource(R.string.xposed_disabled)
                            scope == null -> stringResource(R.string.xposed_scope_all_apps)
                            else -> stringResource(R.string.xposed_scope_count, scope.size)
                        }
                        AppRow(
                            icon = module.app.icon,
                            title = module.app.label,
                            subtitle = if (module.version.isEmpty()) pkg else "$pkg · ${module.version}",
                            supporting = listOf(module.description, scopeText).filter { it.isNotEmpty() }.joinToString("\n"),
                            onClick = { XposedScopeActivity.start(this@XposedModulesActivity, pkg, module.app.label) },
                            trailing = { Switch(checked = enabled, onCheckedChange = { setEnabled(module, it) }) }
                        )
                    }
                }
            }
        }
    }

    private fun setEnabled(module: XposedModule, enabled: Boolean) {
        val set = LinkedHashSet(XposedManager.loadEnabledModules())
        if (enabled) {
            set.add(module.app.packageName)
            if (XposedManager.loadScope(module.app.packageName) == null) {
                // A freshly enabled module starts with its recommended apps only (LSPosed behaviour).
                XposedManager.saveScope(
                    module.app.packageName,
                    module.recommendedScope.filter { it in installedPackages }.toSet()
                )
            }
        } else {
            set.remove(module.app.packageName)
        }
        XposedManager.saveEnabledModules(set)
        version++
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, XposedModulesActivity::class.java))
        }
    }
}
