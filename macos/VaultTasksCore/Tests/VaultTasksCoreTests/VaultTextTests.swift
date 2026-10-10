import Testing
import Foundation
@testable import VaultTasksCore

@Suite struct VaultTextTests {
    @Test func parseToStringIsLossless() {
        let samples = ["", "\n", "\n\n", "a", "a\n", "a\r\nb\nc", "\u{FEFF}", "\u{FEFF}a\r\n", "a\rb", "a\r\n\r\n", "  \t\n"]
        for s in samples {
            #expect(sameBytes(VaultText.parse(s).string, s), "sample=\(FixtureRunner.show(s))")
        }
    }

    @Test func appendKeepsNoTrailingNewlineState() {
        #expect(VaultText.parse("a").appendLine("b").string == "a\nb")
        #expect(sameBytes(VaultText.parse("a\r\n").appendLine("b").string, "a\r\nb\r\n"))
        #expect(VaultText.parse("").appendLine("b").string == "b\n")
    }

    @Test func insertAndRemove() {
        let v = VaultText.parse("a\nb\nc\n")
        #expect(v.insertLine(1, "X").string == "a\nX\nb\nc\n")
        #expect(v.insertLine(99, "X").string == "a\nb\nc\nX\n")
        #expect(v.removeLine(1).string == "a\nc\n")
    }

    @Test func replaceLinesKeepsTailTerminator() {
        let v = VaultText.parse("a\r\nb\r\nc")
        #expect(sameBytes(v.replaceLines(0, count: 2, with: ["X", "Y", "Z"]).string, "X\r\nY\r\nZ\r\nc"))
        #expect(sameBytes(v.replaceLines(2, count: 1, with: ["Q"]).string, "a\r\nb\r\nQ"))
        #expect(sameBytes(v.removeLines(0, count: 2).string, "c"))
        #expect(sameBytes(VaultText.parse("a\nb\n").insertLines(1, ["x", "y"]).string, "a\nx\ny\nb\n"))
    }

    @Test func identityIgnoresVariationSelectorsAndNbspAndTrailingSpace() {
        let t = TaskParser.parseFile(path: "f.md", text: VaultText.parse("- [ ] A\u{00A0}b \u{23EB}\n- [ ] A b \u{23EB}\u{FE0F}  \n"))
        #expect(t.map(\.id.occurrence) == [0, 1])
        #expect(Set(t.map { Array($0.id.normalizedText.utf8) }).count == 1)
    }
}
