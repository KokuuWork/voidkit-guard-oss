package org.voidkit.guard.client.platform.forge

import net.minecraftforge.fml.ModList
import org.voidkit.guard.client.platform.GModInfo
import org.voidkit.guard.client.platform.GMods

/**
 * Forge 的 mod 枚举实现。
 *
 * 数据链与 NeoForge 版相同：`ModList.get().mods` → owningFile.file.filePath`
 * —— 1.21.1 上两加载器的 `ModList` 形状一致（同为 1.21.1 原版 Mjg 映射下的
 * `IModInfo` 体系），只是包名 `net.neoforged.fml` → `net.minecraftforge.fml`。
 *
 * 开发环境（classes 目录加载）时 filePath 是目录不是 jar，[ModScanner] 会自然
 * 跳过（只哈希 isFile 的目标），不会炸。
 */
class ForgeMods : GMods {

    override fun loadedMods(): List<GModInfo> = runCatching {
        ModList.get().mods.map { info ->
            val path = runCatching { info.owningFile.file.filePath }.getOrNull()?.toString()
            GModInfo(
                id = info.modId,
                version = info.version?.toString() ?: "0",
                filePath = path,
            )
        }
    }.getOrElse { emptyList() }
}