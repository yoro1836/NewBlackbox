package top.niunaijun.blackboxa.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches the pinned microG release (GitHub release assets, which are served from a CDN, then
 * microG's own F-Droid repository, which is slow from Asia) and checks the SHA-256. Bump the
 * versions and hashes together (from https://microg.org/fdroid/repo/index-v1.json).
 */
object MicroGDownloader {
    const val VERSION = "0.3.18"

    private val MIRRORS = listOf(
        "https://github.com/microg/GmsCore/releases/download/v0.3.18.252432/",
        "https://microg.org/fdroid/repo/"
    )

    class Apk(val label: String, val file: String, val sha256: String, val size: Long)

    val GMS_CORE = Apk("microG Services", "com.google.android.gms-252432036.apk",
        "1094fa60ace2367ac52bd08d1bd4f33fa8144ba930e355121b28234b8ebbf382", 108520063L)
    val COMPANION = Apk("microG Companion", "com.android.vending-84022636.apk",
        "3f1bde2f72bca746fbb53b61a32c2a85fcb026f886ee96d6fe9a05de9a6c6d5b", 4212484L)

    /** Returns the verified APK, reusing a complete earlier download. */
    fun fetch(apk: Apk, dir: File, progress: (Long, Long) -> Unit): File {
        dir.mkdirs()
        val out = File(dir, apk.file)
        if (out.length() == apk.size && sha256(out) == apk.sha256) {
            return out
        }
        val part = File(dir, apk.file + ".part")
        var lastError: IOException? = null
        for (base in MIRRORS) {
            try {
                download(base + apk.file, part, apk.size, progress)
                lastError = null
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        val hash = sha256(part)
        if (hash != apk.sha256) {
            part.delete()
            throw IOException("${apk.file}: SHA-256 mismatch ($hash)")
        }
        out.delete()
        if (!part.renameTo(out)) {
            throw IOException("Could not save ${apk.file}")
        }
        return out
    }

    /** Downloads url into part, resuming a partial file when the server honours Range. */
    private fun download(url: String, part: File, size: Long, progress: (Long, Long) -> Unit) {
        var have = if (part.length() in 1 until size) part.length() else 0L
        if (have == 0L) part.delete()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code == 200) {
                have = 0
            } else if (code != 206) {
                throw IOException("HTTP $code for $url")
            }
            val total = if (code == 206) size else conn.contentLengthLong.takeIf { it > 0 } ?: size
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, have > 0).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = have
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 512 * 1024) {
                            lastReport = done
                            progress(done, total)
                        }
                    }
                    progress(done, total)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(f: File): String {
        if (!f.isFile) return ""
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
