package io.tesseraql.test;

import io.tesseraql.test.TestSuite.TestCase;
import java.util.List;
import java.util.Map;

/**
 * The Identity SQL Contract case kind: the named contract executes through the configured
 * {@link io.tesseraql.identity.IdentityService} against the case's realm, and its result rows
 * are the case's rows — the contract's own statements, not a copy of them.
 */
final class IdentityCases {

    private final SuiteContext context;

    IdentityCases(SuiteContext context) {
        this.context = context;
    }

    List<Map<String, Object>> evaluate(TestCase test) {
        if (context.identity() == null || context.realm() == null) {
            throw new IllegalStateException(
                    "Contract tests require an identity service and realm");
        }
        return context.identity().execute(context.realm(),
                stripIdentityPrefix(test.contract()), test.params());
    }

    private static String stripIdentityPrefix(String contract) {
        return io.tesseraql.identity.IdentityContracts.unqualify(contract);
    }
}
