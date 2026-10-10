import SwiftUI
import AppKit
import VaultTasksCore

// ---- motion ------------------------------------------------------------------------------------------------

/// Spring animations that switch off under System Settings > Accessibility > Reduce Motion. Animation never alters or delays
/// a write: views derive from the repository snapshot, so skipping an animation only skips the tween.
@MainActor enum Motion {
    static var reduced: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }
    static func spring(_ duration: Double = 0.35, bounce: Double = 0) -> Animation? {
        reduced ? nil : .spring(duration: duration, bounce: bounce)
    }
}

// ---- category colors (port of CATEGORY_PALETTE / onCategoryColor in Components.kt) --------------------------

enum CategoryPalette {
    /// The 10 swatches offered in the "New category" dialog (ARGB), all light enough for dark text.
    static let swatches: [UInt32] = [
        0xFFE57373, 0xFFFFB74D, 0xFFFFD54F, 0xFF81C784, 0xFF4DB6AC, 0xFF64B5F6, 0xFF9575CD, 0xFFF06292, 0xFFA1887F, 0xFF90A4AE,
    ]
    static let defaultSwatch = swatches[5]

    /// Compose's `Color.luminance()`: relative luminance of the linearized sRGB channels.
    static func luminance(_ argb: UInt32) -> Double {
        func lin(_ shift: UInt32) -> Double {
            let c = Double((argb >> shift) & 0xFF) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    /// Readable text color on a swatch.
    static func textColor(on argb: UInt32) -> Color {
        luminance(argb) > 0.35 ? Color(red: 0x1C / 255, green: 0x1B / 255, blue: 0x1F / 255) : .white
    }
}

extension Color {
    init(argb: UInt32) {
        self.init(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

// ---- small views ---------------------------------------------------------------------------------------------

/// Selectable pill. With `accent` (a category color) the selected fill is that color and an unselected chip carries a dot.
struct Chip: View {
    let label: String
    var selected = false
    var accent: UInt32? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let accent, !selected { Circle().fill(Color(argb: accent)).frame(width: 8, height: 8) }
                Text(label)
            }
            .font(.callout)
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(fill, in: Capsule())
            .foregroundStyle(selected ? (accent.map { CategoryPalette.textColor(on: $0) } ?? .white) : .primary)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .animation(Motion.spring(0.3), value: selected)
    }

    private var fill: Color {
        selected ? (accent.map { Color(argb: $0) } ?? .accentColor) : Color.secondary.opacity(0.15)
    }
}

/// The `#tag` chip on a task row; filled with the category color when one was chosen.
struct CategoryChip: View {
    let name: String
    let argb: UInt32?

    var body: some View {
        Text("#\(name)")
            .font(.caption)
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(argb.map { Color(argb: $0) } ?? Color.secondary.opacity(0.18), in: Capsule())
            .foregroundStyle(argb.map { CategoryPalette.textColor(on: $0) } ?? .secondary)
    }
}

struct CountPill: View {
    let count: Int
    var tint: Color = .secondary

    var body: some View {
        Text("\(count)").font(.caption.monospacedDigit())
            .padding(.horizontal, 7).padding(.vertical, 1)
            .background(tint.opacity(0.16), in: Capsule())
            .foregroundStyle(tint)
    }
}

/// Wraps its children onto as many rows as the width needs.
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(width: proposal.width ?? .infinity, subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let r = arrange(width: bounds.width, subviews)
        for (i, f) in r.frames.enumerated() {
            subviews[i].place(at: CGPoint(x: bounds.minX + f.minX, y: bounds.minY + f.minY), proposal: ProposedViewSize(f.size))
        }
    }

    private func arrange(width: CGFloat, _ subviews: Subviews) -> (size: CGSize, frames: [CGRect]) {
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        var frames: [CGRect] = []
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            frames.append(CGRect(origin: CGPoint(x: x, y: y), size: size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            maxX = max(maxX, x - spacing)
        }
        return (CGSize(width: maxX, height: y + rowHeight), frames)
    }
}

struct ToastView: View {
    let toast: Toast
    let dismiss: () -> Void

    var body: some View {
        GlassEffectContainer {
            HStack(spacing: 14) {
                Text(toast.text)
                if let label = toast.actionLabel, let action = toast.action {
                    Button(label) { action() }.buttonStyle(.borderless).fontWeight(.semibold)
                }
            }
            .padding(.horizontal, 18).padding(.vertical, 11)
            .glassEffect(.regular, in: .capsule)
        }
        .onTapGesture(perform: dismiss)
    }
}

// ---- date text and picker bridging ----------------------------------------------------------------------------

/// Display uses the user's locale; the domain value stays a plain y-m-d (§3.1 rule 4).
enum DateText {
    static func date(_ d: LocalDate) -> String {
        guard let noon = LocalDateTime(d, LocalTime(hour: 12, minute: 0)!).instant() else { return d.description }
        return noon.formatted(date: .abbreviated, time: .omitted)
    }

    static func time(_ t: LocalTime) -> String {
        guard let at = LocalDateTime(LocalDate.today(), t).instant() else { return t.description }
        return at.formatted(date: .omitted, time: .shortened)
    }

    static func due(_ d: LocalDate, _ t: LocalTime?) -> String {
        t.map { "\(date(d)), \(time($0))" } ?? date(d)
    }

    static func lead(_ minutes: Int) -> String {
        switch minutes {
        case 0: "At time"
        case 60: "1 h before"
        case 1440: "1 day before"
        case 2880: "2 days before"
        case 10080: "1 week before"
        default: "\(minutes) min before"
        }
    }
}

/// `DatePicker` works on `Date`; these bind it to the domain's zone-less values.
func dateBinding(_ get: @escaping () -> LocalDate?, _ set: @escaping (LocalDate) -> Void) -> Binding<Date> {
    Binding(get: { LocalDateTime(get() ?? .today(), LocalTime(hour: 12, minute: 0)!).instant() ?? Date() },
            set: { set(LocalDateTime($0).date) })
}

func timeBinding(_ get: @escaping () -> LocalTime?, _ set: @escaping (LocalTime) -> Void) -> Binding<Date> {
    Binding(get: { LocalDateTime(.today(), get() ?? LocalTime(hour: 9, minute: 0)!).instant() ?? Date() },
            set: { set(LocalDateTime($0).time) })
}
