import Foundation
import UserNotifications
import AppKit
import Observation
import VaultTasksCore

enum NotifID {
    static let category = "vt.task"
    static let done = "vt.done"
    static let tomorrow = "vt.tomorrow"
    static let test = "vt.test"
    /// Pending-request cap we stay under (the system limit is 64 per app; verified by the Reminders screen's pending count).
    static let window = 60
}

// ---- platform seams -------------------------------------------------------------------------------------------

/// `UNUserNotificationCenter` backend (§7). Identifier = the alarm code, so re-adding replaces and never duplicates.
/// The trigger is wall-clock `DateComponents` without a zone, so the Mac's zone at fire time applies.
struct UNAlarmBackend: AlarmBackend {
    func set(_ spec: AlarmSpec) async throws {
        let content = UNMutableNotificationContent()
        content.title = Categories.split(spec.title).0
        var body = spec.dueDate.map { DateText.due($0, spec.dueTime) } ?? ""
        if !spec.spaceName.isEmpty { body += body.isEmpty ? spec.spaceName : " · \(spec.spaceName)" }
        content.body = body
        content.sound = .default
        content.categoryIdentifier = NotifID.category
        content.userInfo = [
            "key": AlarmPlanner.keyOf(spec.id), "path": spec.id.path, "desc": spec.title,
            "fireAt": spec.fireAt.instant()?.timeIntervalSince1970 ?? 0,
        ]
        var comps = spec.fireAt.dateComponents()
        comps.timeZone = nil
        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: String(spec.code), content: content, trigger: trigger))
    }

    func cancel(_ code: Int32) async {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [String(code)])
    }
}

struct JSONAlarmStore: AlarmStore {
    private let file = JSONFile<[AlarmSpec]>("alarms_v1.json")
    func load() -> [AlarmSpec] { file.load() ?? [] }
    func save(_ specs: [AlarmSpec]) { file.save(specs) }
}

struct DeliveredInfo: Sendable { let id: String; let key: String?; let path: String?; let scheduled: Double?; let delivered: Date }
struct PendingInfo: Sendable { let id: String; let title: String; let next: Date? }

struct DiagnosticEntry: Codable, Sendable, Identifiable {
    var id: String { "\(title)|\(scheduled)" }
    let title: String
    let scheduled: Double
    let delivered: Double
    var lateness: Double { delivered - scheduled }
}

enum NotifCenter {
    static func delivered() async -> [DeliveredInfo] {
        await withCheckedContinuation { c in
            UNUserNotificationCenter.current().getDeliveredNotifications { notes in
                c.resume(returning: notes.map {
                    let u = $0.request.content.userInfo
                    return DeliveredInfo(id: $0.request.identifier, key: u["key"] as? String, path: u["path"] as? String,
                                         scheduled: u["fireAt"] as? Double, delivered: $0.date)
                })
            }
        }
    }

    static func pending() async -> [PendingInfo] {
        await withCheckedContinuation { c in
            UNUserNotificationCenter.current().getPendingNotificationRequests { reqs in
                c.resume(returning: reqs.map {
                    PendingInfo(id: $0.identifier, title: $0.content.title, next: ($0.trigger as? UNCalendarNotificationTrigger)?.nextTriggerDate())
                })
            }
        }
    }

    static func authorization() async -> UNAuthorizationStatus {
        await withCheckedContinuation { c in
            UNUserNotificationCenter.current().getNotificationSettings { c.resume(returning: $0.authorizationStatus) }
        }
    }

    static func registerCategory() {
        let done = UNNotificationAction(identifier: NotifID.done, title: "Done", options: [])
        let tomorrow = UNNotificationAction(identifier: NotifID.tomorrow, title: "Move to tomorrow", options: [.foreground])
        let cat = UNNotificationCategory(identifier: NotifID.category, actions: [done, tomorrow], intentIdentifiers: [], options: [])
        UNUserNotificationCenter.current().setNotificationCategories([cat])
    }
}

// ---- coordinator ----------------------------------------------------------------------------------------------

/// Owns the one scheduling path (`AlarmPlanner.plan` -> `window` -> `AlarmSyncer.sync`), dismissal of delivered reminders
/// and the data behind the Reminders screen.
@MainActor @Observable
final class ReminderCoordinator {
    private(set) var authorization: UNAuthorizationStatus = .notDetermined
    private(set) var pending: [PendingInfo] = []
    private(set) var lastReport: AlarmSyncReport?
    private(set) var diagnostics: [DiagnosticEntry]

    @ObservationIgnored private let syncer = AlarmSyncer(backend: UNAlarmBackend(), store: JSONAlarmStore())
    @ObservationIgnored private let logFile = JSONFile<[DiagnosticEntry]>("alarm-diagnostics_v1.json")

    init() { diagnostics = logFile.load() ?? [] }

    var nextFive: [PendingInfo] {
        pending.filter { $0.id != NotifID.test && $0.next != nil }.sorted { $0.next! < $1.next! }.prefix(5).map { $0 }
    }
    var pendingCount: Int { pending.filter { $0.id != NotifID.test }.count }

    func requestAuthorization() async {
        NotifCenter.registerCategory()
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
        authorization = await NotifCenter.authorization()
    }

    func refreshStatus() async {
        authorization = await NotifCenter.authorization()
        pending = await NotifCenter.pending()
    }

    func reconcile(state: RepoState, spaces: SpacesData, leads: LeadStore, defaultTime: LocalTime, force: Bool) async {
        guard state.phase == .ready, !state.fromCache, state.scanError == nil else { return }
        let now = LocalDateTime(Date())
        var pairs: [(VaultTask, String)] = []
        for t in state.tasks {
            if let s = spaces.spaces.first(where: { $0.files.contains(t.id.path) }) { pairs.append((t, s.name)) }
        }
        let planned = AlarmPlanner.plan(pairs, settings: ReminderSettings(defaultTime: defaultTime), now: now) {
            leads.get(path: $0.id.path, description: $0.description)
        }
        let unreadable = Set(state.unreadable)
        lastReport = await syncer.sync(desired: AlarmPlanner.window(planned, limit: NotifID.window), preservePaths: unreadable,
                                       now: now, force: force)

        // Delivered reminders whose task is no longer open and dated disappear (also after completing in-app).
        let live = Set(state.tasks.filter { $0.state == .open && $0.dueDate != nil }.map { AlarmPlanner.keyOf($0.id) })
        let stale = await NotifCenter.delivered().filter { d in
            guard let key = d.key, d.id != NotifID.test else { return false }
            return !live.contains(key) && !(d.path.map(unreadable.contains) ?? false)
        }
        if !stale.isEmpty { UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: stale.map(\.id)) }
        await refreshStatus()
    }

    /// Compares each delivered notification's delivery time with its scheduled time, so lateness is measurable.
    func recordDelivered() async {
        var log = diagnostics
        let known = Set(log.map(\.id))
        let clearedAt = UserDefaults.standard.double(forKey: Self.clearedKey)
        for d in await NotifCenter.delivered() {
            guard let s = d.scheduled, s > 0, d.delivered.timeIntervalSince1970 > clearedAt else { continue }
            let e = DiagnosticEntry(title: d.id, scheduled: s, delivered: d.delivered.timeIntervalSince1970)
            if !known.contains(e.id) { log.append(e) }
        }
        log = Array(log.suffix(100))
        diagnostics = log
        logFile.save(log)
    }

    private static let clearedKey = "diagnosticsClearedAt"

    /// Empties the lateness log. Reminders already delivered stay in Notification Center, so they are remembered as
    /// cleared (by delivery time) and are not recorded again.
    func clearDiagnostics() {
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.clearedKey)
        diagnostics = []
        logFile.save([])
    }

    func sendTest() async {
        let c = UNMutableNotificationContent()
        c.title = "VaultTasks test"
        c.body = "Reminders work."
        c.sound = .default
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(
            identifier: NotifID.test, content: c, trigger: UNTimeIntervalNotificationTrigger(timeInterval: 10, repeats: false)))
        await refreshStatus()
    }
}

// ---- delegate -------------------------------------------------------------------------------------------------

final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    private let handler: @Sendable (String, String, String, String) async -> Void
    init(handler: @escaping @Sendable (String, String, String, String) async -> Void) { self.handler = handler }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let u = response.notification.request.content.userInfo
        let key = u["key"] as? String ?? "", path = u["path"] as? String ?? "", desc = u["desc"] as? String ?? ""
        await handler(response.actionIdentifier, key, path, desc)
    }
}
