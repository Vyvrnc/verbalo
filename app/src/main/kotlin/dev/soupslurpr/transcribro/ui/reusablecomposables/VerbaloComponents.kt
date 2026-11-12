package dev.soupslurpr.transcribro.ui.reusablecomposables

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.soupslurpr.transcribro.ui.theme.VerbaloTheme

/** The building blocks of docs/design-language.md ("Components"). */

private val CardShape = RoundedCornerShape(12.dp)
private val ButtonShape = RoundedCornerShape(12.dp)

/** Card: surface, 1 px border, radius 12, padding 16. Opaque, no shadow. */
@Composable
fun VerbaloCard(
    modifier: Modifier = Modifier,
    borderColor: Color = VerbaloTheme.colors.border,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(VerbaloTheme.colors.surface)
            .border(1.dp, borderColor, CardShape)
            .padding(contentPadding),
        content = content,
    )
}

/** Section label: 11 / 600, +1.2, uppercase, muted; 24 above and 8 below come from the list. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = VerbaloTheme.colors.textMuted,
        modifier = modifier.padding(top = 16.dp),
    )
}

/** 40 × 40 icon tile on the raised surface; [tint] is the item's colour (teal by default). */
@Composable
fun IconTile(icon: ImageVector, tint: Color = VerbaloTheme.colors.primarySoft, filled: Boolean = false) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) VerbaloTheme.colors.primary else VerbaloTheme.colors.surfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) VerbaloTheme.colors.onPrimary else tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Action row: icon tile, title, one caption line, chevron. The whole card is the target.
 * [primary] fills the tile teal: the one main action of the screen.
 */
@Composable
fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(VerbaloTheme.colors.surface)
            .border(1.dp, if (primary) VerbaloTheme.colors.primary else VerbaloTheme.colors.border, CardShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, filled = primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) VerbaloTheme.colors.textPrimary else VerbaloTheme.colors.textMuted,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = VerbaloTheme.colors.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = VerbaloTheme.colors.textMuted,
            )
        }
    }
}

/** Primary button: teal fill, onPrimary text, radius 12, height 52. One per screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        modifier = modifier.heightIn(min = 52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VerbaloTheme.colors.primary,
            contentColor = VerbaloTheme.colors.onPrimary,
            disabledContainerColor = VerbaloTheme.colors.surfaceRaised,
            disabledContentColor = VerbaloTheme.colors.textMuted,
        ),
    ) {
        ButtonContent(text, icon)
    }
}

/** Secondary button: 1 px teal outline, teal text. [destructive] uses the error colour instead. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val color = if (destructive) VerbaloTheme.colors.error else VerbaloTheme.colors.primarySoft
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        modifier = modifier.heightIn(min = 48.dp),
        border = BorderStroke(1.dp, if (enabled) color else VerbaloTheme.colors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) {
        ButtonContent(text, icon)
    }
}

/** Text button: teal text, for the least important action. */
@Composable
fun VerbaloTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = VerbaloTheme.colors.primarySoft),
    ) { Text(text) }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text)
}

/** Pill chip on the raised surface: a teal icon and a short word ("Offline"). */
@Composable
fun FeatureChip(icon: ImageVector, text: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(VerbaloTheme.colors.surfaceRaised)
            .border(1.dp, VerbaloTheme.colors.border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = VerbaloTheme.colors.primarySoft, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = VerbaloTheme.colors.textSecondary)
    }
}
