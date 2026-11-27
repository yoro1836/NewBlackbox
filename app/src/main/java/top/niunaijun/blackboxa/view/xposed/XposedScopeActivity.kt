package top.niunaijun.blackboxa.view.xposed

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.niunaijun.blackbox.core.xposed.XposedManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.ui.ScopeScreen
import top.niunaijun.blackboxa.ui.setThemedContent
import kotlin.concurrent.thread

/** Chooses which virtual apps a module is applied to. Changes are saved immediately. */
class XposedScopeActivity : ComponentActivity() {
    private lateinit var modulePkg: String
    private var apps by mutableStateOf<List<VirtualApp>?>(null)
    private var recommended: List<String> = emptyList()
    private val selected = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modulePkg = intent.getStringExtra(EXTRA_MODULE) ?: run { finish(); return }
        val label = intent.getStringExtra(EXTRA_LABEL)
        thread {
            val all = XposedRepo.installedApps()
            val module = XposedRepo.modules(all).firstOrNull { it.app.packageName == modulePkg }
            runOnUiThread {
                val list = all.filter { it.packageName != modulePkg }
                recommended = module?.recommendedScope.orEmpty()
                // No stored scope means a module enabled before scopes existed: it applied to everything.
                selected.addAll(XposedManager.loadScope(modulePkg) ?: list.map { it.packageName })
                apps = list
            }
        }
        setThemedContent {
            ScopeScreen(
                title = getString(R.string.xposed_scope),
                subtitle = label,
                hint = getString(R.string.xposed_scope_hint),
                apps = apps,
                isSelected = { it in selected },
                onToggle = { pkg ->
                    if (!selected.remove(pkg)) selected.add(pkg)
                    persist()
                },
                menu = listOf(
                    getString(R.string.xposed_scope_recommended) to {
                        selected.clear()
                        selected.addAll(recommended.filter { rec -> apps.orEmpty().any { it.packageName == rec } })
                        persist()
                    },
                    getString(R.string.xposed_scope_all) to {
                        selected.clear(); selected.addAll(apps.orEmpty().map { it.packageName }); persist()
                    },
                    getString(R.string.xposed_scope_none) to { selected.clear(); persist() },
                ),
                onBack = { finish() }
            )
        }
    }

    private fun persist() {
        XposedManager.saveScope(modulePkg, LinkedHashSet(selected))
    }

    companion object {
        private const val EXTRA_MODULE = "module"
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, modulePkg: String, label: String) {
            context.startActivity(
                Intent(context, XposedScopeActivity::class.java)
                    .putExtra(EXTRA_MODULE, modulePkg)
                    .putExtra(EXTRA_LABEL, label)
            )
        }
    }
}
