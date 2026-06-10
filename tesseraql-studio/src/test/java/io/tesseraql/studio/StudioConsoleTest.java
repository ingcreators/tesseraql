package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StudioConsoleTest {

    @Test
    void rendersWizardIndex() {
        String html = StudioConsole.renderWizardIndex(StudioWizards.all());

        assertThat(html).contains("Setup wizards");
        assertThat(html).contains("SAML SP").contains("SCIM provisioning")
                .contains("Identity realm mapping");
        assertThat(html).contains("/_tesseraql/studio/ui/wizard/saml");
    }

    @Test
    void rendersWizardForm() {
        String html = StudioConsole.renderWizardForm(StudioWizards.byKind("saml"));

        assertThat(html).contains("SAML SP wizard");
        assertThat(html).contains("action=\"/_tesseraql/studio/ui/wizard/saml\"");
        assertThat(html).contains("name=\"acsUrl\"").contains("name=\"publicKey\"");
        // The certificate field is a textarea; required fields are marked.
        assertThat(html).contains("<textarea name=\"publicKey\"");
        assertThat(html).contains("Generate config");
    }

    @Test
    void rendersWizardResultWithEscapedYaml() {
        String html = StudioConsole.renderWizardResult(StudioWizards.byKind("scim"),
                "tesseraql:\n  scim:\n    enabled: true\n");

        assertThat(html).contains("SCIM provisioning config");
        assertThat(html).contains("<pre class=\"source\">");
        assertThat(html).contains("tesseraql:").contains("enabled: true");
    }
}
