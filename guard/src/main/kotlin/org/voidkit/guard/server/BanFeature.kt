package org.voidkit.guard.server

import org.voidkit.core.api.ApiJson
import org.voidkit.core.api.ApiRouter
import org.voidkit.core.api.Feature
import org.voidkit.core.api.VoidkitServices
import org.voidkit.core.api.events.VPlayerJoin
import org.voidkit.core.util.Msg
import org.voidkit.guard.common.Fingerprint
import org.voidkit.guard.platform.GPlayer
import org.voidkit.guard.platform.neoforge.NeoforgePlayer
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 模块二：封禁（IP + 主板机器码 + 网卡 + QQ）。
 *
 * ## 为什么一条封禁写多行
 *
 * 作弊者换皮的成本极低：换名字（新账号）、换 IP（重启路由器）、甚至换 QQ 小号。
 * 但**机器码和网卡 MAC 跟着硬件走**，换这些等于换电脑。所以一次封禁把
 * 玩家当时暴露出的全部身份各写一条（`bans` 表一行一个 (类型,值)），
 * 任一条命中即拒 —— 这就是「超级严格」的落地方式。
 *
 * ## 两道检查点
 *
 * 1. **登录即检**（[onLogin]）：此时只有 IP/UUID/名字，撞上就踢。
 * 2. **auth 后检**（[checkAndEnforce]）：握手拿到完整指纹后再撞一次库 ——
 *    机器码/MAC/QQ 封禁对「刚被封就换号重进」的人生效，靠的就是这一道。
 */
class BanFeature : GuardFeature() {

    override val id = "guard_ban"
    override val displayName = "封禁（IP/机器码/网卡/QQ）"
    override val description = "多身份封禁：IP、主板机器码、网卡 MAC、QQ 登录记录，任一条命中即拒"

    // ---------- 登录即检 ----------

    /** 走 core 的 VEvent 抽象（不直接监听 NeoForge 事件），与套件其他插件一致。 */
    override fun onPlayerJoin(e: VPlayerJoin) {
        if (!enabled) return
        val gp = NeoforgePlayer(e.player)
        val keys = buildList {
            add("uuid" to gp.uuid)
            add("name" to gp.name.lowercase())
            if (hub.config.banIp && gp.ip.isNotBlank()) add("ip" to gp.ip)
        }
        val hit = hub.db.matchBan(keys) ?: return
        val until = if (hit.expires == 0L) "永久" else SimpleDateFormat("yyyy-MM-dd").format(Date(hit.expires))
        hub.audit(
            "ban_block", actor = "system", target = gp.name, ip = gp.ip,
            detail = mapOf("banId" to hit.id, "key" to "${hit.keyType}=${hit.keyValue}", "until" to until),
        )
        hub.server.disconnect(gp, "你已被封禁（$until）\n原因：${hit.reason}\n申诉请联系服主")
    }

    // ---------- auth 后检 ----------

    /** 用完整指纹撞库。命中返回被封的行。 */
    fun checkAndEnforce(player: GPlayer, fp: Fingerprint?): GuardDb.BanRow? {
        if (!enabled) return null
        val keys = buildList {
            add("uuid" to player.uuid)
            add("name" to player.name.lowercase())
            if (hub.config.banIp && player.ip.isNotBlank()) add("ip" to player.ip)
            if (fp != null) {
                if (hub.config.banMachine && fp.machine.isNotBlank()) add("machine" to fp.machine)
                if (hub.config.banMac) fp.macs.forEachIndexed { i, m -> add("mac$i" to m) }
                if (hub.config.banQQ) fp.qq.forEachIndexed { i, q -> add("qq$i" to q) }
            }
        }
        val hit = hub.db.matchBan(keys) ?: return null
        val until = if (hit.expires == 0L) "永久" else SimpleDateFormat("yyyy-MM-dd").format(Date(hit.expires))
        hub.audit(
            "ban_block", actor = "system", target = player.name, ip = player.ip,
            detail = mapOf("banId" to hit.id, "key" to "${hit.keyType}=${hit.keyValue}", "stage" to "auth"),
        )
        hub.server.disconnect(player, "你已被封禁（$until）\n原因：${hit.reason}\n申诉请联系服主")
        return hit
    }

    // ---------- 封禁操作 ----------

    /**
     * 封禁一个玩家（在线或离线）的全部当前身份。
     *
     * @return 写入的封禁行数。0 = 没找到这个玩家的任何身份档案。
     */
    fun banPlayer(playerName: String, reason: String, admin: String, days: Int): Int {
        val cfg = hub.config
        val online = hub.server.playerByName(playerName)
        val identity = hub.db.identityByName(playerName)
            ?: online?.let { hub.db.identityByUuid(it.uuid) }

        val keys = buildList {
            // 在线玩家：拿实时连接信息（比档案新）
            if (online != null) {
                add("uuid" to online.uuid)
                add("name" to online.name.lowercase())
                if (cfg.banIp && online.ip.isNotBlank()) add("ip" to online.ip)
            }
            if (identity != null) {
                add("uuid" to identity.uuid)
                add("name" to identity.name.lowercase())
                if (cfg.banIp && identity.ip.isNotBlank()) add("ip" to identity.ip)
                if (cfg.banMachine && identity.machine.isNotBlank()) add("machine" to identity.machine)
                if (cfg.banMac) identity.macs.forEachIndexed { i, m -> add("mac$i" to m) }
                if (cfg.banQQ) identity.qq.forEachIndexed { i, q -> add("qq$i" to q) }
            }
        }.distinct()

        if (keys.isEmpty()) return 0
        return banKeys(keys, reason, admin, days, target = playerName)
    }

    /** 按显式标识封禁（`/guard ban ip:1.2.3.4` 这种）。 */
    fun banKeys(
        keys: List<Pair<String, String>>,
        reason: String,
        admin: String,
        days: Int,
        target: String = keys.first().second,
    ): Int {
        if (keys.isEmpty()) return 0
        val expires = if (days <= 0) 0L else System.currentTimeMillis() + days * 86_400_000L
        var n = 0
        for ((type, value) in keys.distinct()) {
            if (hub.db.activeBan(type, value) != null) continue // 已在封存中，跳过
            hub.db.addBan(type, value, reason, admin, expires)
            n++
        }
        if (n > 0) {
            hub.audit(
                "ban_add", actor = admin, target = target,
                detail = mapOf("keys" to keys.map { "${it.first}=${it.second.take(24)}" }, "reason" to reason, "days" to days),
            )
        }
        return n
    }

    /** 会话失败时的自动封禁（握手校验失败升级）。 */
    fun banFromSession(s: GuardSessions.Session, reason: String, admin: String) {
        val cfg = hub.config
        val fp = s.report?.fingerprint
        val keys = buildList {
            add("uuid" to s.player.uuid)
            add("name" to s.player.name.lowercase())
            if (cfg.banIp && s.player.ip.isNotBlank()) add("ip" to s.player.ip)
            if (fp != null) {
                if (cfg.banMachine && fp.machine.isNotBlank()) add("machine" to fp.machine)
                if (cfg.banMac) fp.macs.forEachIndexed { i, m -> add("mac$i" to m) }
                if (cfg.banQQ) fp.qq.forEachIndexed { i, q -> add("qq$i" to q) }
            }
        }.distinct()
        banKeys(keys, reason, admin, days = cfg.defaultBanDays, target = s.player.name)
    }

    fun unban(id: Long): Boolean {
        val n = hub.db.liftBan(id)
        if (n > 0) {
            hub.audit("ban_lift", actor = "admin", target = "#$id")
            return true
        }
        return false
    }

    fun list(limit: Int, offset: Int): List<GuardDb.BanRow> = hub.db.bans(limit, offset)

    fun count(): Int = hub.db.banCount()

    // ---------- 命令 ----------

    override fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>) {
        registerBanCommand(dispatcher, "gban")
        registerBanCommand(dispatcher, "guardban")
        registerLookupCommand(dispatcher, "gcheck")
        registerListCommand(dispatcher, "gbanlist")
        registerUnbanCommand(dispatcher, "gunban")
    }

    private fun perm(source: net.minecraft.commands.CommandSourceStack) =
        VoidkitServices.permission().has(source, "voidkit.guard.ban")

    private fun registerBanCommand(
        dispatcher: com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>,
        name: String,
    ) {
        dispatcher.register(
            net.minecraft.commands.Commands.literal(name)
                .requires { perm(it) }
                .then(
                    net.minecraft.commands.Commands.argument("target", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes {
                            banCmd(it.source, com.mojang.brigadier.arguments.StringArgumentType.getString(it, "target"), "")
                        }
                        .then(
                            net.minecraft.commands.Commands.argument("reason", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes {
                                    banCmd(
                                        it.source,
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(it, "target"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(it, "reason"),
                                    )
                                }
                        )
                )
        )
    }

    private fun banCmd(
        source: net.minecraft.commands.CommandSourceStack,
        target: String,
        reason: String,
    ): Int {
        val cfg = hub.config
        val admin = source.player?.gameProfile?.name ?: "console"
        val parsed = parseTarget(target)

        if (parsed != null) {
            // 显式标识：ip:xxx / machine:xxx / qq:xxx ...
            val n = banKeys(listOf(parsed), reason.ifBlank { "管理员封禁" }, admin, cfg.defaultBanDays)
            Msg.ok(source, if (n > 0) "已封禁 ${parsed.first}=${parsed.second}（$n 条）" else "该标识已在封禁中")
            return if (n > 0) 1 else 0
        }

        val n = banPlayer(target, reason.ifBlank { "管理员封禁" }, admin, cfg.defaultBanDays)
        return if (n > 0) {
            Msg.ok(source, "已封禁 &f$target&a 的全部身份（$n 条）：IP/机器码/网卡/QQ")
            1
        } else {
            Msg.err(source, "找不到 $target 的身份档案（玩家进过服且通过校验才有指纹记录）")
            0
        }
    }

    /** 解析 `ip:1.2.3.4` / `machine:abc` / `qq:123` 形式；不是则返回 null（按玩家名处理）。 */
    private fun parseTarget(target: String): Pair<String, String>? {
        val idx = target.indexOf(':')
        if (idx <= 0) return null
        val type = target.substring(0, idx).lowercase()
        val value = target.substring(idx + 1)
        if (value.isBlank()) return null
        val ok = type == "ip" || type == "machine" || type == "mac" || type == "qq" || type == "uuid" || type == "name"
        return if (ok) type to value else null
    }

    private fun registerUnbanCommand(
        dispatcher: com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>,
        name: String,
    ) {
        dispatcher.register(
            net.minecraft.commands.Commands.literal(name)
                .requires { perm(it) }
                .then(
                    net.minecraft.commands.Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                        .executes {
                            val id = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(it, "id").toLong()
                            if (unban(id)) {
                                Msg.ok(it.source, "已解封 #$id")
                                1
                            } else {
                                Msg.err(it.source, "封禁 #$id 不存在")
                                0
                            }
                        }
                )
        )
    }

    private fun registerListCommand(
        dispatcher: com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>,
        name: String,
    ) {
        dispatcher.register(
            net.minecraft.commands.Commands.literal(name)
                .requires { perm(it) }
                .executes { listCmd(it.source, 1) }
                .then(
                    net.minecraft.commands.Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                        .executes { listCmd(it.source, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(it, "page")) }
                )
        )
    }

    private fun listCmd(source: net.minecraft.commands.CommandSourceStack, page: Int): Int {
        val per = 10
        val rows = list(per, (page - 1) * per)
        if (rows.isEmpty()) {
            Msg.info(source, "没有封禁记录")
            return 0
        }
        Msg.info(source, "===== 封禁列表（第 $page 页，共 ${count()} 条）=====")
        val fmt = SimpleDateFormat("MM-dd HH:mm")
        for (r in rows) {
            val until = if (r.expires == 0L) "永久" else fmt.format(Date(r.expires))
            Msg.text(source, "&f#${r.id} &7[${r.keyType}] &f${r.keyValue.take(32)} &7| &f$until &7| &f${r.reason} &7| by ${r.admin}")
        }
        if (rows.size >= per) {
            Msg.text(source, "&8[&b下一页 &7→&8](run:/gbanlist ${page + 1} \"&7查看下一页\")")
        }
        return 1
    }

    private fun registerLookupCommand(
        dispatcher: com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>,
        name: String,
    ) {
        dispatcher.register(
            net.minecraft.commands.Commands.literal(name)
                .requires { perm(it) }
                .then(
                    net.minecraft.commands.Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .executes { lookupCmd(it.source, com.mojang.brigadier.arguments.StringArgumentType.getString(it, "player")) }
                )
        )
    }

    private fun lookupCmd(source: net.minecraft.commands.CommandSourceStack, name: String): Int {
        val id = hub.db.identityByName(name)
        if (id == null) {
            Msg.err(source, "没有 $name 的身份档案")
            return 0
        }
        val hit = hub.db.matchBan(
            buildList {
                add("uuid" to id.uuid)
                add("name" to id.name.lowercase())
                if (id.ip.isNotBlank()) add("ip" to id.ip)
                if (id.machine.isNotBlank()) add("machine" to id.machine)
                id.macs.forEachIndexed { i, m -> add("mac$i" to m) }
                id.qq.forEachIndexed { i, q -> add("qq$i" to q) }
            }
        )
        Msg.info(source, "===== $name 身份档案 =====")
        Msg.info(source, "UUID：${id.uuid}")
        Msg.info(source, "最近 IP：${id.ip.ifBlank { "未知" }}")
        Msg.info(source, "机器码：${id.machine.ifBlank { "未知" }}")
        Msg.info(source, "网卡：${if (id.macs.isEmpty()) "未知" else id.macs.joinToString(", ")}")
        Msg.info(source, "QQ 记录：${if (id.qq.isEmpty()) "未采集到" else id.qq.joinToString(", ")}")
        Msg.text(
            source,
            if (hit != null) "状态：&c封禁中（#${hit.id} ${hit.reason}）" else "状态：&a正常",
        )
        return 1
    }

    // ---------- API ----------

    override fun registerApi(router: ApiRouter) {
        router.get("/api/guard/bans") { req ->
            val limit = req.queryInt("limit", 50)
            val offset = req.queryInt("offset", 0)
            ApiJson.obj(
                "ok", true,
                "total", count(),
                "bans", list(limit, offset).map { b ->
                    ApiJson.obj(
                        "id", b.id,
                        "keyType", b.keyType,
                        "keyValue", b.keyValue,
                        "reason", b.reason,
                        "admin", b.admin,
                        "created", b.created,
                        "expires", b.expires,
                    )
                },
            )
        }
        router.post("/api/guard/bans") { req ->
            val json = req.bodyJson()
            val target = json.get("target")?.asString ?: return@post ApiJson.error("bad_request", "缺少 target")
            val reason = json.get("reason")?.asString ?: "管理员封禁"
            val days = json.get("days")?.asInt ?: hub.config.defaultBanDays
            val admin = json.get("admin")?.asString ?: "panel"
            val parsed = parseTarget(target)
            val n = if (parsed != null) {
                banKeys(listOf(parsed), reason, admin, days)
            } else {
                banPlayer(target, reason, admin, days)
            }
            if (n > 0) ApiJson.ok("已封禁 $n 条身份") else ApiJson.error("not_found", "没有可封禁的身份")
        }
        router.post("/api/guard/bans/lift") { req ->
            val json = req.bodyJson()
            val id = json.get("id")?.asLong ?: return@post ApiJson.error("bad_request", "缺少 id")
            if (unban(id)) ApiJson.ok("已解封") else ApiJson.error("not_found", "封禁不存在")
        }
        router.get("/api/guard/identities") { req ->
            val name = req.query("player")
            val id = if (name.isNullOrBlank()) null else hub.db.identityByName(name)
                ?: hub.server.playerByName(name)?.let { hub.db.identityByUuid(it.uuid) }
            if (id == null) {
                return@get ApiJson.obj("ok", true, "found", false)
            }
            ApiJson.obj(
                "ok", true,
                "found", true,
                "identity", ApiJson.obj(
                    "uuid", id.uuid,
                    "name", id.name,
                    "ip", id.ip,
                    "machine", id.machine,
                    "macs", ApiJson.arr(id.macs),
                    "qq", ApiJson.arr(id.qq),
                    "firstSeen", id.firstSeen,
                    "lastSeen", id.lastSeen,
                ),
            )
        }
    }
}
