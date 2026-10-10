import SwiftUI
import VaultTasksCore

/// Menu bar label: the count of overdue + today (open tasks in any space), nothing when there are none.
struct MenuBarLabel: View {
    @Environment(AppContainer.self) private var app

    var body: some View {
        let c = app.menuBarCounts
        let n = c.overdue + c.today
        HStack(spacing: 3) {
            Image(systemName: c.overdue > 0 ? "exclamationmark.circle" : "checklist")
            if n > 0 { Text("\(n)").monospacedDigit() }
        }
    }
}

/// Menu bar panel: the day at a glance and a quick-add field that writes to the active space's creation file.
struct MenuBarView: View {
    @Environment(AppContainer.self) private var app
    @State private var title = ""
    @State private var dueToday = false
    @State private var message: String?
    @State private var adding = false
    @FocusState private var focused: Bool

    var body: some View {
        let c = app.menuBarCounts
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(headline(c)).font(.headline)
                if c.overdue > 0 && c.today > 0 { Text("\(c.overdue) overdue · \(c.today) today").font(.callout).foregroundStyle(.secondary) }
            }
            if let space = app.spaces.active, space.creationFile != nil {
                VStack(alignment: .leading, spacing: 8) {
                    TextField("Add to \(space.name)", text: $title)
                        .textFieldStyle(.roundedBorder)
                        .focused($focused)
                        .onSubmit(add)
                        .disabled(adding)
                    HStack {
                        Toggle("Due today", isOn: $dueToday).toggleStyle(.checkbox)
                        Spacer()
                        Button("Add", action: add)
                            .keyboardShortcut(.defaultAction)
                            .disabled(adding || title.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            } else {
                Text("Create a space with a vault file to add tasks here.").font(.callout).foregroundStyle(.secondary)
            }
            if let message { Text(message).font(.caption).foregroundStyle(.red) }
            Divider()
            HStack {
                Button("Open VaultTasks") { app.showMainWindow() }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .frame(width: 300)
        .task { app.start() }
        .onAppear { focused = true; message = nil }
    }

    private func headline(_ c: (overdue: Int, today: Int)) -> String {
        switch c.overdue + c.today {
        case 0: "All clear"
        case 1: "1 task needs you"
        case let n: "\(n) tasks need you"
        }
    }

    private func add() {
        guard !adding, !title.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        adding = true
        let text = title, today = dueToday
        Task {
            let error = await app.quickAdd(text, today: today)
            adding = false
            message = error
            if error == nil { title = ""; focused = true }
        }
    }
}
