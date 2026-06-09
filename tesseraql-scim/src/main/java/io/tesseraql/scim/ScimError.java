package io.tesseraql.scim;

import java.util.List;

/**
 * A SCIM 2.0 error response (design ch. 10.15, RFC 7644 §3.12).
 *
 * @param detail   a human-readable description of the error
 * @param status   the HTTP status code as a string (SCIM carries it in the body)
 * @param scimType the SCIM detail error keyword, when applicable (e.g. {@code uniqueness})
 */
public record ScimError(List<String> schemas, String detail, String status, String scimType) {

    public static final String SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";

    public static ScimError of(int status, String detail) {
        return new ScimError(List.of(SCHEMA), detail, String.valueOf(status), null);
    }

    public static ScimError of(int status, String detail, String scimType) {
        return new ScimError(List.of(SCHEMA), detail, String.valueOf(status), scimType);
    }
}
