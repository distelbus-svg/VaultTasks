import SwiftUI
import AppKit
import UserNotifications
import VaultTasksCore

/// Settings > Reminders (§7, §9.5): default time, authorization and health, test notification, diagnostics.
struct RemindersView: View {
    @Environment(AppContainer.self) private var app

    var body: some View {
        let r = app.reminders
        Form {
            Section("Default time") {
                DatePicker("Tasks without a time remind at", selection: timeBinding({ app.defaultReminderTime }, { app.defaultReminderTime = $0 }),
                           displayedComponents: .hourAndMinute)
            }
            Section("Status") {
                LabeledContent("Notifications") {
                    Text(authText(r.authorization)).foregroundStyle(r.authorization == .authorized ? Color.secondary : Color.red)
                }
                if r.authorization == .denied {
                    Button("Open System Settings…") {
                        if let u = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") { NSWorkspace.shared.open(u) }
                    }
                }
                LabeledContent("Scheduled") { Text("\(r.pendingCount) of \(NotifID.window)").monospacedDigit() }
                if let rep = r.lastReport {
                    LabeledContent("Last sync") {
                        Text("\(rep.scheduled) set, \(rep.cancelled) cancelled, \(rep.unchanged) unchanged" + (rep.failed > 0 ? ", \(rep.failed) failed" : ""))
                            .foregroundStyle(rep.failed > 0 ? Color.red : Color.secondary)
                    }
                }
                ForEach(r.nextFive, id: \.id) { p in
                    LabeledContent(p.title) { Text(p.next.map { $0.formatted(date: .abbreviated, time: .shortened) } ?? "") }
                }
                HStack {
                    Button("Send test notification in 10 s") { Task { await r.sendTest() } }
                    Button("Re-schedule all") { app.rescheduleAll() }
                }
            }
            Section("Lateness (delivered vs scheduled)") {
                if r.diagnostics.isEmpty {
                    Text("No delivered reminders recorded yet.").foregroundStyle(.secondary)
                } else {
                    let worst = r.diagnostics.map(\.lateness).max() ?? 0
                    LabeledContent("Recorded") {
                        Text("\(r.diagnostics.count), latest \(String(format: "%+.0f", r.diagnostics.last?.lateness ?? 0)) s, worst \(String(format: "%+.0f", worst)) s")
                            .monospacedDigit().foregroundStyle(.secondary)
                    }
                }
                ForEach(r.diagnostics.suffix(10).reversed()) { e in
                    LabeledContent(Date(timeIntervalSince1970: e.scheduled).formatted(date: .abbreviated, time: .shortened)) {
                        Text(String(format: "%+.0f s", e.lateness)).monospacedDigit()
                    }
                }
                if !r.diagnostics.isEmpty {
                    Button("Clear log") { r.clearDiagnostics() }
                }
                Text("Reminders that come due while the Mac sleeps are delivered by the system on wake; check the lateness above.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .task { await app.reminders.refreshStatus(); await app.reminders.recordDelivered() }
    }

    private func authText(_ s: UNAuthorizationStatus) -> String {
        switch s {
        case .authorized, .provisional: "Allowed"
        case .denied: "Denied in System Settings"
        case .notDetermined: "Not asked yet"
        default: "Unknown"
        }
    }
}
