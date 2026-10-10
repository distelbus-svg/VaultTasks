import SwiftUI
import VaultTasksCore

// ---- display text (locale-aware; the domain values stay zone-less y-m-d) --------------------------------------

extension DateText {
    private static func noon(_ d: LocalDate) -> Date? { LocalDateTime(d, LocalTime(hour: 12, minute: 0)!).instant() }

    static func monthTitle(_ m: YearMonth) -> String {
        noon(m.atDay(1))?.formatted(.dateTime.month(.wide).year()) ?? "\(m.year)-\(m.month)"
    }

    static func dayHeader(_ d: LocalDate) -> String {
        noon(d)?.formatted(.dateTime.weekday(.wide).day().month(.wide)) ?? d.description
    }
}

/// Month view (§9.4) for the active space: a grid with a count marker per day, and the selected day's tasks in a side
/// panel (a popover while the window is narrow). Everything shown derives from the repository snapshot; the view owns
/// only presentation state (the popover day and the slide direction).
struct CalendarView: View {
    @Environment(AppContainer.self) private var app
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let space: Space

    @State private var forward = true
    @State private var popoverDay: LocalDate?

    private let panelWidth: CGFloat = 320
    private let wideThreshold: CGFloat = 820

    private var firstDay: DayOfWeek { TaskCalendar.firstDayOfWeek(calendarFirstWeekday: Calendar.current.firstWeekday) }

    var body: some View {
        TimelineView(.everyMinute) { ctx in
            GeometryReader { geo in
                let wide = geo.size.width >= wideThreshold
                let today = LocalDate.today(now: ctx.date)
                let byDay = TaskCalendar.byDay(app.tasks(in: space), fileOrder: space.files)
                let keys = TaskKeys(Array(byDay.values.joined()))
                let panel = { (day: LocalDate) in
                    DayPanel(space: space, date: day, today: today, tasks: byDay[day] ?? [], keys: keys)
                }
                HStack(spacing: 0) {
                    VStack(spacing: 0) {
                        header
                        weekdayRow
                        monthGrid(byDay: byDay, today: today, wide: wide, panel: panel)
                    }
                    .padding(.horizontal, 16).padding(.bottom, 16)
                    .background(ScrollSwipeCatcher { go($0) })
                    if wide {
                        panel(app.calendarDay).frame(width: panelWidth)
                            .glassEffect(.regular, in: .rect(cornerRadius: 22))
                            .padding([.vertical, .trailing], 12)
                    }
                }
                .onChange(of: wide) { popoverDay = nil }
            }
        }
        .toolbar {
            ToolbarItemGroup {
                if app.spaces.spaces.count > 1 {
                    Menu {
                        ForEach(app.spaces.spaces, id: \.id) { s in
                            Button { app.selectCalendarSpace(s.id) } label: {
                                if s.id == space.id { Label(s.name, systemImage: "checkmark") } else { Text(s.name) }
                            }
                        }
                    } label: { Label(space.name, systemImage: "square.stack") }
                    .help("Space shown in the calendar")
                }
                Button { app.openCreate(date: app.calendarDay) } label: { Label("New task", systemImage: "plus") }
                    .help("New task on the selected day (⌘N)")
            }
        }
    }

    // ---- header and navigation -----------------------------------------------------------------------------

    private var header: some View {
        HStack(spacing: 8) {
            Text(DateText.monthTitle(app.calendarMonth))
                .font(.largeTitle.bold())
                .id(app.calendarMonth)
                .transition(.opacity)
            Spacer()
            Button { go(-1) } label: { Image(systemName: "chevron.left") }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .help("Previous month (⌘←)")
            Button("Today") { goToday() }
                .help("Jump to today")
            Button { go(1) } label: { Image(systemName: "chevron.right") }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .help("Next month (⌘→)")
        }
        .padding(.top, 14).padding(.bottom, 10)
    }

    private var weekdayRow: some View {
        let cal = Calendar.current
        let symbols = cal.shortStandaloneWeekdaySymbols // index 0 = Sunday
        return HStack(spacing: 4) {
            ForEach(0..<7, id: \.self) { i in
                Text(symbols[(cal.firstWeekday - 1 + i) % 7])
                    .font(.caption.weight(.medium)).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.bottom, 6)
    }

    private var slide: Animation? { reduceMotion ? nil : .spring(duration: 0.45, bounce: 0.1) }

    private func go(_ delta: Int) {
        forward = delta > 0
        withAnimation(slide) { app.calendarMonth = app.calendarMonth.plusMonths(delta) }
    }

    private func goToday() {
        let today = LocalDate.today()
        let target = YearMonth(today)
        forward = target > app.calendarMonth
        withAnimation(slide) { app.calendarMonth = target; app.calendarDay = today }
    }

    private func select(_ d: LocalDate, wide: Bool) {
        let month = YearMonth(d)
        if month != app.calendarMonth { forward = month > app.calendarMonth }
        withAnimation(slide) { app.calendarDay = d; app.calendarMonth = month }
        if !wide { popoverDay = d }
    }

    // ---- grid ------------------------------------------------------------------------------------------------

    private func monthGrid(byDay: [LocalDate: [VaultTask]], today: LocalDate, wide: Bool,
                           panel: @escaping (LocalDate) -> DayPanel) -> some View {
        let month = app.calendarMonth
        let weeks = TaskCalendar.gridDays(month, firstDayOfWeek: firstDay).chunked(7)
        return ZStack {
            VStack(spacing: 4) {
                ForEach(weeks.indices, id: \.self) { w in
                    HStack(spacing: 4) {
                        ForEach(weeks[w], id: \.self) { date in
                            DayCell(date: date, inMonth: YearMonth(date) == month, today: today, selected: date == app.calendarDay,
                                    summary: TaskCalendar.summary(byDay[date] ?? [], on: date, today: today),
                                    reduceMotion: reduceMotion) { select(date, wide: wide) }
                                .popover(isPresented: Binding(
                                    get: { popoverDay == date },
                                    set: { if !$0, popoverDay == date { popoverDay = nil } }
                                ), arrowEdge: .bottom) {
                                    panel(date).frame(width: panelWidth, height: 400)
                                }
                        }
                    }
                }
            }
            .id(month)
            .transition(reduceMotion ? .opacity : .push(from: forward ? .trailing : .leading))
        }
        .clipped()
    }
}

private extension Array {
    func chunked(_ n: Int) -> [[Element]] {
        stride(from: 0, to: count, by: n).map { Array(self[$0..<Swift.min($0 + n, count)]) }
    }
}

/// One day. Selection morphs the rounded square toward a pill with a spring; up to three dots, a count beyond that.
private struct DayCell: View {
    let date: LocalDate
    let inMonth: Bool
    let today: LocalDate
    let selected: Bool
    let summary: TaskCalendar.DaySummary
    let reduceMotion: Bool
    let action: () -> Void

    var body: some View {
        let isToday = date == today
        let radius: CGFloat = selected ? 22 : 12
        Button(action: action) {
            VStack(spacing: 5) {
                Text("\(date.day)")
                    .font(.title3.weight(isToday || selected ? .semibold : .regular))
                    .foregroundStyle(numberColor(isToday))
                marker
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(RoundedRectangle(cornerRadius: radius, style: .continuous)
                .fill(selected ? Color.accentColor : Color.secondary.opacity(inMonth ? 0.09 : 0.035)))
            .overlay {
                if isToday && !selected {
                    RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Color.accentColor, lineWidth: 1.5)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .animation(reduceMotion ? nil : .spring(duration: 0.35, bounce: 0.25), value: selected)
        .accessibilityLabel(Text(label))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func numberColor(_ isToday: Bool) -> Color {
        if selected { return .white }
        if isToday { return .accentColor }
        return inMonth ? .primary : .secondary.opacity(0.55)
    }

    private var markerColor: Color {
        if selected { return .white }
        if summary.overdue { return .red }
        return summary.open > 0 ? .accentColor : .secondary
    }

    @ViewBuilder private var marker: some View {
        if summary.total > 3 {
            Text("\(summary.total)").font(.caption2.monospacedDigit().weight(.semibold)).foregroundStyle(markerColor)
                .frame(height: 7)
        } else {
            HStack(spacing: 3) {
                ForEach(0..<summary.total, id: \.self) { _ in Circle().fill(markerColor).frame(width: 5, height: 5) }
            }
            .frame(height: 7)
        }
    }

    private var label: String {
        let day = DateText.date(date)
        switch (summary.open, summary.done) {
        case (0, 0): return day
        case (let o, 0): return "\(day), \(o) open"
        case (0, let d): return "\(day), \(d) done"
        case (let o, let d): return "\(day), \(o) open, \(d) done"
        }
    }
}

/// The selected day's tasks (`TaskCalendar.byDay` order: open before done, then time, untimed last) and a New Task
/// action with the date prefilled. Read from the snapshot, so toggling a task re-sorts it in place.
struct DayPanel: View {
    @Environment(AppContainer.self) private var app
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let space: Space
    let date: LocalDate
    let today: LocalDate
    let tasks: [VaultTask]
    let keys: TaskKeys

    private struct Item: Identifiable { let key: String; let task: VaultTask; var id: String { key } }

    var body: some View {
        let s = TaskCalendar.summary(tasks, on: date, today: today)
        let items = tasks.map { Item(key: keys[$0], task: $0) }
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                Text(DateText.dayHeader(date)).font(.title3.weight(.semibold))
                Text(tasks.isEmpty ? "Nothing planned" : "\(s.open) open · \(s.done) done")
                    .font(.callout).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 10)

            if items.isEmpty {
                Spacer(minLength: 0)
            } else {
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(items) { item in card(item.task) }
                    }
                    .padding(.horizontal, 12).padding(.bottom, 8)
                    .animation(reduceMotion ? nil : .spring(duration: 0.4, bounce: 0.15), value: items.map(\.key))
                }
            }

            Button { app.openCreate(date: date) } label: {
                Label("New task", systemImage: "plus").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            .padding(12)
        }
        .frame(maxHeight: .infinity, alignment: .top)
    }

    private func card(_ t: VaultTask) -> some View {
        let overdue = date < today && t.state == .open
        return TaskRow(task: t, showFile: space.files.count > 1, late: overdue,
                       categoryColor: t.category.flatMap { app.categoryColor($0) }, timeOnly: true) { app.toggle(t) }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background((overdue ? Color.red.opacity(0.10) : Color.secondary.opacity(0.09)),
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .onTapGesture { app.openEdit(t) }
            .contextMenu {
                if t.completable {
                    Button { app.toggle(t) } label: { Label(t.state == .done ? "Reopen" : "Complete", systemImage: "checkmark.circle") }
                }
                Button { app.openEdit(t) } label: { Label("Edit…", systemImage: "pencil") }
                if t.state != .done {
                    Button { app.moveToTomorrow(t) } label: { Label("Move to tomorrow", systemImage: "arrow.turn.up.right") }
                }
                Divider()
                Button(role: .destructive) { app.delete(t) } label: { Label("Delete", systemImage: "trash") }
            }
    }
}
