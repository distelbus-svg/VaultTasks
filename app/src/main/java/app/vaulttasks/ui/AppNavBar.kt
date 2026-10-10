package app.vaulttasks.ui

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.vaulttasks.ui.components.Glyph
import app.vaulttasks.ui.components.GlyphIcon

/** Top-level destinations (Tasks, Calendar) as peers; hosted once in MainActivity so it stays put while screens slide. */
@Composable
fun AppNavBar(calendarSelected: Boolean, onTasks: () -> Unit, onCalendar: () -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = !calendarSelected,
            onClick = onTasks,
            icon = { GlyphIcon(Glyph.Check) },
            label = { Text("Tasks") },
        )
        NavigationBarItem(
            selected = calendarSelected,
            onClick = onCalendar,
            icon = { GlyphIcon(Glyph.Calendar) },
            label = { Text("Calendar") },
        )
    }
}
