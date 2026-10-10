import SwiftUI
import AppKit

/// Turns a two-finger horizontal trackpad swipe over its area into one callback (`-1` previous, `+1` next), like the
/// swipe on the Android month grid. SwiftUI has no scroll-wheel hook on macOS, so a local event monitor watches
/// precise scroll events whose pointer is inside this view. Events are never consumed; vertical scrolling and mice
/// are ignored.
struct ScrollSwipeCatcher: NSViewRepresentable {
    let onSwipe: @MainActor (Int) -> Void

    func makeNSView(context: Context) -> CatcherView {
        let v = CatcherView()
        v.onSwipe = onSwipe
        return v
    }

    func updateNSView(_ v: CatcherView, context: Context) { v.onSwipe = onSwipe }

    final class CatcherView: NSView {
        var onSwipe: (@MainActor (Int) -> Void)?
        /// Only touched on the main thread; `nonisolated(unsafe)` lets `deinit` remove it.
        nonisolated(unsafe) private var monitor: Any?
        private var accumulated: CGFloat = 0
        private var fired = false
        private let threshold: CGFloat = 60

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if let monitor { NSEvent.removeMonitor(monitor); self.monitor = nil }
            guard window != nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
                MainActor.assumeIsolated { self?.handle(event) }
                return event
            }
        }

        deinit { if let monitor { NSEvent.removeMonitor(monitor) } }

        override func hitTest(_ point: NSPoint) -> NSView? { nil } // never steals clicks from the grid

        private func handle(_ e: NSEvent) {
            guard let window, e.window === window, e.hasPreciseScrollingDeltas, e.momentumPhase.isEmpty else { return }
            guard bounds.contains(convert(e.locationInWindow, from: nil)) else { return }
            if e.phase.contains(.began) { accumulated = 0; fired = false }
            if e.phase.contains(.ended) || e.phase.contains(.cancelled) { accumulated = 0; fired = false; return }
            guard abs(e.scrollingDeltaX) > abs(e.scrollingDeltaY), !fired else { return }
            accumulated += e.scrollingDeltaX
            if abs(accumulated) > threshold {
                fired = true
                onSwipe?(accumulated < 0 ? 1 : -1) // fingers swipe left: next month (as on Android)
            }
        }
    }
}
