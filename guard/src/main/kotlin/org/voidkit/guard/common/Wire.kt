package org.voidkit.guard.common

/**
 * 极简二进制读写（大端，带长度前缀）。
 *
 * 所有 guard 报文都是同一种信封 `GuardWire(id, body)`，body 的编解码全在这里完成。
 * 不引第三方序列化框架：报文结构固定且极小（握手几个字段），手写编解码
 * 比 Gson 更可控 —— 尤其是长度前缀能天然防「超长字段撑爆内存」。
 */
class ByteWriter(capacity: Int = 256) {
    private val buf = java.io.ByteArrayOutputStream(capacity)

    fun u8(v: Int): ByteWriter { buf.write(v and 0xFF); return this }
    fun u16(v: Int): ByteWriter {
        buf.write((v shr 8) and 0xFF); buf.write(v and 0xFF); return this
    }
    fun i32(v: Int): ByteWriter {
        buf.write((v shr 24) and 0xFF); buf.write((v shr 16) and 0xFF)
        buf.write((v shr 8) and 0xFF); buf.write(v and 0xFF); return this
    }
    fun i64(v: Long): ByteWriter {
        for (s in 56 downTo 0 step 8) buf.write(((v shr s) and 0xFF).toInt())
        return this
    }
    /** 长度前缀（i32，上限 16MB）字节数组。超限直接抛异常，不给畸形包机会。 */
    fun bytes(v: ByteArray?): ByteWriter {
        require(v == null || v.size <= MAX_FIELD) { "字段超长: ${v?.size}" }
        if (v == null) i32(-1) else { i32(v.size); buf.write(v) }
        return this
    }
    /** 长度前缀 UTF-8 字符串。 */
    fun str(v: String?): ByteWriter {
        if (v == null) { i32(-1); return this }
        val b = v.toByteArray(Charsets.UTF_8)
        require(b.size <= MAX_FIELD) { "字段超长: ${b.size}" }
        i32(b.size); buf.write(b)
        return this
    }
    fun strList(v: List<String>?): ByteWriter {
        if (v == null) { i32(-1); return this }
        i32(v.size)
        for (s in v) str(s)
        return this
    }

    fun toByteArray(): ByteArray = buf.toByteArray()

    companion object {
        /** 单字段硬上限 16MB —— 截屏分片单独走加密流，不经过这个编解码器。 */
        const val MAX_FIELD = 16 * 1024 * 1024
    }
}

class ByteReader(private val buf: ByteArray) {
    private var pos = 0

    private fun need(n: Int) {
        if (pos + n > buf.size) throw IllegalArgumentException("报文截断：还需要 $n 字节，剩 ${buf.size - pos}")
    }

    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }
    fun u16(): Int { need(2); return (u8() shl 8) or u8() }
    fun i32(): Int { need(4); return (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8() }
    fun i64(): Long {
        var v = 0L
        for (s in 56 downTo 0 step 8) v = (v shl 8) or u8().toLong()
        return v
    }
    fun bytes(): ByteArray? {
        val n = i32()
        if (n < 0) return null
        require(n <= ByteWriter.MAX_FIELD) { "字段超长: $n" }
        need(n)
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
    fun str(): String? {
        val b = bytes() ?: return null
        return String(b, Charsets.UTF_8)
    }
    fun strList(): List<String>? {
        val n = i32()
        if (n < 0) return null
        require(n in 0..4096) { "列表长度异常: $n" }
        return (0 until n).map { str() ?: "" }
    }
    fun remaining(): Int = buf.size - pos
    fun fullyConsumed(): Boolean = pos == buf.size
}
