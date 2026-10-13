package rocks.pkb.plektra.clipboard

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Data Access Object for clipboard history.
 *
 * The in-memory [cache] is the read model and is updated synchronously on the calling
 * thread (guarded by its own lock); every SQLite touch, including the initial load, runs
 * on [dbExecutor] so the clipboard listener never blocks the IME main thread.
 */
class ClipboardDao internal constructor(private val db: ClipboardDatabase) {

    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
        /** Called on the main thread once the stored history has been merged into the cache. */
        fun onHistoryLoaded() {}
    }

    @Volatile
    var listener: Listener? = null

    // All of the following are guarded by the cache lock: clearOldClips can run on the main
    // thread and the database thread.
    private var lastClearOldClips = 0L
    private var nextExpiry = 0L
    private var lastRetention: Pair<Long, Long>? = null

    private val cache = mutableListOf<ClipboardHistoryEntry>()

    // Whether the stored history has been merged into the cache, and what a clear that ran
    // before that point still owes the rows the load brings in.
    private enum class ClearedBeforeLoad { NONE, NON_PINNED, ALL }
    private var loaded = false
    private var clearedBeforeLoad = ClearedBeforeLoad.NONE

    init {
        dbExecutor.execute { loadFromDb() }
    }

    private fun loadFromDb() {
        val stored = mutableListOf<ClipboardHistoryEntry>()
        db.readableDatabase.query(
            TABLE,
            arrayOf(COLUMN_ID, COLUMN_TIMESTAMP, COLUMN_PINNED, COLUMN_TEXT, COLUMN_SENSITIVE),
            null,
            null,
            null,
            null,
            "$COLUMN_PINNED DESC, $COLUMN_TIMESTAMP DESC"
        ).use {
            while (it.moveToNext()) {
                stored.add(
                    ClipboardHistoryEntry(
                        it.getLong(0),
                        it.getLong(1),
                        it.getInt(2) != 0,
                        it.getString(3),
                        it.getInt(4) != 0
                    )
                )
            }
        }
        // A clip copied before the load finished is already in the cache and queued for
        // insert behind us, so the row from the previous session would be a duplicate. The
        // cached entry keeps the stronger of both flags (a stored secret must stay sensitive
        // however the same text was copied since) and its queued insert writes that state.
        val superseded = mutableListOf<Long>()
        synchronized(cache) {
            for (entry in stored) {
                // Rows a clear issued before the load already removed on disk (its delete is
                // queued behind this task) must not reappear in the cache.
                when (clearedBeforeLoad) {
                    ClearedBeforeLoad.ALL -> continue
                    ClearedBeforeLoad.NON_PINNED -> if (!entry.isPinned) continue
                    ClearedBeforeLoad.NONE -> {}
                }
                val current = cache.firstOrNull { it.text == entry.text }
                if (current == null) {
                    cache.add(entry)
                } else {
                    current.isSensitive = current.isSensitive || entry.isSensitive
                    current.isPinned = (current.isPinned || entry.isPinned) && !current.isSensitive
                    current.timeStamp = maxOf(current.timeStamp, entry.timeStamp)
                    superseded.add(entry.id)
                }
            }
            cache.sort()
            loaded = true
            // Expiry bookkeeping computed while the cache was incomplete is void.
            nextExpiry = 0L
            lastRetention = null
            lastClearOldClips = 0L
        }
        superseded.forEach { db.writableDatabase.delete(TABLE, "$COLUMN_ID = $it", null) }
        mainHandler.post { listener?.onHistoryLoaded() }
    }

    /**
     * Add a new clipboard entry or update existing one with same text.
     * @param retentionMinutes Normal retention in minutes; zero keeps entries until deleted.
     * @param sensitiveRetentionMinutes Sensitive entries expire independently and cannot be pinned.
     */
    fun addClip(
        timestamp: Long,
        pinned: Boolean,
        text: String,
        retentionMinutes: Long = 0,
        sensitive: Boolean = false,
        sensitiveRetentionMinutes: Long = 30
    ) {
        clearOldClips(
            now = true,
            retentionMinutes = retentionMinutes,
            sensitiveRetentionMinutes = sensitiveRetentionMinutes
        )
        if (sensitive && timestamp <= System.currentTimeMillis() - sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L) {
            return
        }

        val existing = synchronized(cache) {
            nextExpiry = 0L
            cache.firstOrNull { it.text == text }
        }
        if (existing != null) {
            val (oldPos, newPos) = synchronized(cache) {
                val oldPos = cache.indexOfFirst { it === existing }
                existing.timeStamp = timestamp
                existing.isSensitive = existing.isSensitive || sensitive
                if (existing.isSensitive) existing.isPinned = false
                cache.sort()
                oldPos to cache.indexOfFirst { it === existing }
            }
            listener?.onClipMoved(oldPos, newPos)
            persistState(existing)
            return
        }

        insertNewEntry(timestamp, pinned, text, sensitive)
    }

    private fun insertNewEntry(timestamp: Long, pinned: Boolean, text: String, sensitive: Boolean) {
        val effectivePinned = pinned && !sensitive
        val entry = ClipboardHistoryEntry(nextPendingId.getAndDecrement(), timestamp, effectivePinned, text, sensitive)
        val position = synchronized(cache) {
            cache.add(entry)
            cache.sort()
            cache.indexOfFirst { it === entry }
        }
        listener?.onClipInserted(position)

        dbExecutor.execute {
            // The load may have merged stronger flags from a stored duplicate into the entry
            // since it was queued, so write its current state.
            val cv = synchronized(cache) {
                ContentValues().apply {
                    put(COLUMN_TIMESTAMP, entry.timeStamp)
                    put(COLUMN_PINNED, entry.isPinned)
                    put(COLUMN_TEXT, entry.text)
                    put(COLUMN_SENSITIVE, entry.isSensitive)
                }
            }
            entry.rowId = db.writableDatabase.insert(TABLE, null, cv)
        }
    }

    fun isPinned(index: Int) = synchronized(cache) { cache.getOrNull(index)?.isPinned ?: false }

    fun getAt(index: Int) = synchronized(cache) { cache.getOrNull(index) }

    fun get(id: Long) = synchronized(cache) { cache.firstOrNull { it.id == id } }

    fun count() = synchronized(cache) { cache.size }

    fun sort() = synchronized(cache) { cache.sort() }

    fun togglePinned(id: Long) {
        val entry: ClipboardHistoryEntry
        val oldPos: Int
        val newPos: Int
        synchronized(cache) {
            entry = cache.firstOrNull { it.id == id } ?: return
            if (entry.isSensitive) return
            nextExpiry = 0L
            entry.isPinned = !entry.isPinned
            entry.timeStamp = System.currentTimeMillis()
            oldPos = cache.indexOfFirst { it === entry }
            cache.sort()
            newPos = cache.indexOfFirst { it === entry }
        }
        listener?.onClipMoved(oldPos, newPos)
        persistState(entry)
    }

    /** Queues a write of the entry's state as it is when the task runs, not when it was queued. */
    private fun persistState(entry: ClipboardHistoryEntry) {
        dbExecutor.execute {
            val rowId = entry.rowId
            if (rowId == PENDING_ID) return@execute
            val cv = synchronized(cache) {
                ContentValues(3).apply {
                    put(COLUMN_TIMESTAMP, entry.timeStamp)
                    put(COLUMN_SENSITIVE, entry.isSensitive)
                    put(COLUMN_PINNED, entry.isPinned)
                }
            }
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = ?", arrayOf(rowId.toString()))
        }
    }

    /** Deletes by entry id, which stays valid when a background merge shifts positions. */
    fun deleteClip(id: Long) {
        val removedIndex: Int
        val entry = synchronized(cache) {
            removedIndex = cache.indexOfFirst { it.id == id }
            if (removedIndex < 0) return
            cache.removeAt(removedIndex)
        }
        listener?.onClipsRemoved(removedIndex, 1)
        dbExecutor.execute {
            val rowId = entry.rowId
            if (rowId == PENDING_ID) return@execute
            db.writableDatabase.delete(TABLE, "$COLUMN_ID = $rowId", null)
        }
    }

    /**
     * Remove old clipboard entries based on retention time setting.
     * @param now If true, force clear immediately; otherwise respect debounce
     * @param retentionMinutes Normal retention in minutes; zero keeps normal entries indefinitely.
     * @param sensitiveRetentionMinutes Sensitive retention, clamped to 1–120 minutes.
     */
    fun clearOldClips(
        now: Boolean = false,
        retentionMinutes: Long = 0,
        sensitiveRetentionMinutes: Long = 30
    ) {
        val removed: List<Pair<Int, ClipboardHistoryEntry>>
        synchronized(cache) {
            if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5_000) return
            lastClearOldClips = SystemClock.elapsedRealtime()
            val currentTime = System.currentTimeMillis()
            val retention = retentionMinutes to sensitiveRetentionMinutes
            if (lastRetention == retention && currentTime < nextExpiry) return
            lastRetention = retention
            val normalCutoff = if (retentionMinutes > 0) currentTime - retentionMinutes * 60_000L else Long.MIN_VALUE
            val sensitiveCutoff = currentTime - sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L

            val expired = cache.indices.filter { index ->
                val clip = cache[index]
                if (clip.isSensitive) clip.timeStamp <= sensitiveCutoff
                else !clip.isPinned && clip.timeStamp <= normalCutoff
            }
            removed = expired.asReversed().map { index ->
                index to cache.removeAt(index)
            }
            // Until the stored history is merged in, the cache is incomplete and says nothing
            // about when the next entry expires; the load resets this.
            nextExpiry = if (!loaded) 0L else cache.minOfOrNull { clip ->
                when {
                    clip.isSensitive -> clip.timeStamp + sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L
                    !clip.isPinned && retentionMinutes > 0 -> clip.timeStamp + retentionMinutes * 60_000L
                    else -> Long.MAX_VALUE
                }
            } ?: Long.MAX_VALUE
        }
        if (removed.isEmpty()) return
        removed.forEach { (index, _) -> listener?.onClipsRemoved(index, 1) }
        dbExecutor.execute {
            removed.forEach { (_, entry) ->
                val rowId = entry.rowId
                if (rowId != PENDING_ID) {
                    db.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(rowId.toString()))
                }
            }
        }
    }

    fun clearNonPinned() {
        var firstRemoved = -1
        var removedCount = 0
        synchronized(cache) {
            if (!loaded && clearedBeforeLoad == ClearedBeforeLoad.NONE) {
                clearedBeforeLoad = ClearedBeforeLoad.NON_PINNED
            }
            firstRemoved = cache.indexOfFirst { !it.isPinned }
            if (firstRemoved >= 0) {
                removedCount = cache.count { !it.isPinned }
                cache.removeAll { !it.isPinned }
            }
        }
        if (firstRemoved >= 0) listener?.onClipsRemoved(firstRemoved, removedCount)
        // Unconditional: rows of the stored history may not be in the cache yet.
        dbExecutor.execute {
            db.writableDatabase.delete(TABLE, "$COLUMN_PINNED = 0", null)
        }
    }

    fun clear() {
        val count = synchronized(cache) {
            if (!loaded) clearedBeforeLoad = ClearedBeforeLoad.ALL
            cache.size.also { cache.clear() }
        }
        if (count > 0) listener?.onClipsRemoved(0, count)
        // Unconditional: rows of the stored history may not be in the cache yet.
        dbExecutor.execute {
            db.writableDatabase.delete(TABLE, null, null)
        }
    }

    companion object {
        private const val TAG = "ClipboardDao"
        const val PENDING_ID = -1L

        // Handles for entries not yet stored. Each is unique, so a lookup by id can never
        // reach a different pending entry (paste or pin the wrong, possibly sensitive, clip).
        private val nextPendingId = AtomicLong(-2L)

        internal val dbWorker = Executors.newSingleThreadExecutor()

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        /** Blocks until every database task queued before this call has run. For tests. */
        internal fun awaitDatabaseIdle() {
            dbWorker.submit {}.get()
        }

        // An exception escaping the worker thread would kill the IME process, and the
        // database is first opened here rather than inside getInstance's try/catch.
        private val dbExecutor = Executor { task ->
            dbWorker.execute {
                try {
                    task.run()
                } catch (e: Exception) {
                    Log.e(TAG, "Clipboard database operation failed", e)
                }
            }
        }

        const val TABLE = "CLIPBOARD"
        private const val COLUMN_ID = "ID"
        private const val COLUMN_TIMESTAMP = "TIMESTAMP"
        private const val COLUMN_PINNED = "PINNED"
        private const val COLUMN_TEXT = "TEXT"
        private const val COLUMN_SENSITIVE = "SENSITIVE"

        const val CREATE_TABLE = """
            CREATE TABLE $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY,
                $COLUMN_TIMESTAMP INTEGER NOT NULL,
                $COLUMN_PINNED TINYINT NOT NULL,
                $COLUMN_TEXT TEXT,
                $COLUMN_SENSITIVE TINYINT NOT NULL DEFAULT 0
            )
        """

        @Volatile
        private var instance: ClipboardDao? = null

        /**
         * Get the singleton instance, or create it if needed.
         * Returns null if instance can't be created (e.g. device locked).
         */
        fun getInstance(context: Context): ClipboardDao? {
            return instance ?: synchronized(this) {
                instance ?: try {
                    ClipboardDao(ClipboardDatabase.getInstance(context)).also {
                        instance = it
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to create ClipboardDao", e)
                    null
                }
            }
        }
    }
}
