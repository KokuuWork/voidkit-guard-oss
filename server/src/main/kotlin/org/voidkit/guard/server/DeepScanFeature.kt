package org.voidkit.guard.server

import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import org.voidkit.core.api.ApiJson
import org.voidkit.core.api.ApiRouter
import org.voidkit.core.api.VoidkitServices
import org.voidkit.core.util.Msg
import org.voidkit.guard.common.DeepScanReq
import org.voidkit.guard.common.DeepScanResult
import org.voidkit.guard.common.GuardMsg
import org.voidkit.guard.platform.GPlayer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块五：深度扫描 + 综合判定报告。
 *
 * ## 深度扫描（服务端可控）
 *
 * `/guard scan <玩家>` 随时下发：客户端回传**本机 IP 列表 + 文件夹清单 +
 * 心跳 RTT**。专治「智能识别看不见」的那类情况 —— mods 目录里躺着一个
 * 没加载的作弊 jar、根目录里的来路不明 exe、多网卡/VPN 痕迹。
 *
 * ## 综合判定（[GuardVerdict]）
 *
 * 把所有线索汇成一份报告 + 一个结论：
 * 身份档案、握手结果、mod 清单与信号、硬件指纹、深度扫描、封禁历史。
 * 结论分四级：`clean / suspicious / likely / confirmed`，
 * 每条结论都带理由（哪条线索、加多少分），服主看报告就能判断，
 * 不用自己翻审计日志。
 */
class DeepScanFeature : GuardFeature() {

    override val id = "guard_deepscan"
    override val displayName = "深度扫描与综合判定"
    override val description = "可控上报本机 IP/文件夹清单/心跳，汇总多源线索生成作弊判定报告"

    /** 进行中的深度扫描请求。 */
    private class Pending(val reqId: String, val playerUuid: String, val deadline: Long, val admin: String)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** 每个玩家最近一次深度扫描结果（内存里留一份，报告即时可出）。 */
    private val lastResult = ConcurrentHashMap<String, DeepScanResult>()

    // ---------- 请求 ----------

    fun request(player: GPlayer, reason: String, admin: String): Boolean {
        if (!enabled) return false
        val s = hub.sessions.session(player.uuid)
        if (s == null || s.state != GuardSessions.State.VERIFIED) {
            hub.server.playerByName(admin)?.let { hub.server.sendErr(it, "玩家未通过校验，无法深度扫描") }
            return false
        }
        if (pending.values.any { it.playerUuid == player.uuid }) {
            hub.server.playerByName(admin)?.let { hub.server.sendInfo(it, "该玩家已有一个进行中的扫描") }
            return false
        }
        val reqId = UUID.randomUUID().toString().replace("-", "").take(16)
        pending[reqId] = Pending(reqId, player.uuid, System.currentTimeMillis() + 60_000L, admin)

        val req = DeepScanReq(
            reqId = reqId,
            scanIp = true,
            folderTargets = listOf("", "mods", "config", "logs", "versions", "screenshots"),
            heartbeat = true,
        )
        val body = hub.sessions.seal(s, GuardMsg.DEEP_SCAN_REQ, req.encode())
        if (body == null) {
            pending.remove(reqId)
            return false
        }
        hub.channel.send(player, GuardMsg.DEEP_SCAN_REQ, body)
        hub.audit(
            "deep_req", actor = admin, target = player.name, ip = player.ip,
            detail = mapOf("reqId" to reqId, "reason" to reason),
        )
        return true
    }

    /** 客户端回传结果。 */
    fun handleResult(s: GuardSessions.Session, result: DeepScanResult) {
        val p = pending.remove(result.reqId) ?: return
        if (p.playerUuid != s.player.uuid) return
        lastResult[s.player.uuid] = result

        val confirmed = result.signals.filter { it.severity >= 2 }
        val suspiciousProcs = result.processes.count { it.suspicious }
        hub.audit(
            "deep_done", actor = p.admin, target = s.player.name, ip = s.player.ip,
            detail = mapOf(
                "ips" to result.ips.size,
                "folders" to result.folders.size,
                "processes" to result.processes.size,
                "suspiciousProcesses" to suspiciousProcs,
                "rtt" to result.rttMs,
                "signals" to result.signals.map { "${it.type}:${it.detail}" },
            ),
        )

        // 管理员即时反馈（真实细节只给管理员）
        val admin = hub.server.playerByName(p.admin)
        if (admin != null) {
            if (confirmed.isNotEmpty()) {
                hub.server.sendErr(admin, "深度扫描发现 ${confirmed.size} 个确证信号：${confirmed.joinToString("；") { it.detail }}")
            } else {
                hub.server.sendOk(admin, "深度扫描完成：${result.ips.size} 个 IP、${result.folders.size} 个文件条目、RTT ${result.rttMs}ms")
            }
        }
    }

    // ---------- 周期：超时清理 ----------

    override fun onSecond(server: net.minecraft.server.MinecraftServer) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        pending.values.removeAll { now > it.deadline }
    }

    // ---------- 综合报告 ----------

    /** 生成一个玩家的综合判定报告（命令与 API 共用）。 */
    fun report(playerName: String): GuardVerdict.Report? {
        val online = hub.server.playerByName(playerName)
        val identity = hub.db.identityByName(playerName)
            ?: online?.let { hub.db.identityByUuid(it.uuid) }
        val session = online?.let { hub.sessions.session(it.uuid) }
            ?: identity?.let { hub.sessions.session(it.uuid) }
        val deep = identity?.let { lastResult[it.uuid] } ?: online?.let { lastResult[it.uuid] }
        return GuardVerdict.build(
            name = playerName,
            identity = identity,
            session = session?.report,
            deep = deep,
            verified = session?.state == GuardSessions.State.VERIFIED,
            failCount = session?.failCount ?: 0,
            banned = identity?.let { id ->
                hub.db.matchBan(
                    buildList {
                        add("uuid" to id.uuid)
                        add("name" to id.name.lowercase())
                        if (id.ip.isNotBlank()) add("ip" to id.ip)
                        if (id.machine.isNotBlank()) add("machine" to id.machine)
                        id.macs.forEachIndexed { i, m -> add("mac$i" to m) }
                        id.qq.forEachIndexed { i, q -> add("qq$i" to q) }
                    }
                )
            },
            xrayWarnings = identity?.let { hub.xrayStats.recentWarnings(it.uuid) } ?: emptyList(),
            combatWarnings = identity?.let { hub.combat.recentWarnings(it.uuid) } ?: emptyList(),
            related = identity?.let { hub.db.relatedIdentities(it) } ?: emptyList(),
            relatedBanned = identity?.let { hub.db.relatedBanned(it) } ?: emptyList(),
        )
    }

    // ---------- 命令 ----------

    override fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("gscan")
                .requires { VoidkitServices.permission().has(it, "voidkit.guard") }
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .executes {
                            val name = StringArgumentType.getString(it, "player")
                            val admin = it.source.player?.gameProfile?.name ?: "console"
                            val player = hub.server.playerByName(name)
                            if (player == null) {
                                Msg.err(it.source, "玩家 $name 不在线")
                                return@executes 0
                            }
                            if (request(player, "管理员扫描", admin)) {
                                Msg.ok(it.source, "已下发深度扫描，收到后此处会提示")
                                1
                            } else 0
                        }
                )
        )

        dispatcher.register(
            Commands.literal("greport")
                .requires { VoidkitServices.permission().has(it, "voidkit.guard") }
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .executes { reportCmd(it.source, StringArgumentType.getString(it, "player")) }
                )
        )
    }

    private fun reportCmd(source: CommandSourceStack, name: String): Int {
        val r = report(name)
        if (r == null) {
            Msg.err(source, "没有 $name 的任何记录（玩家进过服且通过校验才有档案）")
            return 0
        }
        Msg.info(source, "===== $name 综合判定报告 =====")
        Msg.text(source, "结论：${r.level.display}&7（${r.score} 分）")
        for (line in r.lines) Msg.text(source, line)
        if (r.reasons.isEmpty()) {
            Msg.info(source, "（没有任何可疑线索）")
        } else {
            Msg.info(source, "----- 判定依据 -----")
            for (reason in r.reasons) Msg.text(source, "&7- $reason")
        }
        return 1
    }

    // ---------- API ----------

    override fun registerApi(router: ApiRouter) {
        router.post("/api/guard/scan") { req ->
            val json = req.bodyJson()
            val name = json.get("player")?.asString ?: return@post ApiJson.error("bad_request", "缺少 player")
            val player = hub.server.playerByName(name) ?: return@post ApiJson.error("offline", "玩家不在线")
            val reason = json.get("reason")?.asString ?: "面板扫描"
            if (request(player, reason, admin = "panel")) {
                ApiJson.ok("已下发深度扫描")
            } else {
                ApiJson.error("busy", "请求未发出（已在扫描或未通过校验）")
            }
        }
        router.get("/api/guard/report/{player}") { req ->
            val name = req.param("player") ?: return@get ApiJson.error("bad_request", "缺少 player")
            val r = report(name) ?: return@get ApiJson.error("not_found", "没有该玩家的记录")
            ApiJson.obj(
                "ok", true,
                "player", name,
                "level", r.level.name,
                "levelDisplay", r.level.display,
                "score", r.score,
                "lines", ApiJson.arr(r.lines),
                "reasons", ApiJson.arr(r.reasons),
                "related", r.related?.map { hit ->
                    ApiJson.obj(
                        "name", hit.identity.name,
                        "reasons", ApiJson.arr(hit.reasons),
                        "strength", hit.strength,
                    )
                },
                "deep", r.deep?.let { d ->
                    ApiJson.obj(
                        "ips", ApiJson.arr(d.ips.map { "${it.iface}:${it.ip}${if (it.virtual) "(虚拟)" else ""}" }),
                        "files", ApiJson.arr(d.folders.map { "${it.dir}/${it.name}" }),
                        "rttMs", d.rttMs,
                    )
                },
            )
        }
    }
}

/**
 * 综合判定引擎。
 *
 * ## 评分逻辑（透明可审计）
 *
 * 每条线索一个分值，累加出总分；结论按分段映射。
 * 分数和理由都写进报告 —— 服主能看到「为什么是这个结论」，
 * 而不是面对一个黑盒分数。
 *
 * | 线索 | 分 |
 * |---|---|
 * | 确证级信号（已知作弊 mod / javaagent / 调试器 / 可疑文件） | +100 |
 * | Agent 自完整性失败 | +100 |
 * | 身份撞有效封禁 | +100 |
 * | 哈希锁白名单不一致 | +60 |
 * | 深度扫描确证信号（每个） | +50（封顶 100） |
 * | 可疑级信号（每个） | +15（封顶 45） |
 * | 保活失败累计（每次） | +10 |
 */
object GuardVerdict {

    enum class Level(val display: String) {
        CLEAN("&a正常"),
        SUSPICIOUS("&e可疑"),
        LIKELY("&6大概率作弊"),
        CONFIRMED("&c确证作弊"),
    }

    class Report(
        val name: String,
        val level: Level,
        val score: Int,
        val lines: List<String>,
        val reasons: List<String>,
        val deep: DeepScanResult?,
        /** 同源账号（小号挖掘），面板/命令展示用。 */
        val related: List<GuardDb.RelatedHit> = emptyList(),
    )

    fun build(
        name: String,
        identity: GuardDb.IdentityRow?,
        session: org.voidkit.guard.common.ClientReport?,
        deep: DeepScanResult?,
        verified: Boolean,
        failCount: Int,
        banned: GuardDb.BanRow?,
        /** 近 24h 挖掘统计告警（疑似信号，加分不确证）。 */
        xrayWarnings: List<String> = emptyList(),
        /** 近 24h 战斗遥测告警（同上）。 */
        combatWarnings: List<String> = emptyList(),
        /** 同源账号（小号挖掘结果，含各自命中字段）。 */
        related: List<GuardDb.RelatedHit> = emptyList(),
        /** 关联账号中正处在封禁中的（换号 evasion 实锤）。 */
        relatedBanned: List<GuardDb.RelatedHit> = emptyList(),
    ): Report {
        var score = 0
        val reasons = ArrayList<String>()
        val lines = ArrayList<String>()

        // ---- 身份档案 ----
        lines.add("身份：&f${identity?.name ?: name}&7｜UUID &f${identity?.uuid?.take(13) ?: "未知"}")
        val ipRegion = identity?.ip?.let { IpRegion.lookup(it) } ?: ""
        val ipText = identity?.ip?.ifBlank { "未知" } ?: "未知"
        lines.add("连接：&f$ipText&7${if (ipRegion.isNotBlank()) " &7($ipRegion)" else ""}｜机器码 &f${identity?.machine?.take(12)?.ifBlank { "未采集" } ?: "未采集"}")
        lines.add("网卡 &f${identity?.macs?.size ?: 0}&7 个｜QQ 记录 &f${identity?.qq?.size ?: 0}&7 个")

        // ---- 同源账号（小号挖掘） ----
        if (related.isNotEmpty()) {
            val top = related.take(5).joinToString("；") { hit ->
                "${hit.identity.name}&7（${hit.reasons.joinToString("/")}）"
            }
            lines.add("关联账号：&f${related.size}&7 个 —— $top")
        } else {
            lines.add("关联账号：&7无（机器码/网卡/QQ/IP 都没撞上）")
        }

        // ---- 握手状态 ----
        if (session != null) {
            lines.add("客户端：&f${session.clientBrand}&7｜Agent &f${session.agent}&7｜mod &f${session.mods.size}&7 个")
            val env = session.env
            if (env.cpu.isNotBlank() || env.gpu.isNotBlank()) {
                lines.add("环境：&f${env.osName} ${env.osVersion}&7｜&f${env.cpu.take(40)}&7｜&f${env.gpu.take(40)}&7｜&f${env.ramMb}MB&7｜&f${env.timezone}")
            }
        } else {
            lines.add("客户端：&7无握手记录（未通过校验或未安装）")
        }
        lines.add("状态：${if (verified) "&a已通过校验" else if (session != null) "&c未通过" else "&7不在线/无会话"}")

        // ---- 判定线索 ----
        if (banned != null) {
            score += 100
            reasons.add("身份撞有效封禁（#${banned.id} ${banned.keyType}）")
        }
        // 同源账号里有人被封 = 「换号 evasion」实锤档：机器码/网卡跟着硬件走，
        // 能共享到被封账号标识的，基本就是同一个人换皮重进。
        if (relatedBanned.isNotEmpty()) {
            score += 60
            reasons.add(
                "关联账号在封禁中 ${relatedBanned.size} 个（+60）：" +
                    relatedBanned.joinToString("；") { "${it.identity.name}（${it.reasons.joinToString("/")}）" }.take(120)
            )
        } else if (related.any { it.strength >= 4 }) {
            // 强同源（机器码共享）但没人被封：只提示不重罚 —— 兄弟共用电脑也可能
            score += 25
            reasons.add("存在机器码同源账号 ${related.count { it.strength >= 4 }} 个（+25，小号嫌疑，需人工复核）")
        }
        if (session != null) {
            val confirmed = session.signals.filter { it.severity >= 2 }
            if (confirmed.isNotEmpty()) {
                score += 100
                reasons.add("确证级信号 ${confirmed.size} 个：${confirmed.joinToString("；") { it.detail.take(60) }}")
            }
            val suspicious = session.signals.filter { it.severity == 1 }
            if (suspicious.isNotEmpty()) {
                val add = (suspicious.size * 15).coerceAtMost(45)
                score += add
                reasons.add("可疑级信号 ${suspicious.size} 个（+$add）")
            }
            if (!session.selfIntegrity.ok) {
                score += 100
                reasons.add("Agent 自完整性失败：${session.selfIntegrity.detail}")
            }
        }
        if (deep != null) {
            val dConfirmed = deep.signals.filter { it.severity >= 2 }
            if (dConfirmed.isNotEmpty()) {
                val add = (dConfirmed.size * 50).coerceAtMost(100)
                score += add
                reasons.add("深度扫描确证信号 ${dConfirmed.size} 个（+$add）：${dConfirmed.joinToString("；") { it.detail.take(60) }}")
            }
            val badProcs = deep.processes.filter { it.suspicious }
            if (badProcs.isNotEmpty()) {
                val add = (badProcs.size * 50).coerceAtMost(100)
                score += add
                reasons.add(
                    "发现可疑进程/窗口 ${badProcs.size} 个（+$add）：" +
                        badProcs.joinToString("；") { p -> p.name + (if (p.title.isNotBlank()) "（${p.title.take(30)}）" else "") }
                )
            }
            lines.add("深度扫描：&f${deep.ips.size}&7 个 IP｜&f${deep.folders.size}&7 个文件｜&f${deep.processes.size}&7 个进程｜RTT &f${deep.rttMs}ms")
            val virtualVpn = deep.ips.count { it.virtual }
            if (virtualVpn > 0) lines.add("网络：&f$virtualVpn&7 个虚拟网卡 IP（VPN/容器痕迹，仅记录）")
        } else {
            lines.add("深度扫描：&7未执行（/gscan 触发）")
        }
        if (failCount > 0) {
            score += failCount * 10
            reasons.add("历史校验失败 $failCount 次")
        }

        // ---- 统计检测告警（疑似档：只加分引导人工复核，永不单独触发处置） ----
        // 统计天生有误报空间（高延迟抬 reach、运气探洞抬稀有矿占比），所以：
        // 单条 +25，合计封顶 55 —— 即使两类全命中，单靠统计也只到「可疑」档
        // （≥20），够不到「大概率/确证」（≥60）。处置权永远留给服主。
        val statCount = xrayWarnings.size + combatWarnings.size
        if (statCount > 0) {
            score += (statCount * 25).coerceAtMost(55)
            if (xrayWarnings.isNotEmpty()) {
                reasons.add("挖掘统计检测疑似信号（需人工复核）：${xrayWarnings.joinToString("；") { it.take(60) }}")
            }
            if (combatWarnings.isNotEmpty()) {
                reasons.add("战斗遥测疑似信号（需人工复核）：${combatWarnings.joinToString("；") { it.take(60) }}")
            }
        }

        val level = when {
            score >= 100 -> Level.CONFIRMED
            score >= 60 -> Level.LIKELY
            score >= 20 -> Level.SUSPICIOUS
            else -> Level.CLEAN
        }
        return Report(name, level, score, lines, reasons, deep, related)
    }
}
