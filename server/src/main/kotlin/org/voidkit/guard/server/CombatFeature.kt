package org.voidkit.guard.server

import net.minecraft.util.Mth
import org.voidkit.core.api.events.VPlayerQuit
import org.voidkit.core.util.ConfigFiles
import org.voidkit.guard.platform.GPlayer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 模块七：战斗遥测（服务端行为分析）—— reach / 瞄准急转 / CPS 机械性。
 *
 * ## 为什么这是「刺向技术玩家」的主刃
 *
 * 客户端校验（哈希锁/特征库/进程检测）拦的是「懒得藏」的人；战斗遥测拦的是
 * 「改客户端也没用」的人 —— 杀戮光环、自动点击、远程攻击全部会在**服务端
 *  observable 的行为数据**里留痕，与客户端装了什么、改了什么完全无关。
 * EAC/BattlEye 的看家本领正是这一类。
 *
 * ## 三个信号（互相独立，全部只告警不处置）
 *
 * 1. **reach**：攻击瞬间攻击者眼睛到目标碰撞箱最近点的距离。生存模式正常
 *    ≤3.5；持续超出阈值 = 远程攻击。创造/旁观在桥接层就滤掉（那俩模式
 *    5 格是合法的）。
 * 2. **aim snap**：攻击发生那个 tick 的合成视角角速度。人类打斗时视角钉在
 *    目标上（小 delta）；自动瞄准在攻击前瞬间大角度对齐 —— 若**高比例**的
 *    攻击都带大急转，是杀戮光环的典型指纹。单次大急转不判（人类也会甩镜头）。
 * 3. **CPS 机械性**：攻击间隔的变异系数 CV=σ/μ。人类高 CPS 时手抖（CV 大），
 *    自动点击器恒定（CV 小）。要求既快又稳才算 —— 慢而稳的点击器无害。
 *
 * ## 纪律：只告警，绝不自动处置
 *
 * 与 X 射线统计检测同一哲学：统计天生有误报空间（高延迟会同时抬高 reach 和
 * snap 的读数），所以只打日志 + 可选 JSONL，供服主人工判断。
 * 自动封禁的确证级门槛在 [GuardSessions]，两个体系互不越界。
 */
class CombatFeature : GuardFeature() {

    override val id = "guard_combat"
    override val displayName = "战斗遥测"
    override val description = "reach/瞄准急转/CPS 机械性滑动窗口分析，战斗类作弊只告警不处置"
    // 默认关：战斗检测是误报重灾区（高延迟会同时抬高 reach/snap 读数），
    // 宁可装完不动，服主想开再去面板「模块」页或 core 配置 features 表打开。
    override val defaultEnabled = false

    /** 一次攻击的样本。 */
    private class AttackSample(val at: Long, val distance: Double, val snapDeg: Double)

    private class PlayerCombat {
        var lastYaw = Float.NaN
        var lastPitch = Float.NaN
        /** 最近一个 tick 的合成视角角速度（度）—— 攻击时读它当 snap。 */
        var snapDeg: Double = 0.0
        val attacks = ArrayDeque<AttackSample>()
        val lastWarnAt = HashMap<String, Long>()
        /** 告警历史（供综合判定报告引用，保留最近 20 条）。 */
        val warnings = ArrayDeque<WarnRecord>()
    }

    /** 一条告警记录（报告用，带时间戳）。 */
    class WarnRecord(val at: Long, val reason: String, val metric: String)

    private val stats = ConcurrentHashMap<String, PlayerCombat>()

    // ---------- 事件入口（桥接层调用，均已在主线程） ----------

    /**
     * 每 tick 的视角采样（由 platform 桥接对所有在线玩家调用）。
     *
     * 只存 delta 不存历史：一个 tick 一次覆盖，内存 O(在线玩家)。
     */
    fun onRotate(uuid: String, yaw: Float, pitch: Float) {
        if (!enabled) return
        val s = stats.getOrPut(uuid) { PlayerCombat() }
        if (!s.lastYaw.isNaN()) {
            val dyaw = abs(Mth.wrapDegrees(yaw - s.lastYaw))
            val dpitch = abs(pitch - s.lastPitch)
            s.snapDeg = hypot(dyaw.toDouble(), dpitch.toDouble())
        }
        s.lastYaw = yaw
        s.lastPitch = pitch
    }

    /**
     * 攻击发生时的视角角速度读数（桥接层在调 [onAttack] 前取）。
     *
     * 0.0 = 该玩家还没被 tick 采样过（刚进服第一 tick 就挨打）。
     */
    fun snapOf(uuid: String): Double = stats[uuid]?.snapDeg ?: 0.0

    /**
     * 一次攻击。
     *
     * @param distance 攻击者眼睛到目标碰撞箱最近点的距离（格）。
     * @param snapDeg 攻击所在 tick 的合成视角角速度（度）。
     */
    fun onAttack(player: GPlayer, distance: Double, snapDeg: Double) {
        if (!enabled) return
        val cfg = hub.config
        val s = stats.getOrPut(player.uuid) { PlayerCombat() }
        val now = System.currentTimeMillis()
        s.attacks.addLast(AttackSample(now, distance, snapDeg))
        val windowMs = cfg.combatWindowMinutes * 60_000L
        while (s.attacks.isNotEmpty() && now - s.attacks.first().at > windowMs) {
            s.attacks.removeFirst()
        }
        evaluate(s, player, now)
    }

    // ---------- 判定 ----------

    private fun evaluate(s: PlayerCombat, player: GPlayer, now: Long) {
        val cfg = hub.config
        val n = s.attacks.size
        if (n < cfg.combatMinSamples) return

        // 信号一：reach 超距攻击占比
        val far = s.attacks.count { it.distance > cfg.combatReachThreshold }
        if (far > 0) {
            val ratio = far.toDouble() / n
            if (ratio >= cfg.combatReachRatioThreshold) {
                warn(
                    s, player, now, "reach",
                    "${"%.0f".format(ratio * 100)}% 的攻击距离超 ${cfg.combatReachThreshold} 格（$far/$n）",
                    "阈值 ≥${"%.0f".format(cfg.combatReachRatioThreshold * 100)}%",
                )
            }
        }

        // 信号二：瞄准急转占比（自动瞄准指纹）
        val snaps = s.attacks.count { it.snapDeg > cfg.combatSnapThresholdDeg }
        if (snaps > 0) {
            val ratio = snaps.toDouble() / n
            if (ratio >= cfg.combatSnapRatioThreshold) {
                warn(
                    s, player, now, "aim_snap",
                    "${"%.0f".format(ratio * 100)}% 的攻击前视角急转 >${cfg.combatSnapThresholdDeg.toInt()}°（$snaps/$n）",
                    "阈值 ≥${"%.0f".format(cfg.combatSnapRatioThreshold * 100)}%",
                )
            }
        }

        // 信号三：CPS 既快又稳（变异系数低于阈值的自动点击器指纹）
        val intervals = ArrayList<Long>(n)
        for (i in 1 until n) intervals.add(s.attacks[i].at - s.attacks[i - 1].at)
        if (intervals.size >= cfg.combatMinSamples) {
            val mean = intervals.average()
            if (mean > 0) {
                val cps = 1000.0 / mean
                val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
                val cv = sqrt(variance) / mean
                if (cps >= cfg.combatCpsMin && cv <= cfg.combatCpsCvThreshold) {
                    warn(
                        s, player, now, "cps_robotic",
                        "CPS ${"%.1f".format(cps)} 且间隔变异系数 ${"%.3f".format(cv)}（过于恒定）",
                        "阈值 CPS ≥${cfg.combatCpsMin} 且 CV ≤${cfg.combatCpsCvThreshold}",
                    )
                }
            }
        }
    }

    /** 告警出口：logger warn + 可选 JSONL。同玩家同原因冷却期内只报一次。 */
    private fun warn(s: PlayerCombat, player: GPlayer, now: Long, reason: String, metric: String, threshold: String) {
        val cfg = hub.config
        val cooldown = cfg.combatWarnCooldownSeconds * 1000L
        val last = s.lastWarnAt[reason] ?: 0L
        if (cooldown > 0 && now - last < cooldown) return
        s.lastWarnAt[reason] = now

        hub.logWarn(
            "战斗遥测：${player.name} 疑似战斗类作弊（$reason）｜$metric｜$threshold｜窗口 ${cfg.combatWindowMinutes} 分钟 ${s.attacks.size} 次攻击"
        )
        s.warnings.addLast(WarnRecord(now, reason, metric))
        while (s.warnings.size > 20) s.warnings.removeFirst()
        if (cfg.combatJsonlEnabled) appendJsonl(player, reason, metric, s)
    }

    /**
     * 玩家近 24 小时的战斗遥测告警（综合判定报告的数据源）。
     *
     * 同 X 射线的语义：**疑似**信号，报告里只做加分项供人工复核，不进确证档。
     */
    fun recentWarnings(uuid: String): List<String> {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        return stats[uuid]?.warnings
            ?.filter { it.at >= cutoff }
            ?.map { "${it.reason}（${it.metric}）" }
            ?: emptyList()
    }

    private fun appendJsonl(player: GPlayer, reason: String, metric: String, s: PlayerCombat) {
        runCatching {
            val path = ConfigFiles.resolve(hub.config.combatJsonlFile)
            Files.createDirectories(path.parent)
            val record = com.google.gson.JsonObject().apply {
                addProperty("ts", System.currentTimeMillis())
                addProperty("player", player.name)
                addProperty("uuid", player.uuid)
                addProperty("reason", reason)
                addProperty("metric", metric)
                addProperty("samples", s.attacks.size)
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

    /** 玩家退出即清：遥测是会话级数据，没有跨会话价值。 */
    override fun onPlayerQuit(e: VPlayerQuit) {
        stats.remove(e.player.stringUUID)
    }

    override fun onStart(server: net.minecraft.server.MinecraftServer) {
        val cfg = hub.config
        hub.logInfo(
            "guard: 战斗遥测已启用（窗口 ${cfg.combatWindowMinutes} 分钟｜reach >${cfg.combatReachThreshold}｜急转 >${cfg.combatSnapThresholdDeg.toInt()}°｜CPS ≥${cfg.combatCpsMin} 且 CV ≤${cfg.combatCpsCvThreshold}｜JSONL ${if (cfg.combatJsonlEnabled) "开" else "关"}）"
        )
    }

    override fun onServerStop(server: net.minecraft.server.MinecraftServer) {
        stats.clear()
    }
}
