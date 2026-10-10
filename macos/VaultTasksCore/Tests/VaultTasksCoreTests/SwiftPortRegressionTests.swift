import Testing
import Foundation
@testable import VaultTasksCore

/// §13.3: pitfalls that would silently break byte-exactness in Swift but cannot occur on the JVM.
@Suite struct SwiftPortRegressionTests {
    private func tasks(_ s: String, filter: String = "") -> [VaultTask] {
        TaskParser.parseFile(path: "f.md", text: VaultText.parse(s), globalFilter: filter)
    }

    // --- line splitting -------------------------------------------------------------------------------------

    @Test func crlfIsTwoTerminatorsNotOneCharacter() {
        let s = "- [ ] A\r\n- [ ] B \u{1F4C5} 2026-09-28\r\n"
        #expect(s.split(separator: "\n").count == 1) // proves the Character-view trap this port avoids
        let vt = VaultText.parse(s)
        #expect(vt.lines.count == 2)
        #expect(vt.lines.map(\.eol) == ["\r\n", "\r\n"])
        #expect(tasks(s).count == 2)
        #expect(sameBytes(vt.string, s))
    }

    @Test func loneCarriageReturnStaysInsideTheLine() {
        let vt = VaultText.parse("a\rb\n")
        #expect(vt.lines.count == 1)
        #expect(sameBytes(vt.lines[0].text, "a\rb"))
        let t = tasks("- [ ] A\rB\n- [ ] C\n")
        #expect(t.count == 2)
        #expect(sameBytes(t[0].description, "A\rB"))
    }

    @Test func bomIsAFlagAndFirstLineStillParses() {
        let vt = VaultText.parse("\u{FEFF}- [ ] A\n")
        #expect(vt.bom)
        #expect(tasks("\u{FEFF}- [ ] A\n").count == 1)
        #expect(Array(vt.string.utf8.prefix(3)) == [0xEF, 0xBB, 0xBF])
        #expect(!VaultText.parse("- [ ] A\n").bom)
    }

    // --- identity: bytes, not canonical equivalence ---------------------------------------------------------

    @Test func canonicallyEquivalentButByteDifferentLinesAreDifferentIdentities() {
        let nfc = "- [ ] caf\u{E9}", nfd = "- [ ] cafe\u{301}"
        #expect(nfc == nfd) // Swift String equality would conflate them
        let t = tasks(nfc + "\n" + nfd + "\n")
        #expect(t.count == 2)
        #expect(t[0].id != t[1].id)
        #expect(t.map(\.id.occurrence) == [0, 0]) // two distinct keys, so neither is an "nth duplicate"
        #expect(Set(t.map(\.id)).count == 2)
    }

    @Test func identicalBytesStillCountOccurrences() {
        #expect(tasks("- [ ] caf\u{E9}\n- [ ] caf\u{E9}\n").map(\.id.occurrence) == [0, 1])
    }

    @Test func taskEqualityIsByteExact() {
        let a = tasks("- [ ] caf\u{E9}\n")[0], b = tasks("- [ ] cafe\u{301}\n")[0]
        #expect(a != b)
        #expect(a == tasks("- [ ] caf\u{E9}\n")[0])
    }

    @Test func reconcilerSeesCanonicalEquivalentRewriteAsDeletePlusInsert() throws {
        let c = try Reconciler.reconcile(cached: tasks("- [ ] caf\u{E9}\n"), parsed: tasks("- [ ] cafe\u{301}\n"))
        #expect(c.inserts.count == 1 && c.deletes.count == 1 && c.updates.isEmpty)
        // same identity, different notes or hint → update, never silently "unchanged"
        let u = try Reconciler.reconcile(cached: tasks("- [ ] a\n\t- caf\u{E9}\n"), parsed: tasks("- [ ] a\n\t- cafe\u{301}\n"))
        #expect(u.updates.count == 1 && u.inserts.isEmpty && u.deletes.isEmpty)
    }

    @Test func identityNormalizesOnlyVariationSelectorsAndNbsp() {
        #expect(TaskParser.normalize("  - [ ] A\u{00A0}b \u{23EB}\u{FE0F} \t") == "- [ ] A b \u{23EB}")
        #expect(sameBytes(TaskParser.normalize("- [ ] cafe\u{301}"), "- [ ] cafe\u{301}")) // not NFC-normalized
    }

    // --- untouched bytes stay untouched ---------------------------------------------------------------------

    @Test func editLeavesVariationSelectorsAndNbspOnOtherLinesByteIdentical() throws {
        let src = "- [ ] keep\u{00A0}me \u{23EB}\u{FE0F}\r\n- [ ] edit me\r\n\u{00A0}\u{00A0}- [ ] nbsp indent\r\nplain \u{1F4C5}\u{FE0F} text"
        let vt = VaultText.parse(src)
        let p = try #require(TaskParser.parseLine(vt.lines[1].text))
        let edited = try TaskSerializer.applyFields(p, TaskFields(description: "edit me", dueDate: LocalDate.parse("2026-10-05"), dueTime: nil))
        let out = vt.replaceLine(1, edited).string
        #expect(sameBytes(out, "- [ ] keep\u{00A0}me \u{23EB}\u{FE0F}\r\n- [ ] edit me \u{1F4C5} 2026-10-05\r\n\u{00A0}\u{00A0}- [ ] nbsp indent\r\nplain \u{1F4C5}\u{FE0F} text"))
    }

    @Test func variationSelectorOnAnEditedSignifierIsPreservedWhenUnchanged() throws {
        // the due token keeps its original bytes (emoji variant + VS) when the date is not changed
        let p = try #require(TaskParser.parseLine("- [ ] x \u{1F4C6}\u{FE0F} 2026-10-05"))
        let same = try TaskSerializer.applyFields(p, TaskFields(description: "x", dueDate: LocalDate.parse("2026-10-05"), dueTime: nil))
        #expect(sameBytes(same, "- [ ] x \u{1F4C6}\u{FE0F} 2026-10-05"))
    }

    // --- emoji at the line end ------------------------------------------------------------------------------

    @Test func emojiAtLineEnd() throws {
        let a = try #require(tasks("- [ ] Do it \u{1F53A}\n").first)
        #expect(a.priority == .highest && a.description == "Do it")
        let b = try #require(tasks("- [ ] Do it \u{23EB}\u{FE0F}\n").first)
        #expect(b.priority == .high && b.description == "Do it")
        let c = try #require(tasks("- [ ] Do it \u{1F4C5} 2026-10-05").first) // no trailing newline
        #expect(c.dueDate == LocalDate.parse("2026-10-05"))
        let d = try #require(tasks("- [ ] Ship \u{1F680}\n").first) // unknown emoji stays in the description
        #expect(d.description == "Ship \u{1F680}")
    }

    @Test func descriptionKeepsCombiningMarksAndZwjSequences() throws {
        let t = try #require(tasks("- [ ] fam \u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467} caf\u{65}\u{301} \u{1F4C5} 2026-10-05\n").first)
        #expect(sameBytes(t.description, "fam \u{1F468}\u{200D}\u{1F469}\u{200D}\u{1F467} caf\u{65}\u{301}"))
    }

    // --- global filter is a scalar-exact substring ----------------------------------------------------------

    @Test func globalFilterMatchesScalarsNotGraphemes() {
        #expect(tasks("- [ ] a #task\n- [ ] b\n", filter: "#task").count == 1)
        // "e" alone must not match inside "e\u{301}" (a grapheme-level contains would)
        #expect(tasks("- [ ] cafe\u{301}\n", filter: "cafe\u{301}").count == 1)
        #expect(tasks("- [ ] caf\u{E9}\n", filter: "cafe\u{301}").count == 0)
    }

    // --- Kotlin whitespace vs Java regex whitespace ---------------------------------------------------------

    @Test func unicodeWhitespaceTrimsButOnlyAsciiSpaceSeparatesTokens() throws {
        // trailing NBSP is trimmed like Kotlin's trim(); the ⏰ pattern's \s* is ASCII-only
        let t = try #require(tasks("- [ ] x \u{1F4C5} 2026-10-05\u{00A0}\n").first)
        #expect(t.dueDate == LocalDate.parse("2026-10-05"))
        #expect(tasks("- [ ] \u{00A0}\n").isEmpty) // blank (Unicode) description is not a task
        let u = try #require(tasks("- [ ] call\u{00A0}\u{23F0} 09:00 \u{1F4C5} 2026-10-05\n").first)
        #expect(u.dueTime == LocalTime(hour: 9, minute: 0))
        #expect(sameBytes(u.description, "call"))
    }

    @Test func fencedCodeIsIgnoredAndFencesDoNotNestAcrossKinds() {
        let s = "- [ ] a\n```\n- [ ] in code\n~~~\n- [ ] still code\n```\n- [ ] b\n~~~\n- [ ] c\n"
        #expect(tasks(s).map(\.description) == ["a", "b"])
    }
}
