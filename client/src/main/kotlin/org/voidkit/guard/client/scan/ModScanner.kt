package org.voidkit.guard.client.scan

import org.voidkit.guard.common.GCrypto
import org.voidkit.guard.common.ModEntry
import org.voidkit.guard.common.Signal
import org.voidkit.guard.platform.GModInfo
import org.voidkit.guard.platform.GuardPlatforms
import java.nio.file.Files
import java.nio.file.Path

/**
 * 客户端 mod 清单扫描。
 *
 * ## 两个职责
 *
 * 1. **列清单**：所有已加载 mod 的 (id, version, 文件名, 大小, SHA-256)。
 *    这是哈希锁的原料 —— 服务端拿它和白名单逐项比对。
 * 2. **初筛信号**：用服务端下发的特征串匹配 mod id / 文件名，
 *    产出 `signals` 上报（severity>=2 的才会被服务端拒）。
 *
 * ## 哈希缓存
 *
 * 几百个 mod 的 jar 全量 SHA-256 首次要几秒（整合包常见），
 * 所以按 (路径, 大小, mtime) 缓存到 `.minecraft/voidkit_guard/modcache.json` ——
 * 第二次进服毫秒级。改一个 mod 的 mtime 就会重算，缓存不会掩盖改动。
 */
object ModScanner {

    private const val CACHE_FILE = "voidkit_guard/modcache.json"

    /**
     * 列清单。
     *
     * @param forceFull true = 忽略本地缓存全量重算（中途复检用）。
     *   缓存按 (size, mtime) 命中，理论上「换文件但保留 size+mtime」能骗过缓存 ——
     *   复检时的全量重扫就是补这个缺口的。
     *
     * mod 枚举走 [GuardPlatforms.mods]（加载器抽象层）—— 不在业务代码里
     * 直接摸 `ModList`，Fabric 移植时这里零改动。
     */
    fun collect(forceFull: Boolean = false): List<ModEntry> = runCatching {
        val cache = if (forceFull) HashMap() else loadCache()
        var dirty = false
        val out = ArrayList<ModEntry>()
        for (info in GuardPlatforms.mods.loadedMods()) {
            val entry = entryFor(info, cache, forceFull)
            if (entry == null) {
                dirty = true
            } else {
                out.add(entry)
            }
        }
        if (dirty) saveCache(cache)
        out.sortedBy { it.id }
    }.getOrElse { emptyList() }

    private fun entryFor(info: GModInfo, cache: MutableMap<String, CacheItem>, forceFull: Boolean): ModEntry? {
        val path: Path = info.filePath?.let { runCatching { Path.of(it) }.getOrNull() } ?: return null
        val file = path.toFile()
        if (!file.isFile) return null

        val key = path.toAbsolutePath().toString()
        val size = file.length()
        val mtime = runCatching { Files.getLastModifiedTime(path).toString() }.getOrNull() ?: ""
        val cached = cache[key]
        val hash = if (!forceFull && cached != null && cached.size == size && cached.mtime == mtime) {
            cached.sha256
        } else {
            runCatching { GCrypto.sha256Hex(Files.readAllBytes(path)) }.getOrElse { return null }
                .also { cache[key] = CacheItem(size, mtime, it) }
        }
        return ModEntry(
            id = info.id,
            version = info.version,
            file = file.name,
            size = size,
            sha256 = hash,
        )
    }

    /**
     * 特征匹配：把服务端下发的特征串对 (modId + 文件名) 做子串包含。
     *
     * 分级由**服务端**的签名表决定（确证级/可疑级两列）—— 客户端只负责
     * 「撞上了就报」，不自己判生死，避免客户端被改后放宽判定。
     */
    fun signals(mods: List<ModEntry>, signatures: List<String>): List<Signal> {
        if (signatures.isEmpty()) return emptyList()
        val out = ArrayList<Signal>()
        for (m in mods) {
            val hay = (m.id + " " + m.file).lowercase()
            for (sig in signatures) {
                if (sig.isBlank()) continue
                if (hay.contains(sig.lowercase())) {
                    out.add(Signal("signature_hit", "mod ${m.id} 命中特征 $sig", 1))
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 缓存

    private class CacheItem(val size: Long, val mtime: String, val sha256: String)

    private fun cachePath(): Path =
        GuardPlatforms.gameDir().resolve(CACHE_FILE)

    private fun loadCache(): MutableMap<String, CacheItem> = runCatching {
        val f = cachePath().toFile()
        if (!f.isFile) return HashMap()
        val obj = com.google.gson.JsonParser.parseString(f.readText()).takeIf { it.isJsonObject }?.asJsonObject
            ?: return HashMap()
        val out = HashMap<String, CacheItem>()
        for ((k, v) in obj.entrySet()) {
            val o = v.asJsonObject
            out[k] = CacheItem(
                o.get("size")?.asLong ?: -1,
                o.get("mtime")?.asString ?: "",
                o.get("sha256")?.asString ?: "",
            )
        }
        out
    }.getOrElse { HashMap() }

    private fun saveCache(cache: Map<String, CacheItem>) {
        runCatching {
            val obj = com.google.gson.JsonObject()
            for ((k, v) in cache) {
                obj.add(k, com.google.gson.JsonObject().apply {
                    addProperty("size", v.size)
                    addProperty("mtime", v.mtime)
                    addProperty("sha256", v.sha256)
                })
            }
            val f = cachePath().toFile()
            f.parentFile?.mkdirs()
            // 原子写：先 tmp 再替换，避免写到一半崩掉留个坏 JSON
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(f)
        }
    }
}