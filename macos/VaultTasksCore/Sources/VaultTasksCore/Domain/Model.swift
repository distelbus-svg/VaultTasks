import Foundation

/// Emoji constants as explicit scalars (never typed as glyphs, so no variation selector can sneak in).
enum Emoji {
    static let highest = "\u{1F53A}", high = "\u{23EB}", medium = "\u{1F53C}", low = "\u{1F53D}", lowest = "\u{23EC}"
    static let start = "\u{1F6EB}", created = "\u{2795}", hourglassFlowing = "\u{23F3}", hourglass = "\u{231B}"
    static let due = "\u{1F4C5}", dueTearOff = "\u{1F4C6}", dueSpiral = "\u{1F5D3}"
    static let done = "\u{2705}", cancelled = "\u{274C}", recurrence = "\u{1F501}", onCompletion = "\u{1F3C1}"
    static let depends = "\u{26D4}", id = "\u{1F194}", alarm = "\u{23F0}"
    static let variationSelector: Unicode.Scalar = "\u{FE0F}"
}

public enum TaskState: Codable, Sendable {
    case open, done, cancelled
    public var mark: Unicode.Scalar {
        switch self { case .open: " "; case .done: "x"; case .cancelled: "-" }
    }
    public var name: String {
        switch self { case .open: "open"; case .done: "done"; case .cancelled: "cancelled" }
    }
}

public enum Priority: CaseIterable, Codable, Sendable {
    case highest, high, medium, low, lowest
    public var emoji: String {
        switch self {
        case .highest: Emoji.highest
        case .high: Emoji.high
        case .medium: Emoji.medium
        case .low: Emoji.low
        case .lowest: Emoji.lowest
        }
    }
    public var name: String {
        switch self { case .highest: "highest"; case .high: "high"; case .medium: "medium"; case .low: "low"; case .lowest: "lowest" }
    }
}

/// Identity per spec §4.3. lineIndex is deliberately NOT part of it. Equality and hashing are byte-exact (§3.1 rule 2).
public struct TaskId: Hashable, Codable, Sendable {
    public let path: String
    public let normalizedText: String
    public let occurrence: Int

    public init(path: String, normalizedText: String, occurrence: Int) {
        self.path = path; self.normalizedText = normalizedText; self.occurrence = occurrence
    }
    public static func == (a: TaskId, b: TaskId) -> Bool {
        a.occurrence == b.occurrence && sameBytes(a.path, b.path) && sameBytes(a.normalizedText, b.normalizedText)
    }
    public func hash(into h: inout Hasher) {
        h.combine(Array(path.utf8)); h.combine(Array(normalizedText.utf8)); h.combine(occurrence)
    }
}

/// Kotlin `Task`. Renamed because a module-level `Task` would collide with Swift Concurrency's `Task` in every client file.
public struct VaultTask: Hashable, Codable, Sendable {
    public let id: TaskId
    public let lineIndex: Int // hint only
    public let state: TaskState
    public let description: String // without ⏰ HH:mm and without Tasks signifiers
    public let dueDate: LocalDate?
    public let dueTime: LocalTime?
    public let priority: Priority?
    public let isRecurring: Bool
    public let doneDate: LocalDate?
    public let rawLine: String
    /// Sub-bullet lines under the task, joined with "\n" (see `Notes`); "" if none. Not part of the identity.
    public let notes: String
    /// Rule text after 🔁 (e.g. "every week"), nil if not recurring.
    public let recurrence: String?

    public init(id: TaskId, lineIndex: Int, state: TaskState, description: String, dueDate: LocalDate?, dueTime: LocalTime?,
                priority: Priority?, isRecurring: Bool, doneDate: LocalDate?, rawLine: String, notes: String = "",
                recurrence: String? = nil) {
        self.id = id; self.lineIndex = lineIndex; self.state = state; self.description = description
        self.dueDate = dueDate; self.dueTime = dueTime; self.priority = priority; self.isRecurring = isRecurring
        self.doneDate = doneDate; self.rawLine = rawLine; self.notes = notes; self.recurrence = recurrence
    }

    public static func == (a: VaultTask, b: VaultTask) -> Bool {
        a.id == b.id && a.lineIndex == b.lineIndex && a.state == b.state && sameBytes(a.description, b.description)
            && a.dueDate == b.dueDate && a.dueTime == b.dueTime && a.priority == b.priority
            && a.isRecurring == b.isRecurring && a.doneDate == b.doneDate && sameBytes(a.rawLine, b.rawLine)
            && sameBytes(a.notes, b.notes) && sameBytes(a.recurrence, b.recurrence)
    }
    public func hash(into h: inout Hasher) { h.combine(id); h.combine(lineIndex) }

    /// Trailing `#tag` of the description, if any; the app's "category".
    public var category: String? { Categories.split(description).1 }

    /// Whether the checkbox can be toggled in the app: recurring tasks only if their rule is one we can advance.
    public var completable: Bool {
        state != .cancelled && (!isRecurring || state == .done || (dueDate != nil && Recurrences.parse(recurrence ?? "") != nil))
    }
}
