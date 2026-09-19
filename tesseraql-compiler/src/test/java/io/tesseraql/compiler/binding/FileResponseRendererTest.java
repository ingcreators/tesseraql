package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.ResponseSpec.FileResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A file response's download name is a template over the context its {@code model:} reads
 * (docs/route-filename-placeholders.md decision 1): {@code user-{path.id}.txt} is offered as
 * {@code user-42.txt}, a request value is folded before it names the file, and a literal name
 * is what it always was. Red on a renderer that writes the declared name as given.
 */
class FileResponseRendererTest {

    @TempDir
    Path home;

    private Path routeDir;

    @BeforeEach
    void appWithATemplate() throws Exception {
        routeDir = Files.createDirectories(home.resolve("web/users/{id}/print"));
        Files.writeString(routeDir.resolve("receipt.txt"), "User [(${name})]\n");
    }

    private Exchange exchange(Object id, String month) {
        Exchange exchange = new Exchange(Beans.NONE);
        Map<String, Object> context = new HashMap<>();
        context.put("path", Map.of("id", id));
        context.put("params", month == null ? Map.of() : Map.of("month", month));
        context.put("main", Map.of("rows", List.of(Map.of("name", "aoki")), "rowCount", 1));
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        return exchange;
    }

    private FileResponseRenderer renderer(String filename) {
        return new FileResponseRenderer(new FileResponse(null, "receipt.txt", "text/plain",
                filename, Map.of("name", "main.rows")), home, routeDir);
    }

    @Test
    void thePlaceholdersResolveAgainstTheRequestAndTheSources() {
        Exchange exchange = exchange(42, "2026/09");

        renderer("user-{path.id}-{params.month}-{main.rowCount}.txt").process(exchange);

        assertThat(exchange.response().header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"user-42-2026_09-1.txt\"");
        assertThat(exchange.response().header("Content-Type")).isEqualTo("text/plain");
        assertThat(String.valueOf(exchange.getBody())).contains("aoki");
    }

    @Test
    void anAbsentValueIsAnUnderscoreAndALiteralNameIsUnchanged() {
        Exchange absent = exchange(7, null);
        renderer("user-{params.month}.txt").process(absent);
        assertThat(absent.response().header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"user-_.txt\"");

        Exchange literal = exchange(7, null);
        renderer("users.txt").process(literal);
        assertThat(literal.response().header("Content-Disposition"))
                .isEqualTo("attachment; filename=\"users.txt\"");

        Exchange none = exchange(7, null);
        renderer(null).process(none);
        assertThat(none.response().header("Content-Disposition")).isNull();
    }
}
