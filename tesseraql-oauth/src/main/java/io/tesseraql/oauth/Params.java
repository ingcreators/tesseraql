package io.tesseraql.oauth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query-string and form-urlencoded parsing for the protocol endpoints — first value wins, keys
 * without a value land as empty strings. Small and local on purpose: the endpoints live outside
 * the compiled-route pipeline, so the request binder never sees their parameters.
 */
final class Params {

    private Params() {
    }

    static Map<String, String> parse(String encoded) {
        Map<String, String> params = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return params;
        }
        for (String pair : encoded.split("&")) {
            int split = pair.indexOf('=');
            String key = decode(split < 0 ? pair : pair.substring(0, split));
            String value = split < 0 ? "" : decode(pair.substring(split + 1));
            params.putIfAbsent(key, value);
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
