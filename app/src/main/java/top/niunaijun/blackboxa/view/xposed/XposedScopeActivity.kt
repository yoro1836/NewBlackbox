package top.niunaijun.blackboxa.view.xposed

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackbox.core.xposed.XposedManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityXposedScopeBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.BaseActivity
import kotlin.concurrent.thread

/** Chooses which virtual apps a module is applied to. Changes are saved immediately. */
class XposedScopeActivity : BaseActivity() {
    private val binding: ActivityXposedScopeBinding by inflate()
    private val adapter = ScopeAdapter()
    private lateinit var modulePkg: String
    private var apps: List<VirtualApp> = emptyList()
    private var recommended: List<String> = emptyList()
    private val selected = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        modulePkg = intent.getStringExtra(EXTRA_MODULE) ?: run { finish(); return }
        initToolbar(binding.toolbarLayout.toolbar, R.string.xposed_scope, true)
        binding.toolbarLayout.toolbar.subtitle = intent.getStringExtra(EXTRA_LABEL)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        thread {
            val all = XposedRepo.installedApps()
            val module = XposedRepo.modules(all).firstOrNull { it.app.packageName == modulePkg }
            runOnUiThread {
                apps = all.filter { it.packageName != modulePkg }
                recommended = module?.recommendedScope.orEmpty()
                // No stored scope means a module enabled before scopes existed: it applied to everything.
                selected.addAll(XposedManager.loadScope(modulePkg) ?: apps.map { it.packageName })
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.xposed_scope_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.scope_recommended -> {
                selected.clear()
                selected.addAll(recommended.filter { rec -> apps.any { it.packageName == rec } })
            }
            R.id.scope_all -> {
                selected.clear()
                selected.addAll(apps.map { it.packageName })
            }
            R.id.scope_none -> selected.clear()
            else -> return super.onOptionsItemSelected(item)
        }
        persist()
        adapter.notifyDataSetChanged()
        return true
    }

    private fun persist() {
        XposedManager.saveScope(modulePkg, selected)
    }

    private inner class ScopeAdapter : RecyclerView.Adapter<ScopeAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_xposed_scope, parent, false)
        )

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.label
            holder.pkg.text = app.packageName
            holder.check.isChecked = app.packageName in selected
            holder.itemView.setOnClickListener {
                if (!selected.add(app.packageName)) selected.remove(app.packageName)
                holder.check.isChecked = app.packageName in selected
                persist()
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon)
            val name: TextView = view.findViewById(R.id.name)
            val pkg: TextView = view.findViewById(R.id.pkg)
            val check: CheckBox = view.findViewById(R.id.check)
        }
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
