import Foundation

/// A category is the LAST word of a task's description when it is an Obsidian tag: `Buy milk #home`.
/// Tags in the middle of a sentence are left alone and are not categories.
public enum Categories {
    private static func isTagChar(_ u: Unicode.Scalar) -> Bool {
        regexIsLetter(u) || regexIsNumber(u) || u == "_" || u == "/" || u == "-"
    }

    /// `^(.*?)(?:^|\s+)#([\p{L}\p{N}_/-]+)$` (DOT_MATCHES_ALL, matchEntire): (text before the whitespace run, tag).
    private static func trailingTag(_ d: [Unicode.Scalar]) -> (rest: [Unicode.Scalar], tag: [Unicode.Scalar])? {
        var k = d.count
        while k > 0 && isTagChar(d[k - 1]) { k -= 1 }
        guard k < d.count, k > 0, d[k - 1] == "#" else { return nil }
        let tag = Array(d[k...])
        let h = k - 1
        if h == 0 { return ([], tag) }
        guard javaIsSpace(d[h - 1]) else { return nil }
        var w = h
        while w > 0 && javaIsSpace(d[w - 1]) { w -= 1 }
        return (Array(d[..<w]), tag)
    }

    /// (description without its trailing tag, tag without '#' or nil). A bare "#tag" description is not split.
    public static func split(_ description: String) -> (String, String?) {
        let d = ktTrim(description.scalarArray)
        guard let m = trailingTag(d) else { return (str(d), nil) }
        let rest = ktTrim(m.rest)
        // Obsidian: a tag needs a non-digit; a title is required
        if rest.isEmpty || m.tag.allSatisfy(ktIsDigit) { return (str(d), nil) }
        return (str(rest), str(m.tag))
    }

    /// Normalizes user input to a tag name: no '#', whitespace → '-'. nil if nothing usable is left.
    public static func clean(_ input: String) -> String? {
        var s = ktTrim(input.scalarArray)
        while s.first == "#" { s.removeFirst() }
        s = ktTrim(s)
        var t: [Unicode.Scalar] = []
        var inWs = false
        for c in s {
            if javaIsSpace(c) { if !inWs { t.append("-"); inWs = true } } else { t.append(c); inWs = false }
        }
        let f = t.filter { ktIsLetterOrDigit($0) || $0 == "_" || $0 == "/" || $0 == "-" }
        guard !f.isEmpty, !f.allSatisfy(ktIsDigit) else { return nil }
        return str(f)
    }

    public static func join(_ description: String, _ category: String?) -> String {
        let d = description.ktTrimmed
        return category.map { "\(d) #\($0)" } ?? d
    }

    /// Distinct categories of `tasks`, alphabetical (case-insensitive, UTF-16 order, first-seen wins ties).
    public static func of(_ tasks: [VaultTask]) -> [String] {
        var seen = Set<[UInt8]>()
        var distinct: [String] = []
        for t in tasks { if let c = t.category, seen.insert(Array(c.utf8)).inserted { distinct.append(c) } }
        return distinct.enumerated().map { ($0.offset, $0.element, $0.element.lowercased()) }
            .sorted { a, b in sameBytes(a.2, b.2) ? a.0 < b.0 : utf16Less(a.2, b.2) }
            .map { $0.1 }
    }
}
