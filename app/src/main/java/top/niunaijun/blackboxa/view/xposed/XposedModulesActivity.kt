package top.niunaijun.blackboxa.view.xposed

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackbox.core.xposed.XposedManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityXposedModulesBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.BaseActivity
import kotlin.concurrent.thread

/** LSPosed-style module list: enable/disable modules and open their scope. */
class XposedModulesActivity : BaseActivity() {
    private val binding: ActivityXposedModulesBinding by inflate()
    private val adapter = ModuleAdapter()
    private var installedPackages: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initToolbar(binding.toolbarLayout.toolbar, R.string.xposed_manager, true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        thread {
            val apps = XposedRepo.installedApps()
            val modules = XposedRepo.modules(apps)
            runOnUiThread {
                installedPackages = apps.map { it.packageName }.toSet()
                adapter.submit(modules)
                binding.empty.visibility = if (modules.isEmpty()) View.VISIBLE else View.GONE
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
        adapter.notifyDataSetChanged()
    }

    private inner class ModuleAdapter : RecyclerView.Adapter<ModuleAdapter.VH>() {
        private var items: List<XposedModule> = emptyList()

        fun submit(list: List<XposedModule>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_xposed_module, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val module = items[position]
            val pkg = module.app.packageName
            val enabled = pkg in XposedManager.loadEnabledModules()
            holder.icon.setImageDrawable(module.app.icon)
            holder.name.text = module.app.label
            holder.pkg.text = if (module.version.isEmpty()) pkg else "$pkg  ·  ${module.version}"
            holder.desc.text = module.description
            holder.desc.visibility = if (module.description.isEmpty()) View.GONE else View.VISIBLE
            val scope = XposedManager.loadScope(pkg)
            holder.scope.text = when {
                !enabled -> getString(R.string.xposed_disabled)
                scope == null -> getString(R.string.xposed_scope_all_apps)
                else -> getString(R.string.xposed_scope_count, scope.size)
            }
            holder.enable.setOnCheckedChangeListener(null)
            holder.enable.isChecked = enabled
            holder.enable.setOnCheckedChangeListener { _, checked -> setEnabled(module, checked) }
            holder.itemView.setOnClickListener {
                XposedScopeActivity.start(this@XposedModulesActivity, pkg, module.app.label)
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.name)
            val pkg: TextView = view.findViewById(R.id.pkg)
            val desc: TextView = view.findViewById(R.id.desc)
            val scope: TextView = view.findViewById(R.id.scope)
            val enable: Switch = view.findViewById(R.id.enable)
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, XposedModulesActivity::class.java))
        }
    }
}
