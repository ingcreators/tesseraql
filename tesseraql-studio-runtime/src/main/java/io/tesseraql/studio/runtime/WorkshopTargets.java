package io.tesseraql.studio.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.service.ServiceProviders;
import io.tesseraql.runtime.HostContext;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Where the studio shell's delegated calls go (docs/studio-shell.md structural decision 2): a
 * hosted stack's shell makes real HTTP over loopback to the selected member's workshop API with
 * the caller's own credentials — the member re-runs its own atom check, so the shell adds reach,
 * never authority — while the unhosted boot's shell invokes its one member's providers in
 * process, because an ephemeral test port is no address to call yourself on (the ops shell's
 * `Targets.self` precedent).
 */
interface WorkshopTargets {

    /** TQL-STUDIO-4043: unknown member and out-of-scope member read identically (404). */
    TqlErrorCode NOT_FOUND = new TqlErrorCode(TqlDomain.STUDIO, 4043);
    /** TQL-STUDIO-5030: the member's runtime did not answer the delegated call (503). */
    TqlErrorCode UNREACHABLE = new TqlErrorCode(TqlDomain.STUDIO, 5030);

    List<String> memberNames();

    /**
     * Invokes {@code op} on {@code member}'s workshop with the caller's credentials. The
     * result is the provider's own return value: usually a view model map, occasionally a
     * scalar (a CSV string, a byte array) that a file response templates.
     */
    Object invoke(String member, String op, Map<String, Object> params,
            List<String> permissions, String cookie, String csrf);

    static TqlException notFound(String member) {
        return new TqlException(NOT_FOUND, "No workshop for '" + member + "'");
    }

    /** The hosted shell: HTTP over loopback, the member's port resolved live per call. */
    static WorkshopTargets of(List<String> members, HostContext.MemberOrigins origins) {
        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newHttpClient();
        return new WorkshopTargets() {
            @Override
            public List<String> memberNames() {
                return members;
            }

            @Override
            public Object invoke(String member, String op, Map<String, Object> params,
                    List<String> permissions, String cookie, String csrf) {
                int port;
                try {
                    port = origins.port(member, false);
                } catch (TqlException ex) {
                    throw notFound(member);
                }
                String verb = WorkshopOps.OPS.get(op);
                StringBuilder form = new StringBuilder();
                params.forEach((key, value) -> {
                    if (value == null) {
                        return;
                    }
                    if (form.length() > 0) {
                        form.append('&');
                    }
                    form.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                            .append(URLEncoder.encode(String.valueOf(value),
                                    StandardCharsets.UTF_8));
                });
                String url = "http://localhost:" + port + "/" + member
                        + "/_tesseraql/studio/data/"
                        + (WorkshopOps.PUBLIC.contains(op) ? "public/" : "") + op
                        + ("GET".equals(verb) && form.length() > 0 ? "?" + form : "");
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60));
                if (cookie != null) {
                    request.header("Cookie", cookie);
                }
                if ("POST".equals(verb)) {
                    request.header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form.toString()));
                    if (csrf != null) {
                        request.header("X-CSRF-Token", csrf);
                    }
                }
                HttpResponse<String> response;
                try {
                    response = client.send(request.build(),
                            HttpResponse.BodyHandlers.ofString());
                } catch (Exception ex) {
                    if (ex instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw new TqlException(UNREACHABLE,
                            "The workshop for '" + member + "' did not answer");
                }
                if (response.statusCode() == 404) {
                    throw notFound(member);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    // The member's own refusal (a 403 atom refusal, a 4xx input rejection)
                    // rides back as-is: re-throw its code so the surface answers with the
                    // member's verdict rather than inventing one.
                    throw memberError(response);
                }
                try {
                    Map<String, Object> decoded = decodeBytes(
                            mapper.readValue(response.body(), Map.class));
                    // A scalar result rode the hop in the value envelope (see the member's
                    // workshop API); a map result is itself.
                    return decoded.size() == 1 && decoded.containsKey("__value__")
                            ? decoded.get("__value__")
                            : decoded;
                } catch (java.io.IOException ex) {
                    throw new TqlException(UNREACHABLE,
                            "The workshop for '" + member + "' answered unparseably");
                }
            }

            private TqlException memberError(HttpResponse<String> response) {
                try {
                    Map<?, ?> body = mapper.readValue(response.body(), Map.class);
                    if (body.get("error") instanceof Map<?, ?> error
                            && error.get("code") instanceof String code) {
                        String[] parts = code.split("-");
                        return new TqlException(new TqlErrorCode(
                                TqlDomain.valueOf(parts[1]), Integer.parseInt(parts[2])),
                                String.valueOf(error.get("message")));
                    }
                } catch (Exception ignored) {
                    // Fall through to the generic refusal below.
                }
                return new TqlException(UNREACHABLE, "The workshop call was refused ("
                        + response.statusCode() + ")");
            }
        };
    }

    /** The unhosted boot: one member — this runtime — invoked in process. */
    static WorkshopTargets self(String appName, Supplier<ServiceProviders> providers) {
        return new WorkshopTargets() {
            @Override
            public List<String> memberNames() {
                return List.of(appName);
            }

            @Override
            public Object invoke(String member, String op, Map<String, Object> params,
                    List<String> permissions, String cookie, String csrf) {
                if (!appName.equals(member)) {
                    throw notFound(member);
                }
                // The identity stamp the hosted path's member applies from its own
                // authenticated principal: in process there is no second principal — the
                // caller's own binding is it.
                params.put("permissions", permissions == null ? List.of() : permissions);
                return providers.get().require(op).invoke(params);
            }
        };
    }

    /**
     * Byte-valued model entries ride the JSON hop as {@code {"__bytes__": base64}} markers —
     * the render-preview PDF and the routes-PDF export are byte arrays inside otherwise plain
     * view models, and Jackson would silently deliver them back as strings.
     */
    static Object encodeBytes(Object value) {
        if (value instanceof byte[] bytes) {
            return Map.of("__bytes__", Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), encodeBytes(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(WorkshopTargets::encodeBytes).toList();
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodeBytes(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), decodeValue(v)));
        return out;
    }

    private static Object decodeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.get("__bytes__") instanceof String encoded) {
                return Base64.getDecoder().decode(encoded);
            }
            return decodeBytes(map);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(WorkshopTargets::decodeValue).toList();
        }
        return value;
    }
}
