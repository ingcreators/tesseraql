package io.tesseraql.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Template-ready models for the bundled account surface (roadmap Phase 48). The route maps
 * the session principal's facts into the service params — the provider never resolves a
 * subject itself, so the page can only ever describe the caller.
 */
final class AccountViews {

    private AccountViews() {
    }

    /** The profile page model: who the system thinks the signed-in user is. */
    static Map<String, Object> profile(Map<String, Object> params) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", text(params.get("displayName"), text(params.get("loginId"), "")));
        profile.put("loginId", text(params.get("loginId"), ""));
        profile.put("subject", text(params.get("subject"), ""));
        profile.put("tenantId", text(params.get("tenantId"), ""));
        profile.put("roles", strings(params.get("roles")));
        profile.put("permissions", strings(params.get("permissions")));
        return profile;
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
