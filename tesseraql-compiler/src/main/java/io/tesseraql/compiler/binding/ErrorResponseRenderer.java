package io.tesseraql.compiler.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Renders a caught exception into a JSON error response (design ch. 37.2, 37.4).
 *
 * <p>External responses expose only {@code code}, a generic {@code message}, and a trace id;
 * internal diagnostics (source, line) are not leaked (design ch. 37.3).
 */
public final class ErrorResponseRenderer implements Processor {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void process(Exchange exchange) throws Exception {
        Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        TqlErrorCode code = cause instanceof TqlException tql
                ? tql.code()
                : new TqlErrorCode(TqlDomain.CAMEL, 5000);
        int status = httpStatus(code);

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.toString());
        error.put("message", reasonPhrase(status));
        Map<String, Object> body = Map.of("error", error);

        // Inbound form fields can surface as multi-line message headers (platform-http); drop them
        // so the error response is writable as HTTP (header values must not contain newlines).
        exchange.getMessage().getHeaders().entrySet()
                .removeIf(entry -> entry.getValue() instanceof String value
                        && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0));

        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getMessage().setBody(mapper.writeValueAsString(body));
    }

    /** Maps an error code to an HTTP status (design ch. 37.4). */
    public static int httpStatus(TqlErrorCode code) {
        return switch (code.domain()) {
            case SEC -> switch (code.number()) {
                case 4011 -> 401;
                case 4031, 4032 -> 403;
                default -> 401;
            };
            case FIELD -> 400;
            case RATE -> 429;
            case LANE -> code.number() == 5031 ? 503 : 500;
            case STUDIO -> switch (code.number()) {
                case 4002 -> 400;
                case 4030 -> 403;
                case 4040 -> 404;
                case 4221 -> 422;
                default -> 500;
            };
            case IDEM -> code.number() == 4090 ? 409 : 500;
            case LD -> switch (code.number()) {
                case 2820 -> 400; // file-import without an uploaded body
                case 2822 -> 404; // unknown transfer id
                case 2823 -> 409; // export not ready for download yet
                default -> 500;
            };
            case IAM -> code.number() == 4030 ? 403 : 500;
            case SQL -> switch (code.number()) {
                case 4001, 4002 -> 400; // not-null / check violation
                case 4090, 4091, 4093 -> 409; // unique / foreign-key / serialization conflict
                default -> 500;
            };
            case TENANT, APP -> switch (code.number()) {
                case 4001 -> 400;
                case 4031 -> 403;
                default -> 404;
            };
            default -> 500;
        };
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 422 -> "Unprocessable Entity";
            case 429 -> "Too Many Requests";
            case 503 -> "Service Unavailable";
            default -> "Internal Server Error";
        };
    }
}
