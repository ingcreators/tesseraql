package io.tesseraql.scim;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A SCIM 2.0 list response envelope (design ch. 10.15, RFC 7644 §3.4.2): paginated query results
 * with 1-based {@code startIndex} and the total match count.
 *
 * @param totalResults total number of matching resources
 * @param startIndex   1-based index of the first returned resource
 * @param itemsPerPage number of resources returned in this page
 * @param resources    the page of resources
 */
public record ScimListResponse<T>(List<String> schemas, int totalResults, int startIndex,
        int itemsPerPage, @JsonProperty("Resources") List<T> resources) {

    public static final String SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";

    public static <T> ScimListResponse<T> of(List<T> resources, int totalResults, int startIndex) {
        return new ScimListResponse<>(List.of(SCHEMA), totalResults, startIndex,
                resources.size(), List.copyOf(resources));
    }
}
