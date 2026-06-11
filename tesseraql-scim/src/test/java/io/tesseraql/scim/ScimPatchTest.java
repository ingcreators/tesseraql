package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScimPatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ScimUser sample() {
        return new ScimUser(null, "1", "ext", "asmith",
                new ScimUser.Name("Anne", "Smith", null),
                List.of(new ScimUser.Email("anne@example.com", true)), Boolean.TRUE);
    }

    private static ScimPatchRequest patch(String json) throws Exception {
        return MAPPER.readValue(json, ScimPatchRequest.class);
    }

    @Test
    void replacesAttributeByPath() throws Exception {
        ScimUser result = ScimPatch.apply(sample(), patch("""
                {"Operations":[{"op":"replace","path":"name.givenName","value":"Annette"}]}
                """));
        assertThat(result.name().givenName()).isEqualTo("Annette");
        assertThat(result.name().familyName()).isEqualTo("Smith"); // untouched
    }

    @Test
    void deactivatesViaPathlessReplaceObject() throws Exception {
        ScimUser result = ScimPatch.apply(sample(), patch("""
                {"Operations":[{"op":"replace","value":{"active":false}}]}
                """));
        assertThat(result.active()).isFalse();
        assertThat(result.userName()).isEqualTo("asmith"); // untouched
    }

    @Test
    void removeClearsAttribute() throws Exception {
        ScimUser result = ScimPatch.apply(sample(), patch("""
                {"Operations":[{"op":"remove","path":"externalId"}]}
                """));
        assertThat(result.externalId()).isNull();
    }

    @Test
    void rejectsUnsupportedPath() throws Exception {
        ScimPatchRequest request = patch("""
                {"Operations":[{"op":"replace","path":"x509Certificates","value":"abc"}]}
                """);
        assertThatThrownBy(() -> ScimPatch.apply(sample(), request))
                .isInstanceOf(ScimException.class)
                .satisfies(ex -> assertThat(((ScimException) ex).scimType()).isEqualTo("invalidPath"));
    }
}
