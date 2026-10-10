import Foundation

// Kotlin-compatible text primitives (port rules §3.1): everything works on unicode scalars or UTF-8 bytes, never on
// `Character`, and identity/"unchanged" checks compare bytes, never `String ==` (canonical equivalence).

typealias Scalars = [Unicode.Scalar]

func str<S: Sequence>(_ s: S) -> String where S.Element == Unicode.Scalar {
    var v = String.UnicodeScalarView()
    v.append(contentsOf: s)
    return String(v)
}

extension String {
    var scalarArray: Scalars { Array(unicodeScalars) }
}

/// Kotlin `Char.isWhitespace()` = Java `isWhitespace || isSpaceChar`: Zs, Zl, Zp plus \t..\r and U+001C..U+001F.
@inline(__always) func ktIsWhitespace(_ u: Unicode.Scalar) -> Bool {
    switch u.value {
    case 0x09...0x0D, 0x1C...0x1F, 0x20, 0xA0, 0x1680, 0x2000...0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000: return true
    default: return false
    }
}

/// Java regex `\s` without UNICODE_CHARACTER_CLASS: [ \t\n\x0B\f\r].
@inline(__always) func javaIsSpace(_ u: Unicode.Scalar) -> Bool {
    switch u.value {
    case 0x09...0x0D, 0x20: return true
    default: return false
    }
}

@inline(__always) func isAsciiDigit(_ u: Unicode.Scalar) -> Bool { u.value >= 0x30 && u.value <= 0x39 }
@inline(__always) func isAsciiLetter(_ u: Unicode.Scalar) -> Bool {
    (u.value >= 0x41 && u.value <= 0x5A) || (u.value >= 0x61 && u.value <= 0x7A)
}

/// Kotlin `Char.isDigit()` works on UTF-16 chars: supplementary-plane digits are surrogate pairs and never count.
func ktIsDigit(_ u: Unicode.Scalar) -> Bool { u.value <= 0xFFFF && u.properties.generalCategory == .decimalNumber }

/// Kotlin `Char.isLetterOrDigit()` (BMP only, as above).
func ktIsLetterOrDigit(_ u: Unicode.Scalar) -> Bool {
    guard u.value <= 0xFFFF else { return false }
    switch u.properties.generalCategory {
    case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: return true
    default: return false
    }
}

/// Java regex `\p{L}` / `\p{N}` work on code points.
func regexIsLetter(_ u: Unicode.Scalar) -> Bool {
    switch u.properties.generalCategory {
    case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
    default: return false
    }
}
func regexIsNumber(_ u: Unicode.Scalar) -> Bool {
    switch u.properties.generalCategory {
    case .decimalNumber, .letterNumber, .otherNumber: return true
    default: return false
    }
}

func ktTrimStart(_ s: Scalars) -> Scalars {
    var i = 0
    while i < s.count && ktIsWhitespace(s[i]) { i += 1 }
    return Array(s[i...])
}
func ktTrimEnd(_ s: Scalars) -> Scalars {
    var j = s.count
    while j > 0 && ktIsWhitespace(s[j - 1]) { j -= 1 }
    return Array(s[..<j])
}
func ktTrim(_ s: Scalars) -> Scalars { ktTrimStart(ktTrimEnd(s)) }
func ktIsBlank(_ s: Scalars) -> Bool { s.allSatisfy(ktIsWhitespace) }

extension String {
    var ktTrimmed: String { str(ktTrim(scalarArray)) }
    var ktTrimmedStart: String { str(ktTrimStart(scalarArray)) }
    var ktTrimmedEnd: String { str(ktTrimEnd(scalarArray)) }
    var ktIsBlank: Bool { unicodeScalars.allSatisfy(ktIsWhitespace) }
}

func sameBytes(_ a: String, _ b: String) -> Bool { a.utf8.elementsEqual(b.utf8) }
func sameBytes(_ a: String?, _ b: String?) -> Bool {
    switch (a, b) {
    case (nil, nil): return true
    case let (x?, y?): return sameBytes(x, y)
    default: return false
    }
}

/// Kotlin `String.compareTo` order: UTF-16 code units.
public func utf16Less(_ a: String, _ b: String) -> Bool { a.utf16.lexicographicallyPrecedes(b.utf16) }

func scalarsHavePrefix(_ s: Scalars, _ p: Scalars) -> Bool { s.count >= p.count && Array(s[..<p.count]) == p }

func scalarsContain(_ hay: Scalars, _ needle: Scalars) -> Bool {
    if needle.isEmpty { return true }
    if needle.count > hay.count { return false }
    for i in 0...(hay.count - needle.count) where hay[i] == needle[0] {
        if Array(hay[i..<(i + needle.count)]) == needle { return true }
    }
    return false
}

/// Kotlin `String.lines()`: splits on \r\n, \n and \r; a trailing terminator yields a trailing empty line.
func ktLines(_ s: String) -> [String] {
    var out: [String] = []
    var cur = Scalars()
    var it = s.unicodeScalars.makeIterator()
    var pending: Unicode.Scalar? = it.next()
    while let c = pending {
        pending = it.next()
        if c == "\r" {
            if pending == "\n" { pending = it.next() }
            out.append(str(cur)); cur.removeAll()
        } else if c == "\n" {
            out.append(str(cur)); cur.removeAll()
        } else {
            cur.append(c)
        }
    }
    out.append(str(cur))
    return out
}

/// Kotlin `String.replace(old, new)` (all occurrences, code-unit exact; byte-exact here).
func replacingAll(_ s: String, _ target: String, with r: String) -> String {
    let h = Array(s.utf8), t = Array(target.utf8), rep = Array(r.utf8)
    guard !t.isEmpty, h.count >= t.count else { return s }
    var out: [UInt8] = []
    var i = 0
    while i < h.count {
        if i + t.count <= h.count, h[i] == t[0], Array(h[i..<(i + t.count)]) == t {
            out += rep; i += t.count
        } else { out.append(h[i]); i += 1 }
    }
    return String(decoding: out, as: UTF8.self)
}
