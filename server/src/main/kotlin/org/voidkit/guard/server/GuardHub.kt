package org.voidkit.guard.server

import org.voidkit.guard.common.ClientReport
import org.voidkit.guard.common.DeepScanResult
import org.voidkit.guard.platform.GChannel
import org.voidkit.guard.platform.GPlayer
import org.voidkit.guard.platform.GServer
import org.voidkit.guard.GuardBootstrap

/**
 * guard 模块基类：持有共享 [GuardHub]。
 *
 * 四大模块都继承它，装配时由 `GuardBootstrap` 统一注入 hub ——
 * 模块自己不去单例里找依赖，保持可测试、可拆装。
 *
 * hub 的装配发生在 [onServerAboutToStart]（每个模块都会调，幂等）：
 * 这是套件铁律规定的「插件读配置的正确时机」，也保证任何模块的
 * 后续回调拿到 hub 时，数据库/密钥/白名单都已就绪。
 */
abstract class GuardFeature : org.voidkit.core.api.Feature() {
    lateinit var hub: GuardHub

    final override fun onServerAboutToStart(server: net.minecraft.server.MinecraftServer) {
        hub = GuardBootstrap.assembleHub(server)
        onStart(server)
    }

    /** 子类的启动钩子（hub 已就绪）。 */
    protected open fun onStart(server: net.minecraft.server.MinecraftServer) {}
}

/**
 * guard 服务端共享上下文。
 *
 * 四大模块（完整性/封禁/截屏/审计）之间不互相 import，全靠这一个 hub 中转 ——
 * 和套件「跨模块走 CoreServices」是同一条纪律：任意一个模块拆出去都能单独跑。
 *
 * 装配顺序（GuardBootstrap.assembleHub，ServerAboutToStart 阶段）：
 * ```
 * config.load() → db.open() → keys.load() → whitelist.load()
 *   → new BanFeature/ScreenshotFeature/AuditFeature/IntegrityFeature（挂进 hub）
 *   → channel.onClientMessage = { 切主线程 → sessions.onMessage }
 * ```
 */
class GuardHub {

    lateinit var server: GServer
    lateinit var channel: GChannel
    lateinit var db: GuardDb
    lateinit var config: GuardConfig
    lateinit var keys: ServerKeyPair
    lateinit var whitelist: WhitelistStore

    /** 四大模块。sessions 持有对 screenshots/bans 的引用（失败处置用）。 */
    lateinit var sessions: GuardSessions
    lateinit var bans: BanFeature
    lateinit var screenshots: ScreenshotFeature
    lateinit var deep: DeepScanFeature
    lateinit var xrayStats: XrayStatisticsFeature
    lateinit var combat: CombatFeature

    @Volatile
    var started: Boolean = false

    fun logInfo(message: String) = server.logInfo(message)

    fun logWarn(message: String) = server.logWarn(message)

    /** 记一条审计（入队即走，不阻塞调用方）。 */
    fun audit(
        type: String,
        actor: String = "",
        target: String = "",
        ip: String = "",
        detail: Map<String, Any?> = emptyMap(),
    ) {
        if (!started) return
        db.offerAudit(GuardAudit.row(type, actor, target, ip, detail))
    }

    /** 握手通过：落身份档案 → 审计 → 撞库检查（封禁可能在 auth 之后才生效）。 */
    fun onVerified(s: GuardSessions.Session) {
        val report: ClientReport = s.report ?: return
        val fp = report.fingerprint
        db.recordIdentity(
            uuid = s.player.uuid,
            name = s.player.name,
            ip = s.player.ip,
            machine = fp?.machine ?: "",
            macs = fp?.macs ?: emptyList(),
            qq = fp?.qq ?: emptyList(),
        )
        audit(
            "guard_pass", actor = s.player.name, target = s.player.uuid, ip = s.player.ip,
            detail = mapOf(
                "mods" to report.mods.size,
                "machine" to (fp?.machine?.take(12) ?: ""),
                "macs" to (fp?.macs?.size ?: 0),
                "qq" to (fp?.qq?.size ?: 0),
            ),
        )
        // auth 之后才拿到完整指纹 —— 这时再撞一次库（登录时只撞了 IP/UUID）
        bans.checkAndEnforce(s.player, report.fingerprint)
    }
}
