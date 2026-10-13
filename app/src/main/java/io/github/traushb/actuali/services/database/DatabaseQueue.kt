package io.github.traushb.actuali.services.database

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One fetched row, keyed by column name like a GRDB `Row`. Values are what
 * SQLite stored: Long, Double, String, ByteArray or null. The typed readers
 * convert the way GRDB does — numbers between each other, text from text or
 * UTF-8 blobs.
 */
class Row(private val columns: Map<String, Int>, private val values: Array<Any?>) {
    operator fun get(column: String): Any? = columns[column]?.let { values[it] }

    operator fun get(index: Int): Any? = values.getOrNull(index)

    fun hasColumn(column: String): Boolean = column in columns

    fun string(column: String): String? = text(get(column))

    fun string(index: Int): String? = text(get(index))

    fun long(column: String): Long? = (get(column) as? Number)?.toLong()

    fun long(index: Int): Long? = (get(index) as? Number)?.toLong()

    fun int(column: String): Int? = long(column)?.toInt()

    fun double(column: String): Double? = (get(column) as? Number)?.toDouble()

    fun double(index: Int): Double? = (get(index) as? Number)?.toDouble()

    fun bool(column: String): Boolean? = long(column)?.let { it != 0L }

    fun blob(column: String): ByteArray? = when (val value = get(column)) {
        is ByteArray -> value
        is String -> value.toByteArray(Charsets.UTF_8)
        else -> null
    }

    private fun text(value: Any?): String? = when (value) {
        is String -> value
        is ByteArray -> value.toString(Charsets.UTF_8)
        else -> null
    }
}

/** A connection inside a `read`/`write` block — the `Database` of a GRDB closure. */
class Db internal constructor(private val connection: SQLiteConnection) {
    private fun prepare(sql: String, args: List<Any?>): SQLiteStatement {
        val statement = connection.prepare(sql)
        try {
            args.forEachIndexed { i, value -> bind(statement, i + 1, value) }
        } catch (e: Exception) {
            statement.close()
            throw e
        }
        return statement
    }

    private fun bind(statement: SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.bindNull(index)
            is Long -> statement.bindLong(index, value)
            is Int -> statement.bindLong(index, value.toLong())
            is Short -> statement.bindLong(index, value.toLong())
            is Byte -> statement.bindLong(index, value.toLong())
            is Boolean -> statement.bindLong(index, if (value) 1 else 0)
            is Double -> statement.bindDouble(index, value)
            is Float -> statement.bindDouble(index, value.toDouble())
            is String -> statement.bindText(index, value)
            is ByteArray -> statement.bindBlob(index, value)
            else -> statement.bindText(index, value.toString())
        }
    }

    /** Executes one statement. */
    fun execute(sql: String, args: List<Any?> = emptyList()) {
        prepare(sql, args).use { statement -> while (statement.step()) Unit }
    }

    fun execute(sql: String, vararg args: Any?) = execute(sql, args.toList())

    /**
     * Executes every `;`-separated statement in [sql], as GRDB's `execute`
     * does. Splits outside quotes; enough for the migration DDL it runs.
     */
    fun executeScript(sql: String) {
        for (statement in splitStatements(sql)) execute(statement)
    }

    fun fetchAll(sql: String, args: List<Any?> = emptyList()): List<Row> {
        prepare(sql, args).use { statement ->
            val count = statement.getColumnCount()
            val columns = HashMap<String, Int>(count * 2)
            // First occurrence wins, matching GRDB's name lookup.
            for (i in 0 until count) columns.putIfAbsent(statement.getColumnName(i), i)
            val rows = ArrayList<Row>()
            while (statement.step()) {
                val values = arrayOfNulls<Any?>(count)
                for (i in 0 until count) {
                    values[i] = when (statement.getColumnType(i)) {
                        SQLITE_DATA_INTEGER -> statement.getLong(i)
                        SQLITE_DATA_FLOAT -> statement.getDouble(i)
                        SQLITE_DATA_TEXT -> statement.getText(i)
                        SQLITE_DATA_BLOB -> statement.getBlob(i)
                        else -> null
                    }
                }
                rows += Row(columns, values)
            }
            return rows
        }
    }

    fun fetchAll(sql: String, vararg args: Any?): List<Row> = fetchAll(sql, args.toList())

    fun fetchOne(sql: String, args: List<Any?> = emptyList()): Row? = fetchAll(sql, args).firstOrNull()

    fun fetchOne(sql: String, vararg args: Any?): Row? = fetchOne(sql, args.toList())

    /** First column of every row, as strings (null rows dropped). */
    fun strings(sql: String, args: List<Any?> = emptyList()): List<String> = fetchAll(sql, args).mapNotNull { it.string(0) }

    fun strings(sql: String, vararg args: Any?): List<String> = strings(sql, args.toList())

    fun longs(sql: String, args: List<Any?> = emptyList()): List<Long> = fetchAll(sql, args).mapNotNull { it.long(0) }

    fun longs(sql: String, vararg args: Any?): List<Long> = longs(sql, args.toList())

    /** First column of the first row. */
    fun value(sql: String, args: List<Any?> = emptyList()): Any? = fetchOne(sql, args)?.get(0)

    fun string(sql: String, vararg args: Any?): String? = fetchOne(sql, args.toList())?.string(0)

    fun long(sql: String, vararg args: Any?): Long? = fetchOne(sql, args.toList())?.long(0)

    fun double(sql: String, vararg args: Any?): Double? = fetchOne(sql, args.toList())?.double(0)

    fun tableExists(name: String): Boolean =
        fetchOne("SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?", name) != null

    /** Column names of [table], in declaration order. */
    fun columns(table: String): List<String> =
        fetchAll("PRAGMA table_info(${quoteIdentifier(table)})").mapNotNull { it.string("name") }

    /** Rows changed by the most recent INSERT/UPDATE/DELETE. */
    val changes: Int get() = long("SELECT changes()")?.toInt() ?: 0

    val lastInsertedRowId: Long get() = long("SELECT last_insert_rowid()") ?: 0

    val inTransaction: Boolean get() = connection.inTransaction()

    companion object {
        fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

        fun splitStatements(sql: String): List<String> {
            val statements = mutableListOf<String>()
            val current = StringBuilder()
            var quote: Char? = null
            for (c in sql) {
                when {
                    quote != null -> {
                        current.append(c)
                        if (c == quote) quote = null
                    }
                    c == '\'' || c == '"' || c == '`' -> {
                        quote = c
                        current.append(c)
                    }
                    c == ';' -> {
                        current.toString().trim().takeIf { it.isNotEmpty() }?.let { statements += it }
                        current.clear()
                    }
                    else -> current.append(c)
                }
            }
            current.toString().trim().takeIf { it.isNotEmpty() }?.let { statements += it }
            return statements
        }

        /** `?, ?, ?` for an IN list. */
        fun placeholders(count: Int): String = List(count) { "?" }.joinToString(", ")
    }
}

/**
 * A single serialized SQLite connection, like GRDB's `DatabaseQueue`: every
 * access takes the same lock, `write` wraps its block in a transaction (a
 * savepoint when nested), and the async variants run the same blocks on the
 * IO dispatcher so callers on the main thread never block on disk.
 */
class DatabaseQueue(val path: String) : AutoCloseable {
    private val lock = ReentrantLock()
    private val connection: SQLiteConnection = BundledSQLiteDriver().open(path)
    private val db = Db(connection)
    private var savepointDepth = 0
    private var closed = false

    init {
        // Concurrent opens of the same file are expected (a background refresh
        // racing the app's own load). Wait for the lock instead of failing
        // with SQLITE_BUSY — contention here is milliseconds.
        lock.withLock {
            db.fetchAll("PRAGMA busy_timeout = 5000")
        }
    }

    fun <T> read(block: (Db) -> T): T = lock.withLock {
        check(!closed) { "database is closed" }
        block(db)
    }

    fun <T> write(block: (Db) -> T): T = lock.withLock {
        check(!closed) { "database is closed" }
        if (db.inTransaction) {
            val name = "sp${++savepointDepth}"
            db.execute("SAVEPOINT $name")
            try {
                val result = block(db)
                db.execute("RELEASE $name")
                result
            } catch (e: Throwable) {
                runCatching {
                    db.execute("ROLLBACK TO $name")
                    db.execute("RELEASE $name")
                }
                throw e
            } finally {
                savepointDepth--
            }
        } else {
            db.execute("BEGIN IMMEDIATE")
            try {
                val result = block(db)
                db.execute("COMMIT")
                result
            } catch (e: Throwable) {
                runCatching { db.execute("ROLLBACK") }
                throw e
            }
        }
    }

    /** For statements SQLite refuses inside a transaction (`VACUUM INTO`). */
    fun <T> writeWithoutTransaction(block: (Db) -> T): T = read(block)

    suspend fun <T> readAsync(block: (Db) -> T): T = withContext(Dispatchers.IO) { read(block) }

    suspend fun <T> writeAsync(block: (Db) -> T): T = withContext(Dispatchers.IO) { write(block) }

    override fun close() = lock.withLock {
        if (!closed) {
            closed = true
            connection.close()
        }
    }
}
