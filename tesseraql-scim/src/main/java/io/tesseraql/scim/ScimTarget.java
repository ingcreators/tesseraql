package io.tesseraql.scim;

/**
 * A downstream SCIM service provider to provision to (design ch. 10.15 outbound): its base URL and
 * the bearer token used to authenticate provisioning calls.
 *
 * @param baseUrl     the SCIM base URL, e.g. {@code https://idp.example.com/scim/v2}
 * @param bearerToken the bearer token for the {@code Authorization} header
 */
public record ScimTarget(String baseUrl, String bearerToken) {

    /** The {@code /Users} collection URL, with any trailing slash on the base normalized. */
    public String usersUrl() {
        return base() + "/Users";
    }

    /** The {@code /Groups} collection URL, with any trailing slash on the base normalized. */
    public String groupsUrl() {
        return base() + "/Groups";
    }

    private String base() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
