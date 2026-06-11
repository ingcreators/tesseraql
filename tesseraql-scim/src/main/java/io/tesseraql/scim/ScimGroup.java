package io.tesseraql.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The SCIM 2.0 core Group resource, restricted to the attributes TesseraQL maps (design ch. 10.15,
 * RFC 7643 §4.2). Unknown attributes are ignored on input and omitted when null on output.
 *
 * @param schemas     the resource's schema URIs (defaults to the core Group schema)
 * @param id          the service-provider id (our group id)
 * @param externalId  the id assigned by the provisioning client (the IdP)
 * @param displayName the human-readable group name (required by SCIM)
 * @param members     the group's members (user ids)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimGroup(List<String> schemas, String id, String externalId, String displayName,
        List<Member> members) {

    public static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    public ScimGroup {
        schemas = schemas == null || schemas.isEmpty() ? List.of(SCHEMA) : List.copyOf(schemas);
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** A SCIM group member (RFC 7643 §4.2): {@code value} is the member resource id. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(String value, String display, @JsonProperty("$ref") String ref) {
    }
}
