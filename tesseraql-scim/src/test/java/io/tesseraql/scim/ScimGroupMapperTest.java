package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScimGroupMapperTest {

    @Test
    void defaultsSchemaAndMembersWhenAbsent() {
        ScimGroup group = new ScimGroup(null, "1", null, "team", null);
        assertThat(group.schemas()).containsExactly(ScimGroup.SCHEMA);
        assertThat(group.members()).isEmpty();
    }

    @Test
    void toParamsFlattensGroupAttributes() {
        ScimGroup group = new ScimGroup(null, "7", "ext-7", "engineers",
                List.of(new ScimGroup.Member("100", null, null)));
        assertThat(ScimGroupMapper.toParams(group))
                .containsEntry("id", "7")
                .containsEntry("externalId", "ext-7")
                .containsEntry("displayName", "engineers");
    }

    @Test
    void fromRowRebuildsGroupWithResolvedMembers() {
        Map<String, Object> row = Map.of("id", 9, "displayName", "ops", "externalId", "ext-9");
        ScimGroup group = ScimGroupMapper.fromRow(row,
                List.of(ScimGroupMapper.memberFromRow(Map.of("value", "200", "display", "Bob"))));
        assertThat(group.id()).isEqualTo("9");
        assertThat(group.displayName()).isEqualTo("ops");
        assertThat(group.externalId()).isEqualTo("ext-9");
        assertThat(group.members()).hasSize(1);
        assertThat(group.members().get(0).value()).isEqualTo("200");
        assertThat(group.members().get(0).display()).isEqualTo("Bob");
    }
}
