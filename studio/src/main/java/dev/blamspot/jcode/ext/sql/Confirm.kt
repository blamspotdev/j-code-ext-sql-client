package dev.blamspot.jcode.ext.sql

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactDestructiveButton
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.Space

/**
 * Something the extension wants a yes to before it does it.
 *
 * Shared by the drawer panel and the manage page rather than written twice: they ask the same kinds
 * of question — discard this, drop that, rename this to what — and an answer that looked different
 * depending on which surface asked would be two dialogs to trust instead of one.
 *
 * [input] turns the prompt into a small form: non-null shows a field seeded with that value, and
 * [onConfirm] receives what it holds. Without it the field is absent and [onConfirm] gets "".
 *
 * Whether that field is required follows [destructive], and both call sites depend on it: a rename
 * has nothing to do without a name, so its button waits for one; a stash message only improves a
 * name git would otherwise invent, so a blank one still goes through.
 */
internal data class Confirm(
    val title: String,
    val body: String,
    val action: String,
    /** Outlined error styling and the non-default slot. False for a rename or anything reversible. */
    val destructive: Boolean = true,
    val input: String? = null,
    val placeholder: String = "",
    val onConfirm: (String) -> Unit,
)

/**
 * The question, as a real dialog window.
 *
 * Being native, this can put a prompt on the whole screen rather than squeezing it into a drawer a
 * few hundred pixels wide — and it asks with the app's own [AlertDialog], so a question from Source
 * Control looks like every other question JCode asks.
 */
@Composable
internal fun ConfirmDialog(confirm: Confirm, onDismiss: () -> Unit) {
    var value by remember(confirm) { mutableStateOf(confirm.input.orEmpty()) }
    val submit = {
        onDismiss()
        confirm.onConfirm(value.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(confirm.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    text = confirm.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (confirm.input != null) {
                    CompactField(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = confirm.placeholder,
                    )
                }
            }
        },
        // For a destructive action the slots are positions, not names: rightmost is where a thumb
        // lands, and that is not where "Delete" belongs. A reversible action takes the usual layout.
        confirmButton = {
            if (confirm.destructive) {
                CompactFilledButton(text = "Cancel", onClick = onDismiss)
            } else {
                CompactFilledButton(
                    text = confirm.action,
                    onClick = submit,
                    enabled = confirm.input == null || value.isNotBlank(),
                )
            }
        },
        dismissButton = {
            if (confirm.destructive) {
                CompactDestructiveButton(text = confirm.action, onClick = submit)
            } else {
                CompactFilledButton(text = "Cancel", onClick = onDismiss)
            }
        },
    )
}
