package app.vaulttasks.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vaulttasks.data.alarms.DiagEntry
import app.vaulttasks.data.alarms.HealthCheck
import app.vaulttasks.data.alarms.HealthReport
import app.vaulttasks.domain.alarms.ReminderSettings
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val stampFmt = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
private val LEAD_OPTIONS = listOf(0, 5, 10, 15, 30, 60)

/** Spec §7.4/§8.5: reminder settings, the health check, MagicOS guidance and the fire-time diagnostics log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(
    health: HealthReport?,
    settings: ReminderSettings,
    diagnostics: List<DiagEntry>,
    onBack: () -> Unit,
    onFixNotifications: () -> Unit,
    onFixExactAlarms: () -> Unit,
    onFixBattery: () -> Unit,
    onOpenLaunchManager: () -> Unit,
    onRefresh: () -> Unit,
    onClearDiagnostics: () -> Unit,
    onDefaultTime: (LocalTime) -> Unit,
    onLeadMinutes: (Int) -> Unit,
) {
    var showTime by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reminders") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
                actions = { TextButton(onClick = onRefresh) { Text("Re-check") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Section("Health check")
            if (health == null) {
                Text("Checking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CheckRow("Notifications", health.notifications, onFixNotifications)
                CheckRow("Exact alarms", health.exactAlarms, onFixExactAlarms)
                CheckRow("Battery optimization", health.battery, onFixBattery)
            }

            Section("MagicOS (Honor)")
            Text(
                "These cannot be detected, so check them by hand (menu names vary by MagicOS version). In the system battery " +
                    "or app-launch settings for VaultTasks, set “App launch” to manual and allow auto-launch, secondary " +
                    "launch and running in the background. Also lock the app in the recents screen so cleaners skip it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenLaunchManager) { Text("Open launch settings") }

            Section("Timing")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Default time for date-only tasks", Modifier.weight(1f))
                OutlinedButton(onClick = { showTime = true }) { Text(settings.defaultTime.format(timeFmt)) }
            }
            Text("Remind me before the due time", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LEAD_OPTIONS.forEach { m ->
                    FilterChip(
                        selected = settings.leadMinutes == m,
                        onClick = { onLeadMinutes(m) },
                        label = { Text(if (m == 0) "At time" else if (m == 60) "1 h" else "$m min") },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearDiagnostics, enabled = diagnostics.isNotEmpty()) { Text("Clear") }
            }
            Text(
                "Each reminder that fired: when it was due, and how late the app actually ran. Last 100.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (diagnostics.isEmpty()) {
                Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                diagnostics.forEach { DiagRow(it) }
            }
        }
    }

    if (showTime) {
        val picker = rememberTimePickerState(settings.defaultTime.hour, settings.defaultTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onDefaultTime(LocalTime.of(picker.hour, picker.minute))
                    showTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = picker) },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun CheckRow(name: String, check: HealthCheck, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (check.ok) "✓" else "✕", color = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(check.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!check.ok) OutlinedButton(onClick = onFix) { Text("Fix") }
    }
}

@Composable
private fun DiagRow(e: DiagEntry) {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(e.atMillis).atZone(zone).format(stampFmt)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val late = e.latenessMillis
        val head = when (e.kind) {
            DiagEntry.Kind.EVENT -> "$at  ·  ${e.label}"
            else -> "$at  ·  ${lateness(late!!)}" + if (e.kind == DiagEntry.Kind.SNOOZE_FIRE) "  ·  snoozed" else ""
        }
        Text(head, style = MaterialTheme.typography.bodyMedium)
        if (e.kind != DiagEntry.Kind.EVENT) {
            Text(e.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

private fun lateness(ms: Long): String {
    val s = abs(ms) / 1000.0
    val text = if (s < 60) "%.1f s".format(s) else "%d min %02d s".format((s / 60).toInt(), (s % 60).toInt())
    return if (ms >= 0) "$text late" else "$text early"
}
