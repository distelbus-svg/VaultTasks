package app.vaulttasks.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VaultTextTest {
    @Test fun parseToStringIsLossless() {
        val samples = listOf("", "\n", "\n\n", "a", "a\n", "a\r\nb\nc", "\uFEFF", "\uFEFFa\r\n", "a\rb", "a\r\n\r\n", "  \t\n")
        for (s in samples) assertEquals(s, VaultText.parse(s).toString(), "sample=${s.replace("\r", "\\r").replace("\n", "\\n")}")
    }

    @Test fun appendKeepsNoTrailingNewlineState() {
        assertEquals("a\nb", VaultText.parse("a").appendLine("b").toString())
        assertEquals("a\r\nb\r\n", VaultText.parse("a\r\n").appendLine("b").toString())
        assertEquals("b\n", VaultText.parse("").appendLine("b").toString())
    }

    @Test fun insertAndRemove() {
        val v = VaultText.parse("a\nb\nc\n")
        assertEquals("a\nX\nb\nc\n", v.insertLine(1, "X").toString())
        assertEquals("a\nb\nc\nX\n", v.insertLine(99, "X").toString())
        assertEquals("a\nc\n", v.removeLine(1).toString())
    }

    @Test fun identityIgnoresVariationSelectorsAndNbspAndTrailingSpace() {
        val t = TaskParser.parseFile("f.md", VaultText.parse("- [ ] A\u00a0b ⏫\n- [ ] A b ⏫\ufe0f  \n"))
        assertEquals(listOf(0, 1), t.map { it.id.occurrence })
        assertEquals(1, t.map { it.id.normalizedText }.toSet().size)
    }
}
