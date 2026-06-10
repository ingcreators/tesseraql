package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StudioWizardsTest {

    @Test
    void listsThreeWizardsWithFields() {
        assertThat(StudioWizards.all()).extracting(StudioWizards.Wizard::kind)
                .containsExactly("saml", "scim", "identity");
        assertThat(StudioWizards.byKind("saml").fields()).isNotEmpty();
        assertThatThrownBy(() -> StudioWizards.byKind("nope")).isInstanceOf(TqlException.class);
    }

    @Test
    void generatesSamlConfig() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("spAudience", "https://app.example.com/saml");
        in.put("acsUrl", "https://app.example.com/acs");
        in.put("ssoUrl", "https://idp.example.com/sso");
        in.put("loginIdAttribute", "uid");
        in.put("provision", "true");

        String yaml = StudioWizards.generate("saml", in);

        assertThat(yaml).contains("tesseraql:").contains("saml:").contains("enabled: true");
        assertThat(yaml).contains("audience: \"https://app.example.com/saml\"");
        assertThat(yaml).contains("ssoUrl: \"https://idp.example.com/sso\"");
        assertThat(yaml).contains("loginId: \"uid\"").contains("provision: true");
        // Optional fields left blank are omitted.
        assertThat(yaml).doesNotContain("sloUrl").doesNotContain("email:");
    }

    @Test
    void samlMissingRequiredFieldThrows() {
        Map<String, String> in = Map.of("acsUrl", "https://x/acs");
        assertThatThrownBy(() -> StudioWizards.generate("saml", in))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("spAudience");
    }

    @Test
    void scimEmitsTokenEnvPlaceholderNeverLiteralSecret() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("groups", "true");
        in.put("outbound", "true");
        in.put("outboundUrl", "https://idp.example.com/scim");
        in.put("outboundTokenEnv", "MY_TOKEN");

        String yaml = StudioWizards.generate("scim", in);

        assertThat(yaml).contains("scim:").contains("groups:").contains("enabled: true");
        assertThat(yaml).contains("url: \"https://idp.example.com/scim\"");
        assertThat(yaml).contains("token: \"${MY_TOKEN}\"");
    }

    @Test
    void scimOutboundDisabledOmitsTarget() {
        String yaml = StudioWizards.generate("scim", Map.of("outbound", "false"));
        assertThat(yaml).contains("enabled: false").doesNotContain("target:");
    }

    @Test
    void generatesIdentityRealmMapping() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("realmId", "corp");
        in.put("type", "sql");
        in.put("datasource", "main");
        in.put("sqlRoot", "security/identity/corp");
        in.put("userManagement", "readWrite");

        String yaml = StudioWizards.generate("identity", in);

        assertThat(yaml).contains("defaultRealm: \"corp\"");
        assertThat(yaml).contains("corp:");
        assertThat(yaml).contains("type: \"sql\"").contains("datasource: \"main\"");
        assertThat(yaml).contains("sqlRoot: \"security/identity/corp\"");
        assertThat(yaml).contains("capabilities:").contains("userManagement: \"readWrite\"");
    }

    @Test
    void escapesQuotesInValues() {
        Map<String, String> in = Map.of("realmId", "a\"b", "type", "managed", "datasource", "main");
        String yaml = StudioWizards.generate("identity", in);
        assertThat(yaml).contains("defaultRealm: \"a\\\"b\"");
    }
}
