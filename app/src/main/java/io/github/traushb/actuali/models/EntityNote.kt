package io.github.traushb.actuali.models

/**
 * The note attached to one row of the budget, as stored in Actual's `notes`
 * table — categories and accounts alike. The table is keyed by the annotated
 * row's own id, so one model covers both.
 *
 * `supported` is false when the open budget file has no `notes` table at all;
 * the UI hides the note affordance in that case rather than offering an edit
 * that could never save. Empty `text` means "no note": Actual stores a cleared
 * note as an empty string rather than removing the row.
 */
data class EntityNote(val supported: Boolean, val text: String) {
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        val unsupported = EntityNote(false, "")

        /**
         * What to persist for text the user typed. Whitespace-only input clears
         * the note; anything else is stored verbatim — leading indentation and
         * blank lines inside a multi-line note are deliberate.
         */
        fun normalizedForSave(text: String): String = if (text.isBlank()) "" else text

        /**
         * The `notes.id` an account's note lives at. Actual prefixes accounts
         * (`account-${account.id}`) while categories and groups use the bare id.
         */
        fun accountNoteId(accountId: String): String = "account-$accountId"

        /** The `notes.id` a budget month's note lives at, for a `YYYY-MM` month (`budget-${month}`). */
        fun monthNoteId(month: String): String = "budget-$month"
    }
}
