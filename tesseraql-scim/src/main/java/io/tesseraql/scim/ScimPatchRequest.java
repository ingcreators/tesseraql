package io.tesseraql.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A SCIM 2.0 PATCH request (design ch. 10.15, RFC 7644 §3.5.2): an ordered list of add/replace/remove
 * operations, each optionally targeting an attribute {@code path}.
 *
 * @param operations the operations to apply, in order
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimPatchRequest(List<String> schemas,
        @JsonProperty("Operations") List<Operation> operations) {

    public static final String SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";

    public ScimPatchRequest {
        operations = operations == null ? List.of() : List.copyOf(operations);
    }

    /** A single PATCH operation; {@code value} is a raw JSON node (scalar or object). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Operation(String op, String path, JsonNode value) {
    }
}
