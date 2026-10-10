import SwiftUI
import VaultTasksCore

/// Settings > Sync (§8, §9.5): server form with Test connection, Check (read-only status per file), Sync (two-way),
/// last report. No silent overwrite: the status list is always available before syncing.
struct SyncSettingsView: View {
    @Environment(AppContainer.self) private var app
    @State private var url = ""
    @State private var user = ""
    @State private var password = ""
    @State private var folder = ""

    private var form: WebDavConfig { WebDavConfig(url: url, user: user, password: password, baseFolder: folder) }

    var body: some View {
        let sync = app.sync
        Form {
            Section("Server") {
                TextField("URL", text: $url, prompt: Text("https://host/remote.php/dav/files/me"))
                TextField("User", text: $user)
                SecureField("Password", text: $password)
                TextField("Folder", text: $folder, prompt: Text("folder on the server, may be empty"))
                HStack {
                    Button("Test connection") { sync.test(form) }
                        .disabled(sync.busy || url.trimmingCharacters(in: .whitespaces).isEmpty)
                    Button("Save") { sync.save(form) }.disabled(sync.busy)
                    if sync.hasServer { Button("Remove server", role: .destructive) { sync.removeServer(); load() }.disabled(sync.busy) }
                    if sync.busy { ProgressView().controlSize(.small) }
                }
                if let m = sync.message {
                    Text(m).font(.callout).foregroundStyle(sync.messageIsError ? Color.red : Color.secondary).textSelection(.enabled)
                }
            }

            Section("Files") {
                if app.spaces.assignedFiles.isEmpty {
                    Text("No files are assigned to a space yet. Only files that belong to a space are synced.").foregroundStyle(.secondary)
                }
                HStack {
                    Button("Check") { sync.check() }
                    Button("Sync") { sync.sync() }
                }
                .disabled(sync.busy || !sync.hasServer)
                ForEach(sync.files, id: \.path) { f in
                    LabeledContent {
                        Text(Self.label(f)).foregroundStyle(Self.color(f.status)).multilineTextAlignment(.trailing)
                    } label: {
                        Text(f.path).lineLimit(1).truncationMode(.middle)
                    }
                }
                Text("Check only reads and shows what would happen. Sync carries it out: a file changed on one side is copied over; changed on both, the newer one wins (a tie goes to the server). Deletions are never propagated.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            if let r = sync.report {
                Section("Last sync" + (sync.lastRun.map { " · " + $0.formatted(date: .omitted, time: .shortened) } ?? "")) {
                    LabeledContent("Pulled") { Text(r.pulled.isEmpty ? "none" : r.pulled.joined(separator: ", ")).foregroundStyle(.secondary) }
                    LabeledContent("Pushed") { Text(r.pushed.isEmpty ? "none" : r.pushed.joined(separator: ", ")).foregroundStyle(.secondary) }
                    ForEach(r.skipped, id: \.path) { s in
                        LabeledContent("Skipped: \(s.path)") { Text(s.reason).foregroundStyle(.orange) }
                    }
                    ForEach(r.failed, id: \.path) { f in
                        LabeledContent("Failed: \(f.path)") { Text(f.message).foregroundStyle(.red) }
                    }
                }
            }

            Section {
                Text("Syncs automatically when the app is opened, a few seconds after you leave it, and daily while it runs. The password is kept in the Keychain.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .onAppear(perform: load)
    }

    private func load() {
        let c = app.sync.config
        url = c?.url ?? ""; user = c?.user ?? ""; password = c?.password ?? ""; folder = c?.baseFolder ?? ""
    }

    static func label(_ f: FileSyncState) -> String {
        switch f.status {
        case .inSync: "In sync"
        case .localChanged: "Changed here, will upload"
        case .remoteChanged: "Changed on the server, will download"
        case .bothChanged: "Changed on both sides, the newer one wins"
        case .notOnServer: "Not on the server yet, will upload"
        case .missingLocally: "On the server, missing here (not recreated)"
        case .deletedOnServer: "Deleted on the server (kept here)"
        case .gone: "Missing on both sides"
        case .error: "Error: " + (f.detail ?? "unknown")
        }
    }

    static func color(_ s: FileStatus) -> Color {
        switch s {
        case .inSync: .secondary
        case .localChanged, .remoteChanged, .bothChanged, .notOnServer: .blue
        case .missingLocally, .deletedOnServer, .gone: .orange
        case .error: .red
        }
    }
}
