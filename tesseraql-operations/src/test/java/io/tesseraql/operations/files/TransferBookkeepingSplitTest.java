package io.tesseraql.operations.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Whether a transfer writes its record and verdict on its work connection, or on a second one
 * (docs/capacity-defaults.md decision 5b). The test is the database, not the pool object: main's
 * role pools open onto main's own database, where the transfer table lives, so a transfer on one
 * commits its rows and its verdict in one transaction, as on main. Only a tenant's pool splits.
 *
 * <p>Compared by pool object, a role pool would have split like a tenant's: two commits where
 * there was one, and a second connection held for the run.
 */
class TransferBookkeepingSplitTest {

    private static final DataSource MAIN = pool();
    private static final DataSource FILE_TRANSFERS = pool();
    private static final DataSource JOBS = pool();
    private static final DataSource TENANT = pool();

    @Test
    void mainAndItsRolePoolsAreOneDatabaseAndATenantsPoolIsNot() {
        JdbcFileTransferService service = new JdbcFileTransferService(null, null, null, MAIN,
                null, null).mainRoles(FILE_TRANSFERS, JOBS);

        assertThat(service.splits(MAIN)).isFalse();
        assertThat(service.splits(FILE_TRANSFERS)).isFalse();
        assertThat(service.splits(JOBS)).isFalse();
        assertThat(service.splits(TENANT)).isTrue();
    }

    @Test
    void withNoRolesDeclaredOnlyMainIsMainsDatabase() {
        JdbcFileTransferService service = new JdbcFileTransferService(null, null, null, MAIN,
                null, null).mainRoles(null, null);

        assertThat(service.splits(MAIN)).isFalse();
        assertThat(service.splits(FILE_TRANSFERS)).isTrue();
    }

    /** A stand-in whose identity is all that matters here. */
    private static DataSource pool() {
        return (DataSource) Proxy.newProxyInstance(
                TransferBookkeepingSplitTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
