import Foundation

/// What a delete removed, enough to undo it.
public struct RemovedLine: Equatable, Sendable {
    public let path: String
    public let index: Int
    public let text: String
    public let fileHashAfter: String
    public let notes: [String]
    public init(path: String, index: Int, text: String, fileHashAfter: String, notes: [String] = []) {
        self.path = path; self.index = index; self.text = text; self.fileHashAfter = fileHashAfter; self.notes = notes
    }
}

public enum WriteResult: Sendable {
    /// `file` is the verified, re-parsed state after the write.
    case ok(ScannedFile, removed: RemovedLine?)
    /// Target line no longer matches (or the file changed underneath us). Nothing was written. Rescan and retry.
    case stale
    case missing
    /// Nothing was written, or a failed write was rolled back where possible.
    case failed(Error)
}

public enum WriterError: Error, LocalizedError, Sendable {
    case unsupportedRepeat(String)
    case recurringWithoutDate
    case notUtf8(String)
    case readBackFailed(String)
    case moveNotUndone(lineText: String, cause: Error?)

    public var errorDescription: String? {
        switch self {
        case .unsupportedRepeat(let r): "unsupported repeat rule \"\(r)\", complete it in Obsidian"
        case .recurringWithoutDate: "recurring task without a due date, complete it in Obsidian"
        case .notUtf8(let p): "\(p) is not valid UTF-8; refusing to edit"
        case .readBackFailed(let p): "read-back verification failed for \(p)"
        case .moveNotUndone(let l, _): "Move failed and could not be undone. The task line was: \(l)"
        }
    }
}

/// Write protocol: read fresh → locate target by identity → replace only that line → verify nothing changed since the
/// read → write → read back and verify → re-parse. All writes are serialized by one recursive lock (a move nests two
/// edits), so concurrent callers can neither lose an update nor deadlock. In the app every call additionally runs
/// inside the `VaultRepository` actor.
public final class VaultWriter: @unchecked Sendable {
    private let fs: VaultFileSystem
    private let globalFilter: @Sendable () -> String
    private let lock = NSRecursiveLock()

    public init(fs: VaultFileSystem, globalFilter: @escaping @Sendable () -> String = { "" }) {
        self.fs = fs; self.globalFilter = globalFilter
    }

    public func setDone(_ task: VaultTask, done: Bool, doneDate: LocalDate?) -> WriteResult {
        mutate(task.id.path) { text, fresh in
            guard let (index, line) = locate(text, fresh, task) else { return nil }
            if done && line.isRecurring && line.state == .open {
                // Like Tasks: mark this one done and put the next occurrence (with copies of the notes) right below it.
                let rule = line.recurrenceRule ?? ""
                guard let rec = Recurrences.parse(rule) else { throw WriterError.unsupportedRepeat(rule) }
                guard let due = line.dueDate else { throw WriterError.recurringWithoutDate }
                let next = Recurrences.next(rec, due: due, completedOn: doneDate ?? LocalDate.today())
                let (doneLine, nextLine) = try TaskSerializer.complete(line, doneDate: doneDate, nextDue: next)
                let count = 1 + Notes.blockLength(text.lines, taskIndex: index)
                let noteLines = text.lines[(index + 1)..<(index + count)].map(\.text)
                return Edit(text: text.replaceLines(index, count: count, with: [doneLine] + noteLines + [nextLine] + noteLines))
            }
            return Edit(text: text.replaceLine(index, try TaskSerializer.setDone(line, done: done, doneDate: doneDate)))
        }
    }

    /// The note lines to write under an edited task: the old ones untouched unless the notes really change.
    private func notesFor(_ task: VaultTask, _ fields: TaskFields, _ newLine: String, _ old: [String]) -> [String] {
        if let n = fields.notes, !sameBytes(Notes.normalize(n), Notes.normalize(task.notes)) { return Notes.render(taskLine: newLine, notes: n) }
        return old
    }

    public func update(_ task: VaultTask, _ fields: TaskFields) -> WriteResult {
        mutate(task.id.path) { text, fresh in
            guard let (index, line) = locate(text, fresh, task) else { return nil }
            let newLine = try TaskSerializer.applyFields(line, fields, globalFilter: globalFilter())
            let count = 1 + Notes.blockLength(text.lines, taskIndex: index)
            let oldNotes = text.lines[(index + 1)..<(index + count)].map(\.text)
            return Edit(text: text.replaceLines(index, count: count, with: [newLine] + notesFor(task, fields, newLine, oldNotes)))
        }
    }

    public func delete(_ task: VaultTask) -> WriteResult {
        mutate(task.id.path) { text, fresh in
            guard let (index, _) = locate(text, fresh, task) else { return nil }
            let count = 1 + Notes.blockLength(text.lines, taskIndex: index)
            return Edit(text: text.removeLines(index, count: count), removedIndex: index, removedText: text.lines[index].text,
                        removedNotes: text.lines[(index + 1)..<(index + count)].map(\.text))
        }
    }

    /// Appends at the end of an existing file, ensuring a preceding newline.
    public func create(_ path: String, _ fields: TaskFields, bullet: String = "-") -> WriteResult {
        mutate(path) { text, _ in
            let line = try TaskSerializer.newLine(fields, globalFilter: globalFilter(), bullet: bullet)
            return Edit(text: text.appendLines([line] + Notes.render(taskLine: line, notes: fields.notes ?? "")))
        }
    }

    /// Edit + move to another file: remove the line from its file, append the edited line to `toPath`.
    /// Everything the app does not edit (priority, 🆔, …) travels with the line. If the append fails, the removal is
    /// rolled back via `restore`, so a failure never loses the task. The returned file is `toPath`'s new state;
    /// callers rescan the source file.
    public func move(_ task: VaultTask, _ fields: TaskFields, to toPath: String) -> WriteResult {
        let fromPath = task.id.path
        if toPath == fromPath { return update(task, fields) }
        lock.lock(); defer { lock.unlock() }

        var newText = ""
        var movedNotes: [String] = []
        let removal = mutate(fromPath) { text, fresh in
            guard let (index, line) = locate(text, fresh, task) else { return nil }
            let newLine = try TaskSerializer.applyFields(line, fields, globalFilter: globalFilter())
            newText = newLine
            let count = 1 + Notes.blockLength(text.lines, taskIndex: index)
            let old = text.lines[(index + 1)..<(index + count)].map(\.text)
            movedNotes = notesFor(task, fields, newLine, old)
            return Edit(text: text.removeLines(index, count: count), removedIndex: index, removedText: text.lines[index].text, removedNotes: old)
        }
        guard case .ok(_, let removedOpt) = removal else { return removal }
        guard let removed = removedOpt else { return .failed(WriterError.readBackFailed("no removal recorded for \(fromPath)")) }

        let added = mutate(toPath) { text, _ in Edit(text: text.appendLines([newText] + movedNotes)) }
        if case .ok = added { return added }

        switch restore(removed) {
        case .ok: return added
        case .failed(let cause): return .failed(WriterError.moveNotUndone(lineText: removed.text, cause: cause))
        default: return .failed(WriterError.moveNotUndone(lineText: removed.text, cause: nil))
        }
    }

    /// Undo of `delete`: original index if the file is unchanged since, otherwise appended.
    public func restore(_ removed: RemovedLine) -> WriteResult {
        mutate(removed.path) { text, _ in
            let unchanged = sha256Hex(Data(text.string.utf8)) == removed.fileHashAfter
            let all = [removed.text] + removed.notes
            return Edit(text: unchanged ? text.insertLines(removed.index, all) : text.appendLines(all))
        }
    }

    // ---- internals -------------------------------------------------------------------------------------------

    private struct Edit {
        let text: VaultText
        var removedIndex: Int? = nil
        var removedText: String? = nil
        var removedNotes: [String] = []
    }

    /// The target's current line index and parse, or nil if its identity no longer matches anything in the file.
    private func locate(_ text: VaultText, _ fresh: [VaultTask], _ target: VaultTask) -> (Int, ParsedLine)? {
        guard let hit = fresh.first(where: { $0.id == target.id }),
              let line = TaskParser.parseLine(text.lines[hit.lineIndex].text, globalFilter: globalFilter()) else { return nil }
        return (hit.lineIndex, line)
    }

    private func mutate(_ path: String, _ edit: (VaultText, [VaultTask]) throws -> Edit?) -> WriteResult {
        lock.lock(); defer { lock.unlock() }
        do { return try mutateLocked(path, edit) } catch { return .failed(error) }
    }

    private func mutateLocked(_ path: String, _ edit: (VaultText, [VaultTask]) throws -> Edit?) throws -> WriteResult {
        guard let statBefore = try fs.stat(path) else { return .missing }
        guard let original = try fs.read(path) else { return .missing }
        guard let decoded = decodeUtf8Strict(original) else { return .failed(WriterError.notUtf8(path)) }

        let text = VaultText.parse(decoded)
        let filter = globalFilter()
        let fresh = TaskParser.parseFile(path: path, text: text, globalFilter: filter)
        guard let result = try edit(text, fresh) else { return .stale }
        let newBytes = Data(result.text.string.utf8)

        // Last-chance check: someone else wrote while we were working.
        guard let statNow = try fs.stat(path) else { return .missing }
        if statNow.lastModified != statBefore.lastModified || statNow.size != statBefore.size { return .stale }

        try fs.write(path, newBytes)

        let back = try? fs.read(path)
        guard let back, back == newBytes else {
            try? fs.write(path, original) // best-effort rollback
            return .failed(WriterError.readBackFailed(path))
        }

        guard let info = try fs.stat(path) else { return .missing }
        let hash = sha256Hex(newBytes)
        let parsed = TaskParser.parseFile(path: path, text: result.text, globalFilter: filter)
        var removed: RemovedLine? = nil
        if let i = result.removedIndex, let t = result.removedText {
            removed = RemovedLine(path: path, index: i, text: t, fileHashAfter: hash, notes: result.removedNotes)
        }
        return .ok(ScannedFile(state: FileState(path: path, lastModified: info.lastModified, size: info.size, contentHash: hash), tasks: parsed),
                   removed: removed)
    }
}
