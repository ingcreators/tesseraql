package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The login page's first-login hint is a command that runs as written: {@code identity-schema}
 * needs a database, which {@code --app} supplies, so a hint without it taught a command the
 * CLI refuses (docs/audit-medium-leads.md, defect 6).
 */
class LoginMethodsTest {

    @Test
    void theSeedHintNamesTheApplication() {
        Object hint = LoginMethods.of(new AppConfig(Map.of())).get("seedHint");

        assertThat(String.valueOf(hint))
                .startsWith("tesseraql identity-schema --app <dir> --admin-login <id>")
                .contains("--admin-password-file <file>");
    }
}
