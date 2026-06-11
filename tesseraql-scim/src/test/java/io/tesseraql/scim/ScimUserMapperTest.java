package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScimUserMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializesScimUserJsonAndPicksPrimaryEmail() throws Exception {
        String json = """
                {
                  "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                  "userName": "asmith",
                  "externalId": "ext-1",
                  "name": {"givenName": "Anne", "familyName": "Smith"},
                  "emails": [
                    {"value": "work@example.com", "primary": true},
                    {"value": "home@example.com"}
                  ],
                  "active": true
                }
                """;
        ScimUser user = MAPPER.readValue(json, ScimUser.class);

        assertThat(user.userName()).isEqualTo("asmith");
        assertThat(user.externalId()).isEqualTo("ext-1");
        assertThat(user.name().givenName()).isEqualTo("Anne");
        assertThat(user.primaryEmail()).isEqualTo("work@example.com");
        assertThat(user.active()).isTrue();
    }

    @Test
    void mapsScimUserToContractBindParameters() {
        ScimUser user = new ScimUser(null, null, "ext-9", "bjones",
                new ScimUser.Name("Bob", "Jones", null),
                List.of(new ScimUser.Email("bob@example.com", true)), Boolean.TRUE);

        Map<String, Object> params = ScimUserMapper.toParams(user);
        assertThat(params).containsEntry("userName", "bjones")
                .containsEntry("givenName", "Bob")
                .containsEntry("familyName", "Jones")
                .containsEntry("email", "bob@example.com")
                .containsEntry("externalId", "ext-9")
                .containsEntry("active", Boolean.TRUE);
    }

    @Test
    void buildsScimUserFromContractRowCoercingActive() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 42);
        row.put("userName", "carol");
        row.put("givenName", "Carol");
        row.put("familyName", "King");
        row.put("email", "carol@example.com");
        row.put("active", 1); // numeric flag coerced to boolean
        row.put("externalId", "ext-42");

        ScimUser user = ScimUserMapper.fromRow(row);
        assertThat(user.id()).isEqualTo("42");
        assertThat(user.userName()).isEqualTo("carol");
        assertThat(user.name().familyName()).isEqualTo("King");
        assertThat(user.primaryEmail()).isEqualTo("carol@example.com");
        assertThat(user.active()).isTrue();
        assertThat(user.schemas()).containsExactly(ScimUser.SCHEMA);
    }

    @Test
    void serializesListResponseInScimShape() throws Exception {
        ScimUser user = ScimUserMapper.fromRow(Map.of("id", "1", "userName", "dave"));
        ScimListResponse<ScimUser> list = ScimListResponse.of(List.of(user), 1, 1);

        JsonNode json = MAPPER.valueToTree(list);
        assertThat(json.get("schemas").get(0).asText()).isEqualTo(ScimListResponse.SCHEMA);
        assertThat(json.get("totalResults").asInt()).isEqualTo(1);
        assertThat(json.get("startIndex").asInt()).isEqualTo(1);
        assertThat(json.get("itemsPerPage").asInt()).isEqualTo(1);
        assertThat(json.get("Resources").get(0).get("userName").asText()).isEqualTo("dave");
    }

    @Test
    void serializesErrorInScimShape() throws Exception {
        JsonNode json = MAPPER
                .valueToTree(ScimError.of(409, "userName already exists", "uniqueness"));
        assertThat(json.get("schemas").get(0).asText()).isEqualTo(ScimError.SCHEMA);
        assertThat(json.get("status").asText()).isEqualTo("409");
        assertThat(json.get("scimType").asText()).isEqualTo("uniqueness");
    }
}
