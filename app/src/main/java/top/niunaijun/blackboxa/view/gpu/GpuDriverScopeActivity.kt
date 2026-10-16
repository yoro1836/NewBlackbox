package top.niunaijun.blackboxa.view.gpu

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
import top.niunaijun.blackbox.core.gpu.VulkanDriverManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityGpuScopeBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.view.base.BaseActivity
import top.niunaijun.blackboxa.view.xposed.VirtualApp
import top.niunaijun.blackboxa.view.xposed.XposedRepo
import kotlin.concurrent.thread

/**
 * Chooses which virtual apps use a given Vulkan driver. Assignment is exclusive:
 * checking an app here moves it off any other driver. Saved immediately.
 */
class GpuDriverScopeActivity : BaseActivity() {
    private val binding: ActivityGpuScopeBinding by inflate()
    private val adapter = ScopeAdapter()
    private lateinit var driverId: String
    private var apps: List<VirtualApp> = emptyList()
    private val selected = LinkedHashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        driverId = intent.getStringExtra(EXTRA_DRIVER) ?: run { finish(); return }
        initToolbar(binding.toolbarLayout.toolbar, R.string.gpu_scope, true)
        binding.toolbarLayout.toolbar.subtitle = intent.getStringExtra(EXTRA_LABEL)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        thread {
            val all = XposedRepo.installedApps()
            runOnUiThread {
                apps = all
                selected.addAll(VulkanDriverManager.appsForDriver(driverId))
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.gpu_scope_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.gpu_scope_none -> {
                for (pkg in selected.toList()) {
                    VulkanDriverManager.setDriverForApp(pkg, null)
                }
                selected.clear()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        adapter.notifyDataSetChanged()
        return true
    }

    private fun toggle(pkg: String) {
        if (selected.contains(pkg)) {
            selected.remove(pkg)
            VulkanDriverManager.setDriverForApp(pkg, null)
        } else {
            selected.add(pkg)
            // Exclusive: assigning this driver replaces any previous one for the app.
            VulkanDriverManager.setDriverForApp(pkg, driverId)
        }
    }

    private inner class ScopeAdapter : RecyclerView.Adapter<ScopeAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_gpu_scope, parent, false)
        )

        override fun getItemCount() = apps.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.label
            holder.pkg.text = app.packageName
            holder.check.isChecked = app.packageName in selected
            holder.itemView.setOnClickListener {
                toggle(app.packageName)
                holder.check.isChecked = app.packageName in selected
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
