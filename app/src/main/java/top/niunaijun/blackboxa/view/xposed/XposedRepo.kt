package top.niunaijun.blackboxa.view.xposed

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.data.AppsRepository
import java.io.File

/** A virtual app (deduplicated across virtual users). */
data class VirtualApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val sourceDir: String,
)

/** An Xposed module installed in some virtual user. */
data class XposedModule(
    val app: VirtualApp,
    val version: String,
    val description: String,
    val recommendedScope: List<String>,
)

/** Reads installed virtual apps and module metadata for the Xposed management screens. */
object XposedRepo {
    private const val TAG = "XposedRepo"

    fun installedApps(): List<VirtualApp> {
        val core = BlackBoxCore.get()
        val pm = BlackBoxCore.getPackageManager()
        val seen = LinkedHashMap<String, VirtualApp>()
        for (user in core.users) {
            val list = try {
                core.getInstalledApplications(0, user.id)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list apps of user ${user.id}: ${e.message}")
                null
            } ?: continue
            for (info in list) {
                val pkg = info.packageName ?: continue
                if (seen.containsKey(pkg)) continue
                val label = try {
                    pm.getApplicationLabel(info).toString()
                } catch (e: Exception) {
                    pkg
                }
                val icon = try {
                    pm.getApplicationIcon(info)
                } catch (e: Exception) {
                    null
                }
                seen[pkg] = VirtualApp(pkg, label, icon, info.sourceDir ?: "")
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun modules(apps: List<VirtualApp> = installedApps()): List<XposedModule> =
        apps.filter { it.sourceDir.isNotEmpty() && AppsRepository.hasXposedInit(File(it.sourceDir)) }
            .map { readModule(it) }

    private fun readModule(app: VirtualApp): XposedModule {
        var version = ""
        var description = ""
        var recommended: List<String> = emptyList()
        try {
            val pm = BlackBoxCore.getPackageManager()
            val archive = pm.getPackageArchiveInfo(app.sourceDir, PackageManager.GET_META_DATA)
            if (archive != null) {
                version = archive.versionName ?: ""
                val ai = archive.applicationInfo
                if (ai != null) {
                    ai.sourceDir = app.sourceDir
                    ai.publicSourceDir = app.sourceDir
                    description = ai.metaData?.get("xposeddescription")?.let { value ->
                        if (value is Int) pm.getResourcesForApplication(ai).getString(value) else value.toString()
                    } ?: ""
                    recommended = when (val scope = ai.metaData?.get("xposedscope")) {
                        is Int -> pm.getResourcesForApplication(ai).getStringArray(scope).toList()
                        is String -> scope.split(';', ',')
                        else -> emptyList()
                    }.map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read module metadata of ${app.packageName}: ${e.message}")
        }
        return XposedModule(app, version, description, recommended)
    }
}
