import Foundation
import Observation
import ServiceManagement

/// Launch at login (§9.5) through `SMAppService.mainApp`. Default off; the system owns the real state, so it is re-read
/// whenever the General tab appears (the user can also change it in System Settings > Login Items).
@MainActor @Observable
final class LoginItem {
    private(set) var status: SMAppService.Status
    private(set) var error: String?

    init() { status = SMAppService.mainApp.status }

    var isOn: Bool { status == .enabled }
    var needsApproval: Bool { status == .requiresApproval }

    func refresh() { status = SMAppService.mainApp.status }

    func set(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        status = SMAppService.mainApp.status
    }

    func openSystemSettings() { SMAppService.openSystemSettingsLoginItems() }
}
