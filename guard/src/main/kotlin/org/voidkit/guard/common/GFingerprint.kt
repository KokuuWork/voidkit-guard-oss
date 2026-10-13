package org.voidkit.guard.common

/**
 * 硬件指纹。
 *
 * ## 设计原则：原文不出客户端
 *
 * 机器码是**加盐 SHA-256 哈希**（盐来自服务端配置 `fingerprint.hashSalt`）。
 * 服务端只存哈希、只按哈希匹配封禁 —— 拿到库也反推不出机器码原文，
 * 换台服务器盐不同哈希也不同（同一份数据不能跨服串用）。
 *
 * ## 为何只采集机器码
 *
 * 网卡 MAC、QQ 登录记录、微信痕迹属于更敏感的个人信息，
 * 时一律不采集 —— 机器码（系统/主板唯一标识）是「封禁换号」所需的最少
 * 且不可再少的标识：跟着硬件走，换号无效；只剩它一个维度已足够拦住
 * 「换个号重进」的普通作弊者。
 *
 * @param machine 主板/系统级机器码哈希（Windows: MachineGuid；Linux: machine-id；
 *   macOS: IOPlatformUUID）。换系统/换主板才会变。
 */
data class Fingerprint(
    val machine: String = "",
    val platform: String = "unknown",
) {
    /** 参与封禁匹配的标识集合（空字段自动剔除）。 */
    fun banKeys(): Map<String, String> = buildMap {
        if (machine.isNotBlank()) put("machine", machine)
    }
}

/** 客户端上报的一条 mod。 */
data class ModEntry(
    val id: String,
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String?,
)

/** 客户端上报的可疑信号（智能识别的产出）。 */
data class Signal(
    /** cheat_mod / tampered_mod / javaagent / debugger / self_tampered / unknown_mod ... */
    val type: String,
    val detail: String,
    /** 0=提示 1=可疑 2=确证（服务端按阈值决定动作）。 */
    val severity: Int,
)

/** 客户端自身完整性自检结果。 */
data class SelfIntegrity(
    val ok: Boolean,
    val expected: String,
    val actual: String,
    val detail: String,
)

/**
 * 客户端运行环境画像（系统版本 + 硬件配置）。
 *
 * ## 用途
 *
 * 1. **身份佐证**：同一台机器的 CPU/GPU/内存组合是稳定的辅助标识，
 *    换号不换机的人在这露出马脚（进封禁匹配的 `envHash`）。
 * 2. **异常信号**：软件渲染器（llvmpipe/软渲染）+ 虚拟机特征，
 *    都是远程桌面/虚拟机/无显卡环境的典型痕迹。
 * 3. **报告可读性**：服主看报告时能直接看到「Win11 + 13700K + 4070」，
 *    比一串哈希好用。
 *
 * ## 不采位置/区域
 *
 * 时区、系统区域、语言这些「定位替身」属于位置类信息，
 * 一律不采集（`EnvInfo` 里根本没有这些字段）。环境画像只保留设备配置：
 * OS/CPU/GPU/内存 —— 这些不指向「谁」，只指向「什么机器」。
 */
data class EnvInfo(
    /** 操作系统名 + 版本，如 `Windows 11 / 10.0.26100`。 */
    val osName: String = "",
    val osVersion: String = "",
    val osArch: String = "",
    /** CPU 型号 + 核心数。 */
    val cpu: String = "",
    val cpuCores: Int = 0,
    /** GPU 型号（GL_RENDERER）。空 = 没取到（服务端/无上下文）。 */
    val gpu: String = "",
    val glVendor: String = "",
    /** 物理内存（MB）。 */
    val ramMb: Long = 0,
    val javaVersion: String = "",
) {
    /** 参与身份匹配的稳定组合哈希（不含任何位置/区域字段）。 */
    fun envHash(): String = GCrypto.sha256Hex(
        listOf(osName, osVersion, cpu, cpuCores.toString(), gpu, ramMb.toString()).joinToString("|")
    )
}

/**
 * 客户端完整报告（AES-GCM 加密后传输，服务端验签解密）。
 *
 * @param nonce 回显服务端 nonce —— 服务端校验一致，重放旧报告直接死。
 * @param env 运行环境画像（系统版本/硬件配置，不含位置/区域）。
 */
data class ClientReport(
    val proto: Int,
    val nonce: String,
    val agent: String,
    val player: String,
    val uuid: String,
    val clientBrand: String,
    val mods: List<ModEntry>,
    val fingerprint: Fingerprint?,
    val signals: List<Signal>,
    val selfIntegrity: SelfIntegrity,
    val env: EnvInfo = EnvInfo(),
)

/** 服务端完整性策略。 */
enum class IntegrityMode(val id: String, val display: String) {
    /** 智能识别：特征库 + 自检 + 信号分级，命中才处置（默认，零配置可用）。 */
    SMART("smart", "智能识别"),

    /** 哈希锁：mod 清单必须和白名单逐项一致，多一个少一个都拒。 */
    HASH_LOCK("hashlock", "哈希锁");

    companion object {
        fun from(id: String?): IntegrityMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SMART
    }
}

/** 校验失败时的服务端动作。 */
enum class FailAction(val id: String, val display: String) {
    KICK("kick", "踢出"),
    KICK_AND_BAN("kick_ban", "踢出并封禁机器"),
    ;

    companion object {
        fun from(id: String?): FailAction =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: KICK
    }
}
