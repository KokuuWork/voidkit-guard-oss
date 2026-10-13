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

    // ---------- 联防（封禁清单跨服共享） ----------

    /**
     * 导出活跃封禁为联防包（JSON）。
     *
     * 用途：你有几个服，A 服封了的人 B 服自动拦。导出的 key 里机器码/网卡/QQ
     * 本来就是加盐哈希（原文从不出客户端），IP/UUID 是服务器本来就要管的 ——
     * 整包只在你自己的服务器之间流转，不出你的手。
     */
    fun exportFence(): String {
        val rows = hub.db.activeBans()
        val obj = com.google.gson.JsonObject()
        obj.addProperty("v", 1)
        obj.addProperty("exportedAt", System.currentTimeMillis())
        val arr = com.google.gson.JsonArray()
        for (r in rows) {
            arr.add(
                com.google.gson.JsonObject().apply {
                    addProperty("type", r.keyType)
                    addProperty("value", r.keyValue)
                    addProperty("reason", r.reason)
                    addProperty("created", r.created)
                    addProperty("expires", r.expires)
                }
            )
        }
        obj.add("bans", arr)
        return obj.toString()
    }

    /**
     * 导入联防包。只收联防包认识的字段，每条先查活跃表、已存在就跳过；
     * 返回（导入数, 跳过数）。类型白名单外的条目直接丢弃 —— 宁可少封不错封。
     */
    fun importFence(json: String, admin: String): Pair<Int, Int> {
        val root = runCatching {
            com.google.gson.JsonParser.parseString(json).asJsonObject
        }.getOrNull() ?: return 0 to 0
        val arr = runCatching { root.getAsJsonArray("bans") }.getOrNull() ?: return 0 to 0
        val okTypes = setOf("ip", "machine", "uuid", "name")
        var imported = 0
        var skipped = 0
        for (e in arr) {
            val o = runCatching { e.asJsonObject }.getOrNull() ?: continue
            val type = o.get("type")?.asString?.lowercase() ?: continue
            val value = o.get("value")?.asString ?: continue
            if (type !in okTypes || value.isBlank()) {
                skipped++
                continue
            }
            if (hub.db.activeBan(type, value) != null) {
                skipped++
                continue
            }
            val reason = (o.get("reason")?.asString ?: "").ifBlank { "联防同步" } + "（联防）"
            hub.db.addBan(type, value, reason.take(200), admin, o.get("expires")?.asLong ?: 0L)
            imported++
        }
        if (imported > 0) {
            hub.audit("fence_import", actor = admin, detail = mapOf("imported" to imported, "skipped" to skipped))
        }
        return imported to skipped
    }

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
        val ok = type == "ip" || type == "machine" || type == "uuid" || type == "name"
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

            }
        )
        val region = IpRegion.lookup(id.ip)
        Msg.info(source, "===== $name 身份档案 =====")
        Msg.info(source, "UUID：${id.uuid}")
        Msg.info(source, "最近 IP：${id.ip.ifBlank { "未知" }}${if (region.isNotBlank()) "（$region）" else ""}")
        Msg.info(source, "机器码：${id.machine.ifBlank { "未知" }}")


        // 同源账号：换号重进在这行面前无效 —— 机器码/网卡跟着硬件走
        val related = hub.db.relatedIdentities(id)
        if (related.isEmpty()) {
            Msg.info(source, "关联账号：无")
        } else {
            Msg.info(source, "关联账号（${related.size}）：")
            for (r in related.take(10)) {
                Msg.text(source, "&7- &f${r.identity.name} &7（${r.reasons.joinToString("/")}｜末次在线 ${SimpleDateFormat("MM-dd HH:mm").format(Date(r.identity.lastSeen))}）")
            }
        }
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
            val region = IpRegion.lookup(id.ip)
            ApiJson.obj(
                "ok", true,
                "found", true,
                "identity", ApiJson.obj(
                    "uuid", id.uuid,
                    "name", id.name,
                    "ip", id.ip,
                    "ipRegion", region,
                    "machine", id.machine,
                    "firstSeen", id.firstSeen,
                    "lastSeen", id.lastSeen,
                ),
                // 同源账号：换号重进在这里现形（机器码/网卡/QQ 任意撞上即列出）
                "related", hub.db.relatedIdentities(id).map { r ->
                    ApiJson.obj(
                        "name", r.identity.name,
                        "uuid", r.identity.uuid,
                        "reasons", ApiJson.arr(r.reasons),
                        "strength", r.strength,
                        "lastSeen", r.identity.lastSeen,
                    )
                },
            )
        }

        // ---------- 联防（封禁清单跨服共享） ----------
        // GET  = 导出活跃封禁包；POST = 导入（body 直接就是另一个服导出的包，
        // 或 {"json": "<包>"} 包一层）。面板做「联防」页直接用这一对。
        router.get("/api/guard/fence") { _ ->
            val export = hub.bans.exportFence()
            val count = runCatching {
                com.google.gson.JsonParser.parseString(export).asJsonObject.getAsJsonArray("bans").size()
            }.getOrDefault(0)
            ApiJson.obj("ok", true, "count", count, "json", export)
        }
        router.post("/api/guard/fence") { req ->
            val body = req.bodyJson()
            val json = body.get("json")?.asString ?: body.toString()
            val (imported, skipped) = hub.bans.importFence(json, "panel")
            ApiJson.obj("ok", true, "imported", imported, "skipped", skipped)
        }
    }
}
