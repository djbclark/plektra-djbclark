package rocks.pkb.plektra

import rocks.pkb.plektra.toolbox.PackageState
import rocks.pkb.plektra.toolbox.RemovalJournal

/**
 * What "Reset device settings to stock" would undo, decided from facts about the phone right now.
 *
 * Pure logic with no Android types: [SystemChangeManager] gathers the facts into a [ChangedState]
 * (reading settings, preferences and the removal journal) and this turns them into the ordered
 * list the confirm dialog shows and the reset then runs. A change that is not in effect never
 * produces an entry, so the dialog lists exactly what will be undone and nothing else.
 */
object ResetPlan {

    /** One undoable change, in the order the reset runs them. */
    enum class Item {
        /** Fn -> Ctrl programmable-key remap (`Settings.System`). */
        FN_CTRL,

        /** A display density override set by the toolbox (`wm density reset`). */
        DISPLAY_DENSITY,

        /** Animation scale and the two hidden toggles the system tweaks page writes. */
        SYSTEM_TWEAKS,

        /** Apps the bloat remover disabled or uninstalled for the user, from its journal. */
        REMOVED_APPS,

        /** The notification ring's own on/off switch. */
        RING_OFF,

        /** The ring's notification access and full-screen intent grants. */
        RING_GRANTS,

        /** The always-on keyboard backlight timeout (back to the stock 30 s) and its switch. */
        KEYBOARD_TIMEOUT,

        /** The vendor keyboard-light switch, held dark on the ring's behalf. */
        KEYBOARD_LIGHT_HELD,

        /** The orange side key's long press, pointed at Pastiera's assistant trigger. */
        SIDE_KEY
    }

    /**
     * One line of the plan. [needsBroker] is true when undoing it needs the paired ADB broker (and
     * so Wireless debugging); [packages] is only set for [Item.REMOVED_APPS].
     */
    data class Entry(
        val item: Item,
        val needsBroker: Boolean = false,
        val packages: List<String> = emptyList()
    )

    /** The facts [build] needs, each true only while the change is in effect on the phone. */
    data class ChangedState(
        val fnCtrlMapped: Boolean = false,
        val densityChanged: Boolean = false,
        val tweaksChanged: Boolean = false,
        /** Journaled packages that are still removed or disabled (see [partitionJournal]). */
        val packagesToRestore: List<String> = emptyList(),
        val ringEnabled: Boolean = false,
        val ringAccessGranted: Boolean = false,
        /** The always-on timeout was written to the phone and not reverted since. */
        val timeoutApplied: Boolean = false,
        /** The user's own "smart backlight" switch is on (it would re-apply the timeout later). */
        val smartBacklightEnabled: Boolean = false,
        val ringHoldsKeyboardLight: Boolean = false,
        val sideKeyBound: Boolean = false
    )

    /** The ordered entries for [state]; empty when there is nothing to undo. */
    fun build(state: ChangedState): List<Entry> = buildList {
        if (state.fnCtrlMapped) add(Entry(Item.FN_CTRL))
        if (state.densityChanged) add(Entry(Item.DISPLAY_DENSITY, needsBroker = true))
        if (state.tweaksChanged) add(Entry(Item.SYSTEM_TWEAKS, needsBroker = true))
        if (state.packagesToRestore.isNotEmpty()) {
            add(Entry(Item.REMOVED_APPS, needsBroker = true, packages = state.packagesToRestore.distinct().sorted()))
        }
        if (state.ringEnabled) add(Entry(Item.RING_OFF))
        if (state.ringAccessGranted) add(Entry(Item.RING_GRANTS, needsBroker = true))
        // The switch alone needs no broker: only a timeout that was really written does.
        if (state.timeoutApplied || state.smartBacklightEnabled) {
            add(Entry(Item.KEYBOARD_TIMEOUT, needsBroker = state.timeoutApplied))
        }
        if (state.ringHoldsKeyboardLight) add(Entry(Item.KEYBOARD_LIGHT_HELD))
        if (state.sideKeyBound) add(Entry(Item.SIDE_KEY))
    }

    fun needsBroker(entries: List<Entry>): Boolean = entries.any { it.needsBroker }

    /**
     * Splits the removal journal by what the phone itself says about each package.
     *
     * [restore] holds the records still worth restoring: the package is not active, or the phone
     * cannot say ([localState] returns null for a package this app cannot see, and a journaled
     * package must still come back). [stale] holds records whose package is already active, for
     * instance because the user re-enabled it from system settings: nothing to restore, and the
     * journal entry is dropped. A record is never in both.
     */
    fun partitionJournal(
        records: List<RemovalJournal.Record>,
        localState: (String) -> PackageState?
    ): Journal {
        val restore = mutableListOf<RemovalJournal.Record>()
        val stale = mutableListOf<RemovalJournal.Record>()
        records.forEach { record ->
            if (localState(record.packageName) == PackageState.ACTIVE) stale += record else restore += record
        }
        return Journal(restore = restore, stale = stale)
    }

    data class Journal(
        val restore: List<RemovalJournal.Record>,
        val stale: List<RemovalJournal.Record>
    )

    /** How one step ended. [count] and [total] are set only for the apps step. */
    data class StepResult(
        val status: Status,
        /** A reason code (see [SystemChangeManager]) or a short message; null when none applies. */
        val reason: String? = null,
        val count: Int = 0,
        val total: Int = 0
    ) {
        enum class Status { DONE, FAILED, NOTHING_TO_DO }

        val isDone: Boolean get() = status == Status.DONE

        companion object {
            val DONE = StepResult(Status.DONE)
            val NOTHING_TO_DO = StepResult(Status.NOTHING_TO_DO)
            fun failed(reason: String? = null) = StepResult(Status.FAILED, reason)
        }
    }

    /** Per-item results of a finished reset, in the order the steps ran. */
    data class Result(val steps: List<Pair<Entry, StepResult>>) {
        /** True when no step failed (an empty reset, or only "nothing to do", counts). */
        val allSucceeded: Boolean get() = steps.none { it.second.status == StepResult.Status.FAILED }

        val failed: List<Pair<Entry, StepResult>> get() = steps.filter { it.second.status == StepResult.Status.FAILED }

        fun resultFor(item: Item): StepResult? = steps.firstOrNull { it.first.item == item }?.second
    }

    /**
     * The result of restoring [total] packages of which [restored] came back. Done only when all
     * did; [firstFailure] names why the first one that did not, so a partial result still says
     * what went wrong.
     */
    fun appsResult(restored: Int, total: Int, firstFailure: String?): StepResult = when {
        total == 0 -> StepResult.NOTHING_TO_DO
        restored >= total -> StepResult(StepResult.Status.DONE, count = restored, total = total)
        else -> StepResult(StepResult.Status.FAILED, reason = firstFailure, count = restored, total = total)
    }
}
