package org.voidkit.guard.server

import org.voidkit.core.util.ConfigFiles
import org.voidkit.core.api.events.VPlayerQuit
import org.voidkit.guard.platform.GPlayer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块六：X 射线统计检测（服务端行为分析）。
 *
 * ## 为什么用「统计」而不是「特征」
 *
 * 透视类作弊不修改协议、不留下已知 mod 特征（行为类作弊），服务端无法直接
 * 「看到」玩家开了透视 —— 但挖矿行为会说话：
 *
 * - **稀有矿占比异常**：正常玩家（含洞穴探索、合理条纹挖矿）挖出的方块里
 *   钻石/绿宝石级别矿石占比远低于 5%；透视用户能精准直奔矿脉，占比显著升高。
 * - **暴露度异常低**：本模块在玩家挖到稀有矿时，统计该方块六面中「贴着空气」
 *   的面数。正常玩家只能看到**已经暴露**的矿才去挖（≥1 面临空）；被墙包死、
 *   六面全是石头还能精准挖到的，是透视最强的行为信号。
 *
 * ## 纪律：只告警，绝不自动处置
 *
 * 统计检测天生有误报空间（运气好的探洞者、大型矿洞清理），所以本模块
 * **只打日志 + 可选 JSONL 落盘**，供服主人工判断 —— 宁可漏报，不可误伤。
 * 自动封禁的哲学门槛（确证级证据）在 [GuardSessions] 那边，两个体系互不越界。
 *
 * 事件来源：platform/neoforge 的方块破坏桥接（零 mixin，加载器事件 API）。
 * 本文件不 import 任何加载器类，只收 `blockId 字符串 + 暴露面数`。
 */
class XrayStatisticsFeature : GuardFeature() {

    override val id = "guard_xray_stats"
    override val displayName = "挖掘统计检测"
    override val description = "稀有矿占比 + 挖矿暴露度滑动窗口分析，透视行为只告警不处置"

    // ---------- 矿物分级 ----------

    /**
     * 稀有矿识别：按注册名子串匹配（覆盖深板岩/凝灰岩变体与模组矿）。
     * 古代残骸没有 _ore 后缀，单独点名。
     */
    private val RARE_MARKERS = listOf("diamond_ore", "emerald_ore", "ancient_debris")

    // ---------- 会话统计 ----------

    /** 挖到稀有矿时的一条样本（时间戳 + 当时暴露的空气面数）。 */
    private class RareDig(val at: Long, val exposure: Int)

    /** 单个玩家的累计统计。只在服务端主线程读写（事件桥接统一 execute 切主线程）。 */
    private class PlayerStats {
        var minedTotal: Long = 0
        var commonOres: Long = 0
        var rareOres: Long = 0
        /** 稀有矿样本队列，按时间窗口裁剪 —— 滑动窗口的存储本体。 */
        val rareDigs = ArrayDeque<RareDig>()
        /** 每个告警原因上次触发时间（防日志刷屏）。 */
        val lastWarnAt = HashMap<String, Long>()
    }

    private val stats = ConcurrentHashMap<String, PlayerStats>()

    // ---------- 事件入口 ----------

    /**
     * 方块破坏桥接回调（已在服务端主线程）。
     *
     * @param blockId 方块注册名（如 `minecraft:deepslate_diamond_ore`）。
     * @param exposedAirFaces 被挖方块六面中贴着空气的面数（近似暴露度）。
     */
    fun onBlockBreak(player: GPlayer, blockId: String, exposedAirFaces: Int) {
        if (!enabled || !hub.config.xrayStatsEnabled) return
        val s = stats.getOrPut(player.uuid) { PlayerStats() }
        s.minedTotal++

        val isRare = RARE_MARKERS.any { it in blockId }
        if (blockId.endsWith("_ore") || blockId == "ancient_debris") {
            if (isRare) s.rareOres++ else s.commonOres++
        }
        // 非矿石不计入分级，但计入总数 —— 占比的分母是「全部挖掉的方块」

        if (!isRare) return

        val cfg = hub.config
        val now = System.currentTimeMillis()
        s.rareDigs.addLast(RareDig(now, exposedAirFaces))
        pruneWindow(s, now, cfg.xrayStatsWindowMinutes * 60_000L)
        evaluate(s, player, now)
    }

    // ---------- 判定 ----------

    private fun pruneWindow(s: PlayerStats, now: Long, windowMs: Long) {
        while (s.rareDigs.isNotEmpty() && now - s.rareDigs.first().at > windowMs) {
            s.rareDigs.removeFirst()
        }
    }

    /** 逐条检查两个独立信号，命中即告警（带冷却）。两个信号互相独立，都足够可疑。 */
    private fun evaluate(s: PlayerStats, player: GPlayer, now: Long) {
        val cfg = hub.config

        // 信号一：累计稀有矿占比异常（样本量不足时宁可沉默 —— 冷启动误报是统计检测头号杀手）
        if (s.minedTotal >= cfg.xrayStatsMinMinedSamples) {
            val ratio = s.rareOres.toDouble() / s.minedTotal
            if (ratio >= cfg.xrayStatsRareRatioThreshold) {
                warn(
                    s, player, now, "rare_ratio",
                    "稀有矿占比 ${"%.1f".format(ratio * 100)}%（${s.rareOres}/${s.minedTotal}）",
                    "阈值 ≥${"%.1f".format(cfg.xrayStatsRareRatioThreshold * 100)}%",
                )
            }
        }

        // 信号二：窗口内稀有矿平均暴露度异常低（被墙包着还能精准挖到，透视最强信号）
        if (s.rareDigs.size >= cfg.xrayStatsMinRareSamples) {
            val avg = s.rareDigs.sumOf { it.exposure }.toDouble() / s.rareDigs.size
            if (avg <= cfg.xrayStatsExposureThreshold) {
                warn(
                    s, player, now, "low_exposure",
                    "近 ${cfg.xrayStatsWindowMinutes} 分钟挖到稀有矿 ${s.rareDigs.size} 块，平均暴露面 ${"%.2f".format(avg)}",
                    "阈值 ≤${cfg.xrayStatsExposureThreshold}（样本 ≥${cfg.xrayStatsMinRareSamples}）",
                )
            }
        }
    }

    /** 告警出口：logger warn + 可选 JSONL。同玩家同原因在冷却期内只报一次。 */
    private fun warn(s: PlayerStats, player: GPlayer, now: Long, reason: String, metric: String, threshold: String) {
        val cfg = hub.config
        val cooldown = cfg.xrayStatsWarnCooldownSeconds * 1000L
        val last = s.lastWarnAt[reason] ?: 0L
        if (cooldown > 0 && now - last < cooldown) return
        s.lastWarnAt[reason] = now

        val line = "X 射线统计检测：${player.name} 疑似透视类行为（$reason）｜$metric｜$threshold｜累计 普通矿 ${s.commonOres}/稀有矿 ${s.rareOres}/总 ${s.minedTotal}"
        hub.logWarn(line)

        if (cfg.xrayStatsJsonlEnabled) {
            appendJsonl(player, reason, metric, s)
        }
    }

    /** JSONL 一行一条，追加写。写失败只吞掉（取证是尽力而为，不能影响主流程）。 */
    private fun appendJsonl(player: GPlayer, reason: String, metric: String, s: PlayerStats) {
        runCatching {
            val path = ConfigFiles.resolve(hub.config.xrayStatsJsonlFile)
            Files.createDirectories(path.parent)
            val record = com.google.gson.JsonObject().apply {
                addProperty("ts", System.currentTimeMillis())
                addProperty("player", player.name)
                addProperty("uuid", player.uuid)
                addProperty("reason", reason)
                addProperty("metric", metric)
                addProperty("minedTotal", s.minedTotal)
                addProperty("commonOres", s.commonOres)
                addProperty("rareOres", s.rareOres)
            }
            Files.writeString(
                path,
                record.toString() + System.lineSeparator(),
                Charsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        }
    }

    // ---------- 生命周期 ----------

    /** 玩家退出即清统计：累计比率型的会话数据没有跨会话价值，留着纯浪费内存。 */
    override fun onPlayerQuit(e: VPlayerQuit) {
        stats.remove(e.player.stringUUID)
    }

    override fun onStart(server: net.minecraft.server.MinecraftServer) {
        val cfg = hub.config
        if (cfg.xrayStatsEnabled) {
            hub.logInfo(
                "guard: X 射线统计检测已启用（窗口 ${cfg.xrayStatsWindowMinutes} 分钟｜占比阈值 ${"%.0f".format(cfg.xrayStatsRareRatioThreshold * 100)}%｜暴露度阈值 ${cfg.xrayStatsExposureThreshold}｜JSONL ${if (cfg.xrayStatsJsonlEnabled) "开" else "关"}）"
            )
        }
    }

    override fun onServerStop(server: net.minecraft.server.MinecraftServer) {
        stats.clear()
    }
}
