package app.vaulttasks.domain

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class FixtureTest {
    @Test fun parseFixtures() {
        val f = FixtureRunner.parseFailures()
        if (f.isNotEmpty()) fail<Unit>(f.joinToString("\n"))
    }

    @Test fun editFixtures() {
        val f = FixtureRunner.editFailures()
        if (f.isNotEmpty()) fail<Unit>(f.joinToString("\n"))
    }

    // Guards against the runners passing vacuously if cases are ever deleted. Raise when adding cases.
    @Test fun fixtureFileKeepsItsCases() {
        assertTrue(FixtureRunner.caseCount("parseCases") >= 22, "parseCases shrank")
        assertTrue(FixtureRunner.caseCount("editCases") >= 18, "editCases shrank")
    }
}
