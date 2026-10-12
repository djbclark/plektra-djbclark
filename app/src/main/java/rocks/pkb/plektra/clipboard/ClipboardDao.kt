package rocks.pkb.plektra.clipboard

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Data Access Object for clipboard history.
 *
 * The in-memory [cache] is the read model and is updated synchronously on the calling
 * thread (guarded by its own lock); every SQLite touch, including the initial load, runs
 * on [dbExecutor] so the clipboard listener never blocks the IME main thread.
 */
class ClipboardDao private constructor(private val db: ClipboardDatabase) {

    interface Listener {
        fun onClipInserted(position: Int)
        fun onClipsRemoved(position: Int, count: Int)
        fun onClipMoved(oldPosition: Int, newPosition: Int)
        /** Called off the main thread once the stored history has been read in. */
        fun onHistoryLoaded() {}
    }

    var listener: Listener? = null

    // Track when we last cleaned old clips
    private var lastClearOldClips = 0L
    private var nextExpiry = 0L
    private var lastRetention: Pair<Long, Long>? = null

    private val cache = mutableListOf<ClipboardHistoryEntry>()

    init {
        dbExecutor.execute { loadFromDb() }
    }

    private fun loadFromDb() {
        val loaded = mutableListOf<ClipboardHistoryEntry>()
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
                loaded.add(
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
        // insert behind us, so the row from the previous session would be a duplicate.
        val superseded = mutableListOf<Long>()
        synchronized(cache) {
            for (entry in loaded) {
                if (cache.any { it.text == entry.text }) superseded.add(entry.id) else cache.add(entry)
            }
            cache.sort()
        }
        superseded.forEach { db.writableDatabase.delete(TABLE, "$COLUMN_ID = $it", null) }
        listener?.onHistoryLoaded()
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

        nextExpiry = 0L
        val existing = synchronized(cache) { cache.firstOrNull { it.text == text } }
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

            val entryId = existing.id
            val cvSnapshot = ContentValues().apply {
                put(COLUMN_TIMESTAMP, existing.timeStamp)
                put(COLUMN_SENSITIVE, existing.isSensitive)
                put(COLUMN_PINNED, existing.isPinned)
            }
            dbExecutor.execute {
                if (entryId == PENDING_ID) return@execute
                db.writableDatabase.update(TABLE, cvSnapshot, "$COLUMN_ID = ?", arrayOf(entryId.toString()))
            }
            return
        }

        insertNewEntry(timestamp, pinned, text, sensitive)
    }

    private fun insertNewEntry(timestamp: Long, pinned: Boolean, text: String, sensitive: Boolean) {
        val effectivePinned = pinned && !sensitive
        val entry = ClipboardHistoryEntry(PENDING_ID, timestamp, effectivePinned, text, sensitive)
        val position = synchronized(cache) {
            cache.add(entry)
            cache.sort()
            cache.indexOfFirst { it === entry }
        }
        listener?.onClipInserted(position)

        dbExecutor.execute {
            val cv = ContentValues().apply {
                put(COLUMN_TIMESTAMP, timestamp)
                put(COLUMN_PINNED, effectivePinned)
                put(COLUMN_TEXT, text)
                put(COLUMN_SENSITIVE, sensitive)
            }
            entry.id = db.writableDatabase.insert(TABLE, null, cv)
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

        val pinned = entry.isPinned
        val timestamp = entry.timeStamp
        val entryId = entry.id
        dbExecutor.execute {
            if (entryId == PENDING_ID) return@execute
            val cv = ContentValues(2).apply {
                put(COLUMN_PINNED, pinned)
                put(COLUMN_TIMESTAMP, timestamp)
            }
            db.writableDatabase.update(TABLE, cv, "$COLUMN_ID = $entryId", null)
        }
    }

    fun deleteClipAt(index: Int) {
        val entry = synchronized(cache) {
            cache.getOrNull(index)?.also { cache.removeAt(index) }
        } ?: return
        val entryId = entry.id
        dbExecutor.execute {
            if (entryId == PENDING_ID) return@execute
            db.writableDatabase.delete(TABLE, "$COLUMN_ID = $entryId", null)
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
        if (!now && lastClearOldClips > SystemClock.elapsedRealtime() - 5_000) return
        lastClearOldClips = SystemClock.elapsedRealtime()
        val currentTime = System.currentTimeMillis()
        val retention = retentionMinutes to sensitiveRetentionMinutes
        if (lastRetention == retention && currentTime < nextExpiry) return
        lastRetention = retention
        val normalCutoff = if (retentionMinutes > 0) currentTime - retentionMinutes * 60_000L else Long.MIN_VALUE
        val sensitiveCutoff = currentTime - sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L

        val removed: List<Pair<Int, Long>>
        synchronized(cache) {
            val expired = cache.indices.filter { index ->
                val clip = cache[index]
                if (clip.isSensitive) clip.timeStamp <= sensitiveCutoff
                else !clip.isPinned && clip.timeStamp <= normalCutoff
            }
            if (expired.isEmpty()) {
                nextExpiry = cache.minOfOrNull { clip ->
                    when {
                        clip.isSensitive -> clip.timeStamp + sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L
                        !clip.isPinned && retentionMinutes > 0 -> clip.timeStamp + retentionMinutes * 60_000L
                        else -> Long.MAX_VALUE
                    }
                } ?: Long.MAX_VALUE
                return
            }
            removed = expired.asReversed().map { index ->
                index to cache.removeAt(index).id
            }
            nextExpiry = cache.minOfOrNull { clip ->
                when {
                    clip.isSensitive -> clip.timeStamp + sensitiveRetentionMinutes.coerceIn(1, 120) * 60_000L
                    !clip.isPinned && retentionMinutes > 0 -> clip.timeStamp + retentionMinutes * 60_000L
                    else -> Long.MAX_VALUE
                }
            } ?: Long.MAX_VALUE
        }
        removed.forEach { (index, _) -> listener?.onClipsRemoved(index, 1) }
        dbExecutor.execute {
            removed.forEach { (_, id) ->
                if (id != PENDING_ID) {
                    db.writableDatabase.delete(TABLE, "$COLUMN_ID = ?", arrayOf(id.toString()))
                }
            }
        }
    }

    fun clearNonPinned() {
        val firstRemoved: Int
        val removedCount: Int
        synchronized(cache) {
            firstRemoved = cache.indexOfFirst { !it.isPinned }
            if (firstRemoved < 0) return
            removedCount = cache.count { !it.isPinned }
            cache.removeAll { !it.isPinned }
        }
        listener?.onClipsRemoved(firstRemoved, removedCount)
        dbExecutor.execute {
            db.writableDatabase.delete(TABLE, "$COLUMN_PINNED = 0", null)
        }
    }

    fun clear() {
        val count = synchronized(cache) {
            cache.size.also { cache.clear() }
        }
        if (count == 0) return
        listener?.onClipsRemoved(0, count)
        dbExecutor.execute {
            db.writableDatabase.delete(TABLE, null, null)
        }
    }

    companion object {
        private const val TAG = "ClipboardDao"
        const val PENDING_ID = -1L

        private val dbWorker = Executors.newSingleThreadExecutor()

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
