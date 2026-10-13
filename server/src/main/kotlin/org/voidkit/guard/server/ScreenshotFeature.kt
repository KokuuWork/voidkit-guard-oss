package org.voidkit.guard.server

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import org.voidkit.core.api.ApiJson
import org.voidkit.core.api.ApiRouter
import org.voidkit.core.api.VoidkitServices
import org.voidkit.core.util.ConfigFiles
import org.voidkit.core.util.Msg
import org.voidkit.guard.common.GuardMsg
import org.voidkit.guard.common.ScreenChunk
import org.voidkit.guard.common.ScreenReq
import org.voidkit.guard.platform.GPlayer
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块三：客户端截屏。
 *
 * ## 流程
 *
 * ```
 * /gscreen 玩家 ──▶ S→C screen_req(加密) ──▶ 客户端 Robot 抓图/压缩/分片
 *      ◀── C→S screen_chunk × N ◀── 服务端按 reqId 重组 ──▶ 落盘 + 审计 + API
 * ```
 *
 * ## 设计取舍
 *
 * - **冷却 + 串行**：同一玩家两次截屏有间隔（默认 60s），进行中的请求不重复发 ——
 *   反作弊是长期工作，不是骚扰工具。
 * - **先取证后踢**：校验失败时 `GuardSessions.fail` 会先调 [request] 再踢，
 *   截图失败不影响踢人（取证是尽力而为）。
 * - **落盘不进库**：PNG 存文件系统，库里只存路径+元数据。几十字节的审计行
 *   比几 MB 的图片行好备份好查询。
 */
class ScreenshotFeature : GuardFeature() {

    override val id = "guard_screenshot"
    override val displayName = "客户端截屏"
    override val description = "按需对玩家客户端截屏取证，分片加密传输，落盘 + 审计 + 管理 API"

    /** 一次进行中的截屏组装。字段可变：首片到达才知道分片总数。 */
    private class Assembly(
        val reqId: String,
        val player: GPlayer,
        val deadline: Long,
        val reason: String,
        val admin: String,
        val askedAt: Long = System.currentTimeMillis(),
    ) {
        var total: Int = 0
        var chunks: Array<ByteArray?> = emptyArray()
        var received: Int = 0
    }

    private val pending = ConcurrentHashMap<String, Assembly>()

    /** 每个玩家上次截屏时间（冷却用）。 */
    private val lastShot = ConcurrentHashMap<String, Long>()

    /** 连拍队列：异常取证用（静默、无提示）。 */
    private class Burst(
        val playerUuid: String,
        var remaining: Int,
        val intervalMs: Long,
        val reason: String,
        val admin: String,
        val expireAt: Long,
    ) {
        var nextAt: Long = 0L
    }

    private val bursts = ConcurrentHashMap<String, Burst>()

    /**
     * 异常时的连续抓拍：静默连拍 N 张。
     *
     * 单张截图容易刚好拍到加载界面，或者被 Alt+Tab 躲掉；连拍覆盖
     * 「发现被抓 → 切窗口」的时间差。auto 取证不走冷却、不出提示，
     * 玩家侧唯一的感知是网络卡了一下下。
     */
    fun requestBurst(player: GPlayer, reason: String, admin: String, session: GuardSessions.Session) {
        val cfg = hub.config
        if (!enabled || !cfg.screenshotEnabled || !cfg.screenshotBurstOnFail) return
        if (session.state != GuardSessions.State.VERIFIED) return
        bursts[player.uuid] = Burst(
            playerUuid = player.uuid,
            remaining = cfg.screenshotBurstCount.coerceIn(1, 10),
            intervalMs = cfg.screenshotBurstIntervalMs.toLong(),
            reason = reason,
            admin = admin,
            expireAt = System.currentTimeMillis() + 30_000L,
        )
    }

    // ---------- 请求 ----------

    /**
     * 请求一次截屏。
     *
     * @param session 直接传会话（失败取证路径用，省得再查一次）。
     * @return true = 请求已发出。
     */
    fun request(player: GPlayer, reason: String, admin: String, session: GuardSessions.Session? = null): Boolean {
        val cfg = hub.config
        if (!enabled || !cfg.screenshotEnabled) return false

        val s = session ?: hub.sessions.session(player.uuid)
        if (s == null || s.state != GuardSessions.State.VERIFIED) {
            if (admin != "auto") {
                hub.server.playerByName(admin)?.let { hub.server.sendErr(it, "玩家不在线或未通过校验，无法截屏") }
            }
            return false
        }

        // 冷却（auto 取证豁免冷却 —— 都要踢人了还等 60 秒说不过去）
        val now = System.currentTimeMillis()
        if (admin != "auto") {
            val last = lastShot[player.uuid] ?: 0L
            val cd = cfg.screenshotCooldownSeconds * 1000L
            if (now - last < cd) {
                val wait = (cd - (now - last)) / 1000
                hub.server.playerByName(admin)?.let { hub.server.sendErr(it, "截屏冷却中，请 ${wait}s 后再试") }
                return false
            }
        }
        // 已有进行中的请求：不重复发
        if (pending.values.any { it.player.uuid == player.uuid }) {
            hub.server.playerByName(admin)?.let { hub.server.sendInfo(it, "该玩家已有一个进行中的截屏请求") }
            return false
        }

        val reqId = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val asm = Assembly(
            reqId = reqId,
            player = player,
            deadline = now + 120_000L,
            reason = reason,
            admin = admin,
        )
        pending[reqId] = asm
        lastShot[player.uuid] = now

        val req = ScreenReq(
            reqId = reqId,
            maxW = cfg.screenshotMaxWidth,
            maxH = cfg.screenshotMaxHeight,
            chunkSize = cfg.screenshotChunkSize,
        )
        val body = hub.sessions.seal(s, GuardMsg.SCREEN_REQ, req.encode())
        if (body == null) {
            pending.remove(reqId)
            return false
        }
        hub.channel.send(player, GuardMsg.SCREEN_REQ, body)
        hub.audit(
            "screen_req", actor = admin, target = player.name, ip = player.ip,
            detail = mapOf("reqId" to reqId, "reason" to reason),
        )
        return true
    }

    // ---------- 分片处理 ----------

    /** 收到一个分片（已在主线程，报文已解密）。 */
    fun handleChunk(s: GuardSessions.Session, chunk: ScreenChunk) {
        val asm = pending[chunk.reqId] ?: return // 旧请求/陌生请求：丢弃
        if (asm.player.uuid != s.player.uuid) return // 张冠李戴：丢弃
        if (chunk.total !in 1..512) {
            pending.remove(chunk.reqId)
            return
        }
        synchronized(asm) {
            if (asm.total == 0) {
                if (chunk.index != 0) return
                asm.total = chunk.total
                asm.chunks = arrayOfNulls(chunk.total)
            }
            if (chunk.index >= asm.total) return
            if (asm.chunks[chunk.index] == null) {
                asm.chunks[chunk.index] = chunk.data
                asm.received++
            }
        }
        maybeComplete(asm)
    }

    private fun maybeComplete(asm: Assembly) {
        if (asm.received < asm.total) return
        val complete = synchronized(asm) {
            if (asm.chunks.all { it != null }) {
                val size = asm.chunks.sumOf { it?.size ?: 0 }
                val out = java.io.ByteArrayOutputStream(size)
                for (c in asm.chunks) out.write(c)
                out.toByteArray()
            } else null
        } ?: return
        pending.remove(asm.reqId)
        finish(asm, complete)
    }

    private fun finish(asm: Assembly, png: ByteArray) {
        val cfg = hub.config
        val root = ConfigFiles.resolve(cfg.screenshotDir)
        val day = SimpleDateFormat("yyyy-MM").format(Date())
        val dir = File(root.toFile(), day)
        runCatching { Files.createDirectories(dir.toPath()) }
        val name = "${asm.player.name}_${SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())}.png"
        val file = File(dir, name)
        runCatching { file.writeBytes(png) }.onFailure {
            hub.logWarn("guard: 截屏落盘失败 ${file}: ${it.message}")
            return
        }

        val dims = readPngSize(png)
        hub.db.addScreenshot(
            t = System.currentTimeMillis(),
            player = asm.player.name,
            uuid = asm.player.uuid,
            reqId = asm.reqId,
            path = file.toPath().toAbsolutePath().toString(),
            width = dims?.first ?: 0,
            height = dims?.second ?: 0,
            bytes = png.size.toLong(),
            reason = asm.reason,
            admin = asm.admin,
        )
        hub.audit(
            "screen_done", actor = asm.admin, target = asm.player.name, ip = asm.player.ip,
            detail = mapOf("file" to file.name, "bytes" to png.size, "reason" to asm.reason),
        )
        hub.logInfo("guard: 已保存 ${asm.player.name} 的客户端截屏 ${file.name}（${png.size} 字节）")

        // 通知发起的管理员（auto 取证不通知，避免刷屏）
        if (asm.admin != "auto") {
            hub.server.playerByName(asm.admin)?.let {
                hub.server.sendOk(it, "已收到 ${asm.player.name} 的客户端截屏（${png.size / 1024}KB）：$name")
            }
        }
    }

    /** 从 PNG 头读宽高（IHDR），不依赖 ImageIO 解码整图。 */
    private fun readPngSize(png: ByteArray): Pair<Int, Int>? {
        if (png.size < 24) return null
        if (png[0] != 0x89.toByte() || png[1] != 0x50.toByte() || png[2] != 0x4E.toByte() || png[3] != 0x47.toByte()) return null
        val w = ((png[16].toInt() and 0xFF) shl 24) or ((png[17].toInt() and 0xFF) shl 16) or
            ((png[18].toInt() and 0xFF) shl 8) or (png[19].toInt() and 0xFF)
        val h = ((png[20].toInt() and 0xFF) shl 24) or ((png[21].toInt() and 0xFF) shl 16) or
            ((png[22].toInt() and 0xFF) shl 8) or (png[23].toInt() and 0xFF)
        return w to h
    }

    // ---------- 周期任务：超时清理 + 随机抽查 ----------

    override fun onSecond(server: net.minecraft.server.MinecraftServer) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        val it = pending.values.iterator()
        while (it.hasNext()) {
            val asm = it.next()
            if (now > asm.deadline) {
                it.remove()
                hub.audit(
                    "screen_timeout", actor = asm.admin, target = asm.player.name, ip = asm.player.ip,
                    detail = mapOf("reqId" to asm.reqId, "got" to asm.received, "total" to asm.total),
                )
            }
        }

        // 随机抽查（默认关）：每 30 秒掷一次，5% 概率
        if (hub.config.screenshotSpotCheck && now % 30_000L < 1000L) {
            for (s in hub.sessions.all()) {
                if (s.state != GuardSessions.State.VERIFIED) continue
                if (kotlin.random.Random.nextInt(100) < 5) {
                    request(s.player, "随机抽查", admin = "auto", session = s)
                }
            }
        }

        // 连拍队列：到点就发，被占用就 500ms 后重试
        val it2 = bursts.values.iterator()
        while (it2.hasNext()) {
            val b = it2.next()
            if (now > b.expireAt || b.remaining <= 0) {
                it2.remove()
                continue
            }
            if (now < b.nextAt) continue
            // 注意：continue 不能写在 run{} 内联 lambda 里（Kotlin 2.2 才放开），
            // 所以先取会话再判断
            val session = hub.sessions.session(b.playerUuid)
            if (session == null || session.state != GuardSessions.State.VERIFIED) {
                it2.remove()
                continue
            }
            val sent = request(session.player, b.reason, admin = b.admin, session = session)
            b.remaining--
            b.nextAt = now + if (sent) b.intervalMs else 500L
            if (b.remaining <= 0) it2.remove()
        }
    }

    // ---------- 命令 ----------

    override fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("gscreen")
                .requires { VoidkitServices.permission().has(it, "voidkit.guard.screen") }
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .executes {
                            screenCmd(it.source, StringArgumentType.getString(it, "player"), "")
                        }
                        .then(
                            Commands.argument("reason", StringArgumentType.greedyString())
                                .executes {
                                    screenCmd(
                                        it.source,
                                        StringArgumentType.getString(it, "player"),
                                        StringArgumentType.getString(it, "reason"),
                                    )
                                }
                        )
                )
        )

        dispatcher.register(
            Commands.literal("greports")
                .requires { VoidkitServices.permission().has(it, "voidkit.guard.screen") }
                .then(
                    Commands.argument("player", StringArgumentType.word())
                        .executes { reportsCmd(it.source, StringArgumentType.getString(it, "player"), 1) }
                        .then(
                            Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes {
                                    reportsCmd(
                                        it.source,
                                        StringArgumentType.getString(it, "player"),
                                        IntegerArgumentType.getInteger(it, "page"),
                                    )
                                }
                        )
                )
        )
    }

    private fun screenCmd(source: CommandSourceStack, name: String, reason: String): Int {
        val admin = source.player?.gameProfile?.name ?: "console"
        val player = hub.server.playerByName(name)
        if (player == null) {
            Msg.err(source, "玩家 $name 不在线")
            return 0
        }
        val ok = request(player, reason.ifBlank { "管理员截屏" }, admin)
        if (ok) {
            Msg.ok(source, "已向 ${player.name} 请求客户端截屏，收到后此处会提示")
            return 1
        }
        return 0
    }

    private fun reportsCmd(source: CommandSourceStack, name: String, page: Int): Int {
        val rows = hub.db.screenshots(10, (page - 1) * 10, null)
            .filter { it.player.equals(name, ignoreCase = true) }
        if (rows.isEmpty()) {
            Msg.info(source, "没有 $name 的截屏记录")
            return 0
        }
        Msg.info(source, "===== $name 的截屏记录 =====")
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss")
        for (r in rows) {
            Msg.info(source, "#${r.id} ${fmt.format(Date(r.t))} ${r.width}x${r.height} ${r.bytes / 1024}KB ${r.reason} (by ${r.admin})")
        }
        return rows.size
    }

    // ---------- API ----------

    override fun registerApi(router: ApiRouter) {
        router.get("/api/guard/screenshots") { req ->
            val limit = req.queryInt("limit", 50)
            val player = req.query("player")
            ApiJson.obj(
                "ok", true,
                "screenshots", hub.db.screenshots(limit, req.queryInt("offset", 0), player).map { r ->
                    ApiJson.obj(
                        "id", r.id,
                        "t", r.t,
                        "player", r.player,
                        "uuid", r.uuid,
                        "width", r.width,
                        "height", r.height,
                        "bytes", r.bytes,
                        "reason", r.reason,
                        "admin", r.admin,
                        "path", r.path,
                    )
                },
            )
        }
        router.get("/api/guard/screenshots/{id}") { req ->
            val id = req.param("id")?.toLongOrNull() ?: return@get ApiJson.error("bad_request", "id 无效")
            val row = hub.db.screenshot(id) ?: return@get ApiJson.error("not_found", "截屏不存在")
            val bytes = runCatching { Files.readAllBytes(java.nio.file.Path.of(row.path)) }.getOrNull()
                ?: return@get ApiJson.error("gone", "文件已被清理")
            // 面板走 base64：web 插件的响应体只认 JSON/String
            ApiJson.obj(
                "ok", true,
                "id", row.id,
                "player", row.player,
                "width", row.width,
                "height", row.height,
                "base64", java.util.Base64.getEncoder().encodeToString(bytes),
            )
        }
        router.post("/api/guard/screenshots") { req ->
            val json = req.bodyJson()
            val player = json.get("player")?.asString ?: return@post ApiJson.error("bad_request", "缺少 player")
            val reason = json.get("reason")?.asString ?: "面板截屏"
            val gp = hub.server.playerByName(player) ?: return@post ApiJson.error("offline", "玩家不在线")
            val ok = request(gp, reason, admin = "panel")
            if (ok) ApiJson.ok("已请求截屏") else ApiJson.error("busy", "请求未发出（冷却中或未通过校验）")
        }
    }

    @Suppress("unused")
    fun pendingCount(): Int = pending.size
}
