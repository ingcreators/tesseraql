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
            case TENANT, APP -> code.number() == 4031 ? 403 : 404;
            default -> 500;
        };
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 429 -> "Too Many Requests";
            default -> "Internal Server Error";
        };
    }
}
