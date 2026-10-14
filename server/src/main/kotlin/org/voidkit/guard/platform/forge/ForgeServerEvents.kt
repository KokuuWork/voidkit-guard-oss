package org.voidkit.guard.platform.forge

import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.living.LivingAttackEvent
import net.minecraftforge.event.level.BlockEvent
import org.voidkit.guard.GuardBootstrap
import kotlin.math.sqrt

/**
 * 服务端游戏事件桥接（Forge 1.21.1 全局事件总线 → guard 业务模块）。
 *
 * 与 NeoForge 版 [org.voidkit.guard.platform.neoforge.NeoforgeServerEvents] 逻辑逐行
 * 相同，差异只在事件包与 tick 事件名：
 * - `NeoForge.EVENT_BUS` → `MinecraftForge.EVENT_BUS`
 * - `TickEvent.ServerTickEvent.Post`（1.21.1 两加载器**同名**，NeoForge 也是这个类）
 *
 * 这里翻译成「方块 id + 暴露面数」「攻击距离 + 视角角速度」「每 tick 视角」
 * 再递给 X 射线统计检测与战斗遥测。换加载器时只需照抄这一个文件。
 *
 * 为什么用加载器事件而不是 mixin：挖掘/攻击在服务端都有原生事件出口，
 * 零 mixin 即可拿到 —— mixin 是给「没有事件出口的内部行为」留的最后手段。
 */
object ForgeServerEvents {

    @Volatile
    private var registered = false

    /** 幂等注册（mod 入口构造期调用，纯内存操作）。 */
    fun register() {
        if (registered) return
        registered = true

        // ---- 方块破坏 → X 射线统计检测 ----
        MinecraftForge.EVENT_BUS.addListener { e: BlockEvent.BreakEvent ->
            val h = GuardBootstrap.hub ?: return@addListener
            if (!h.xrayStats.enabled) return@addListener
            val sp = e.player as? ServerPlayer ?: return@addListener
            // 创造/旁观模式挖矿是管理员行为，不进统计 —— 否则清图会瞬间污染比率
            if (sp.isCreative || sp.isSpectator) return@addListener
            val state = e.state
            if (state.isAir) return@addListener

            // 暴露度：被挖方块六面中贴着空气的面数。
            val pos = e.pos
            var exposed = 0
            for (d in Direction.values()) {
                if (e.level.getBlockState(pos.relative(d)).isAir) exposed++
            }

            val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
            val player = ForgePlayer(sp)
            h.server.execute { h.xrayStats.onBlockBreak(player, blockId, exposed) }
        }

        // ---- 近战攻击 → 战斗遥测（reach + 急转） ----
        // 注意用 directEntity 而不是 entity：弓箭/药水由玩家射出，entity 是玩家但
        // directEntity 是箭 —— 那种「伤害」测 reach 没有意义（飞行道具本来就远程）。
        MinecraftForge.EVENT_BUS.addListener { e: LivingAttackEvent ->
            val h = GuardBootstrap.hub ?: return@addListener
            if (!h.combat.enabled) return@addListener
            val attacker = e.source.directEntity as? ServerPlayer ?: return@addListener
            // 创造/旁观在桥接层滤掉：那俩模式 5 格互动范围合法，会污染 reach 读数
            if (attacker.isCreative || attacker.isSpectator) return@addListener

            val eye = attacker.eyePosition
            val box = e.entity.boundingBox
            // 眼睛到目标碰撞箱**最近点**的距离（比中心距准：打大目标边缘时中心距虚高）
            val cx = eye.x.coerceIn(box.minX, box.maxX)
            val cy = eye.y.coerceIn(box.minY, box.maxY)
            val cz = eye.z.coerceIn(box.minZ, box.maxZ)
            val dx = eye.x - cx
            val dy = eye.y - cy
            val dz = eye.z - cz
            val dist = sqrt(dx * dx + dy * dy + dz * dz)

            val player = ForgePlayer(attacker)
            val snap = h.combat.snapOf(attacker.stringUUID)
            h.server.execute { h.combat.onAttack(player, dist, snap) }
        }

        // ---- 每 tick 视角采样 → 战斗遥测的「攻击所在 tick 角速度」 ----
        // 代价：O(在线玩家) 两次浮点读 + 一次 map 查，50 人局约 150 操作/tick，可忽略。
        MinecraftForge.EVENT_BUS.addListener { e: TickEvent.ServerTickEvent.Post ->
            val h = GuardBootstrap.hub ?: return@addListener
            if (!h.combat.enabled) return@addListener
            val players = e.server.playerList.players
            for (sp in players) {
                if (sp.isCreative || sp.isSpectator) continue
                h.combat.onRotate(sp.stringUUID, sp.yRot, sp.xRot)
            }
        }
    }
}