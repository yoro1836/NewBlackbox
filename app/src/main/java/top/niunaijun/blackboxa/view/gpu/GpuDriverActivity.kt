package top.niunaijun.blackboxa.view.gpu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import top.niunaijun.blackbox.core.gpu.VulkanDriverManager
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.databinding.ActivityGpuDriversBinding
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.base.BaseActivity
import kotlin.concurrent.thread

/** Lists imported Vulkan drivers, imports new packages, and opens per-driver scope. */
class GpuDriverActivity : BaseActivity() {
    private val binding: ActivityGpuDriversBinding by inflate()
    private val adapter = DriverAdapter()

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) importFrom(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initToolbar(binding.toolbarLayout.toolbar, R.string.gpu_manager, true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        if (!VulkanDriverManager.isSupported()) {
            toast(R.string.gpu_unsupported)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.gpu_driver_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.gpu_import) {
            // Driver packages are zips; many are served as octet-stream, so accept any file.
            importLauncher.launch("*/*")
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        thread {
            val drivers = VulkanDriverManager.listDrivers()
            runOnUiThread {
                adapter.submit(drivers)
                binding.empty.visibility = if (drivers.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun importFrom(uri: Uri) {
        thread {
            val result = try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    VulkanDriverManager.importDriver(stream)
                }
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

    private fun confirmDelete(driver: VulkanDriverManager.DriverInfo) {
        AlertDialog.Builder(this)
            .setTitle(driver.name)
            .setMessage(R.string.gpu_delete_confirm)
            .setPositiveButton(R.string.gpu_delete) { _, _ ->
                thread {
                    VulkanDriverManager.deleteDriver(driver.id)
                    runOnUiThread {
                        toast(R.string.gpu_driver_deleted)
                        refresh()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class DriverAdapter : RecyclerView.Adapter<DriverAdapter.VH>() {
        private var items: List<VulkanDriverManager.DriverInfo> = emptyList()

        fun submit(list: List<VulkanDriverManager.DriverInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_gpu_driver, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val driver = items[position]
            holder.name.text = driver.name
            val subtitle = listOf(driver.vendor, driver.driverVersion)
                .filter { it.isNotEmpty() }
                .joinToString("  ·  ")
            holder.meta.text = subtitle
            holder.meta.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
            holder.desc.text = driver.description
            holder.desc.visibility = if (driver.description.isEmpty()) View.GONE else View.VISIBLE
            val count = VulkanDriverManager.appsForDriver(driver.id).size
            holder.scope.text = if (count == 0) {
                getString(R.string.gpu_applies_none)
            } else {
                getString(R.string.gpu_applies_count, count)
            }
            holder.itemView.setOnClickListener {
                GpuDriverScopeActivity.start(this@GpuDriverActivity, driver.id, driver.name)
            }
            holder.itemView.setOnLongClickListener {
                confirmDelete(driver)
                true
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val meta: TextView = view.findViewById(R.id.meta)
            val desc: TextView = view.findViewById(R.id.desc)
            val scope: TextView = view.findViewById(R.id.scope)
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, GpuDriverActivity::class.java))
        }
    }
}
