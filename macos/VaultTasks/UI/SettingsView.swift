import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(AppContainer.self) private var app
    @State private var filter = ""
    @State private var picking = false

    var body: some View {
        TabView {
            GeneralSettingsView()
                .tabItem { Label("General", systemImage: "gearshape") }
            Form {
                LabeledContent("Vault folder") {
                    Text(app.state.vaultPath ?? "None").textSelection(.enabled).foregroundStyle(.secondary).lineLimit(2)
                }
                LabeledContent("Status") { Text(statusText).foregroundStyle(.secondary) }
                HStack {
                    Button("Choose folder…") { picking = true }
                    Button("Rescan") { app.refresh() }.disabled(app.state.phase != .ready)
                }
                TextField("Tasks global filter", text: $filter, prompt: Text("empty"))
                    .onSubmit { app.globalFilter = filter }
                Text("Must match the Tasks plugin's global filter exactly. Press Return to apply.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .formStyle(.grouped)
            .tabItem { Label("Vault", systemImage: "folder") }
            RemindersView()
                .tabItem { Label("Reminders", systemImage: "bell") }
            SpacesSettingsTab()
                .tabItem { Label("Spaces", systemImage: "square.stack") }
            SyncSettingsView()
                .tabItem { Label("Sync", systemImage: "arrow.triangle.2.circlepath") }
            CalendarExportView()
                .tabItem { Label("Calendar", systemImage: "calendar.badge.plus") }
        }
        .frame(width: 600, height: 560)
        .onAppear { filter = app.globalFilter }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { app.pickVault(url) }
        }
    }

    private var statusText: String {
        let s = app.state
        switch s.phase {
        case .loading: return "Loading…"
        case .noVault: return "No vault chosen"
        case .permissionLost: return "Access lost, choose the folder again"
        case .ready:
            let n = Set(s.tasks.map(\.id.path)).count
            return "\(s.tasks.count) tasks in \(n) file(s)" + (s.fromCache ? " (cached, verifying…)" : "")
        }
    }
}

/// Settings > General (§9.5): launch at login and the optional menu bar item, both off by default.
struct GeneralSettingsView: View {
    @Environment(AppContainer.self) private var app
    @AppStorage("menuBarItem") private var menuBarItem = false

    var body: some View {
        let login = app.loginItem
        Form {
            Section("Startup") {
                Toggle("Open VaultTasks at login", isOn: Binding(get: { login.isOn }, set: { login.set($0) }))
                if login.needsApproval {
                    Text("macOS needs your approval before VaultTasks can open at login.").font(.callout).foregroundStyle(.orange)
                    Button("Open Login Items…") { login.openSystemSettings() }
                }
                if let e = login.error { Text(e).font(.callout).foregroundStyle(.red) }
                Text("Reminders are delivered by the system even while the app is closed. Opening at login keeps automatic sync and the reminder top-up running.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Menu bar") {
                Toggle("Show menu bar item", isOn: $menuBarItem)
                Text("Shows the number of overdue and due-today tasks, with a quick-add field for the active space.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .onAppear { login.refresh() }
    }
}
