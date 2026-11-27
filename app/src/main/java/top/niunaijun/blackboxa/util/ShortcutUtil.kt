package top.niunaijun.blackboxa.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import top.niunaijun.blackboxa.bean.AppInfo
import top.niunaijun.blackboxa.view.main.ShortcutActivity


object ShortcutUtil {

    fun isSupported(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut that opens the virtual app. */
    fun pinShortcut(context: Context, userID: Int, info: AppInfo, label: String): Boolean {
        if (!isSupported(context)) return false
        val intent = Intent(context, ShortcutActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra("pkg", info.packageName)
            .putExtra("userId", userID)
        val builder = ShortcutInfoCompat.Builder(context, info.packageName + userID)
            .setIntent(intent)
            .setShortLabel(label)
            .setLongLabel(label)
        info.icon?.let { builder.setIcon(IconCompat.createWithBitmap(it.toBitmap())) }
        return ShortcutManagerCompat.requestPinShortcut(context, builder.build(), null)
    }
}
