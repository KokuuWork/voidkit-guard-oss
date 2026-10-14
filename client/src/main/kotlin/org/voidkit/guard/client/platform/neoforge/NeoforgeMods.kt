package org.voidkit.guard.client.platform.neoforge

import net.minecraftforge.fml.ModList
import org.voidkit.guard.client.platform.GModInfo
import org.voidkit.guard.client.platform.GMods

/**
 * Forge 1.20.1 的 mod 枚举实现。
 *
 * 数据链：`ModList.get().mods` → `IModInfo.getOwningFile().getFile().getFilePath()`。
 * 开发环境（classes 目录加载）时 filePath 是目录不是 jar，[org.voidkit.guard.client.hw.ModScanner] 会自然
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
