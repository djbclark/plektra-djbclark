package rocks.pkb.plektra.clipboard

import android.content.ContentValues
import android.content.Context
import android.os.Looper
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Exercises the background load of stored history. Each test parks the database worker
 * on a latch before creating the DAO, so everything the test does before [release] happens
 * while the load has not run yet, which is the window the IME hits on startup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardDaoLoadTest {
    private lateinit var context: Context
    private lateinit var database: ClipboardDatabase
    private val gate = CountDownLatch(1)
    private val dao get() = requireNotNull(createdDao)
    private var createdDao: ClipboardDao? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = ClipboardDatabase.getInstance(context)
        ClipboardDao.awaitDatabaseIdle()
        database.writableDatabase.delete(ClipboardDao.TABLE, null, null)
    }

    @After
    fun tearDown() {
        gate.countDown()
        ClipboardDao.awaitDatabaseIdle()
        database.writableDatabase.delete(ClipboardDao.TABLE, null, null)
    }

    private fun store(text: String, timestamp: Long, pinned: Boolean = false, sensitive: Boolean = false) {
        database.writableDatabase.insert(ClipboardDao.TABLE, null, ContentValues().apply {
            put("TIMESTAMP", timestamp)
            put("PINNED", pinned)
            put("TEXT", text)
            put("SENSITIVE", sensitive)
        })
    }

    /** Creates the DAO with its load queued behind the gate. */
    private fun createDaoBeforeLoad() {
        ClipboardDao.dbWorker.execute { gate.await() }
        createdDao = ClipboardDao(database)
    }

    private fun release() {
        gate.countDown()
        ClipboardDao.awaitDatabaseIdle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private data class Row(val text: String, val pinned: Boolean, val sensitive: Boolean)

    private fun rows(): List<Row> {
        ClipboardDao.awaitDatabaseIdle()
        return database.readableDatabase.query(
            ClipboardDao.TABLE, arrayOf("TEXT", "PINNED", "SENSITIVE"), null, null, null, null, "TEXT"
        ).use {
            buildList {
                while (it.moveToNext()) add(Row(it.getString(0), it.getInt(1) != 0, it.getInt(2) != 0))
            }
        }
    }

    private fun texts() = (0 until dao.count()).mapNotNull(dao::getAt).map { it.text }

    @Test
    fun copyBeforeLoadKeepsStoredSensitivityInCacheAndOnDisk() {
        val now = System.currentTimeMillis()
        store("stored-secret", now - 60_000L, sensitive = true)
        createDaoBeforeLoad()

        dao.addClip(now, false, "stored-secret")
        release()

        val entry = requireNotNull(dao.getAt(0))
        assertEquals(1, dao.count())
        assertTrue(entry.isSensitive)
        assertFalse(entry.isPinned)
        assertEquals(listOf(Row("stored-secret", pinned = false, sensitive = true)), rows())
    }

    @Test
    fun copyBeforeLoadKeepsStoredPinOnDisk() {
        val now = System.currentTimeMillis()
        store("stored-pinned", now - 60_000L, pinned = true)
        createDaoBeforeLoad()

        dao.addClip(now, false, "stored-pinned")
        release()

        assertTrue(requireNotNull(dao.getAt(0)).isPinned)
        assertEquals(listOf(Row("stored-pinned", pinned = true, sensitive = false)), rows())
    }

    @Test
    fun expirySweepBeforeLoadDoesNotLetStoredSecretsOutliveRetention() {
        val now = System.currentTimeMillis()
        store("expired-secret", now - 40 * 60_000L, sensitive = true)
        store("fresh-secret", now - 60_000L, sensitive = true)
        createDaoBeforeLoad()

        // Runs against the still-empty cache and used to record "nothing expires".
        dao.clearOldClips(now = true, retentionMinutes = 0, sensitiveRetentionMinutes = 30)
        release()
        assertEquals(2, dao.count())

        // This is what the service does once onHistoryLoaded arrives (forced here: Robolectric's
        // elapsedRealtime starts near zero, inside the 5 s debounce).
        dao.clearOldClips(now = true, retentionMinutes = 0, sensitiveRetentionMinutes = 30)

        assertEquals(listOf("fresh-secret"), texts())
        assertEquals(listOf(Row("fresh-secret", pinned = false, sensitive = true)), rows())
    }

    @Test
    fun historyLoadedIsDeliveredOnTheMainThread() {
        store("stored", System.currentTimeMillis())
        createDaoBeforeLoad()
        val deliveredOn = mutableListOf<Thread>()
        dao.listener = object : ClipboardDao.Listener {
            override fun onClipInserted(position: Int) {}
            override fun onClipsRemoved(position: Int, count: Int) {}
            override fun onClipMoved(oldPosition: Int, newPosition: Int) {}
            override fun onHistoryLoaded() { deliveredOn += Thread.currentThread() }
        }
        gate.countDown()
        ClipboardDao.awaitDatabaseIdle()
        assertTrue(deliveredOn.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Looper.getMainLooper().thread), deliveredOn)
    }

    @Test
    fun clearAllBeforeLoadAlsoClearsStoredHistory() {
        val now = System.currentTimeMillis()
        store("stored-normal", now - 1_000L)
        store("stored-pinned", now - 2_000L, pinned = true)
        createDaoBeforeLoad()

        dao.clear()
        release()

        assertEquals(0, dao.count())
        assertEquals(emptyList<Row>(), rows())
    }

    @Test
    fun clearNonPinnedBeforeLoadAlsoClearsStoredNormalRows() {
        val now = System.currentTimeMillis()
        store("stored-normal", now - 1_000L)
        store("stored-secret", now - 1_500L, sensitive = true)
        store("stored-pinned", now - 2_000L, pinned = true)
        createDaoBeforeLoad()

        dao.clearNonPinned()
        release()

        assertEquals(listOf("stored-pinned"), texts())
        assertEquals(listOf(Row("stored-pinned", pinned = true, sensitive = false)), rows())
    }

    @Test
    fun clearBeforeLoadDoesNotDropClipsCopiedAfterIt() {
        store("stored", System.currentTimeMillis() - 1_000L)
        createDaoBeforeLoad()

        dao.clear()
        dao.addClip(System.currentTimeMillis(), false, "after-clear")
        release()

        assertEquals(listOf("after-clear"), texts())
        assertEquals(listOf(Row("after-clear", pinned = false, sensitive = false)), rows())
    }

    @Test
    fun deleteByIdRemovesTheIntendedClipAfterTheLoadShiftsPositions() {
        val now = System.currentTimeMillis()
        store("stored-newer", now + 10_000L)
        createDaoBeforeLoad()
        dao.addClip(now, false, "copied")
        val target = requireNotNull(dao.getAt(0))
        assertEquals("copied", target.text)

        release()
        // The merge put the stored clip in front, so position 0 now holds a different clip.
        assertEquals("stored-newer", requireNotNull(dao.getAt(0)).text)

        dao.deleteClip(target.id)

        assertEquals(listOf("stored-newer"), texts())
        assertEquals(listOf(Row("stored-newer", pinned = false, sensitive = false)), rows())
        assertNull(dao.get(target.id))
    }
}
