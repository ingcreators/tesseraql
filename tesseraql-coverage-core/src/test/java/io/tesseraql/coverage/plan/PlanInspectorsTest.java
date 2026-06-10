package io.tesseraql.coverage.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import org.junit.jupiter.api.Test;

class PlanInspectorsTest {

    @Test
    void selectsInspectorByDialect() {
        assertThat(PlanInspectors.find("postgres")).get().isInstanceOf(PostgresPlanInspector.class);
        assertThat(PlanInspectors.find("mysql")).get().isInstanceOf(MysqlPlanInspector.class);
        assertThat(PlanInspectors.find("mariadb")).get().isInstanceOf(MysqlPlanInspector.class);
        assertThat(PlanInspectors.find("oracle")).get().isInstanceOf(OraclePlanInspector.class);
        assertThat(PlanInspectors.find("sqlserver")).get().isInstanceOf(SqlServerPlanInspector.class);
        assertThat(PlanInspectors.find("db2")).isEmpty();
        assertThat(PlanInspectors.find(null)).isEmpty();
    }

    @Test
    void forDialectThrowsOnUnsupported() {
        assertThat(PlanInspectors.forDialect("POSTGRESQL").dialect()).isEqualTo("postgres");
        assertThatThrownBy(() -> PlanInspectors.forDialect("db2"))
                .isInstanceOf(TqlException.class);
    }
}
