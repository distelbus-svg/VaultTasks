import Foundation

/// Lossless view of a file: BOM flag + lines each with their own terminator ("", "\n", "\r\n").
/// `VaultText.parse(s).string == s` for every s (§4.2). Callers must decode bytes as UTF-8 WITHOUT stripping U+FEFF.
/// Splitting happens on UTF-8 bytes, so "\r\n" can never be fused into one Character and a lone "\r" stays in the text.
public struct VaultText: Sendable {
    public struct Line: Equatable, Sendable {
        public let text: String
        public let eol: String
        public init(text: String, eol: String) { self.text = text; self.eol = eol }
        public static func == (a: Line, b: Line) -> Bool { sameBytes(a.text, b.text) && sameBytes(a.eol, b.eol) }
    }

    public let bom: Bool
    public let lines: [Line]

    private init(bom: Bool, lines: [Line]) { self.bom = bom; self.lines = lines }

    /// First terminator found in the file; "\n" if none.
    public var eolStyle: String { lines.first(where: { !$0.eol.isEmpty })?.eol ?? "\n" }

    public func replaceLine(_ index: Int, _ text: String) -> VaultText {
        var ls = lines; ls[index] = Line(text: text, eol: ls[index].eol)
        return VaultText(bom: bom, lines: ls)
    }

    public func removeLine(_ index: Int) -> VaultText {
        var ls = lines; ls.remove(at: index)
        return VaultText(bom: bom, lines: ls)
    }

    /// Replaces `count` (>= 1) lines starting at `index` by `texts`; the last new line keeps the terminator of the last replaced one.
    public func replaceLines(_ index: Int, count: Int, with texts: [String]) -> VaultText {
        precondition(count >= 1 && index + count <= lines.count)
        var ls = lines
        let tailEol = ls[index + count - 1].eol
        ls.removeSubrange(index..<(index + count))
        for (k, t) in texts.enumerated() {
            ls.insert(Line(text: t, eol: k == texts.count - 1 ? tailEol : eolStyle), at: index + k)
        }
        return VaultText(bom: bom, lines: ls)
    }

    public func removeLines(_ index: Int, count: Int) -> VaultText {
        var ls = lines; ls.removeSubrange(index..<(index + count))
        return VaultText(bom: bom, lines: ls)
    }

    /// Undo support for multi-line removals.
    public func insertLines(_ index: Int, _ texts: [String]) -> VaultText {
        var acc = self
        for (k, t) in texts.enumerated() { acc = acc.insertLine(index + k, t) }
        return acc
    }

    public func appendLines(_ texts: [String]) -> VaultText { texts.reduce(self) { $0.appendLine($1) } }

    /// Undo support: insert at `index`; past the end it behaves like `appendLine`.
    public func insertLine(_ index: Int, _ text: String) -> VaultText {
        if index >= lines.count { return appendLine(text) }
        var ls = lines; ls.insert(Line(text: text, eol: eolStyle), at: index)
        return VaultText(bom: bom, lines: ls)
    }

    /// Ensures a preceding newline; keeps "no trailing newline" files that way.
    public func appendLine(_ text: String) -> VaultText {
        var ls = lines
        let style = eolStyle
        let newEol: String
        if let last = ls.last, last.eol.isEmpty {
            ls[ls.count - 1] = Line(text: last.text, eol: style)
            newEol = ""
        } else { newEol = style }
        ls.append(Line(text: text, eol: newEol))
        return VaultText(bom: bom, lines: ls)
    }

    public var string: String {
        var out: [UInt8] = bom ? [0xEF, 0xBB, 0xBF] : []
        for l in lines { out += l.text.utf8; out += l.eol.utf8 }
        return String(decoding: out, as: UTF8.self)
    }

    public static func parse(_ s: String) -> VaultText {
        let all = Array(s.utf8)
        let bom = all.count >= 3 && all[0] == 0xEF && all[1] == 0xBB && all[2] == 0xBF
        let body = bom ? Array(all[3...]) : all
        func dec(_ r: Range<Int>) -> String { String(decoding: body[r], as: UTF8.self) }
        var lines: [Line] = []
        var start = 0
        while start < body.count {
            guard let nl = body[start...].firstIndex(of: 0x0A) else {
                lines.append(Line(text: dec(start..<body.count), eol: ""))
                break
            }
            let crlf = nl > start && body[nl - 1] == 0x0D
            lines.append(Line(text: dec(start..<(crlf ? nl - 1 : nl)), eol: crlf ? "\r\n" : "\n"))
            start = nl + 1
        }
        return VaultText(bom: bom, lines: lines)
    }
}
