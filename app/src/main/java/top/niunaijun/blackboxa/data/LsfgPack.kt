package top.niunaijun.blackboxa.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LSFG shaders, taken from the user's own copy of Lossless Scaling's Lossless.dll.
 * The DLL keeps them as PE resources; the SPIR-V builds sit 49 ids above the shader
 * numbers the wrapper uses. Only the shaders the wrapper runs are kept, in a small pack
 * file ("LSFP", count, then id, word count, words; little endian) the game process reads.
 */
object LsfgPack {
    private const val SPIRV_OFFSET = 49
    private const val SPIRV_MAGIC = 0x07230203
    private const val PACK_MAGIC = 0x5046534c // "LSFP"
    val IDS: List<Int> = listOf(255, 256) + (280..302)

    fun file(c: Context) = File(File(c.filesDir, "framegen"), "lsfg-shaders.pack")

    fun installed(c: Context): Boolean = file(c).let { it.isFile && it.length() > 8 }

    class BadDll(message: String) : Exception(message)

    /** Reads a Lossless.dll and writes the pack. Returns the number of shaders kept. */
    fun import(c: Context, dll: InputStream): Int {
        val (count, pack) = pack(dll.use { readAll(it) })
        val f = file(c)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(pack)
        if (!tmp.renameTo(f)) throw BadDll("cannot write ${f.name}")
        return count
    }

    /** The pack made from a Lossless.dll's bytes, and how many shaders it holds. */
    fun pack(bytes: ByteArray): Pair<Int, ByteArray> {
        val res = resources(bytes)
        val out = ByteArrayOutputStream()
        val shaders = IDS.map { id ->
            val data = res[id + SPIRV_OFFSET] ?: throw BadDll("shader ${id + SPIRV_OFFSET} missing")
            if (data.size < 20 || data.size % 4 != 0 || le32(data, 0) != SPIRV_MAGIC)
                throw BadDll("shader ${id + SPIRV_OFFSET} is not SPIR-V")
            id to data
        }
        out.write(int32(PACK_MAGIC))
        out.write(int32(shaders.size))
        for ((id, data) in shaders) {
            out.write(int32(id))
            out.write(int32(data.size / 4))
            out.write(data)
        }
        return shaders.size to out.toByteArray()
    }

    private fun readAll(s: InputStream): ByteArray {
        val o = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            total += n
            if (total > 256L shl 20) throw BadDll("file too large")
            o.write(buf, 0, n)
        }
        return o.toByteArray()
    }

    /** Resource id -> data, over all resource types (the shaders are RCDATA). */
    private fun resources(b: ByteArray): Map<Int, ByteArray> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        fun u16(at: Int): Int = if (at < 0 || at + 2 > b.size) throw BadDll("truncated") else bb.getShort(at).toInt() and 0xffff
        fun u32(at: Int): Long = if (at < 0 || at + 4 > b.size) throw BadDll("truncated") else bb.getInt(at).toLong() and 0xffffffffL

        if (b.size < 64 || u16(0) != 0x5a4d) throw BadDll("not a Windows DLL")
        val pe = u32(0x3c).toInt()
        if (u32(pe) != 0x00004550L) throw BadDll("not a PE file")
        val sections = u16(pe + 6)
        val optSize = u16(pe + 20)
        val opt = pe + 24
        val dirs = when (u16(opt)) {
            0x10b -> opt + 96
            0x20b -> opt + 112
            else -> throw BadDll("unknown PE format")
        }
        val rsrcRva = u32(dirs + 2 * 8)
        if (rsrcRva == 0L) throw BadDll("no resources")
        val secTable = opt + optSize
        fun offsetOf(rva: Long): Int {
            for (i in 0 until sections) {
                val s = secTable + i * 40
                val va = u32(s + 12)
                val size = maxOf(u32(s + 8), u32(s + 16))
                if (rva >= va && rva < va + size) return (rva - va + u32(s + 20)).toInt()
            }
            throw BadDll("bad resource address")
        }
        val root = offsetOf(rsrcRva)

        // Directory entries: (name or id, offset); the high bit marks a subdirectory.
        fun entries(dir: Int): List<Pair<Long, Long>> {
            val n = u16(dir + 12) + u16(dir + 14)
            if (n > 4096) throw BadDll("bad resource directory")
            return (0 until n).map { u32(dir + 16 + it * 8) to u32(dir + 16 + it * 8 + 4) }
        }
        val out = HashMap<Int, ByteArray>()
        val isDir = 0x80000000L
        for ((type, typeOff) in entries(root)) {
            if (typeOff and isDir == 0L) continue
            for ((name, nameOff) in entries(root + (typeOff and isDir.inv()).toInt())) {
                if (name and isDir != 0L || nameOff and isDir == 0L) continue // named, or no language level
                val id = name.toInt()
                for ((_, langOff) in entries(root + (nameOff and isDir.inv()).toInt())) {
                    if (langOff and isDir != 0L) continue
                    val entry = root + langOff.toInt()
                    val at = offsetOf(u32(entry))
                    val size = u32(entry + 4).toInt()
                    if (size < 0 || at + size > b.size) throw BadDll("truncated resource")
                    // RCDATA (10) wins over any other type with the same id.
                    if (type == 10L || id !in out) out[id] = b.copyOfRange(at, at + size)
                    break
                }
            }
        }
        return out
    }

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun int32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
