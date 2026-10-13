package org.voidkit.guard.platform.neoforge

import net.neoforged.fml.ModList
import org.voidkit.guard.platform.GModInfo
import org.voidkit.guard.platform.GMods

/**
 * NeoForge 的 mod 枚举实现。
 *
 * 数据链：`ModList.get().mods` → `IModInfo.getOwningFile().getFile().getFilePath()`。
 * 开发环境（classes 目录加载）时 filePath 是目录不是 jar，[ModScanner] 会自然
 * 跳过（只哈希 isFile 的目标），不会炸。
 *
 * Fabric 移植时按 [GMods] 写一份 `FabricMods`，入口里
 * `GuardPlatforms.installMods(FabricMods())` 即可。
 */
class NeoforgeMods : GMods {

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
