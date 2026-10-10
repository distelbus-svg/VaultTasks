import AppKit
import UniformTypeIdentifiers
import VaultTasksCore

/// ICS hand-off to Apple Calendar (§11). `IcsExport` builds the document; this only delivers it.
/// Stable UIDs mean importing again updates the events instead of duplicating them.
enum CalendarExport {
    struct Document { let text: String; let count: Int }

    /// All coming tasks (open, due today or later) of every space, with each task's own reminder lead.
    static func build(tasks: [VaultTask], leads: LeadStore, defaultTime: LocalTime, now: Date = Date()) -> Document {
        let count = IcsExport.coming(tasks, today: LocalDate.today()).count
        let text = IcsExport.build(tasks, now: now, defaultTime: defaultTime) { leads.get(path: $0.id.path, description: $0.description) }
        return Document(text: text, count: count)
    }

    static func writeTemp(_ text: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: "VaultTasks.ics", directoryHint: .notDirectory)
        try Data(text.utf8).write(to: url, options: .atomic)
        return url
    }

    /// Opens the file with Calendar, which asks which calendar to add the events to.
    @MainActor
    static func openInCalendar(_ url: URL) async throws {
        guard let calendar = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.apple.iCal") else {
            throw CocoaError(.fileNoSuchFile, userInfo: [NSLocalizedDescriptionKey: "Calendar was not found"])
        }
        _ = try await NSWorkspace.shared.open([url], withApplicationAt: calendar, configuration: NSWorkspace.OpenConfiguration())
    }

    /// "Save as…": the file instead of the hand-off. Returns the saved location, nil if cancelled.
    @MainActor
    static func saveAs(_ text: String) throws -> URL? {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "ics") ?? .calendarEvent]
        panel.nameFieldStringValue = "VaultTasks.ics"
        panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let url = panel.url else { return nil }
        try Data(text.utf8).write(to: url, options: .atomic)
        return url
    }
}
