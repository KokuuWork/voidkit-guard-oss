package org.voidkit.guard.common

import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * guard 加密集。
 *
 * ## 密钥体系（一次连接一把会话密钥）
 *
 * 1. 服务端持有持久 RSA-2048 密钥对（`guard/keys/server.pem`，PKCS8）。
 * 2. 玩家进服：服务端发 [Challenge]（带 nonce + RSA 公钥）。
 * 3. 客户端随机生成 AES-256 会话密钥，RSA-OAEP(SHA-256) 包起来随 [Auth] 回传。
 * 4. 此后所有报文 AES-256-GCM，AAD 绑 消息id + 方向 + 序号 ——
 *    重放一条旧报文会因序号/AAD 不匹配直接认证失败。
 *
 * ## 威胁模型边界（写进 README，不自欺）
 *
 * 这套设计防的是「改客户端/装透视的玩家」：他没有服务端 RSA 私钥，
 * 伪造不了任何服务端消息；他的报告被 AES-GCM 包着，中间人拿不到明文，
 * nonce 让重放无用。它**不**防内核级对手（能读进程内存的人本来就能为所欲为）——
 * 那种对抗要内核驱动，属于动视 Ricochet / 腾讯 ACE 的领域，JVM 层做不到，
 * 我们用 [SecretBox] 把门槛抬到「不是随手就能做到」。
 */
object GCrypto {

    private const val RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    // ---------------------------------------------------------------- RSA

    fun newRsaKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("RSA")
        g.initialize(2048, SecureRandom())
        return g.generateKeyPair()
    }

    fun encodePublic(key: PublicKey): ByteArray = key.encoded // X.509 SPKI
    fun encodePrivate(key: PrivateKey): ByteArray = key.encoded // PKCS8

    fun decodePublic(b: ByteArray): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(b))

    fun decodePrivate(b: ByteArray): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(b))

    // ---------------------------------------------------------------- AES

    fun newAesKey(): ByteArray {
        val g = KeyGenerator.getInstance("AES")
        g.init(256, SecureRandom())
        return g.generateKey().encoded
    }

    fun randomBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        SecureRandom.getInstanceStrong().nextBytes(b)
        return b
    }

    /** RSA-OAEP 包会话密钥。服务端私钥只在解包瞬间出现在堆上，用完由调用方擦除。 */
    fun wrapKey(aesKey: ByteArray, publicKey: PublicKey): ByteArray {
        val c = Cipher.getInstance(RSA_TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, publicKey, SecureRandom())
        return c.doFinal(aesKey)
    }

    fun unwrapKey(wrapped: ByteArray, privateKey: PrivateKey): ByteArray? = runCatching {
        val c = Cipher.getInstance(RSA_TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, privateKey)
        c.doFinal(wrapped)
    }.getOrNull()

    /**
     * AES-256-GCM 加密。输出 `iv12 || ct || tag16`。
     *
     * @param aad 附加认证数据（调用方负责把 消息id/方向/序号/nonce 编进去）。
     *   任何一位不对，解密端直接失败 —— 这就是防重放、防换包的结构。
     */
    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(AES_TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return iv + ct
    }

    /** GCM 解密；认证失败返回 null（调用方按「报文被篡改」处理）。 */
    fun open(key: ByteArray, aad: ByteArray, data: ByteArray): ByteArray? {
        if (data.size < IV_LEN + 16) return null
        return runCatching {
            val iv = data.copyOfRange(0, IV_LEN)
            val c = Cipher.getInstance(AES_TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            c.updateAAD(aad)
            c.doFinal(data, IV_LEN, data.size - IV_LEN)
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 哈希

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun sha256Hex(b: ByteArray): String = sha256(b).toHex()

    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String {
        val hex = CharArray(size * 2)
        val digits = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hex[i * 2] = digits[v shr 4]
            hex[i * 2 + 1] = digits[v and 0xF]
        }
        return String(hex)
    }

    // ---------------------------------------------------------------- 密钥文件

    /** 服务端 RSA 私钥的 PEM 落盘格式（PKCS8 base64）。权限 0600 由调用方保证。 */
    fun privateToPem(key: PrivateKey): String = buildString {
        append("-----BEGIN PRIVATE KEY-----\n")
        append(Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(encodePrivate(key)))
        append("\n-----END PRIVATE KEY-----\n")
    }

    fun pemToPrivate(pem: String): PrivateKey? = runCatching {
        val body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        decodePrivate(Base64.getDecoder().decode(body))
    }.getOrNull()

    fun publicToPem(key: PublicKey): String = buildString {
        append("-----BEGIN PUBLIC KEY-----\n")
        append(Base64.getMimeEncoder(76, "\n".toByteArray()).encodeToString(encodePublic(key)))
        append("\n-----END PUBLIC KEY-----\n")
    }

    @Suppress("unused")
    fun secretKeyToString(key: SecretKey): String = Base64.getEncoder().encodeToString(key.encoded)
}
