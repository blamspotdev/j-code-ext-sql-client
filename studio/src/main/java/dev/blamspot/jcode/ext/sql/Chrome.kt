package dev.blamspot.jcode.ext.sql

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.ControlSize
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.handCursor

/**
 * The parts every page of this extension is built from.
 *
 * Six surfaces open in the editor area — sign-in, manage, clone, remote repositories, a diff, a
 * merge — and they are one extension, so they have to look like one. Written once here rather than
 * per page: a header that drifted by a few pixels between two of them would read as two extensions.
 */

/** Icon, title, one line of explanation, and whatever action the page leads with. */
@Composable
internal fun PageHeader(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    monospaceTitle: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(IconSize.xl),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (monospaceTitle) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.headlineSmall,
                fontFamily = if (monospaceTitle) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

/** A titled slab. Every page is a stack of these, the way the settings screens are. */
@Composable
internal fun Card(
    title: String,
    modifier: Modifier = Modifier,
    /** Sits beside the title — a switch over what the card is showing. */
    trailing: (@Composable () -> Unit)? = null,
    /** Sits at the far end — something the card does. */
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radius.xxl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (title.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Beside the title rather than across the card from it: a switch that changes
                    // what the title names belongs next to the title, not at the far edge where it
                    // reads as an unrelated control that happens to share the row.
                    trailing?.invoke()
                    Box(modifier = Modifier.weight(1f))
                    action?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(
        thickness = StrokeWidth.hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** A caption above a field, so a form reads as labelled rows rather than a column of placeholders. */
@Composable
internal fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Explanatory prose inside a card — what a setting means, or what a page is about to do. */
@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** How the last action went, in the colour that says which. */
@Composable
internal fun StatusText(message: String, isError: Boolean) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else JCodeTheme.semanticColors.success,
    )
}

/**
 * What a command actually printed.
 *
 * Scrolls sideways rather than wrapping: git's own output is laid out in columns, and a clone that
 * failed says why on a line long enough that wrapping it loses the shape.
 */
@Composable
internal fun LogBlock(text: String, modifier: Modifier = Modifier) {
    val horizontal = rememberScrollState()
    Surface(
        shape = RoundedCornerShape(Radius.lg),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            modifier = Modifier.horizontalScroll(horizontal).padding(Space.sm),
        )
    }
}

/** A page with nothing to show yet, or nothing to show at all. */
@Composable
internal fun Note(text: String, spinner: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (spinner) {
            CircularProgressIndicator(modifier = Modifier.size(IconSize.sm), strokeWidth = StrokeWidth.thick)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A two-or-more-way switch where exactly one option is on.
 *
 * A filled segment inside a track reads as "this one is selected"; two independent filled pills
 * side by side read as two things shouting to be pressed. That difference is the whole reason this
 * is one control rather than a row of toggles.
 */
@Composable
internal fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row {
            options.forEach { option ->
                val on = option == selected
                Surface(
                    shape = RoundedCornerShape(Radius.pill),
                    color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable { onSelect(option) }
                        .handCursor(),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .defaultMinSize(minHeight = ControlSize.compactHeight)
                            .padding(ControlSize.compactPadding),
                    ) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A setting that is simply on or off.
 *
 * Tinted rather than filled when on: it is a preference about how the page is drawn, not an action,
 * and it should not compete with the content it is a preference about.
 */
@Composable
internal fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = if (on) colors.secondaryContainer else Color.Transparent,
        contentColor = if (on) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (on) null else BorderStroke(StrokeWidth.hairline, colors.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(Radius.pill)).clickable(onClick = onClick).handCursor(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .defaultMinSize(minHeight = ControlSize.compactHeight)
                .padding(ControlSize.compactPadding),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/**
 * An icon on its own, as a button.
 *
 * A row of labelled buttons eats the width the row is actually about — a branch name and where it
 * tracks — and every action added makes that worse. One glyph with a real touch target costs a
 * fixed amount however many actions hide behind it.
 */
@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radius.pill),
        color = Color.Transparent,
        modifier = modifier
            .size(ControlSize.iconButton)
            .then(if (enabled) Modifier.clickable(onClick = onClick).handCursor() else Modifier),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

/**
 * A text field the size of its text.
 *
 * Material's `OutlinedTextField` reserves fifty-six density-independent pixels before it holds
 * anything, which is most of a phone-height drawer spent on three of them. This is a bordered
 * surface around a `BasicTextField`, so an empty one is a line of text tall and grows only when the
 * text does.
 */
@Composable
internal fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = 1,
    /** For a value the keyboard must not help with — a URL, a username, an email, a token. */
    literal: Boolean = false,
    password: Boolean = false,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    // The app's own field, down to the type scale: SettingsTextFieldRow and CompactSearchField agree
    // on Radius.xl, a quarter-strength surface tint and a thin outline at 60%, and this matches the
    // rest of it too — bodyMedium, a placeholder at 60%, Space.ms of horizontal room, and the same
    // 40 / 96dp heights. Written out rather than reused because neither of those takes a trailing
    // action or a multi-line commit message; but a field that is *nearly* the app's field reads as
    // a mistake rather than as a choice, so it is the app's field or it is nothing.
    val single = maxLines == 1
    Surface(
        shape = RoundedCornerShape(Radius.xl),
        color = colors.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(StrokeWidth.thin, colors.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            // The row stays centred so a trailing action sits in the middle of the box however tall
            // it grows; the text inside it starts at the top, which is where a message begins.
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = Space.ms,
                end = if (trailing == null) Space.ms else Space.xxs,
            ),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    // Only the one-line case takes a floor. A multi-line field's height is
                    // [minLines], which the message box sets deliberately — the app's own 96dp
                    // would quietly overrule it in the name of matching the app.
                    .heightIn(min = if (single) SingleLineHeight else Dp.Unspecified)
                    .padding(vertical = if (single) Space.none else Space.sm),
                contentAlignment = if (single) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = colors.onSurface,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    minLines = minLines,
                    maxLines = maxLines,
                    singleLine = maxLines == 1,
                    // Without a declared action the keyboard invents one, and in the landscape
                    // full-screen editor that came out as "EXECUTE" — a word for running something,
                    // over a field that only holds text. Naming it Done also gives the button
                    // something to do: put the keyboard away.
                    //
                    // Safe on the multi-line message box: Android gives a multi-line field a newline
                    // key and reaches the action only from the full-screen editor, so declaring one
                    // does not cost the message its line breaks.
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        capitalization = if (literal) KeyboardCapitalization.None
                        else KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = !literal,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    visualTransformation = if (password) PasswordVisualTransformation()
                    else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke()
        }
    }
}

/** The height a one-line field settles at in the app, so one here is not a different size. */
private val SingleLineHeight = 40.dp
