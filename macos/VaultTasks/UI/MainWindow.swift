import SwiftUI
import UniformTypeIdentifiers
import VaultTasksCore

@main
struct VaultTasksApp: App {
    @State private var container = AppContainer()
    @Environment(\.openWindow) private var openWindow
    @AppStorage("menuBarItem") private var menuBarItem = false

    var body: some Scene {
        let _ = container.openWindow = { openWindow(id: "main") }
        WindowGroup(id: "main") {
            MainWindow()
                .environment(container)
                .task { container.start() }
                .frame(minWidth: 760, minHeight: 480)
        }
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("New Task") { container.openCreate() }.keyboardShortcut("n", modifiers: .command)
                    .disabled(container.spaces.active == nil)
                Button("Refresh") { container.refresh() }.keyboardShortcut("r", modifiers: .command)
            }
            CommandGroup(replacing: .undoRedo) {
                Button("Undo") { container.undo() }.keyboardShortcut("z", modifiers: .command)
                Button("Redo") { NSApp.sendAction(Selector(("redo:")), to: nil, from: nil) }
                    .keyboardShortcut("z", modifiers: [.command, .shift])
            }
        }
        Settings {
            SettingsView().environment(container)
        }
        MenuBarExtra(isInserted: $menuBarItem) {
            MenuBarView().environment(container)
        } label: {
            MenuBarLabel().environment(container)
        }
        .menuBarExtraStyle(.window)
    }
}

struct MainWindow: View {
    /// Selection tag of the Calendar row; space ids are UUIDs, so it cannot collide.
    static let calendarTag = "·calendar"

    @Environment(AppContainer.self) private var app
    @State private var picking = false
    @State private var creatingSpace = false
    @State private var editingSpace: Space?
    @State private var renaming: Space?
    @State private var renameText = ""

    var body: some View {
        @Bindable var app = app
        NavigationSplitView {
            List(selection: Binding(
                get: { app.showCalendar ? Self.calendarTag : app.spaces.active?.id },
                set: { id in
                    guard let id else { return }
                    if id == Self.calendarTag { app.openCalendar() } else { app.selectSpace(id) }
                }
            )) {
                Section("Spaces") {
                    ForEach(app.spaces.spaces, id: \.id) { s in
                        Label(s.name, systemImage: "square.stack").tag(s.id)
                            .contextMenu {
                                Button("Rename…") { renameText = s.name; renaming = s }
                                Button("Edit files…") { editingSpace = s }
                                Divider()
                                Button("Delete", role: .destructive) { app.deleteSpace(s.id) }
                            }
                    }
                    .onMove { app.moveSpace(from: $0, to: $1) }
                }
                Section {
                    Label("Calendar", systemImage: "calendar").tag(Self.calendarTag)
                }
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 210)
            .safeAreaInset(edge: .bottom) {
                Button { creatingSpace = true } label: { Label("New space…", systemImage: "plus") }
                    .buttonStyle(.plain).padding(10).frame(maxWidth: .infinity, alignment: .leading)
            }
        } detail: {
            VStack(spacing: 0) {
                VaultBanner(picking: $picking)
                if let space = app.spaces.active {
                    if app.showCalendar {
                        CalendarView(space: space)
                    } else {
                        TaskListView(space: space)
                    }
                } else {
                    ContentUnavailableView {
                        Label("No spaces yet", systemImage: "square.stack")
                    } description: {
                        Text("A space is a set of vault files that belong to one topic.")
                    } actions: {
                        Button("New space…") { creatingSpace = true }
                    }
                }
            }
            .navigationTitle(app.showCalendar ? "Calendar" : (app.spaces.active?.name ?? "VaultTasks"))
            .overlay(alignment: .bottom) {
                if let t = app.toast {
                    ToastView(toast: t) { app.dismissToast() }
                        .padding(.bottom, 20).transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .toolbar {
                ToolbarItem {
                    if app.sync.busy { ProgressView().controlSize(.small).help("Syncing…") }
                }
                ToolbarItem { Button { app.refresh() } label: { Label("Refresh", systemImage: "arrow.clockwise") } }
            }
        }
        .sheet(item: $app.editor) { TaskEditorSheet(request: $0) }
        .sheet(isPresented: $creatingSpace) { SpaceSheet(space: nil) }
        .sheet(item: $editingSpace) { SpaceSheet(space: $0) }
        .alert("Rename space", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $renameText)
            Button("Cancel", role: .cancel) {}
            Button("Rename") {
                if let s = renaming { app.saveSpace(id: s.id, name: renameText, files: s.files, defaultFile: s.defaultFile) }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.folder]) { result in
            if case .success(let url) = result { app.pickVault(url) }
        }
        .onChange(of: app.state.phase) { _, p in if p == .noVault { picking = true } }
    }
}

struct VaultBanner: View {
    @Environment(AppContainer.self) private var app
    @Binding var picking: Bool

    var body: some View {
        let s = app.state
        if s.phase == .noVault {
            banner("Choose your Obsidian vault folder to see your tasks.", "Choose folder…")
        } else if s.phase == .permissionLost {
            banner("VaultTasks lost access to the vault folder. Pick it again; your spaces are kept.", "Choose folder…")
        } else if let e = s.scanError {
            banner("Scan failed: \(e)", "Retry") { app.refresh() }
        } else if !s.unreadable.isEmpty {
            banner("\(s.unreadable.count) file(s) could not be read (not UTF-8 or being written). Showing the last known state.", nil)
        }
    }

    @ViewBuilder private func banner(_ text: String, _ action: String?, run: (() -> Void)? = nil) -> some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
            Text(text).font(.callout)
            Spacer()
            if let action { Button(action) { if let run { run() } else { picking = true } } }
        }
        .padding(10)
        .background(.orange.opacity(0.12))
    }
}
