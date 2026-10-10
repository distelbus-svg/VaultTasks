import SwiftUI
import VaultTasksCore

extension Space: @retroactive Identifiable {}

/// Create or edit a space: name, the vault files it reads/writes, and the default file for new tasks.
struct SpaceSheet: View {
    @Environment(AppContainer.self) private var app
    @Environment(\.dismiss) private var dismiss
    let space: Space?

    @State private var name = ""
    @State private var files: Set<String> = []
    @State private var defaultFile: String?
    @State private var filter = ""

    private var candidates: [String] {
        let all = Set(app.state.available).union(files).sorted()
        return filter.isEmpty ? all : all.filter { $0.localizedCaseInsensitiveContains(filter) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(space == nil ? "New space" : "Edit space").font(.title3.bold())
            TextField("Name", text: $name).textFieldStyle(.roundedBorder)
            TextField("Filter files", text: $filter).textFieldStyle(.roundedBorder)
            List(candidates, id: \.self) { f in
                HStack {
                    Toggle(isOn: Binding(get: { files.contains(f) }, set: { on in
                        if on { files.insert(f) } else { files.remove(f); if defaultFile == f { defaultFile = nil } }
                    })) { Text(f).lineLimit(1) }
                    Spacer()
                    if files.contains(f) {
                        Button { defaultFile = f } label: { Image(systemName: defaultFile == f ? "star.fill" : "star") }
                            .buttonStyle(.plain).help("Default file for new tasks")
                    }
                }
            }
            .frame(height: 260)
            Text("Files not listed here are never read or changed. The star marks where new tasks go by default.")
                .font(.caption).foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Save", action: save).keyboardShortcut(.defaultAction).disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(20).frame(width: 440)
        .onAppear {
            if let s = space { name = s.name; files = Set(s.files); defaultFile = s.defaultFile }
        }
    }

    private func save() {
        let old = space?.files ?? []
        let list = old.filter(files.contains) + files.subtracting(old).sorted()
        if let s = space { app.saveSpace(id: s.id, name: name, files: list, defaultFile: defaultFile) }
        else { app.addSpace(name: name, files: list, defaultFile: defaultFile) }
        dismiss()
    }
}

struct SpacesSettingsTab: View {
    @Environment(AppContainer.self) private var app
    @State private var editing: Space?
    @State private var creating = false

    var body: some View {
        VStack(alignment: .leading) {
            List {
                ForEach(app.spaces.spaces, id: \.id) { s in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(s.name)
                            Text("\(s.files.count) file(s)").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Edit…") { editing = s }
                        Button(role: .destructive) { app.deleteSpace(s.id) } label: { Image(systemName: "trash") }
                    }
                }
                .onMove { app.moveSpace(from: $0, to: $1) }
            }
            HStack { Button("New space…") { creating = true }; Spacer() }
        }
        .padding()
        .sheet(item: $editing) { SpaceSheet(space: $0) }
        .sheet(isPresented: $creating) { SpaceSheet(space: nil) }
    }
}
