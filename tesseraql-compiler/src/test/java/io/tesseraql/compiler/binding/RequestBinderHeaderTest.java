package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * An input is fed by what the route declares (docs/audit-low-leads.md slice 9).
 *
 * <p>The binder read a request header of the input's name after the path, the body and the
 * query — a fallback docs/vertx-native.md decision 2 said was gone. Every declared input on
 * every route had it: {@code Host} satisfied {@code required: true}, a {@code Priority: u=0, i}
 * header (what Chrome and Firefox send over h2) refused a shipped example's list page with
 * {@code TQL-FIELD-2001}, {@code Cookie} bound the caller's whole cookie header into SQL. And
 * the order the binder did read in disagreed with the one the record and {@code Request.param}
 * declare: the body outranked the query.
 *
 * <p>The header a provider needs is a {@code header.<name>} source on a {@code service:}
 * binding's {@code params:} — read from the wire by the named-query binder, never from a query
 * parameter or a form field spelled like the header. Each test here fails against the binder
 * that fell back.
 */
class RequestBinderHeaderTest {

    private static RuntimeContext context;

    @BeforeAll
    static void start() throws Exception {
        context = new RuntimeContext();
        context.start();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static final RouteDefinition TICKETS = route("""
            version: tesseraql/v1
            id: tickets.list
            kind: route
            recipe: query-json
            input:
              host:
                type: string
                required: true
              priority:
                type: string
                enum: [low, normal, high]
              accept:
                type: boolean
                required: true
              cookie:
                type: string
            response:
              json:
                body:
                  ok: "true"
            """);

    private static RouteDefinition route(String yaml) {
        return new SimpleYamlParser().parseRoute(yaml, "tickets.list");
    }

    private static Exchange browserRequest() {
        Exchange exchange = new Exchange(context.beans());
        exchange.request().uri("/tickets").method("GET");
        exchange.request().header("Host", "shop.example");
        exchange.request().header("Priority", "u=0, i");
        exchange.request().header("Accept", "text/html");
        exchange.request().header("Cookie", "TQLSESSION=s3cret");
        return exchange;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> namespace(Exchange exchange, String key) {
        Map<String, Object> context = (Map<String, Object>) exchange
                .getProperty(TesseraqlProperties.CONTEXT);
        return (Map<String, Object>) context.get(key);
    }

    /**
     * The browser's headers are not the route's inputs: {@code priority} with an enum is not
     * refused for {@code Priority: u=0, i}, {@code cookie} does not carry the session, and
     * {@code required: true} on {@code host} and {@code accept} is missing — the request
     * declared neither.
     */
    @Test
    void aRequestHeaderNeverFeedsAnInput() {
        Exchange exchange = browserRequest();
        exchange.request().queryParams().put("host", List.of("shop-a"));
        exchange.request().queryParams().put("accept", List.of("true"));

        new RequestBinder(TICKETS, "/tickets").process(exchange);

        Map<String, Object> params = namespace(exchange, "params");
        assertThat(params).containsEntry("host", "shop-a").containsEntry("accept", true)
                .doesNotContainKeys("priority", "cookie");
    }

    @Test
    void aRequiredInputIsMissingWhenOnlyAHeaderOfItsNameArrived() {
        Exchange exchange = browserRequest();
        exchange.request().queryParams().put("accept", List.of("true"));

        assertThatThrownBy(() -> new RequestBinder(TICKETS, "/tickets").process(exchange))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-FIELD-2001")
                .hasMessageContaining("Missing required input 'host'");
    }

    /**
     * The declared order, path then query then form — the record's and {@code Request.param}'s
     * — is the binder's: a form field of the query parameter's name no longer outranks it.
     */
    @Test
    void theQueryOutranksTheBody() {
        RouteDefinition echo = route("""
                version: tesseraql/v1
                id: tickets.list
                kind: route
                recipe: command-json
                input:
                  name:
                    type: string
                response:
                  json:
                    body:
                      ok: "true"
                """);
        Exchange form = new Exchange(context.beans());
        form.request().uri("/echo?name=fromquery").method("POST");
        form.request().queryParams().put("name", List.of("fromquery"));
        form.request().formFields().put("name", List.of("fromform"));
        new RequestBinder(echo, "/echo").process(form);
        assertThat(namespace(form, "params")).containsEntry("name", "fromquery");

        // A JSON body is read after the query too, and read at all: the merged view knows
        // nothing of it, so a body-only field still binds.
        Exchange json = new Exchange(context.beans());
        json.request().uri("/echo").method("POST");
        json.setBody("{\"name\":\"frombody\"}");
        new RequestBinder(echo, "/echo").process(json);
        assertThat(namespace(json, "params")).containsEntry("name", "frombody");
    }

    /**
     * The one place a header is a source: a {@code service:} binding's {@code params:}. It reads
     * the wire — the caller's session on the delegated hop — and a query parameter or a form
     * field spelled {@code Cookie} does not stand in for it.
     */
    @Test
    void aServiceBindingReadsADeclaredHeaderSourceFromTheWire() {
        Exchange exchange = browserRequest();
        exchange.request().queryParams().put("Cookie", List.of("TQLSESSION=forged"));
        exchange.request().formFields().put("Cookie", List.of("TQLSESSION=forged"));
        exchange.setProperty(TesseraqlProperties.CONTEXT, new java.util.HashMap<>(Map.of(
                "params", Map.of("Cookie", "TQLSESSION=forged"),
                "query", Map.of("Cookie", "TQLSESSION=forged"),
                "body", Map.of("Cookie", "TQLSESSION=forged"))));
        Binding shell = Binding.service("ops.shell.home", Map.of(
                "cookie", "header.Cookie",
                "csrf", "header.X-CSRF-Token",
                "member", "params.Cookie"));

        new NamedQueryBinder(shell).process(exchange);

        @SuppressWarnings("unchecked")
        Map<String, Object> params = exchange.getProperty(TesseraqlProperties.SQL_PARAMS,
                Map.class);
        assertThat(params).containsEntry("cookie", "TQLSESSION=s3cret")
                .containsEntry("member", "TQLSESSION=forged")
                .containsEntry("csrf", null);
    }

    /** On a statement's binding the same spelling is not a source; the compiler refuses it first. */
    @Test
    void aStatementBindingHasNoHeaderSource() {
        Exchange exchange = browserRequest();
        exchange.setProperty(TesseraqlProperties.CONTEXT, new java.util.HashMap<>());

        new NamedQueryBinder(Binding.sql("list.sql", "query",
                Map.of("cookie", "header.Cookie"))).process(exchange);

        @SuppressWarnings("unchecked")
        Map<String, Object> params = exchange.getProperty(TesseraqlProperties.SQL_PARAMS,
                Map.class);
        assertThat(params).containsEntry("cookie", null);
    }
}
