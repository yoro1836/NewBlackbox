package top.niunaijun.blackboxa.data

import android.content.Context
import java.io.File

/** Frame generation settings for one virtual app. */
data class FrameGenConfig(
    val on: Boolean = false,
    /** Extrapolation (predict ahead, less latency) instead of interpolation. */
    val extrapolate: Boolean = false,
    /** Output frame rate the generator aims for. */
    val target: Int = 120,
    /** Optical flow resolution, percent per axis. */
    val scale: Int = 75,
    /** Engine: [ENGINE_FSR] (FSR 3.1, 2x) or [ENGINE_LSFG] (Lossless Scaling's shaders, 2x .. 4x). */
    val engine: String = ENGINE_FSR,
    /** LSFG: most output frames per game frame. */
    val mult: Int = 2,
    /** The game queues at most one frame ahead of the GPU: less input delay. */
    val lowLatency: Boolean = false,
) {
    val lsfg get() = engine == ENGINE_LSFG

    fun encode() = "fg=${if (on) 1 else 0};extra=${if (extrapolate && !lsfg) 1 else 0};target=$target;scale=$scale" +
        ";engine=$engine;mult=$mult;lowlat=${if (lowLatency) 1 else 0}"

    companion object {
        const val ENGINE_FSR = "fsr"
        const val ENGINE_LSFG = "lsfg"
    }
}

/**
 * Per-app frame generation settings. Plain files, not SharedPreferences, so the game's
 * process reads what the UI process wrote. Virtual packages are shared by all virtual users,
 * so the settings are per package.
 */
object FrameGen {
    const val LIB = "libzfg_vk.so"

    private fun dir(c: Context) = File(c.filesDir, "framegen")
    private fun file(c: Context, pkg: String) = File(dir(c), "$pkg.txt")
    fun logFile(c: Context, pkg: String) = File(dir(c), "$pkg.log")

    fun library(c: Context): File? =
        File(c.applicationInfo.nativeLibraryDir, LIB).takeIf { it.isFile }

    fun get(c: Context, pkg: String): FrameGenConfig {
        val kv = try {
            file(c, pkg).takeIf { it.isFile }?.readText()?.trim()?.split(';')?.mapNotNull { p ->
                p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) }
            }?.toMap()
        } catch (_: Exception) {
            null
        } ?: return FrameGenConfig()
        return FrameGenConfig(
            on = kv["fg"] == "1",
            extrapolate = kv["extra"] == "1",
            target = kv["target"]?.toIntOrNull()?.coerceIn(30, 165) ?: 120,
            scale = kv["scale"]?.toIntOrNull()?.coerceIn(25, 100) ?: 75,
            engine = if (kv["engine"] == FrameGenConfig.ENGINE_LSFG) FrameGenConfig.ENGINE_LSFG else FrameGenConfig.ENGINE_FSR,
            mult = kv["mult"]?.toIntOrNull()?.coerceIn(2, 4) ?: 2,
            lowLatency = kv["lowlat"] == "1",
        )
    }

    fun put(c: Context, pkg: String, cfg: FrameGenConfig) {
        val f = file(c, pkg)
        f.parentFile?.mkdirs()
        f.writeText(cfg.encode())
    }

    /** Last lines of the game's wrapper log. */
    fun logTail(c: Context, pkg: String, lines: Int = 80): String? = try {
        logFile(c, pkg).takeIf { it.isFile }?.readLines()?.takeLast(lines)?.joinToString("\n")
    } catch (_: Exception) {
        null
    }
}
