import SwiftUI
import VaultTasksCore

/// Create / edit sheet (§9.3). Local form state only; the container owns the write.
struct TaskEditorSheet: View {
    @Environment(AppContainer.self) private var app
    let request: EditorRequest

    private enum Repeat: Hashable { case never, daily, weekdays, weekly, monthly, yearly, custom, unsupported }
    private static let leads = [0, 5, 10, 15, 30, 60, 1440, 2880, 10080]

    @State private var title = ""
    @State private var notes = ""
    @State private var category: String?
    @State private var newCategories: [String] = []
    @State private var date: LocalDate?
    @State private var time: LocalTime?
    @State private var repeatChoice: Repeat = .never
    @State private var repeatTouched = false
    @State private var customInterval = 2
    @State private var customUnit: RepeatUnit = .week
    @State private var lead = 0
    @State private var path = ""
    @State private var showNewCategory = false
    @State private var loaded = false
    @FocusState private var titleFocused: Bool

    private var space: Space? { app.spaces.spaces.first { $0.id == request.spaceId } }
    private var isEdit: Bool { request.task != nil }

    private var categories: [String] {
        var names = Categories.of(space.map { app.tasks(in: $0) } ?? [])
        for n in newCategories where !names.contains(n) { names.append(n) }
        if let c = category, !names.contains(c) { names.append(c) }
        return names
    }

    private var cleanTitle: String {
        title.replacingOccurrences(of: "\r\n", with: " ").replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(isEdit ? "Edit task" : "New task").font(.title2.bold())

                    TextField("Title", text: $title)
                        .textFieldStyle(.roundedBorder)
                        .focused($titleFocused)
                        .onSubmit(save)

                    TextField("Notes", text: $notes, axis: .vertical)
                        .lineLimit(3...8)
                        .textFieldStyle(.roundedBorder)

                    field("Category") {
                        FlowLayout {
                            Chip(label: "None", selected: category == nil) { category = nil }
                            ForEach(categories, id: \.self) { c in
                                Chip(label: "#\(c)", selected: category == c, accent: app.categoryColor(c) ?? CategoryPalette.defaultSwatch) { category = c }
                            }
                            Chip(label: "+ New") { showNewCategory = true }
                        }
                    }

                    field("Due") {
                        HStack(spacing: 8) {
                            DatePicker("", selection: dateBinding({ date }, { date = $0 }), displayedComponents: .date)
                                .labelsHidden().opacity(date == nil ? 0.45 : 1)
                            if date == nil {
                                Button("Today") { withAnimation(Motion.spring(0.35)) { date = .today() } }
                                Button("Tomorrow") { withAnimation(Motion.spring(0.35)) { date = LocalDate.today().plusDays(1) } }
                            } else {
                                Button("Clear") { withAnimation(Motion.spring(0.35)) { date = nil; time = nil; setRepeat(.never) } }
                            }
                        }
                    }

                    if date != nil {
                        VStack(alignment: .leading, spacing: 16) {
                            field("Time") {
                                HStack(spacing: 8) {
                                    if time == nil {
                                        Button("Set time") { time = LocalTime(hour: 9, minute: 0) }
                                    } else {
                                        DatePicker("", selection: timeBinding({ time }, { time = $0 }), displayedComponents: .hourAndMinute).labelsHidden()
                                        Button("Clear") { time = nil }
                                    }
                                }
                            }
                            field("Repeat") { repeatControls }
                            field("Remind me") {
                                FlowLayout {
                                    ForEach(Self.leads, id: \.self) { m in
                                        Chip(label: DateText.lead(m), selected: lead == m) { lead = m }
                                    }
                                }
                            }
                        }
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    }

                    if let space, space.files.count > 1 {
                        field("File") {
                            Picker("", selection: $path) {
                                ForEach(space.files, id: \.self) { Text($0).tag($0) }
                            }
                            .labelsHidden().pickerStyle(.menu)
                        }
                    } else if let space, let only = space.files.first {
                        field("File") { Text(only).foregroundStyle(.secondary) }
                    }
                }
                .padding(20)
            }
            Divider()
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { app.closeEditor() }.keyboardShortcut(.cancelAction)
                Button(isEdit ? "Save" : "Add", action: save)
                    .keyboardShortcut(.defaultAction)
                    .disabled(cleanTitle.isEmpty || path.isEmpty || app.saving)
            }
            .padding(12)
        }
        .frame(width: 480, height: 600)
        .animation(Motion.spring(0.4, bounce: 0.1), value: date)
        .sheet(isPresented: $showNewCategory) {
            NewCategorySheet { name, argb in
                app.setCategoryColor(name, argb)
                if !newCategories.contains(name) { newCategories.append(name) }
                category = name
            }
        }
        .onAppear(perform: load)
    }

    @ViewBuilder private var repeatControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            FlowLayout {
                if repeatChoice == .unsupported, let rule = request.task?.recurrence {
                    Chip(label: Recurrences.label(rule), selected: true) {}
                }
                ForEach([(Repeat.never, "Never"), (.daily, "Daily"), (.weekdays, "Weekdays"), (.weekly, "Weekly"),
                         (.monthly, "Monthly"), (.yearly, "Yearly"), (.custom, "Custom…")], id: \.0) { c, label in
                    Chip(label: label, selected: repeatChoice == c) { setRepeat(c) }
                }
            }
            if repeatChoice == .custom {
                HStack {
                    Text("Every")
                    Stepper(value: Binding(get: { customInterval }, set: { customInterval = $0; repeatTouched = true }), in: 1...999) {
                        Text("\(customInterval)").monospacedDigit()
                    }
                    Picker("", selection: Binding(get: { customUnit }, set: { customUnit = $0; repeatTouched = true })) {
                        ForEach(RepeatUnit.allCases, id: \.self) { Text($0.word + (customInterval == 1 ? "" : "s")).tag($0) }
                    }
                    .labelsHidden().frame(width: 110)
                }
            }
        }
    }

    private func field<C: View>(_ label: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            content()
        }
    }

    private func setRepeat(_ c: Repeat) { repeatChoice = c; repeatTouched = true }

    // ---- load / save ---------------------------------------------------------------------------------------

    private func load() {
        guard !loaded else { return }
        loaded = true
        let defaultPath = space?.creationFile ?? space?.files.first ?? ""
        if let t = request.task {
            let (base, cat) = Categories.split(t.description)
            title = base; category = cat; notes = t.notes
            date = request.presetDate ?? t.dueDate
            time = t.dueTime
            lead = request.leadMinutes
            path = t.id.path
            if let rule = t.recurrence {
                if let r = Recurrences.parse(rule), !r.whenDone {
                    if r.weekdays != nil {
                        repeatChoice = rule.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "every weekday" ? .weekdays : .unsupported
                    } else if r.interval == 1 {
                        repeatChoice = [.day: Repeat.daily, .week: .weekly, .month: .monthly, .year: .yearly][r.unit] ?? .unsupported
                    } else {
                        repeatChoice = .custom; customInterval = r.interval; customUnit = r.unit
                    }
                } else {
                    repeatChoice = .unsupported
                }
            }
        } else {
            date = request.createDate
            path = defaultPath
        }
        titleFocused = true
    }

    private func recurrenceEdit() -> RecurrenceEdit {
        if date == nil { return request.task?.recurrence != nil ? .clear : .keep }
        guard repeatTouched else { return .keep }
        switch repeatChoice {
        case .never, .unsupported: return .clear
        case .daily: return .set(Recurrences.rule(interval: 1, unit: .day))
        case .weekdays: return .set("every weekday")
        case .weekly: return .set(Recurrences.rule(interval: 1, unit: .week))
        case .monthly: return .set(Recurrences.rule(interval: 1, unit: .month))
        case .yearly: return .set(Recurrences.rule(interval: 1, unit: .year))
        case .custom: return .set(Recurrences.rule(interval: customInterval, unit: customUnit))
        }
    }

    private func save() {
        guard !cleanTitle.isEmpty, !path.isEmpty, !app.saving else { return }
        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let notesField: String? = isEdit ? (notes == (request.task?.notes ?? "") ? nil : trimmedNotes) : (trimmedNotes.isEmpty ? nil : trimmedNotes)
        let fields = TaskFields(description: Categories.join(cleanTitle, category), dueDate: date, dueTime: date == nil ? nil : time,
                                notes: notesField, recurrence: recurrenceEdit())
        app.saveEditor(request, fields: fields, path: path, lead: date == nil ? 0 : lead)
    }
}

/// Name + the Android 10-swatch palette with a live preview chip.
struct NewCategorySheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var swatch = CategoryPalette.defaultSwatch
    let onCreate: (String, UInt32) -> Void

    private var cleaned: String? { Categories.clean(name) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("New category").font(.title3.bold())
            TextField("Name", text: $name, prompt: Text("#category")).textFieldStyle(.roundedBorder).onSubmit(create)
            HStack(spacing: 8) {
                ForEach(CategoryPalette.swatches, id: \.self) { c in
                    Circle().fill(Color(argb: c)).frame(width: 24, height: 24)
                        .overlay { if c == swatch { Image(systemName: "checkmark").font(.caption.bold()).foregroundStyle(CategoryPalette.textColor(on: c)) } }
                        .onTapGesture { withAnimation(Motion.spring(0.25)) { swatch = c } }
                }
            }
            CategoryChip(name: cleaned ?? "category", argb: swatch)
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Create", action: create).keyboardShortcut(.defaultAction).disabled(cleaned == nil)
            }
        }
        .padding(20).frame(width: 380)
    }

    private func create() {
        guard let c = cleaned else { return }
        onCreate(c, swatch); dismiss()
    }
}
