package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScimGroupPatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ScimGroup group() {
        return new ScimGroup(null, "1", "ext-1", "engineers",
                List.of(new ScimGroup.Member("100", null, null),
                        new ScimGroup.Member("200", null, null)));
    }

    private static ScimGroup patch(ScimGroup current, String json) {
        try {
            return ScimGroupPatch.apply(current, MAPPER.readValue(json, ScimPatchRequest.class));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void replacesDisplayNameAndExternalId() {
        ScimGroup result = patch(group(), """
                {"Operations":[
                  {"op":"replace","path":"displayName","value":"platform"},
                  {"op":"replace","path":"externalId","value":"ext-9"}
                ]}
                """);
        assertThat(result.displayName()).isEqualTo("platform");
        assertThat(result.externalId()).isEqualTo("ext-9");
        assertThat(memberValues(result)).containsExactly("100", "200");
    }

    @Test
    void replaceMembersReplacesWholeSet() {
        ScimGroup result = patch(group(), """
                {"Operations":[{"op":"replace","path":"members","value":[{"value":"300"}]}]}
                """);
        assertThat(memberValues(result)).containsExactly("300");
    }

    @Test
    void addAndRemoveMembersAdjustTheSet() {
        ScimGroup result = patch(group(), """
                {"Operations":[
                  {"op":"add","path":"members","value":[{"value":"300"}]},
                  {"op":"remove","path":"members[value eq \\"100\\"]"}
                ]}
                """);
        assertThat(memberValues(result)).containsExactlyInAnyOrder("200", "300");
    }

    @Test
    void removeMembersWithoutValueClearsAll() {
        ScimGroup result = patch(group(), """
                {"Operations":[{"op":"remove","path":"members"}]}
                """);
        assertThat(result.members()).isEmpty();
    }

    @Test
    void pathlessReplaceAppliesObjectFields() {
        ScimGroup result = patch(group(),
                """
                        {"Operations":[{"op":"replace","value":{"displayName":"renamed","members":[{"value":"9"}]}}]}
                        """);
        assertThat(result.displayName()).isEqualTo("renamed");
        assertThat(memberValues(result)).containsExactly("9");
    }

    @Test
    void unsupportedPathIsRejected() {
        assertThatThrownBy(() -> patch(group(), """
                {"Operations":[{"op":"replace","path":"meta.created","value":"x"}]}
                """)).hasCauseInstanceOf(ScimException.class);
    }

    private static List<String> memberValues(ScimGroup group) {
        return group.members().stream().map(ScimGroup.Member::value).toList();
    }
}
