package rocks.pkb.plektra

import rocks.pkb.plektra.ResetPlan.ChangedState
import rocks.pkb.plektra.ResetPlan.Item
import rocks.pkb.plektra.ResetPlan.StepResult
import rocks.pkb.plektra.toolbox.PackageState
import rocks.pkb.plektra.toolbox.RemovalJournal
import rocks.pkb.plektra.toolbox.SystemTweaks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of "Reset to stock": what counts as changed, in what order, and how results add up. */
class ResetPlanTest {

    private fun record(pkg: String) =
        RemovalJournal.Record(pkg, PackageState.ACTIVE, RemovalJournal.Action.DISABLED, 1L)

    @Test
    fun `nothing changed means an empty plan`() {
        assertTrue(ResetPlan.build(ChangedState()).isEmpty())
        assertFalse(ResetPlan.needsBroker(ResetPlan.build(ChangedState())))
    }

    @Test
    fun `an entry appears only for a change that is in effect`() {
        val plan = ResetPlan.build(ChangedState(densityChanged = true, sideKeyBound = true))
        assertEquals(listOf(Item.DISPLAY_DENSITY, Item.SIDE_KEY), plan.map { it.item })
    }

    @Test
    fun `everything changed runs in the documented order`() {
        val plan = ResetPlan.build(
            ChangedState(
                fnCtrlMapped = true,
                densityChanged = true,
                tweaksChanged = true,
                packagesToRestore = listOf("com.a.app"),
                ringEnabled = true,
                ringAccessGranted = true,
                timeoutApplied = true,
                smartBacklightEnabled = true,
                ringHoldsKeyboardLight = true,
                sideKeyBound = true
            )
        )
        assertEquals(
            listOf(
                Item.FN_CTRL, Item.DISPLAY_DENSITY, Item.SYSTEM_TWEAKS, Item.REMOVED_APPS,
                Item.RING_OFF, Item.RING_GRANTS, Item.KEYBOARD_TIMEOUT, Item.KEYBOARD_LIGHT_HELD, Item.SIDE_KEY
            ),
            plan.map { it.item }
        )
    }

    @Test
    fun `the apps entry lists each package once, sorted`() {
        val plan = ResetPlan.build(ChangedState(packagesToRestore = listOf("com.b.app", "com.a.app", "com.b.app")))
        assertEquals(listOf("com.a.app", "com.b.app"), plan.single().packages)
    }

    @Test
    fun `broker flags follow what each step really needs`() {
        fun needs(state: ChangedState, item: Item) = ResetPlan.build(state).single { it.item == item }.needsBroker

        assertTrue(needs(ChangedState(densityChanged = true), Item.DISPLAY_DENSITY))
        assertTrue(needs(ChangedState(tweaksChanged = true), Item.SYSTEM_TWEAKS))
        assertTrue(needs(ChangedState(packagesToRestore = listOf("com.a.app")), Item.REMOVED_APPS))
        assertTrue(needs(ChangedState(ringAccessGranted = true), Item.RING_GRANTS))
        // Settings-only steps never need the broker.
        assertFalse(needs(ChangedState(fnCtrlMapped = true), Item.FN_CTRL))
        assertFalse(needs(ChangedState(ringEnabled = true), Item.RING_OFF))
        assertFalse(needs(ChangedState(ringHoldsKeyboardLight = true), Item.KEYBOARD_LIGHT_HELD))
        assertFalse(needs(ChangedState(sideKeyBound = true), Item.SIDE_KEY))
    }

    @Test
    fun `the keyboard timeout needs the broker only when it was really written`() {
        val written = ResetPlan.build(ChangedState(timeoutApplied = true)).single()
        assertEquals(Item.KEYBOARD_TIMEOUT, written.item)
        assertTrue(written.needsBroker)

        // Only the user's switch is on (the write never landed): flipping it back needs nothing.
        val switchOnly = ResetPlan.build(ChangedState(smartBacklightEnabled = true)).single()
        assertEquals(Item.KEYBOARD_TIMEOUT, switchOnly.item)
        assertFalse(switchOnly.needsBroker)
    }

    @Test
    fun `an active package is a stale journal record, anything else is still to restore`() {
        val journal = ResetPlan.partitionJournal(
            listOf(record("com.gone.app"), record("com.back.app"), record("com.unknown.app"), record("com.off.app"))
        ) { pkg ->
            when (pkg) {
                "com.gone.app" -> PackageState.UNINSTALLED
                "com.back.app" -> PackageState.ACTIVE
                "com.off.app" -> PackageState.DISABLED
                else -> null // not visible to this app: all we have is the journal
            }
        }
        assertEquals(listOf("com.gone.app", "com.unknown.app", "com.off.app"), journal.restore.map { it.packageName })
        assertEquals(listOf("com.back.app"), journal.stale.map { it.packageName })
    }

    @Test
    fun `an empty journal splits into nothing`() {
        val journal = ResetPlan.partitionJournal(emptyList()) { PackageState.ACTIVE }
        assertTrue(journal.restore.isEmpty())
        assertTrue(journal.stale.isEmpty())
    }

    @Test
    fun `the apps result is done only when every package came back`() {
        assertEquals(StepResult.Status.NOTHING_TO_DO, ResetPlan.appsResult(0, 0, null).status)

        val all = ResetPlan.appsResult(3, 3, null)
        assertEquals(StepResult.Status.DONE, all.status)
        assertEquals(3, all.count)
        assertEquals(3, all.total)

        val some = ResetPlan.appsResult(2, 3, "wireless_debugging_off")
        assertEquals(StepResult.Status.FAILED, some.status)
        assertEquals("wireless_debugging_off", some.reason)
        assertEquals(2, some.count)
        assertEquals(3, some.total)
    }

    @Test
    fun `a reset succeeded unless a step failed`() {
        val ok = ResetPlan.Result(
            listOf(
                ResetPlan.Entry(Item.FN_CTRL) to StepResult.DONE,
                ResetPlan.Entry(Item.SIDE_KEY) to StepResult.NOTHING_TO_DO
            )
        )
        assertTrue(ok.allSucceeded)
        assertTrue(ok.failed.isEmpty())

        val bad = ResetPlan.Result(
            listOf(
                ResetPlan.Entry(Item.FN_CTRL) to StepResult.DONE,
                ResetPlan.Entry(Item.DISPLAY_DENSITY) to StepResult.failed("not_paired")
            )
        )
        assertFalse(bad.allSucceeded)
        assertEquals(listOf(Item.DISPLAY_DENSITY), bad.failed.map { it.first.item })
        assertEquals("not_paired", bad.resultFor(Item.DISPLAY_DENSITY)?.reason)
        assertNull(bad.resultFor(Item.SIDE_KEY))
        assertTrue(ResetPlan.Result(emptyList()).allSucceeded)
    }

    // ---- what counts as a system tweak still in effect -------------------------------------------

    @Test
    fun `tweaks look changed from the marker, a scale off 1, or a toggle that reads on`() {
        val stock = listOf<Float?>(1f, 1f, 1f)
        assertFalse(SystemTweaks.looksChanged(stock, listOf("null", null), touchedByApp = false))
        assertTrue(SystemTweaks.looksChanged(stock, listOf(null, null), touchedByApp = true))
        assertTrue(SystemTweaks.looksChanged(listOf(1f, 0.5f, 1f), listOf(null, null), touchedByApp = false))
        assertTrue(SystemTweaks.looksChanged(listOf(0f, 0f, 0f), listOf(null, null), touchedByApp = false))
        assertTrue(SystemTweaks.looksChanged(stock, listOf("1", null), touchedByApp = false))
    }

    @Test
    fun `unreadable tweak settings decide nothing`() {
        assertFalse(SystemTweaks.looksChanged(listOf(null, null, null), listOf(null, null), touchedByApp = false))
        // A scale within rounding of 1.0 is stock.
        assertFalse(SystemTweaks.looksChanged(listOf(1.004f, 1f, 1f), listOf("0", null), touchedByApp = false))
    }
}
