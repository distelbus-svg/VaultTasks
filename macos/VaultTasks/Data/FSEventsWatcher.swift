import Foundation
import CoreServices
import VaultTasksCore

/// FSEvents stream on the vault root (§5). Raw event paths go to `onEvent`; filtering and debouncing happen in the
/// repository (`VaultEventFilter`, `EventDebouncer`).
final class FSEventsWatcher: @unchecked Sendable {
    private var stream: FSEventStreamRef?
    private let queue = DispatchQueue(label: "app.vaulttasks.fsevents")
    private let onEvent: @Sendable ([String]) -> Void

    init(onEvent: @escaping @Sendable ([String]) -> Void) { self.onEvent = onEvent }
    deinit { stop() }

    @discardableResult
    func start(path: String, latency: Double = 0.2) -> Bool {
        stop()
        var ctx = FSEventStreamContext(version: 0, info: Unmanaged.passUnretained(self).toOpaque(), retain: nil, release: nil, copyDescription: nil)
        let cb: FSEventStreamCallback = { _, info, _, paths, _, _ in
            guard let info else { return }
            let me = Unmanaged<FSEventsWatcher>.fromOpaque(info).takeUnretainedValue()
            me.onEvent((unsafeBitCast(paths, to: NSArray.self) as? [String]) ?? [])
        }
        let flags = FSEventStreamCreateFlags(kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagUseCFTypes)
        guard let s = FSEventStreamCreate(nil, cb, &ctx, [path] as CFArray, FSEventStreamEventId(kFSEventStreamEventIdSinceNow), latency, flags) else { return false }
        stream = s
        FSEventStreamSetDispatchQueue(s, queue)
        return FSEventStreamStart(s)
    }

    func stop() {
        guard let s = stream else { return }
        FSEventStreamStop(s); FSEventStreamInvalidate(s); FSEventStreamRelease(s)
        stream = nil
    }
}
