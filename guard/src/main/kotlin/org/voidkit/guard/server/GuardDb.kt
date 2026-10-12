package org.voidkit.guard.server

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Types
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * guard 的 SQLite 存储：封禁、身份指纹、审计、截屏记录。
 *
 * ## 与查熊（inspector）同款线程模型，但更轻
 *
 * guard 的写量极小（封禁/审计是稀有事件），所以不做字典化和批量事务那套重装备：
 * - 写：单一写线程 + 有界队列。主线程只 `offer`（O(1) 不阻塞），
 *   需要结果的操作（封禁/解封）用 [writeAsync] 拿 future。
 * - 读：独立查询线程池 + ThreadLocal 读连接，WAL 一写多读。
 *   封禁检查每次进服一次、API 查询都在 HTTP 线程 —— 都不会碰主线程。
 *
 * ## 表结构
 *
 * - `bans`：一条封禁 = 一个 (类型, 值)。一个玩家被封会写入多条
 *   （ip/machine/mac0..n/qq0..n/uuid/name），任一条命中即拒。
 * - `identities`：玩家最新一份指纹档案（机器码/MAC/QQ 的哈希）。
 * - `audit`：全部审计事件（进服/校验/封禁/截屏/白名单变更）。
 * - `screenshots`：截屏记录（文件路径 + 尺寸 + 原因）。
 */
class GuardDb(private val dbPath: Path) : AutoCloseable {

    // ---------------- 数据行 ----------------

    class BanRow(
        val id: Long,
        val keyType: String,
        val keyValue: String,
        val reason: String,
        val admin: String,
        val created: Long,
        val expires: Long,
    )

    class IdentityRow(
        val uuid: String,
        val name: String,
        val ip: String,
        val machine: String,
        val macs: List<String>,
        val qq: List<String>,
        val firstSeen: Long,
        val lastSeen: Long,
    )

    class AuditRow(
        val id: Long,
        val t: Long,
        val type: String,
        val actor: String,
        val target: String,
        val ip: String,
        val detail: String,
    )

    class ShotRow(
        val id: Long,
        val t: Long,
        val player: String,
        val uuid: String,
        val reqId: String,
        val path: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val reason: String,
        val admin: String,
    )

    // ---------------- 状态 ----------------

    private val running = AtomicBoolean(true)

    private interface WriteOp {
        fun run(conn: Connection)
    }

    private class ValueOp<T>(val body: (Connection) -> T) : WriteOp {
        val future = CompletableFuture<T>()
        override fun run(conn: Connection) {
            try {
                future.complete(body(conn))
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
    }

    private val writeQueue = LinkedBlockingQueue<WriteOp>()

    /** 审计入队专用有界队列：满了就丢并计数，绝不阻塞主线程。 */
    private val auditQueue = LinkedBlockingQueue<AuditRow>(2048)

    private val queued = AtomicLong(0)
    private val written = AtomicLong(0)
    private val dropped = AtomicLong(0)

    private lateinit var writerConn: Connection
    private lateinit var writer: Thread

    private val queryPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "voidkit-guard-query").apply { isDaemon = true }
    }
    private val readConnLocal = ThreadLocal<Connection>()
    private val readConns = CopyOnWriteArrayList<Connection>()

    private val LOG = org.voidkit.guard.VoidkitGuard.LOG

    // ---------------- 生命周期 ----------------

    fun open() {
        val dir = dbPath.toAbsolutePath().parent
        if (dir != null) Files.createDirectories(dir)
        writerConn = openConnection()
        initSchema(writerConn)
        writer = Thread({ writerLoop() }, "voidkit-guard-writer").apply { isDaemon = true }
        writer.start()
        LOG.info("反作弊数据库就绪：{}", dbPath.toAbsolutePath())
    }

    override fun close() {
        running.set(false)
        runCatching { writer.interrupt() }
        runCatching { writer.join(8000) }
        runCatching { writerConn.close() }
        readConns.forEach { runCatching { it.close() } }
        readConns.clear()
        queryPool.shutdownNow()
    }

    // ---------------- 连接 ----------------

    private fun openConnection(): Connection {
        val cfg = SQLiteConfig()
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL)
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        cfg.setBusyTimeout(5000)
        cfg.setTempStore(SQLiteConfig.TempStore.MEMORY)
        val ds = SQLiteDataSource(cfg)
        ds.url = "jdbc:sqlite:${dbPath.toAbsolutePath()}"
        return ds.connection
    }

    private fun readConn(): Connection {
        readConnLocal.get()?.let { if (!it.isClosed) return it }
        val c = openConnection()
        readConns.add(c)
        readConnLocal.set(c)
        return c
    }

    private fun initSchema(conn: Connection) {
        conn.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS bans (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "key_type TEXT NOT NULL," +
                    "key_value TEXT NOT NULL," +
                    "reason TEXT NOT NULL DEFAULT ''," +
                    "admin TEXT NOT NULL DEFAULT ''," +
                    "created INTEGER NOT NULL," +
                    "expires INTEGER NOT NULL DEFAULT 0)"
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_bans_key ON bans(key_type, key_value)")
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS identities (" +
                    "uuid TEXT PRIMARY KEY," +
                    "name TEXT NOT NULL," +
                    "ip TEXT NOT NULL DEFAULT ''," +
                    "machine TEXT NOT NULL DEFAULT ''," +
                    "macs TEXT NOT NULL DEFAULT ''," +
                    "qq TEXT NOT NULL DEFAULT ''," +
                    "first_seen INTEGER NOT NULL," +
                    "last_seen INTEGER NOT NULL)"
            )
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS audit (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "t INTEGER NOT NULL," +
                    "type TEXT NOT NULL," +
                    "actor TEXT NOT NULL DEFAULT ''," +
                    "target TEXT NOT NULL DEFAULT ''," +
                    "ip TEXT NOT NULL DEFAULT ''," +
                    "detail TEXT NOT NULL DEFAULT '')"
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_t ON audit(t)")
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_type ON audit(type, t)")
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS screenshots (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "t INTEGER NOT NULL," +
                    "player TEXT NOT NULL," +
                    "uuid TEXT NOT NULL DEFAULT ''," +
                    "req_id TEXT NOT NULL DEFAULT ''," +
                    "path TEXT NOT NULL," +
                    "width INTEGER NOT NULL DEFAULT 0," +
                    "height INTEGER NOT NULL DEFAULT 0," +
                    "bytes INTEGER NOT NULL DEFAULT 0," +
                    "reason TEXT NOT NULL DEFAULT ''," +
                    "admin TEXT NOT NULL DEFAULT '')"
            )
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_shot_player ON screenshots(player, t)")
        }
    }

    // ---------------- 写入口 ----------------

    /** 提交一个写操作并等待结果（命令/握手路径用，低频可接受）。 */
    fun <T> writeAsync(body: (Connection) -> T): CompletableFuture<T> {
        val op = ValueOp(body)
        if (!writeQueue.offer(op)) {
            op.future.completeExceptionally(IllegalStateException("写队列已满"))
        }
        return op.future
    }

    /** 审计入队：满了丢弃（计数），永不阻塞主线程。 */
    fun offerAudit(row: AuditRow): Boolean {
        val ok = auditQueue.offer(row)
        if (ok) queued.incrementAndGet() else dropped.incrementAndGet()
        return ok
    }

    fun stats(): Map<String, Long> = mapOf(
        "queued" to queued.get(),
        "written" to written.get(),
        "dropped" to dropped.get(),
        "pending" to (writeQueue.size + auditQueue.size).toLong(),
    )

    /** 提交清理任务（由写线程空闲时执行）。 */
    private val purgeCutoff = java.util.concurrent.atomic.AtomicLong(0L)

    fun schedulePurge(cutoffMs: Long) {
        purgeCutoff.set(cutoffMs)
    }

    private fun writerLoop() {
        try {
            while (running.get()) {
                val first = try {
                    writeQueue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (first != null) {
                    runOp(first)
                    var op = writeQueue.poll()
                    while (op != null) {
                        runOp(op)
                        op = writeQueue.poll()
                    }
                }
                // 审计队列：批量搬进写连接（一个事务）
                drainAudit()
                drainPurge()
            }
        } catch (t: Throwable) {
            if (running.get()) LOG.error("反作弊写线程异常退出", t)
        } finally {
            var op = writeQueue.poll()
            while (op != null) {
                runOp(op)
                op = writeQueue.poll()
            }
            drainAudit()
        }
    }

    private fun runOp(op: WriteOp) {
        try {
            op.run(writerConn)
            written.incrementAndGet()
        } catch (t: Throwable) {
            LOG.warn("写操作失败：{}", t.message)
        }
    }

    private fun drainAudit() {
        val batch = ArrayList<AuditRow>()
        auditQueue.drainTo(batch, 256)
        if (batch.isEmpty()) return
        writerConn.autoCommit = false
        try {
            val ps = writerConn.prepareStatement(
                "INSERT INTO audit(t,type,actor,target,ip,detail) VALUES(?,?,?,?,?,?)"
            )
            for (r in batch) {
                ps.setLong(1, r.t)
                ps.setString(2, r.type)
                ps.setString(3, r.actor)
                ps.setString(4, r.target)
                ps.setString(5, r.ip)
                ps.setString(6, r.detail)
                ps.addBatch()
            }
            ps.executeBatch()
            writerConn.commit()
            written.addAndGet(batch.size.toLong())
        } catch (t: Throwable) {
            LOG.warn("审计批量写入失败：{}", t.message)
            runCatching { writerConn.rollback() }
        } finally {
            runCatching { writerConn.autoCommit = true }
        }
    }

    private fun drainPurge() {
        val cutoff = purgeCutoff.get()
        if (cutoff <= 0L) return
        purgeCutoff.set(0L)
        try {
            writerConn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM audit WHERE t < $cutoff")
                st.executeUpdate("DELETE FROM screenshots WHERE t < $cutoff")
            }
        } catch (t: Throwable) {
            LOG.warn("清理过期记录失败：{}", t.message)
        }
    }

    // ---------------- 读入口 ----------------

    private fun <T> read(body: (Connection) -> T): T = readConn().use { body(it) }

    /** 异步查询（命令路径用，不占主线程）。 */
    fun <T> readAsync(body: (Connection) -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync({ read(body) }, queryPool)

    // ---------------- 封禁 ----------------

    /** 添加一条封禁。返回新行 id。 */
    fun addBan(keyType: String, keyValue: String, reason: String, admin: String, expiresAt: Long): Long =
        writeAsync { conn ->
            conn.prepareStatement(
                "INSERT INTO bans(key_type,key_value,reason,admin,created,expires) VALUES(?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setString(1, keyType)
                ps.setString(2, keyValue)
                ps.setString(3, reason)
                ps.setString(4, admin)
                ps.setLong(5, System.currentTimeMillis())
                ps.setLong(6, expiresAt)
                ps.executeUpdate()
                ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
            }
        }.get(10, TimeUnit.SECONDS)

    /** 解封。返回删除的行数。 */
    fun liftBan(id: Long): Int =
        writeAsync { conn ->
            conn.prepareStatement("DELETE FROM bans WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }.get(10, TimeUnit.SECONDS)

    /** 按 (类型, 值) 精确查有效封禁（未过期）。 */
    fun activeBan(keyType: String, keyValue: String): BanRow? = read { conn ->
        conn.prepareStatement(
            "SELECT id,key_type,key_value,reason,admin,created,expires FROM bans " +
                "WHERE key_type = ? AND key_value = ? AND (expires = 0 OR expires > ?)"
        ).use { ps ->
            ps.setString(1, keyType)
            ps.setString(2, keyValue)
            ps.setLong(3, System.currentTimeMillis())
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    BanRow(
                        id = rs.getLong("id"),
                        keyType = rs.getString("key_type"),
                        keyValue = rs.getString("key_value"),
                        reason = rs.getString("reason"),
                        admin = rs.getString("admin"),
                        created = rs.getLong("created"),
                        expires = rs.getLong("expires"),
                    )
                } else null
            }
        }
    }

    /**
     * 一批标识里有没有撞上有效封禁。返回第一个命中的行。
     *
     * 进服时一次查完 IP/机器码/MAC/QQ/UUID —— 比逐条查少一轮往返。
     */
    fun matchBan(keys: List<Pair<String, String>>): BanRow? {
        if (keys.isEmpty()) return null
        return read { conn ->
            val ph = keys.joinToString(" OR ") { "(key_type = ? AND key_value = ?)" }
            conn.prepareStatement(
                "SELECT id,key_type,key_value,reason,admin,created,expires FROM bans " +
                    "WHERE ($ph) AND (expires = 0 OR expires > ?) LIMIT 1"
            ).use { ps ->
                var i = 1
                for ((t, v) in keys) {
                    ps.setString(i++, t)
                    ps.setString(i++, v)
                }
                ps.setLong(i, System.currentTimeMillis())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        BanRow(
                            id = rs.getLong("id"),
                            keyType = rs.getString("key_type"),
                            keyValue = rs.getString("key_value"),
                            reason = rs.getString("reason"),
                            admin = rs.getString("admin"),
                            created = rs.getLong("created"),
                            expires = rs.getLong("expires"),
                        )
                    } else null
                }
            }
        }
    }

    /** 封禁列表（新的在前）。 */
    fun bans(limit: Int, offset: Int): List<BanRow> = read { conn ->
        conn.prepareStatement(
            "SELECT id,key_type,key_value,reason,admin,created,expires FROM bans " +
                "ORDER BY id DESC LIMIT ? OFFSET ?"
        ).use { ps ->
            ps.setInt(1, limit.coerceIn(1, 500))
            ps.setInt(2, offset.coerceAtLeast(0))
            ps.executeQuery().use { rs ->
                val out = ArrayList<BanRow>()
                while (rs.next()) {
                    out.add(
                        BanRow(
                            id = rs.getLong("id"),
                            keyType = rs.getString("key_type"),
                            keyValue = rs.getString("key_value"),
                            reason = rs.getString("reason"),
                            admin = rs.getString("admin"),
                            created = rs.getLong("created"),
                            expires = rs.getLong("expires"),
                        )
                    )
                }
                out
            }
        }
    }

    fun banCount(): Int = read { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM bans").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    // ---------------- 身份档案 ----------------

    /**  upsert 一份指纹档案（按 uuid）。 */
    fun recordIdentity(
        uuid: String,
        name: String,
        ip: String,
        machine: String,
        macs: List<String>,
        qq: List<String>,
    ) {
        writeAsync { conn ->
            val now = System.currentTimeMillis()
            conn.prepareStatement(
                "INSERT INTO identities(uuid,name,ip,machine,macs,qq,first_seen,last_seen) VALUES(?,?,?,?,?,?,?,?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, ip=excluded.ip, machine=excluded.machine, " +
                    "macs=excluded.macs, qq=excluded.qq, last_seen=excluded.last_seen"
            ).use { ps ->
                ps.setString(1, uuid)
                ps.setString(2, name)
                ps.setString(3, ip)
                ps.setString(4, machine)
                ps.setString(5, macs.joinToString(","))
                ps.setString(6, qq.joinToString(","))
                ps.setLong(7, now)
                ps.setLong(8, now)
                ps.executeUpdate()
            }
        }
    }

    fun identityByUuid(uuid: String): IdentityRow? = read { conn ->
        conn.prepareStatement(
            "SELECT uuid,name,ip,machine,macs,qq,first_seen,last_seen FROM identities WHERE uuid = ?"
        ).use { ps ->
            ps.setString(1, uuid)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    IdentityRow(
                        uuid = rs.getString("uuid"),
                        name = rs.getString("name"),
                        ip = rs.getString("ip"),
                        machine = rs.getString("machine"),
                        macs = rs.getString("macs").split(',').filter { it.isNotBlank() },
                        qq = rs.getString("qq").split(',').filter { it.isNotBlank() },
                        firstSeen = rs.getLong("first_seen"),
                        lastSeen = rs.getLong("last_seen"),
                    )
                } else null
            }
        }
    }

    fun identityByName(name: String): IdentityRow? = read { conn ->
        conn.prepareStatement(
            "SELECT uuid,name,ip,machine,macs,qq,first_seen,last_seen FROM identities WHERE name = ? " +
                "ORDER BY last_seen DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    IdentityRow(
                        uuid = rs.getString("uuid"),
                        name = rs.getString("name"),
                        ip = rs.getString("ip"),
                        machine = rs.getString("machine"),
                        macs = rs.getString("macs").split(',').filter { it.isNotBlank() },
                        qq = rs.getString("qq").split(',').filter { it.isNotBlank() },
                        firstSeen = rs.getLong("first_seen"),
                        lastSeen = rs.getLong("last_seen"),
                    )
                } else null
            }
        }
    }

    // ---------------- 审计 ----------------

    fun audit(limit: Int, type: String?): List<AuditRow> = read { conn ->
        val sql = if (type.isNullOrBlank()) {
            "SELECT id,t,type,actor,target,ip,detail FROM audit ORDER BY id DESC LIMIT ?"
        } else {
            "SELECT id,t,type,actor,target,ip,detail FROM audit WHERE type = ? ORDER BY id DESC LIMIT ?"
        }
        conn.prepareStatement(sql).use { ps ->
            if (!type.isNullOrBlank()) ps.setString(1, type)
            ps.setInt(if (type.isNullOrBlank()) 1 else 2, limit.coerceIn(1, 500))
            ps.executeQuery().use { rs ->
                val out = ArrayList<AuditRow>()
                while (rs.next()) {
                    out.add(
                        AuditRow(
                            id = rs.getLong("id"),
                            t = rs.getLong("t"),
                            type = rs.getString("type"),
                            actor = rs.getString("actor"),
                            target = rs.getString("target"),
                            ip = rs.getString("ip"),
                            detail = rs.getString("detail"),
                        )
                    )
                }
                out
            }
        }
    }

    // ---------------- 截屏记录 ----------------

    fun addScreenshot(
        t: Long, player: String, uuid: String, reqId: String, path: String,
        width: Int, height: Int, bytes: Long, reason: String, admin: String,
    ): Long = writeAsync { conn ->
        conn.prepareStatement(
            "INSERT INTO screenshots(t,player,uuid,req_id,path,width,height,bytes,reason,admin) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setLong(1, t)
            ps.setString(2, player)
            ps.setString(3, uuid)
            ps.setString(4, reqId)
            ps.setString(5, path)
            ps.setInt(6, width)
            ps.setInt(7, height)
            ps.setLong(8, bytes)
            ps.setString(9, reason)
            ps.setString(10, admin)
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else -1L }
        }
    }.get(10, TimeUnit.SECONDS)

    fun screenshot(id: Long): ShotRow? = read { conn ->
        conn.prepareStatement(
            "SELECT id,t,player,uuid,req_id,path,width,height,bytes,reason,admin FROM screenshots WHERE id = ?"
        ).use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    ShotRow(
                        id = rs.getLong("id"),
                        t = rs.getLong("t"),
                        player = rs.getString("player"),
                        uuid = rs.getString("uuid"),
                        reqId = rs.getString("req_id"),
                        path = rs.getString("path"),
                        width = rs.getInt("width"),
                        height = rs.getInt("height"),
                        bytes = rs.getLong("bytes"),
                        reason = rs.getString("reason"),
                        admin = rs.getString("admin"),
                    )
                } else null
            }
        }
    }

    fun screenshots(limit: Int, offset: Int, player: String?): List<ShotRow> = read { conn ->
        val sql = if (player.isNullOrBlank()) {
            "SELECT id,t,player,uuid,req_id,path,width,height,bytes,reason,admin FROM screenshots ORDER BY id DESC LIMIT ? OFFSET ?"
        } else {
            "SELECT id,t,player,uuid,req_id,path,width,height,bytes,reason,admin FROM screenshots WHERE player = ? ORDER BY id DESC LIMIT ? OFFSET ?"
        }
        conn.prepareStatement(sql).use { ps ->
            if (!player.isNullOrBlank()) ps.setString(1, player)
            ps.setInt(if (player.isNullOrBlank()) 1 else 2, limit.coerceIn(1, 200))
            ps.setInt(if (player.isNullOrBlank()) 2 else 3, offset.coerceAtLeast(0))
            ps.executeQuery().use { rs ->
                val out = ArrayList<ShotRow>()
                while (rs.next()) {
                    out.add(
                        ShotRow(
                            id = rs.getLong("id"),
                            t = rs.getLong("t"),
                            player = rs.getString("player"),
                            uuid = rs.getString("uuid"),
                            reqId = rs.getString("req_id"),
                            path = rs.getString("path"),
                            width = rs.getInt("width"),
                            height = rs.getInt("height"),
                            bytes = rs.getLong("bytes"),
                            reason = rs.getString("reason"),
                            admin = rs.getString("admin"),
                        )
                    )
                }
                out
            }
        }
    }

    @Suppress("unused")
    fun unusedTypesImport() {
        // 保持 Types 引用，避免 IDE 误删 import（ps.setNull 路径备用）
        Types.BLOB
    }
}

/** 审计行工厂（业务层不直接拼 SQL / JSON）。 */
object GuardAudit {
    fun row(
        type: String,
        actor: String = "",
        target: String = "",
        ip: String = "",
        detail: Map<String, Any?> = emptyMap(),
    ): GuardDb.AuditRow {
        val json = org.voidkit.core.api.ApiJson.obj()
        for ((k, v) in detail) org.voidkit.core.api.ApiJson.put(json, k, v)
        return GuardDb.AuditRow(
            id = 0,
            t = System.currentTimeMillis(),
            type = type,
            actor = actor,
            target = target,
            ip = ip,
            detail = json.toString(),
        )
    }
}
