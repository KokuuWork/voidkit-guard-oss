package org.voidkit.guard.client

import org.voidkit.guard.common.GCrypto
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 客户端内置的服务端公钥环。
 *
 * ## 工作机制
 *
 * 服务端用私钥给每次挑战签名；客户端拿这里的公钥环验签。
 * 环为空 = 不校验（默认）—— 首次配置前先跑起来。
 *
 * ## 「定期换密钥」的正确用法
 *
 * 1. 服主在服务端执行 `/guard key rotate`（生成新密钥对，旧密钥作废）。
 * 2. `/guard key export` 打出新公钥的 base64。
 * 3. 把公钥贴进下面的 [RING]，重新构建发布客户端 jar。
 * 4. 玩家更新客户端 —— **旧版/被破解的 jar 直接失效**（验不过签，
 *    服务端也永远收不到它的 auth）。
 *
 * 这就是轮换的价值：不是让协议更难读，而是让**旧客户端定期死亡**。
 * 被破解的客户端每换一次密钥就要重新破解一次，成本转到对手身上。
 *
 * 🚨 只放 base64 公钥（SPKI），私钥永远只在服务端。
 */
object GuardKeys {

    /** 构建期内置的公钥环（base64 X.509 SPKI）。为空 = 跳过验签。 */
    val RING: List<String> = listOf(
        // 示例（不是真密钥）：
        // "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...",
    )

    /** 验签。环为空时返回 true（未配置 pinning 阶段）。 */
    fun verify(data: ByteArray, signature: ByteArray): Boolean {
        if (RING.isEmpty()) return true
        return RING.any { verifyOne(it, data, signature) }
    }

    private fun verifyOne(b64: String, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val keyBytes = Base64.getDecoder().decode(b64.replace("\\s".toRegex(), ""))
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val s = Signature.getInstance("SHA256withRSA")
        s.initVerify(key)
        s.update(data)
        s.verify(signature)
    }.getOrDefault(false)

    @Suppress("unused")
    fun ringSize(): Int = RING.size
}
