package app.vaulttasks.ui.components

import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Rounded back button for app bars; same shape language as the rest of the app. */
@Composable
fun BackButton(onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick) { GlyphIcon(Glyph.Back) }
}

/** Dropdown with large corners and a raised surface, matching the cards. */
@Composable
fun AppDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        content = content,
    )
}

@Composable
fun AppMenuItem(text: String, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false, selected: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    DropdownMenuItem(
        text = {
            Text(
                text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        },
        onClick = onClick,
        enabled = enabled,
        colors = MenuDefaults.itemColors(textColor = if (destructive) colors.error else if (selected) colors.onSecondaryContainer else colors.onSurface),
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp).clip(shape)
            .background(if (selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
    )
}

/** Floating pill toast replacing the stock snackbar (delete + Undo, sync results). */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> AppSnackbar(data) }
}

@Composable
private fun AppSnackbar(data: SnackbarData) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .shadow(8.dp, shape).clip(shape).background(colors.inverseSurface)
            .heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            data.visuals.message,
            color = colors.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        data.visuals.actionLabel?.let { label ->
            TextButton(
                onClick = { data.performAction() },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}


/** Category name → ARGB the user picked when creating it. Provided once at the app root. */
val LocalCategoryColors = androidx.compose.runtime.staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

/** Palette offered in the "New category" dialog. All light enough for dark text. */
val CATEGORY_PALETTE: List<Int> = listOf(
    0xFFE57373, 0xFFFFB74D, 0xFFFFD54F, 0xFF81C784, 0xFF4DB6AC, 0xFF64B5F6, 0xFF9575CD, 0xFFF06292, 0xFFA1887F, 0xFF90A4AE,
).map { it.toInt() }

/** Readable text color on a category swatch. */
fun onCategoryColor(argb: Int): androidx.compose.ui.graphics.Color =
    if (androidx.compose.ui.graphics.Color(argb).luminance() > 0.35f) androidx.compose.ui.graphics.Color(0xFF1C1B1F) else androidx.compose.ui.graphics.Color.White
