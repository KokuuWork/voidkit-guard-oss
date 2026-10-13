package org.voidkit.guard.server

import org.voidkit.core.api.ApiJson
import org.voidkit.core.api.ApiRouter

/**
 * 模块四：信息记录（审计）。
 *
 * 记什么：进服/退服、校验通过与失败（含原因码）、可疑信号、封禁/解封、
 * 截屏请求与完成、白名单变更。全部带 IP、玩家、UUID、结构化 detail(JSON)。
 *
 * 为什么单独一个模块：审计是「对接平台管理」的地基（面板/GitHub issue 里
 * 要的深度支持全建在它上面），而且它没有任何游戏内行为 —— 单独可关、
 * 关了不影响其余三个模块工作。
 */
class AuditFeature : GuardFeature() {

    override val id = "guard_audit"
    override val displayName = "信息记录（审计）"
    override val description = "上下线、校验结果、封禁、截屏、白名单变更全量审计，SQLite 存储 + API"

    private var purgeTick = 0

    override fun onSecond(server: net.minecraft.server.MinecraftServer) {
        if (!enabled) return
        // 每 6 小时清理一次过期记录（和查熊同节奏）
        if (++purgeTick >= 6 * 3600) {
            purgeTick = 0
            val days = hub.config.retentionDays
            if (days > 0) {
                hub.db.schedulePurge(System.currentTimeMillis() - days * 86_400_000L)
            }
        }
    }

    override fun registerApi(router: ApiRouter) {
        router.get("/api/guard/audit") { req ->
            val limit = req.queryInt("limit", 100)
            val type = req.query("type")
            ApiJson.obj(
                "ok", true,
                "events", hub.db.audit(limit, type).map { e ->
                    ApiJson.obj(
                        "id", e.id,
                        "t", e.t,
                        "type", e.type,
                        "actor", e.actor,
                        "target", e.target,
                        "ip", e.ip,
                        "detail", e.detail,
                    )
                },
            )
        }
    }
}
