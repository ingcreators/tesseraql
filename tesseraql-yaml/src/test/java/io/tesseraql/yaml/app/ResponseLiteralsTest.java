package io.tesseraql.yaml.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.app.ExportDeclarations.Violation;
import io.tesseraql.yaml.model.ResponseSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The response literals the edge writes as given (docs/audit-low-leads.md EH-06): a file
 * response's {@code charset=} the body is not written in ({@code TQL-YAML-1073}) and a redirect
 * location with whitespace at either end ({@code TQL-YAML-1074}) — one predicate for lint and
 * boot. Neither used to be read by anything.
 */
class ResponseLiteralsTest {

    private static List<Violation> violations(ResponseSpec response) {
        RouteDefinition route = new RouteDefinition("tesseraql/v1", "items.print", "route",
                "page", Map.of(), null, null, null, null, null, Map.of(), Map.of(), null, null,
                null, null, null, null, null, null, null, response, null, null, null, null,
                null, null);
        return ResponseLiterals.violations("t", route, "/items/{id}/print");
    }

    private static ResponseSpec file(String contentType) {
        return new ResponseSpec(null, null, null, null,
                new ResponseSpec.FileResponse(null, "receipt.txt", contentType, null, Map.of()),
                null, null, null);
    }

    private static ResponseSpec redirect(String location) {
        return new ResponseSpec(null, null, null,
                new ResponseSpec.RedirectResponse(null, location), null, null, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain; charset=Shift_JIS", "text/csv;charset=windows-31j",
            "text/plain; CHARSET=\"ISO-8859-1\""})
    void aCharsetTheBodyIsNotWrittenInIsRefused(String contentType) {
        assertThat(violations(file(contentType))).singleElement().satisfies(violation -> {
            assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1073");
            assertThat(violation.kind()).isEqualTo(ExportDeclarations.Kind.INVALID);
            assertThat(violation.key()).isEqualTo("response.file.contentType");
            assertThat(violation.message()).contains("app 't'", "route 'items.print'",
                    "response.file.contentType", "the body is written as UTF-8",
                    "charset=utf-8");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain; charset=utf-8", "text/plain; charset=UTF-8",
            "text/plain;charset=\"utf8\"", "text/plain", "application/json"})
    void utf8OrNoCharsetIsTheBodyAsWritten(String contentType) {
        assertThat(violations(file(contentType))).isEmpty();
        assertThat(violations(file(null))).isEmpty();
    }

    @Test
    void whitespaceAtEitherEndOfARedirectLocationIsRefusedAndTheLeadingCaseNamed() {
        assertThat(violations(redirect("/foo "))).singleElement().satisfies(violation -> {
            assertThat(violation.code().toString()).isEqualTo("TQL-YAML-1074");
            assertThat(violation.key()).isEqualTo("response.redirect.location");
            assertThat(violation.message()).contains("route 'items.print'", "'/foo '",
                    "whitespace at its end", "%20");
        });
        // A leading space is not a %20 cosmetic: the base-path join leaves a value that does
        // not start with / untouched, so the browser resolves it outside the app.
        assertThat(violations(redirect(" /foo"))).singleElement()
                .extracting(Violation::message).asString()
                .contains("whitespace at its start", "keeps the base path off");
        assertThat(violations(redirect("\t/foo\n"))).hasSize(1);
        assertThat(violations(redirect("/foo"))).isEmpty();
        assertThat(violations(redirect("/items/{path.id}"))).isEmpty();
        assertThat(violations(redirect("back"))).isEmpty();
        assertThat(violations(redirect(null))).isEmpty();
    }

    @Test
    void aResponseWithNeitherLiteralIsQuiet() {
        assertThat(violations(new ResponseSpec(null, null, null, null, null, null, null, null)))
                .isEmpty();
        assertThat(violations(null)).isEmpty();
    }
}
