package org.voidkit.guard.platform.neoforge

import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.BlockEvent
import org.voidkit.guard.GuardBootstrap

/**
 * 服务端游戏事件桥接（NeoForge 全局事件总线 → guard 业务模块）。
 *
 * 与 [NeoforgePayloads]/[NeoforgeServer] 同属「唯一按加载器重写」的一层：
 * `BlockEvent.BreakEvent` 是 NeoForge API，业务代码不 import 它 ——
 * 这里翻译成 `blockId 字符串 + 暴露空气面数` 再递给 X 射线统计检测，
 * 换加载器时只需照抄这一个文件，业务代码零改动。
 *
 * 为什么用加载器事件而不是 mixin：挖掘行为在服务端有原生事件出口，
 * 零 mixin 即可拿到（方块、玩家、世界都能直接读）—— mixin 是给
 * 「没有事件出口的内部行为」留的最后手段，能用事件就不用 mixin。
 */
object NeoforgeServerEvents {

    @Volatile
    private var registered = false

    /** 幂等注册（mod 入口构造期调用，纯内存操作）。 */
    fun register() {
        if (registered) return
        registered = true

        NeoForge.EVENT_BUS.addListener { e: BlockEvent.BreakEvent ->
            val h = GuardBootstrap.hub ?: return@addListener
            val sp = e.player as? ServerPlayer ?: return@addListener
            // 创造/旁观模式挖矿是管理员行为，不进统计 —— 否则清图会瞬间污染比率
            if (sp.isCreative || sp.isSpectator) return@addListener
            val state = e.state
            if (state.isAir) return@addListener

            // 暴露度：被挖方块六面中贴着空气的面数。世界边界外读到的也是空气态，方向不亏。
            val pos = e.pos
            var exposed = 0
            for (d in Direction.values()) {
                if (e.level.getBlockState(pos.relative(d)).isAir) exposed++
            }

            val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
            val player = NeoforgePlayer(sp)
            // BreakEvent 本就在主线程，execute 幂等；统一过 execute 保持与网络消息同一条线程纪律
            h.server.execute { h.xrayStats.onBlockBreak(player, blockId, exposed) }
        }
    }
}
