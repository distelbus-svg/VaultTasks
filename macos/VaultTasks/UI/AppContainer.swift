import SwiftUI
import AppKit
import Observation
import UserNotifications
import VaultTasksCore

/// What the editor sheet was opened for. `presetDate` is "move to tomorrow": the date is preset, recurrence and notes
/// stay untouched.
struct EditorRequest: Identifiable {
    let id = UUID()
    let spaceId: String
    let task: VaultTask?          // nil = create
    var createDate: LocalDate? = nil
    var presetDate: LocalDate? = nil
    var leadMinutes = 0
}

struct Toast: Identifiable {
    let id = UUID()
    let text: String
    var actionLabel: String? = nil
    var action: (@MainActor () -> Void)? = nil
}

/// Hand-written DI root (§4). Owns the repository and mirrors its snapshots for the UI.
@MainActor @Observable
final class AppContainer {
    private(set) var state = RepoState()
    private(set) var spaces: SpacesData
    private(set) var prefs: ViewPrefs
    var editor: EditorRequest?
    /// Sidebar destination: the Calendar instead of the active space's list. Month and day live here (not in the view)
    /// so they survive switching to a space and back; they are not persisted across launches.
    var showCalendar = false
    var calendarMonth = YearMonth(LocalDate.today())
    var calendarDay = LocalDate.today()
    private(set) var saving = false
    private(set) var toast: Toast?
    private(set) var lastRemoved: RemovedLine?

    @ObservationIgnored let settings = SettingsStore()
    @ObservationIgnored let leadStore = LeadStore()
    @ObservationIgnored let repository: VaultRepository
    @ObservationIgnored let sync: SyncController
    let reminders = ReminderCoordinator()
    let loginItem = LoginItem()
    /// Bumped every minute so the menu bar count follows the clock (today rolls over at midnight).
    private(set) var minuteTick = Date()
    @ObservationIgnored private var notificationDelegate: NotificationDelegate?
    @ObservationIgnored var openWindow: (@MainActor () -> Void)?
    @ObservationIgnored private var firstReconcile = true
    @ObservationIgnored private let spacesFile = JSONFile<SpacesData>("spaces_v1.json")
    @ObservationIgnored private let prefsFile = JSONFile<ViewPrefs>("view-prefs-v1.json")
    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored private var started = false
    @ObservationIgnored private var closeSyncTask: Task<Void, Never>?
    @ObservationIgnored private var dailySync: NSBackgroundActivityScheduler?

    init() {
        let access = VaultAccess(store: settings, scoped: SecurityScopedBookmarks())
        repository = VaultRepository(access: access, settings: settings, cache: VaultCache())
        sync = SyncController(repository: repository)
        spaces = spacesFile.load() ?? SpacesData()
        prefs = prefsFile.load() ?? ViewPrefs()
        let delegate = NotificationDelegate { [weak self] action, key, path, desc in
            await self?.handleNotification(action: action, key: key, path: path, desc: desc)
        }
        notificationDelegate = delegate
        UNUserNotificationCenter.current().delegate = delegate
        NotifCenter.registerCategory()
        sync.assignedFiles = { [weak self] in self?.spaces.assignedFiles ?? [] }
        sync.announce = { [weak self] in self?.say($0) }
    }

    func start() {
        guard !started else { return }
        started = true
        let repo = repository
        let scope = spaces.assignedFiles
        Task { for await s in repo.states { self.state = s; self.reconcileReminders() } }
        Task { await reminders.requestAuthorization() }
        Task { await repo.setScope(scope); await repo.start(); await self.sync.autoSync(minGap: 60) }
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { await repo.rescan(); await self?.reminders.recordDelivered() }
            Task { @MainActor in self?.cancelCloseSync() }
            Task { await self?.sync.autoSync(minGap: 60) } // automatic sync trigger: app opened (throttled, silent)
        }
        NotificationCenter.default.addObserver(forName: NSApplication.didResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.scheduleCloseSync() }
        }
        startDailySync()
        // The Mac's equivalent of the Android health check: re-set every alarm after wake, clock or zone changes.
        let force: @Sendable (Notification) -> Void = { [weak self] _ in Task { @MainActor in self?.reconcileReminders(force: true) } }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main, using: force)
        NotificationCenter.default.addObserver(forName: .NSSystemClockDidChange, object: nil, queue: .main, using: force)
        NotificationCenter.default.addObserver(forName: NSNotification.Name.NSSystemTimeZoneDidChange, object: nil, queue: .main, using: force)
        // Top-up of the pending window while the app keeps running.
        Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in Task { @MainActor in self?.reconcileReminders() } }
        Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in Task { @MainActor in self?.minuteTick = Date() } }
        NotificationCenter.default.addObserver(forName: NSApplication.willTerminateNotification, object: nil, queue: .main) { _ in
            Task { await repo.shutdown() }
        }
    }

    /// ⌘R: rescan, plus sync if a server is set.
    func refresh() { sync.refresh() }

    // ---- automatic sync (§8) -----------------------------------------------------------------------------------
    // File-watcher events only rescan, they never sync. macOS cannot sync while the app is not running.

    /// Automatic sync trigger: ~5 s after the app resigns active, debounced (coming back cancels it).
    private func scheduleCloseSync() {
        closeSyncTask?.cancel()
        guard sync.hasServer else { return }
        closeSyncTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            await self?.sync.autoSync()
        }
    }

    private func cancelCloseSync() { closeSyncTask?.cancel(); closeSyncTask = nil }

    /// Automatic sync trigger: daily while the app is running.
    private func startDailySync() {
        let scheduler = NSBackgroundActivityScheduler(identifier: "app.vaulttasks.mac.daily-sync")
        scheduler.repeats = true
        scheduler.interval = 24 * 3600
        scheduler.tolerance = 3600
        scheduler.qualityOfService = .utility
        scheduler.schedule { [weak self] completion in
            Task { @MainActor in
                await self?.sync.autoSync()
                completion(.finished)
            }
        }
        dailySync = scheduler
    }

    // ---- reminders ---------------------------------------------------------------------------------------

    var defaultReminderTime: LocalTime {
        get { settings.defaultReminderTime }
        set { settings.defaultReminderTime = newValue; reconcileReminders() }
    }

    /// The one scheduling path: after every scan/write, space change, lead change or time-setting change.
    func reconcileReminders(force: Bool = false) {
        let doForce = force || firstReconcile
        let snapshot = state
        guard snapshot.phase == .ready, !snapshot.fromCache, snapshot.scanError == nil else { return }
        firstReconcile = false
        Task { await reminders.reconcile(state: snapshot, spaces: spaces, leads: leadStore, defaultTime: settings.defaultReminderTime, force: doForce) }
    }

    func rescheduleAll() { reconcileReminders(force: true) }

    /// Notification click or action. Runs headless for "Done" (the app may have been launched just for it).
    func handleNotification(action: String, key: String, path: String, desc: String) async {
        start()
        for _ in 0..<100 {
            let s = await repository.currentState()
            if s.phase == .ready, !s.fromCache, s.lastScan != nil { break }
            try? await Task.sleep(for: .milliseconds(100))
        }
        let tasks = await repository.currentState().tasks
        guard let task = tasks.first(where: { AlarmPlanner.keyOf($0.id) == key })
            ?? tasks.first(where: { $0.id.path == path && $0.description == desc && $0.state == .open }) else { return }
        switch action {
        case NotifID.done:
            report(await repository.setDone(task, done: true))
        case NotifID.tomorrow:
            surface(path: path); moveToTomorrow(task)
        case UNNotificationDefaultActionIdentifier:
            surface(path: path); openEdit(task)
        default: break
        }
    }

    private func surface(path: String) {
        if let active = spaces.active, active.files.contains(path) {} else if let s = spaces.spaces.first(where: { $0.files.contains(path) }) { selectSpace(s.id) }
        showMainWindow()
    }

    func showMainWindow() {
        NSApp.activate()
        if !NSApp.windows.contains(where: { $0.isVisible && $0.canBecomeMain }) { openWindow?() }
    }

    // ---- menu bar (§9.5) -----------------------------------------------------------------------------------

    /// Open overdue and due-today tasks across every space's files (the same scope reminders use).
    var menuBarCounts: (overdue: Int, today: Int) {
        _ = minuteTick
        let files = spaces.assignedFiles
        let paths = Set(files)
        let g = TaskGrouping.group(state.tasks.filter { paths.contains($0.id.path) }, now: LocalDateTime(Date()), fileOrder: files)
        return (g.overdue.count, g.today.count)
    }

    /// Quick add from the menu bar: one undated (or due-today) task appended to the active space's creation file.
    /// Returns an error message, nil on success.
    func quickAdd(_ title: String, today: Bool) async -> String? {
        guard state.phase == .ready else { return "The vault is not available yet." }
        guard let space = spaces.active, let path = space.creationFile else { return "Create a space with a vault file first." }
        let text = title.replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return nil }
        switch await repository.create(path: path, fields: TaskFields(description: text, dueDate: today ? LocalDate.today() : nil, dueTime: nil)) {
        case .done, .removed: return nil
        case .stale: return "The file changed on disk, please retry."
        case .failed(let m): return "Could not save: \(m)"
        }
    }
    func pickVault(_ url: URL) { Task { await repository.pickVault(url) } }

    var globalFilter: String {
        get { settings.globalFilter }
        set {
            guard newValue != settings.globalFilter else { return }
            settings.globalFilter = newValue
            Task { await repository.globalFilterChanged() }
        }
    }

    // ---- spaces ------------------------------------------------------------------------------------------

    private func applySpaces(_ f: (SpacesData) -> SpacesData) {
        let next = f(spaces)
        guard next != spaces else { return }
        spaces = next
        spacesFile.save(next)
        let scope = next.assignedFiles
        Task { await repository.setScope(scope) }
        reconcileReminders()
    }

    func selectSpace(_ id: String) {
        showCalendar = false
        applySpaces { $0.setActive(id: id) }
    }

    func openCalendar() { showCalendar = true }

    /// Switches the space the calendar shows without leaving the calendar.
    func selectCalendarSpace(_ id: String) { applySpaces { $0.setActive(id: id) } }

    func addSpace(name: String, files: [String], defaultFile: String?) {
        let id = UUID().uuidString
        applySpaces { $0.add(id: id, name: name).setFiles(id: id, files: files).setDefault(id: id, file: defaultFile) }
    }

    func saveSpace(id: String, name: String, files: [String], defaultFile: String?) {
        applySpaces { $0.rename(id: id, name: name).setFiles(id: id, files: files).setDefault(id: id, file: defaultFile) }
    }

    /// Deleting a space never touches vault files.
    func deleteSpace(_ id: String) { applySpaces { $0.delete(id: id) } }

    /// `List.onMove` semantics: `destination` is an insertion index before removal.
    func moveSpace(from source: IndexSet, to destination: Int) {
        guard let from = source.first, spaces.spaces.indices.contains(from) else { return }
        let to = destination > from ? destination - 1 : destination
        let id = spaces.spaces[from].id
        applySpaces { $0.move(id: id, delta: to - from) }
    }

    func moveSpace(_ id: String, delta: Int) { applySpaces { $0.move(id: id, delta: delta) } }

    func tasks(in space: Space) -> [VaultTask] {
        let paths = Set(space.files)
        return state.tasks.filter { paths.contains($0.id.path) }
    }

    /// Cancelled tasks are hidden, so they take no part in list keys.
    private func visible(_ tasks: [VaultTask]) -> [VaultTask] { tasks.filter { $0.state != .cancelled } }

    // ---- list preferences (per space, remembered) -----------------------------------------------------------

    private func savePrefs() { prefsFile.save(prefs) }

    static func orderKey(_ spaceId: String, byCategory: Bool) -> String { "\(spaceId)|\(byCategory ? "cat" : "date")" }

    func groupByCategory(_ spaceId: String) -> Bool { prefs.groupByCategory[spaceId] == true }
    func taskOrder(_ spaceId: String, byCategory: Bool) -> [String] { prefs.taskOrders[Self.orderKey(spaceId, byCategory: byCategory)] ?? [] }
    func categoryOrder(_ spaceId: String) -> [String] { prefs.categoryOrders[spaceId] ?? [] }
    func categoryColor(_ name: String) -> UInt32? { prefs.categoryColors[name] }

    func setGroupByCategory(_ spaceId: String, _ on: Bool) { prefs.groupByCategory[spaceId] = on; savePrefs() }

    func setCategoryOrder(_ spaceId: String, _ order: [String]) {
        prefs.categoryOrders[spaceId] = order.isEmpty ? nil : order
        savePrefs()
    }

    func setCategoryColor(_ name: String, _ argb: UInt32) { prefs.categoryColors[name] = argb; savePrefs() }

    /// The user dragged tasks into a new order inside one section; `sectionKeys` are that section's keys, top to bottom.
    func reorderTasks(space: Space, sectionKeys: [String]) {
        let key = Self.orderKey(space.id, byCategory: groupByCategory(space.id))
        let all = visible(tasks(in: space))
        let keys = TaskKeys(all)
        prefs.taskOrders[key] = TaskSections.reordered(existing: prefs.taskOrders[key] ?? [], sectionKeys: sectionKeys,
                                                       alive: Set(all.map { keys[$0] }))
        savePrefs()
    }

    // ---- editor ------------------------------------------------------------------------------------------

    /// A calendar day preselects the date: explicitly from the day panel, implicitly for ⌘N while the calendar is shown.
    func openCreate(date: LocalDate? = nil) {
        guard let s = spaces.active else { return }
        editor = EditorRequest(spaceId: s.id, task: nil, createDate: date ?? (showCalendar ? calendarDay : nil))
    }

    func openEdit(_ task: VaultTask, presetDate: LocalDate? = nil) {
        guard let s = spaces.active else { return }
        editor = EditorRequest(spaceId: s.id, task: task, presetDate: presetDate,
                               leadMinutes: leadStore.get(path: task.id.path, description: task.description))
    }

    /// Opens the editor with the date preset to tomorrow; time, repeat and notes are untouched until the user changes them.
    func moveToTomorrow(_ task: VaultTask) { openEdit(task, presetDate: LocalDate.today().plusDays(1)) }

    func closeEditor() { editor = nil; saving = false }

    func saveEditor(_ req: EditorRequest, fields: TaskFields, path: String, lead: Int) {
        guard !saving, let space = spaces.spaces.first(where: { $0.id == req.spaceId }) else { return }
        saving = true
        Task {
            let beforeTasks = visible(tasks(in: space))
            let beforeKeys = TaskKeys(beforeTasks)
            let beforeSet = Set(beforeTasks.map { beforeKeys[$0] })
            let oldKey = req.task.map { beforeKeys[$0] }
            let outcome: EditOutcome
            if let task = req.task {
                outcome = path == task.id.path
                    ? await repository.update(task, fields: fields)
                    : await repository.move(task, fields: fields, to: path)
            } else {
                outcome = await repository.create(path: path, fields: fields)
            }
            switch outcome {
            case .done, .removed:
                let afterTasks = visible(await taskSnapshot(space))
                let afterKeys = TaskKeys(afterTasks)
                carryOrder(spaceId: space.id, oldKey: oldKey, before: beforeSet, after: Set(afterTasks.map { afterKeys[$0] }))
                // "Remind me before" needs a due date to mean anything.
                leadStore.replace(oldPath: req.task?.id.path, oldDescription: req.task?.description, path: path,
                                  description: fields.description, minutes: fields.dueDate == nil ? 0 : lead)
                closeEditor()
                reconcileReminders()
            case .stale:
                closeEditor()
                say("Task changed on disk, please retry")
            case .failed(let m):
                saving = false
                say("Could not save: \(m)")
            }
        }
    }

    private func taskSnapshot(_ space: Space) async -> [VaultTask] {
        let paths = Set(space.files)
        return await repository.currentState().tasks.filter { paths.contains($0.id.path) }
    }

    /// An edit changes a task's key (its text); carry its manual position over to the new key.
    private func carryOrder(spaceId: String, oldKey: String?, before: Set<String>, after: Set<String>) {
        guard let oldKey, let new = TaskSections.carriedKey(oldKey: oldKey, before: before, after: after) else { return }
        for byCat in [false, true] {
            let key = Self.orderKey(spaceId, byCategory: byCat)
            guard let order = prefs.taskOrders[key], order.contains(oldKey) else { continue }
            prefs.taskOrders[key] = order.map { $0 == oldKey ? new : $0 }
        }
        savePrefs()
    }

    // ---- task actions ------------------------------------------------------------------------------------

    func toggle(_ task: VaultTask) {
        Task { report(await repository.setDone(task, done: task.state != .done)) }
    }

    func delete(_ task: VaultTask) {
        Task {
            let r = await repository.delete(task)
            if case .removed(let removed) = r {
                lastRemoved = removed
                show(Toast(text: "Task deleted", actionLabel: "Undo") { [weak self] in self?.undoDelete() })
            } else {
                report(r)
            }
        }
    }

    func undoDelete() {
        guard let removed = lastRemoved else { return }
        lastRemoved = nil
        if toast?.actionLabel == "Undo" { dismissToast() }
        Task { report(await repository.restore(removed)) }
    }

    /// ⌘Z: text undo while a text field is being edited, otherwise undo of the last delete.
    func undo() {
        if NSApp.keyWindow?.firstResponder is NSTextView {
            NSApp.sendAction(Selector(("undo:")), to: nil, from: nil)
        } else {
            undoDelete()
        }
    }

    private func report(_ o: EditOutcome) {
        switch o {
        case .done, .removed: break
        case .stale: say("Task changed on disk, please retry")
        case .failed(let m): say("Could not save: \(m)")
        }
    }

    // ---- toast -------------------------------------------------------------------------------------------

    func say(_ text: String) { show(Toast(text: text)) }

    private func show(_ t: Toast) {
        toastTask?.cancel()
        withAnimation(Motion.spring(0.35)) { toast = t }
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled else { return }
            self?.dismissToast()
        }
    }

    func dismissToast() {
        toastTask?.cancel()
        withAnimation(Motion.spring(0.3)) { toast = nil }
    }
}
