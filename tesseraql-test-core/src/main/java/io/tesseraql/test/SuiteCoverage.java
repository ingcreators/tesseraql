package io.tesseraql.test;

import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.test.TestSuite.Expectation;
import io.tesseraql.test.TestSuite.TestCase;
import java.util.List;

/**
 * Derives non-SQL coverage kinds from the declarative test suites (design ch. 14): assertion
 * coverage (which cases actually assert something) and IAM-contract coverage (which standard identity
 * contracts the tests exercise). Both reuse the {@link ItemCoverage} model; the manifest-based
 * kinds (route, security, SAML, SCIM) live in {@link ManifestCoverage}.
 */
public final class SuiteCoverage {

    private SuiteCoverage() {
    }

    /** Assertion coverage: every case is declared, and cases that assert are covered. */
    public static ItemCoverage assertions(List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("assertion");
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                coverage.declare(test.name());
                if (asserts(test.expect())) {
                    coverage.cover(test.name());
                }
            }
        }
        return coverage;
    }

    /** IAM-contract coverage: standard contracts declared, those run by a contract case covered. */
    public static ItemCoverage contracts(List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("iam-contract")
                .declareAll(IdentityContracts.standardContracts());
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (test.contract() != null && !test.contract().isBlank()) {
                    coverage.cover(stripIdentityPrefix(test.contract()));
                }
            }
        }
        return coverage;
    }

    private static boolean asserts(Expectation expect) {
        return expect != null && (expect.rowCount() != null || !expect.rows().isEmpty());
    }

    private static String stripIdentityPrefix(String contract) {
        return contract.startsWith("identity.")
                ? contract.substring("identity.".length())
                : contract;
    }
}
