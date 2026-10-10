import Testing
import Foundation
@testable import VaultTasksCore

@Suite struct FixtureTests {
    @Test func parseFixtures() {
        let f = FixtureRunner.parseFailures()
        #expect(f.isEmpty, "\(f.joined(separator: "\n"))")
    }

    @Test func editFixtures() {
        let f = FixtureRunner.editFailures()
        #expect(f.isEmpty, "\(f.joined(separator: "\n"))")
    }

    // Guards against the runners passing vacuously if cases are ever deleted. Raise when adding cases.
    @Test func fixtureFileKeepsItsCases() {
        #expect(FixtureRunner.caseCount("parseCases") >= 22, "parseCases shrank")
        #expect(FixtureRunner.caseCount("editCases") >= 18, "editCases shrank")
    }
}
