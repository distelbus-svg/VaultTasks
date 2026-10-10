import Foundation

/// Minimum gap between automatic sync runs (port of `autoSync(minGapMs)`): quick activate/resign cycles must not
/// hammer the server. A backwards clock jump never blocks a run.
public struct AutoSyncGate: Sendable {
    private var last: Date?
    public init() {}

    /// true = the run may start, and the gap counts from `now`.
    public mutating func tryStart(now: Date, minGap: TimeInterval) -> Bool {
        if let last {
            let elapsed = now.timeIntervalSince(last)
            if elapsed >= 0 && elapsed < minGap { return false }
        }
        last = now
        return true
    }
}

extension SyncReport {
    /// One-line result for toasts ("1 pulled, 0 pushed, 2 failed").
    public var summaryLine: String {
        let base = "\(pulled.count) pulled, \(pushed.count) pushed"
        return failed.isEmpty ? base : "\(base), \(failed.count) failed"
    }
}
