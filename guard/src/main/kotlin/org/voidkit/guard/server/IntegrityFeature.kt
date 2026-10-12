package org.voidkit.guard.server

import com.mojang.brigadier.arguments.StringArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import org.voidkit.core.api.ApiJson
import org.voidkit.core.api.ApiRouter
import org.voidkit.core.api.ConfigSnapshots
import org.voidkit.core.api.VoidkitServices
import org.voidkit.core.util.Msg
import org.voidkit.guard.common.IntegrityMode
import org.voidkit.guard.platform.neoforge.NeoforgePlayer
import net.minecraft.server.level.ServerPlayer

/**
 * 模块一：客户端完整性校验（智能识别 / 哈希锁）。
 *
 * 本模块是 guard 的「总控」：
 * - 驱动握手生命周期（进服发挑战、收应答、保活、超时）—— 见 [GuardSessions]；
 * - 持有 `/guard` 主命令（状态 / 模式切换 / 白名单 / 重载）；
 * - 挂 `/api/guard/status` 与配置快照（给管理面板）。
 *
 * 校验策略本身全在 [GuardSessions.verify]，本类不碰判定逻辑 ——
 * 「什么时候验」和「怎么算过」分开，改策略不用动事件接线。
 */
class IntegrityFeature : GuardFeature() {

    override val id = "guard_integrity"
    override val displayName = "客户端完整性校验"
    override val description = "智能识别 / 哈希锁双模式：进服握手 + 会话保活，防改客户端、防透视模组"

    // ---------- 事件接线 ----------

    @SubscribeEvent
    fun onLogin(e: PlayerEvent.PlayerLoggedInEvent) {
        if (!enabled || !hub.config.enabled) return
        val player = e.entity as? ServerPlayer ?: return
        hub.sessions.onJoin(NeoforgePlayer(player))
    }

    @SubscribeEvent
    fun onLogout(e: PlayerEvent.PlayerLoggedOutEvent) {
        if (!enabled) return
        val player = e.entity as? ServerPlayer ?: return
        hub.sessions.onQuit(NeoforgePlayer(player))
    }

    // ---------- 周期 ----------

    override fun onSecond(server: net.minecraft.server.MinecraftServer) {
        if (!enabled) return
        hub.sessions.onSecond()
    }

    // ---------- 重载 ----------

    override fun onReload(server: net.minecraft.server.MinecraftServer) {
        hub.config = GuardStore.load()
        hub.whitelist.load()
        hub.logInfo("guard: 配置与白名单已重载")
    }

    // ---------- 命令 ----------

    override fun registerCommands(dispatcher: com.mojang.brigadier.CommandDispatcher<CommandSourceStack>) {
        val wl = Commands.literal("whitelist")
            .executes { wlList(it.source) }
            .then(
                Commands.literal("list")
                    .executes { wlList(it.source, 1) }
                    .then(
                        Commands.argument("page", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                            .executes { wlList(it.source, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(it, "page")) }
                    )
            )
            .then(Commands.literal("pending").executes { wlPending(it.source) })
            .then(
                Commands.literal("approve")
                    .then(
                        Commands.argument("modId", StringArgumentType.greedyString())
                            .executes { wlApprove(it.source, StringArgumentType.getString(it, "modId")) }
                    )
            )
            .then(Commands.literal("approve-all").executes { wlApproveAll(it.source) })
            .then(
                Commands.literal("remove")
                    .then(
                        Commands.argument("modId", StringArgumentType.greedyString())
                            .executes { wlRemove(it.source, StringArgumentType.getString(it, "modId")) }
                    )
            )
            .then(
                Commands.literal("learn")
                    .then(
                        Commands.argument("player", StringArgumentType.word())
                            .executes { wlLearn(it.source, StringArgumentType.getString(it, "player")) }
                    )
            )

        dispatcher.register(
            Commands.literal("guard")
                .requires { VoidkitServices.permission().has(it, "voidkit.guard") }
                .executes { statusCmd(it.source) }
                .then(Commands.literal("status").executes { statusCmd(it.source) })
                .then(
                    Commands.literal("mode")
                        .then(
                            Commands.argument("mode", StringArgumentType.word())
                                .suggests { _, b ->
                                    b.suggest("smart")
                                    b.suggest("hashlock")
                                    b.buildFuture()
                                }
                                .executes { modeCmd(it.source, StringArgumentType.getString(it, "mode")) }
                        )
                )
                .then(Commands.literal("reload").executes { reloadCmd(it.source) })
                .then(
                    Commands.literal("key")
                        .then(Commands.literal("rotate").executes { keyRotate(it.source) })
                        .then(Commands.literal("export").executes { keyExport(it.source) })
                )
                .then(wl)
        )
    }

    private fun statusCmd(source: CommandSourceStack): Int {
        val cfg = hub.config
        val stats = hub.db.stats()
        val verified = hub.sessions.all().count { it.state == GuardSessions.State.VERIFIED }
        val pendingHandshake = hub.sessions.all().count { it.state == GuardSessions.State.CHALLENGE_SENT }
        Msg.info(source, "===== VoidkitGuard 状态 =====")
        Msg.text(source, "模块：&f${if (enabled && cfg.enabled) "&a运行中" else "&c已关闭"}")
        Msg.text(source, "完整性模式：&f${cfg.integrityModeEnum().display}&7（${cfg.integrityMode}）")
        Msg.text(source, "失败动作：&f${cfg.failActionEnum().display}&7｜累计 ${cfg.integrityMaxFailsBeforeBan} 次升级封禁")
        Msg.text(source, "会话：&f$verified&7 已通过｜&f$pendingHandshake&7 握手中｜保活 &f${if (cfg.keepaliveEnabled) "开" else "关"}")
        Msg.text(source, "封禁：&f${hub.bans.count()}&7 条｜白名单：&f${hub.whitelist.entries.size}&7 条（&f${hub.whitelist.pendingCount()}&7 待审）")
        Msg.text(source, "数据库：&f${cfg.databaseFile}&7｜队列 &f${stats["pending"] ?: 0}&7｜丢弃 &f${stats["dropped"] ?: 0}")
        Msg.text(source, "截屏：&f${if (cfg.screenshotEnabled) "开" else "关"}&7｜失败自动取证 &f${if (cfg.screenshotAutoOnFail) "开" else "关"}")
        return 1
    }

    private fun modeCmd(source: CommandSourceStack, mode: String): Int {
        val m = IntegrityMode.from(mode)
        hub.config.integrityMode = m.id
        GuardStore.save()
        Msg.ok(source, "完整性模式已切换为：${m.display}")
        if (m == IntegrityMode.HASH_LOCK && hub.whitelist.entries.isEmpty()) {
            Msg.info(source, "白名单是空的 —— 已开启自动学习：首个玩家的清单会成为基线（待审）")
        }
        return 1
    }

    private fun reloadCmd(source: CommandSourceStack): Int {
        onReload(hub.server.let { org.voidkit.core.api.VoidkitServer.require() })
        Msg.ok(source, "guard 配置与白名单已重载")
        return 1
    }

    /**
     * 轮换服务端密钥对。
     *
     * 换完旧客户端**立刻全部失效**（挑战签名验不过）—— 这就是
     * 「定期换密钥防破解」的落地：把重新破解的成本转嫁给对手。
     * 换完记得 `/guard key export` 拿新公钥，贴进客户端 GuardKeys.RING
     * 重新构建发布，否则**你自己的客户端也进不来**。
     */
    private fun keyRotate(source: CommandSourceStack): Int {
        val cfg = hub.config
        val path = org.voidkit.core.util.ConfigFiles.resolve(cfg.serverKeyFile)
        val old = ServerKeyPair(path)
        old.load()
        val fresh = org.voidkit.guard.common.GCrypto.newRsaKeyPair()
        runCatching {
            java.nio.file.Files.writeString(
                path,
                org.voidkit.guard.common.GCrypto.privateToPem(fresh.private) +
                    org.voidkit.guard.common.GCrypto.publicToPem(fresh.public),
                Charsets.UTF_8,
            )
        }.onFailure {
            Msg.err(source, "写入新密钥失败：${it.message}")
            return 0
        }
        old.wipe()
        hub.keys = ServerKeyPair(path).also { it.load() }
        hub.audit("key_rotate", actor = source.player?.gameProfile?.name ?: "console")
        Msg.ok(source, "已轮换服务端密钥。执行 /guard key export 拿新公钥，更新客户端后旧客户端全部失效")
        return 1
    }

    /** 导出当前公钥（base64 SPKI），贴到客户端 GuardKeys.RING。 */
    private fun keyExport(source: CommandSourceStack): Int {
        val b64 = java.util.Base64.getEncoder()
            .encodeToString(org.voidkit.guard.common.GCrypto.encodePublic(hub.keys.publicKey()))
        val file = org.voidkit.core.util.ConfigFiles.resolve(serverKeyPubPath())
        runCatching {
            java.nio.file.Files.createDirectories(file.parent)
            java.nio.file.Files.writeString(file, b64, Charsets.UTF_8)
        }
        Msg.ok(source, "公钥已写入 ${file.toAbsolutePath()}")
        Msg.text(source, "&7把它整行贴进客户端 GuardKeys.kt 的 RING 列表，重新构建发布")
        Msg.text(source, "&8$b64")
        return 1
    }

    private fun serverKeyPubPath(): String =
        hub.config.serverKeyFile.replace(Regex("\\.pem$"), "") + ".pub.b64"

    private fun wlList(source: CommandSourceStack, page: Int = 1): Int {
        val per = 10
        val all = hub.whitelist.entries
        if (all.isEmpty()) {
            Msg.info(source, "白名单为空（哈希锁未启用或未学习）")
            return 0
        }
        val from = ((page - 1) * per).coerceIn(0, all.size)
        val slice = all.drop(from).take(per)
        Msg.info(source, "===== 白名单（第 $page 页，共 ${all.size} 条，${hub.whitelist.pendingCount()} 待审）=====")
        for (e in slice) {
            val mark = if (e.approved) "&a✓" else "&e?"
            Msg.text(source, "$mark &f${e.modId} &7v${e.version} &8${e.sha256.take(12)} &7${e.note}")
        }
        return 1
    }

    private fun wlPending(source: CommandSourceStack): Int {
        val p = hub.whitelist.entries.filter { !it.approved }
        if (p.isEmpty()) {
            Msg.ok(source, "没有待审条目")
            return 0
        }
        Msg.info(source, "===== 待审条目（${p.size}）=====")
        for (e in p) {
            Msg.text(source, "&e? &f${e.modId} &7v${e.version} &8${e.sha256.take(12)}")
        }
        Msg.info(source, "用 /guard whitelist approve-all 全部批准，或 approve <modId> 逐个批")
        return p.size
    }

    private fun wlApprove(source: CommandSourceStack, modId: String): Int {
        if (modId.equals("all", ignoreCase = true)) return wlApproveAll(source)
        val n = hub.whitelist.approve(modId)
        return if (n > 0) {
            Msg.ok(source, "已批准 $modId（$n 个版本）")
            1
        } else {
            Msg.err(source, "没有 $modId 的待审条目")
            0
        }
    }

    private fun wlApproveAll(source: CommandSourceStack): Int {
        val n = hub.whitelist.approveAll()
        return if (n > 0) {
            Msg.ok(source, "已批准全部 $n 个待审条目")
            1
        } else {
            Msg.info(source, "没有待审条目")
            0
        }
    }

    private fun wlRemove(source: CommandSourceStack, modId: String): Int {
        val n = hub.whitelist.remove(modId)
        return if (n > 0) {
            Msg.ok(source, "已从白名单移除 $modId（$n 个版本）")
            1
        } else {
            Msg.err(source, "白名单里没有 $modId")
            0
        }
    }

    private fun wlLearn(source: CommandSourceStack, player: String): Int {
        val s = hub.sessions.all().firstOrNull { it.player.name.equals(player, ignoreCase = true) }
        val report = s?.report
        if (report == null) {
            Msg.err(source, "玩家 $player 不在线或尚未通过校验（没有可学习的报告）")
            return 0
        }
        val n = hub.whitelist.learn(report)
        Msg.ok(source, "已从 $player 的清单学习 $n 个新条目（待审）")
        return 1
    }

    // ---------- API ----------

    override fun registerApi(router: ApiRouter) {
        router.get("/api/guard/status") { _ ->
            val cfg = hub.config
            val stats = hub.db.stats()
            ApiJson.obj(
                "ok", true,
                "enabled", enabled && cfg.enabled,
                "mode", cfg.integrityMode,
                "modeDisplay", cfg.integrityModeEnum().display,
                "onFail", cfg.integrityOnFail,
                "graceSeconds", cfg.integrityGraceSeconds,
                "maxFailsBeforeBan", cfg.integrityMaxFailsBeforeBan,
                "keepalive", cfg.keepaliveEnabled,
                "sessions", ApiJson.obj(
                    "verified", hub.sessions.all().count { it.state == GuardSessions.State.VERIFIED },
                    "handshaking", hub.sessions.all().count { it.state == GuardSessions.State.CHALLENGE_SENT },
                ),
                "bans", hub.bans.count(),
                "whitelist", ApiJson.obj(
                    "total", hub.whitelist.entries.size,
                    "pending", hub.whitelist.pendingCount(),
                ),
                "db", ApiJson.obj(
                    "queued", stats["queued"] ?: 0,
                    "written", stats["written"] ?: 0,
                    "dropped", stats["dropped"] ?: 0,
                ),
                "screenshotEnabled", cfg.screenshotEnabled,
            )
        }

        router.post("/api/guard/mode") { req ->
            val json = req.bodyJson()
            val mode = json.get("mode")?.asString ?: return@post ApiJson.error("bad_request", "缺少 mode")
            val m = IntegrityMode.from(mode)
            hub.config.integrityMode = m.id
            GuardStore.save()
            ApiJson.ok("模式已切换为 ${m.display}")
        }

        router.post("/api/guard/reload") { _ ->
            onReload(org.voidkit.core.api.VoidkitServer.require())
            ApiJson.ok("已重载")
        }

        // 在线玩家的实时校验状态（面板「反作弊」页核心数据）
        router.get("/api/guard/sessions") { _ ->
            ApiJson.obj(
                "ok", true,
                "sessions", hub.sessions.all().map { s ->
                    val r = s.report
                    ApiJson.obj(
                        "uuid", s.player.uuid,
                        "name", s.player.name,
                        "state", s.state.name,
                        "fails", s.failCount,
                        "verifiedAt", s.verifiedAt,
                        "mods", r?.mods?.size ?: 0,
                        "signals", r?.signals?.size ?: 0,
                        "machine", r?.fingerprint?.machine?.take(16) ?: "",
                        "macs", r?.fingerprint?.macs?.size ?: 0,
                        "qq", r?.fingerprint?.qq?.size ?: 0,
                    )
                },
            )
        }
    }

    /** 配置快照（面板「模块」页显示，web 插件不需要 import 本插件）。 */
    fun publishSnapshot() {
        ConfigSnapshots.register("guard") {
            val cfg = GuardStore.config
            mapOf(
                "guard.enabled" to cfg.enabled,
                "guard.mode" to cfg.integrityMode,
                "guard.onFail" to cfg.integrityOnFail,
                "guard.keepalive" to cfg.keepaliveEnabled,
                "guard.screenshot" to cfg.screenshotEnabled,
                "guard.bans" to hub.bans.count(),
            )
        }
    }
}
