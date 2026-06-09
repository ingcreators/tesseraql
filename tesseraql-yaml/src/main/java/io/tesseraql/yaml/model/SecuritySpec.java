package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Route security declaration (design ch. 6.3, 11). Routes are deny-by-default; a route is only
 * public when explicitly declared so (design ch. 20.14).
 *
 * @param auth     authentication type: {@code bearer}, {@code browser}, {@code apiKey}, etc.
 * @param policy   authorization policy id evaluated against the principal
 * @param provider optional named provider (for example a SAML provider)
 * @param csrf     whether CSRF protection is required (browser state-changing routes)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecuritySpec(String auth, String policy, String provider, Boolean csrf) {
}
