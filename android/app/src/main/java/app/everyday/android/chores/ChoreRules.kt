package app.everyday.android.chores

import app.everyday.android.data.ChoreCompletion
import java.time.LocalDate

/** One completion: [memberId] did the chore on [date]. */
data class Completion(val memberId: String, val date: LocalDate)

/** Pure rules for when a chore counts as done. */
object ChoreRules {
    /**
     * The days the chore counts as done for [memberId], which silences their reminders for it:
     * ANY once anyone has done it, EACH once [memberId] has done their own part.
     */
    fun doneDatesFor(completion: ChoreCompletion, completions: List<Completion>, memberId: String): Set<LocalDate> =
        when (completion) {
            ChoreCompletion.ANY -> completions.map { it.date }.toSet()
            ChoreCompletion.EACH -> completions.filter { it.memberId == memberId }.map { it.date }.toSet()
        }

    /** Whether the whole chore is finished on [date]: ANY by anyone, EACH by every current assignee. */
    fun isComplete(completion: ChoreCompletion, assigneeIds: List<String>, completions: List<Completion>, date: LocalDate): Boolean {
        val doneBy = completions.filter { it.date == date }.map { it.memberId }.toSet()
        return when (completion) {
            ChoreCompletion.ANY -> doneBy.isNotEmpty()
            ChoreCompletion.EACH -> assigneeIds.isNotEmpty() && doneBy.containsAll(assigneeIds)
        }
    }
}
