import Foundation
@testable import VaultTasksCore

/// Runs parser-fixtures.json (language-neutral; shared unchanged with Android). Returns failure messages.
enum FixtureRunner {
    static func load() -> [String: Any] {
        let url = Bundle.module.url(forResource: "parser-fixtures", withExtension: "json")
            ?? Bundle.module.url(forResource: "parser-fixtures", withExtension: "json", subdirectory: "Resources")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }

    static func cases(_ key: String) -> [[String: Any]] { load()[key] as! [[String: Any]] }
    static func caseCount(_ key: String) -> Int { cases(key).count }

    private struct Row: Equatable, CustomStringConvertible {
        let line: Int, occ: Int
        let state: String
        let desc: [UInt8]
        let due, time, prio: String?
        let rec: Bool
        let done: String?
        var description: String {
            "line=\(line) occ=\(occ) state=\(state) desc=[\(String(decoding: desc, as: UTF8.self))] due=\(due ?? "nil") time=\(time ?? "nil") prio=\(prio ?? "nil") rec=\(rec) done=\(done ?? "nil")"
        }
    }
    private static func row(_ t: VaultTask) -> Row {
        Row(line: t.lineIndex, occ: t.id.occurrence, state: t.state.name, desc: Array(t.description.utf8), due: t.dueDate?.description,
            time: t.dueTime?.description, prio: t.priority?.name, rec: t.isRecurring, done: t.doneDate?.description)
    }

    static func show(_ s: String) -> String {
        s.replacingOccurrences(of: "\r", with: "\\r").replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\u{FEFF}", with: "<BOM>").replacingOccurrences(of: "\u{00A0}", with: "<NBSP>")
    }

    static func parseFailures() -> [String] {
        var out: [String] = []
        for c in cases("parseCases") {
            let name = c["name"] as! String
            let filter = c["globalFilter"] as? String ?? ""
            let tasks = TaskParser.parseFile(path: "f.md", text: VaultText.parse(c["text"] as! String), globalFilter: filter)
            let expected: [Row] = (c["tasks"] as! [[String: Any]]).map {
                Row(line: $0["line"] as! Int, occ: ($0["occurrence"] as? Int) ?? 0, state: $0["state"] as? String ?? "open",
                    desc: Array(($0["description"] as! String).utf8), due: $0["due"] as? String, time: $0["time"] as? String,
                    prio: $0["priority"] as? String, rec: $0["recurring"] as? Bool ?? false, done: $0["done"] as? String)
            }
            let actual = tasks.map(row)
            if actual != expected { out.append("\(name): parse mismatch\n  expected=\(expected)\n  actual  =\(actual)") }
            // §12.7: independent Tasks-style reader must agree on priority/due/done.
            let blind = c["tasksBlind"] as? Bool ?? false
            for t in tasks {
                guard let tv = TasksStyleParser.parse(t.rawLine) else { out.append("\(name): Tasks-style reader rejects '\(t.rawLine)'"); continue }
                let appDue = blind ? nil : t.dueDate
                if tv.due != appDue || tv.done != t.doneDate || !sameBytes(tv.priority, t.priority?.emoji) {
                    out.append("\(name): Tasks view differs for '\(t.rawLine)': \(tv) vs due=\(appDue?.description ?? "nil") done=\(t.doneDate?.description ?? "nil") prio=\(t.priority?.name ?? "nil")")
                }
            }
        }
        return out
    }

    static func editFailures() -> [String] {
        var out: [String] = []
        for c in cases("editCases") {
            let name = c["name"] as! String
            let filter = c["globalFilter"] as? String ?? ""
            let vt = VaultText.parse(c["text"] as! String)
            let idx = c["line"] as? Int ?? -1
            let op = c["op"] as! String
            let fields = TaskFields(description: c["description"] as? String ?? "",
                                    dueDate: (c["due"] as? String).flatMap(LocalDate.parse),
                                    dueTime: (c["time"] as? String).flatMap(LocalTime.parse))
            let doneDate = (c["doneDate"] as? String).flatMap(LocalDate.parse)
            let editedLine: String
            let newText: String
            do {
                if op == "append" {
                    editedLine = try TaskSerializer.newLine(fields, globalFilter: filter)
                    newText = vt.appendLine(editedLine).string
                } else {
                    guard let p = TaskParser.parseLine(vt.lines[idx].text, globalFilter: filter) else {
                        out.append("\(name): target line is not a task"); continue
                    }
                    switch op {
                    case "setFields": editedLine = try TaskSerializer.applyFields(p, fields, globalFilter: filter)
                    case "done": editedLine = try TaskSerializer.setDone(p, done: true, doneDate: doneDate)
                    case "undone": editedLine = try TaskSerializer.setDone(p, done: false, doneDate: nil)
                    default: out.append("\(name): unknown op \(op)"); continue
                    }
                    newText = vt.replaceLine(idx, editedLine).string
                }
            } catch { out.append("\(name): threw \(error)"); continue }

            let expected = c["expected"] as! String
            if !sameBytes(newText, expected) { out.append("\(name): expected=\(show(expected))\n  actual  =\(show(newText))") }
            // §4.2: every other line is byte-identical.
            let after = VaultText.parse(newText)
            if op == "append" {
                let a = vt.lines.map(\.text), b = after.lines.enumerated().filter { $0.offset != vt.lines.count }.map(\.element.text)
                if a.count != b.count || !zip(a, b).allSatisfy({ sameBytes($0, $1) }) { out.append("\(name): other lines changed") }
            } else {
                let a = vt.lines.enumerated().filter { $0.offset != idx }.map(\.element)
                let b = after.lines.enumerated().filter { $0.offset != idx }.map(\.element)
                if a != b { out.append("\(name): other lines changed") }
            }
            // §12.7 on the result: Tasks must read what the app reads.
            let tv = TasksStyleParser.parse(editedLine), av = TaskParser.parseLine(editedLine, globalFilter: filter)
            if let tv, let av {
                if tv.due != av.dueDate || tv.done != av.doneDate || !sameBytes(tv.priority, av.priority?.emoji) {
                    out.append("\(name): Tasks sees different metadata on '\(editedLine)': \(tv)")
                }
            } else { out.append("\(name): edited line unreadable: '\(editedLine)'") }
        }
        return out
    }
}
