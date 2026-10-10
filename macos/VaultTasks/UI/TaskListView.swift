import SwiftUI
import VaultTasksCore

/// The active space's tasks (§9.2). Everything shown is derived from the repository snapshot and the clock; the view
/// owns only presentation state (selection, the collapsed Done group, tasks lingering after completion).
struct TaskListView: View {
    @Environment(AppContainer.self) private var app
    let space: Space

    @State private var selection: String?
    @State private var doneExpanded = false
    @State private var lingering: [String: Linger] = [:]
    @State private var lingerStamp = 0
    @State private var showCategoryOrder = false

    /// A just-completed (non-recurring) task keeps its old place, struck through, for `lingerSeconds` (Android `LINGER_MS`).
    struct Linger { var sectionKey: String; var title: String; var sectionIndex: Int; var index: Int; var stamp: Int }
    private let lingerSeconds = 2.0

    struct Model {
        var sections: [TaskSection]
        var done: [VaultTask]
        var keys: TaskKeys
        var byKey: [String: VaultTask]
        var late: Set<TaskId>
        var multiFile: Bool
        var isEmpty: Bool
    }

    struct Item: Identifiable { let key: String; let task: VaultTask; var id: String { key } }

    private func makeModel(now: Date) -> Model {
        let tasks = app.tasks(in: space)
        let g = TaskGrouping.group(tasks, now: LocalDateTime(now), fileOrder: space.files)
        let all = g.overdue + g.today + g.upcoming + g.noDate + g.done
        let keys = TaskKeys(all)
        let byCat = app.groupByCategory(space.id)
        var sections = TaskSections.build(g, byCategory: byCat, keys: keys, order: app.taskOrder(space.id, byCategory: byCat),
                                          categoryOrder: app.categoryOrder(space.id))
        var byKey: [String: VaultTask] = [:]
        for t in all { byKey[keys[t]] = t }

        var done = g.done
        for (key, l) in lingering.sorted(by: { $0.value.stamp < $1.value.stamp }) {
            guard let t = byKey[key], t.state == .done else { continue }
            done.removeAll { keys[$0] == key }
            if let i = sections.firstIndex(where: { $0.key == l.sectionKey }) {
                sections[i].tasks.insert(t, at: min(l.index, sections[i].tasks.count))
            } else {
                sections.insert(TaskSection(key: l.sectionKey, title: l.title, tasks: [t]), at: min(l.sectionIndex, sections.count))
            }
        }
        return Model(sections: sections, done: done, keys: keys, byKey: byKey, late: Set(g.overdue.map(\.id)),
                     multiFile: space.files.count > 1, isEmpty: sections.isEmpty && done.isEmpty)
    }

    var body: some View {
        TimelineView(.everyMinute) { ctx in
            let m = makeModel(now: ctx.date)
            Group {
                if m.isEmpty {
                    ContentUnavailableView("All clear", systemImage: "checkmark.circle", description: Text("No tasks in \(space.name)."))
                } else {
                    list(m)
                }
            }
            .animation(Motion.spring(0.4, bounce: 0.15), value: m.sections.map { $0.tasks.map { m.keys[$0] } })
        }
        .id(space.id) // switching spaces starts a fresh list scrolled to the top
        .onChange(of: space.id) { selection = nil; lingering = [:]; doneExpanded = false }
        .toolbar {
            ToolbarItemGroup {
                Toggle(isOn: Binding(get: { app.groupByCategory(space.id) }, set: { app.setGroupByCategory(space.id, $0) })) {
                    Label("Group by category", systemImage: "tag")
                }
                .help("Group by category")
                if app.groupByCategory(space.id) {
                    Button { showCategoryOrder = true } label: { Label("Reorder categories", systemImage: "arrow.up.arrow.down") }
                        .help("Reorder categories")
                }
                Button { app.openCreate() } label: { Label("New task", systemImage: "plus") }
                    .help("New task (⌘N)")
            }
        }
        .sheet(isPresented: $showCategoryOrder) {
            CategoryOrderSheet(initial: TaskSections.categoryNames(makeModel(now: Date()).sections)) { app.setCategoryOrder(space.id, $0) }
        }
    }

    // ---- list ----------------------------------------------------------------------------------------------

    @ViewBuilder private func list(_ m: Model) -> some View {
        List(selection: $selection) {
            ForEach(m.sections) { section in
                SwiftUI.Section {
                    let items = section.tasks.map { Item(key: m.keys[$0], task: $0) }
                    ForEach(items) { item in
                        row(item, section: section, m)
                    }
                    .onMove { from, to in
                        var keys = items.map(\.key)
                        keys.move(fromOffsets: from, toOffset: to)
                        app.reorderTasks(space: space, sectionKeys: keys)
                    }
                } header: {
                    header(section, m)
                }
            }
            if !m.done.isEmpty {
                SwiftUI.Section {
                    if doneExpanded {
                        ForEach(m.done.map { Item(key: m.keys[$0], task: $0) }) { item in row(item, section: nil, m) }
                    }
                } header: {
                    Button {
                        withAnimation(Motion.spring(0.35)) { doneExpanded.toggle() }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "chevron.right").rotationEffect(.degrees(doneExpanded ? 90 : 0)).font(.caption.bold())
                            Text("Done").font(.headline)
                            CountPill(count: m.done.count)
                        }
                        .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .onKeyPress(.return) {
            guard let sel = selection, let t = m.byKey[sel] else { return .ignored }
            app.openEdit(t); return .handled
        }
        .onKeyPress(.space) {
            guard let sel = selection, let t = m.byKey[sel], t.completable else { return .ignored }
            toggle(t, m); return .handled
        }
        .onDeleteCommand {
            guard let sel = selection, let t = m.byKey[sel] else { return }
            app.delete(t)
        }
    }

    private func header(_ s: TaskSection, _ m: Model) -> some View {
        let name = s.key.hasPrefix("cat:") && s.key != "cat:" ? String(s.key.dropFirst(4)) : nil
        let tint: Color = s.key == "overdue" ? .red : (name.flatMap { app.categoryColor($0) }.map { Color(argb: $0) } ?? .primary)
        return HStack(spacing: 6) {
            Text(s.title).font(.headline).foregroundStyle(tint)
            CountPill(count: s.tasks.count, tint: tint == .primary ? .secondary : tint)
        }
    }

    private func row(_ item: Item, section: TaskSection?, _ m: Model) -> some View {
        let t = item.task
        return TaskRow(task: t, showFile: m.multiFile, late: m.late.contains(t.id),
                       categoryColor: t.category.flatMap { app.categoryColor($0) }) { toggle(t, m) }
            .tag(item.key)
            .onTapGesture(count: 2) { app.openEdit(t) }
            .swipeActions(edge: .leading) {
                if t.completable {
                    Button { toggle(t, m) } label: { Label(t.state == .done ? "Reopen" : "Complete", systemImage: "checkmark") }.tint(.green)
                }
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button(role: .destructive) { app.delete(t) } label: { Label("Delete", systemImage: "trash") }
                if t.state != .done {
                    Button { app.moveToTomorrow(t) } label: { Label("Tomorrow", systemImage: "arrow.turn.up.right") }.tint(.orange)
                }
            }
            .contextMenu {
                if t.completable {
                    Button { toggle(t, m) } label: { Label(t.state == .done ? "Reopen" : "Complete", systemImage: "checkmark.circle") }
                }
                Button { app.openEdit(t) } label: { Label("Edit…", systemImage: "pencil") }
                if t.state != .done {
                    Button { app.moveToTomorrow(t) } label: { Label("Move to tomorrow", systemImage: "arrow.turn.up.right") }
                }
                Divider()
                Button(role: .destructive) { app.delete(t) } label: { Label("Delete", systemImage: "trash") }
            }
    }

    private func toggle(_ t: VaultTask, _ m: Model) {
        let key = m.keys[t]
        if t.state == .open, !t.isRecurring,
           let (si, s) = m.sections.enumerated().first(where: { $0.element.tasks.contains { m.keys[$0] == key } }),
           let idx = s.tasks.firstIndex(where: { m.keys[$0] == key }) {
            lingerStamp += 1
            let stamp = lingerStamp
            lingering[key] = Linger(sectionKey: s.key, title: s.title, sectionIndex: si, index: idx, stamp: stamp)
            Task {
                try? await Task.sleep(for: .seconds(lingerSeconds))
                if lingering[key]?.stamp == stamp { withAnimation(Motion.spring(0.4)) { lingering[key] = nil } }
            }
        } else if t.state == .done {
            lingering[key] = nil
        }
        app.toggle(t)
    }
}

struct TaskRow: View {
    let task: VaultTask
    let showFile: Bool
    let late: Bool
    let categoryColor: UInt32?
    /// In the calendar's day panel the date is already known: show only the time of day.
    var timeOnly = false
    let onToggle: () -> Void

    var body: some View {
        let (title, category) = Categories.split(task.description)
        let done = task.state == .done
        HStack(alignment: .top, spacing: 10) {
            Button(action: onToggle) {
                Image(systemName: done ? "checkmark.circle.fill" : "circle")
                    .imageScale(.large)
                    .foregroundStyle(done ? Color.accentColor : .secondary)
                    .contentTransition(.symbolEffect(.replace))
            }
            .buttonStyle(.plain)
            .disabled(!task.completable)
            .help(task.completable ? "" : "Complete in Obsidian")

            VStack(alignment: .leading, spacing: 2) {
                Text(title).strikethrough(done).foregroundStyle(done ? .secondary : .primary)
                if let first = task.notes.split(separator: "\n", omittingEmptySubsequences: true).first {
                    Text(first).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                HStack(spacing: 10) {
                    if let d = task.dueDate {
                        Label(timeOnly ? (task.dueTime.map(DateText.time) ?? "All day") : DateText.due(d, task.dueTime),
                              systemImage: timeOnly ? "clock" : "calendar")
                            .foregroundStyle(late && !done ? Color.red : .secondary)
                    }
                    if let rule = task.recurrence { Label(Recurrences.label(rule), systemImage: "repeat") }
                    if let p = task.priority, p != .medium { Image(systemName: priorityIcon(p)) }
                    if showFile { Text(fileName).lineLimit(1) }
                }
                .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 8)
            if let category { CategoryChip(name: category, argb: categoryColor) }
        }
        .padding(.vertical, 2)
        .opacity(done ? 0.75 : 1)
    }

    private var fileName: String { String(task.id.path.split(separator: "/").last ?? "").replacingOccurrences(of: ".md", with: "") }

    private func priorityIcon(_ p: Priority) -> String {
        switch p {
        case .highest: "exclamationmark.3"
        case .high: "exclamationmark.2"
        case .medium: "exclamationmark"
        case .low: "arrow.down"
        case .lowest: "arrow.down.to.line"
        }
    }
}

/// Manual category order for the active space (§9.2). Offered only while grouped by category.
struct CategoryOrderSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var names: [String]
    let onSave: ([String]) -> Void

    init(initial: [String], onSave: @escaping ([String]) -> Void) {
        _names = State(initialValue: initial)
        self.onSave = onSave
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Reorder categories").font(.title3.bold())
            Text("Drag to change the order of the category sections.").font(.callout).foregroundStyle(.secondary)
            List {
                ForEach(names, id: \.self) { Label("#\($0)", systemImage: "line.3.horizontal") }
                    .onMove { names.move(fromOffsets: $0, toOffset: $1) }
            }
            .frame(height: 260)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Save") { onSave(names); dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 360)
    }
}
