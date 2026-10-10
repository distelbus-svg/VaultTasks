import SwiftUI
import VaultTasksCore

/// Settings > Calendar export (§11): the coming tasks as an .ics, handed to Calendar or saved as a file.
struct CalendarExportView: View {
    @Environment(AppContainer.self) private var app
    @State private var message: String?
    @State private var failed = false

    var body: some View {
        let count = IcsExport.coming(app.state.tasks, today: LocalDate.today()).count
        Form {
            Section("Upcoming tasks") {
                Text(count == 0 ? "Nothing to export: no open task is due today or later."
                                : "\(count) open task\(count == 1 ? "" : "s") due today or later, from every space.")
                HStack {
                    Button("Open in Calendar") { export(openInCalendar: true) }
                    Button("Save as…") { export(openInCalendar: false) }
                }
                .disabled(count == 0)
                if let message {
                    Text(message).font(.callout).foregroundStyle(failed ? Color.red : Color.secondary)
                }
            }
            Section {
                Text("Calendar asks which calendar to add the events to. Pick the same one every time: each task keeps a stable ID, so importing again updates its event instead of adding a duplicate. Events carry notes, category, repeat rule and an alarm matching the task's reminder. Done, undated and overdue tasks are left out. Events of tasks that are no longer upcoming stay in Calendar until you delete them there.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    private func export(openInCalendar: Bool) {
        let doc = CalendarExport.build(tasks: app.state.tasks, leads: app.leadStore, defaultTime: app.defaultReminderTime)
        Task {
            do {
                if openInCalendar {
                    try await CalendarExport.openInCalendar(try CalendarExport.writeTemp(doc.text))
                    note("Opened \(doc.count) task\(doc.count == 1 ? "" : "s") in Calendar.", error: false)
                } else if let url = try CalendarExport.saveAs(doc.text) {
                    note("Saved \(doc.count) task\(doc.count == 1 ? "" : "s") to \(url.lastPathComponent).", error: false)
                }
            } catch {
                note("Export failed: " + ((error as? LocalizedError)?.errorDescription ?? error.localizedDescription), error: true)
            }
        }
    }

    private func note(_ text: String, error: Bool) { message = text; failed = error }
}
