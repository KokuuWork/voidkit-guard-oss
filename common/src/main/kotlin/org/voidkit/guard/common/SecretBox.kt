package org.voidkit.guard.common

import java.util.Arrays

/**
 * 内存级机密保护（对标动视 Ricochet / 腾讯 ACE 的「运行时内存加密」思路，
 * 但在 JVM 用户态能做到的程度内）。
 *
 * ## 它防什么
 *
 * - Cheat Engine / 进程内存扫描器按**明文字符串**找密钥、机器码、QQ 号：
 *  [SecretBox] 在堆里永远只放 XOR 混淆副本，明文只在 `use { }`  lambda 内存活
 *  几微秒，出来立即擦除。字符串扫描器什么也搜不到。
 * - 简单 hex 编辑器改内存里的会话密钥：混淆副本和明文不是同一块内存，
 *  改混淆副本只会让认证失败（GCM tag 对不上），改不了行为。
 * - Java Agent  attach / Instrumentation：客户端 [SelfIntegrity] 会检测
 *  `-javaagent` 参数与自身 jar 哈希（见 client/scan/SelfIntegrity.kt）。
 *
 * ## 它不防什么（README 里必须写清楚，不夸大）
 *
 * - 内核级对手：能读任意进程内存的人不需要绕过这些花活，直接读明文窗口期。
 * - 有 JVM 经验的对手：可以 dump 堆、在 GCM 调用点下断。动视/腾讯靠内核驱动
 *  解决这个问题，JVM 层物理上做不到。
 * - 结论：**这是把作弊成本从「随手一个工具」抬到「要写针对性代码」的工程，
 *  不是铜墙铁壁。** 真正的防线是哈希锁 + 服务端校验 + 封禁组合拳。
 */
object HeapGuard {
    /** 就地擦除字节数组。防御性编程：失败也不抛（擦除失败不该中断业务）。 */
    fun wipe(b: ByteArray?) {
        if (b == null || b.isEmpty()) return
        runCatching { Arrays.fill(b, 0) }
    }

    fun wipe(c: CharArray?) {
        if (c == null || c.isEmpty()) return
        runCatching { Arrays.fill(c, '\u0000') }
    }
}

/**
 * 混淆保存的字节秘密。构造时立即混淆原件并保留副本，原件由调用方负责擦除。
 *
 * ```kotlin
 * val box = SecretBox(sessionKey)   // 此后堆里只有混淆副本
 * box.use { plain -> send(plain) }  // 明文只在这几行里存在
 * box.wipe()                        // 连混淆副本一起擦
 * ```
 */
class SecretBox(secret: ByteArray) {
    private var masked: ByteArray? = ByteArray(secret.size)
    private val mask: ByteArray = GCrypto.randomBytes(secret.size)

    init {
        for (i in secret.indices) masked!![i] = (secret[i].toInt() xor mask[i].toInt()).toByte()
        HeapGuard.wipe(secret)
    }

    /** 明文只在 lambda 内可见；lambda 返回后立即擦除临时明文。 */
    fun <T> use(body: (ByteArray) -> T): T {
        val m = masked ?: error("SecretBox 已擦除")
        val plain = ByteArray(m.size)
        for (i in m.indices) plain[i] = (m[i].toInt() xor mask[i].toInt()).toByte()
        try {
            return body(plain)
        } finally {
            HeapGuard.wipe(plain)
        }
    }

    fun wiped(): Boolean = masked == null

    fun wipe() {
        HeapGuard.wipe(masked)
        HeapGuard.wipe(mask)
        masked = null
    }
}

/**
 * 混淆保存的字符串秘密（机器码哈希、QQ 号哈希等）。
 *
 * 为什么不用 String：String 不可变，进了堆就等到 GC，且字符串常量池 /
 * heap dump 里明晃晃。char[] 至少能擦。
 */
class SecretString(secret: String) {
    private var masked: CharArray? = CharArray(secret.length)
    private val mask: CharArray = CharArray(secret.length) { kotlin.random.Random.nextInt().toChar() }

    init {
        for (i in secret.indices) masked!![i] = (secret[i].code xor mask[i].code).toChar()
    }

    fun <T> use(body: (String) -> T): T {
        val m = masked ?: error("SecretString 已擦除")
        val plain = CharArray(m.size)
        for (i in m.indices) plain[i] = (m[i].code xor mask[i].code).toChar()
        try {
            return body(String(plain))
        } finally {
            HeapGuard.wipe(plain)
        }
    }

    fun wipe() {
        HeapGuard.wipe(masked)
        HeapGuard.wipe(mask)
        masked = null
    }
}
