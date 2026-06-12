package io.tesseraql.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfinedUriResolverTest {

    @Test
    void resolvesFilesInsideTheRootOnly(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("web/print"));
        Files.writeString(root.resolve("web/print/style.css"), "body{}");
        ConfinedUriResolver resolver = new ConfinedUriResolver(root);
        String base = root.resolve("web/print").toUri().toString();

        assertThat(resolver.resolveURI(base, "style.css"))
                .isEqualTo(root.resolve("web/print/style.css").toUri().toString());
        assertThat(resolver.resolveURI(base, "../../../etc/passwd")).isNull();
        assertThat(resolver.resolveURI(base, "/etc/passwd")).isNull();
    }

    @Test
    void blocksNetworkFetchesAndKeepsDataUris(@TempDir Path root) {
        ConfinedUriResolver resolver = new ConfinedUriResolver(root);
        String base = root.toUri().toString();

        assertThat(resolver.resolveURI(base, "https://example.com/font.ttf")).isNull();
        assertThat(resolver.resolveURI(base, "http://example.com/x.css")).isNull();
        assertThat(resolver.resolveURI(base, "data:image/png;base64,AAAA"))
                .isEqualTo("data:image/png;base64,AAAA");
    }

    @Test
    void withoutARootNothingFileBasedResolves() {
        ConfinedUriResolver resolver = new ConfinedUriResolver(null);

        assertThat(resolver.resolveURI(null, "style.css")).isNull();
        assertThat(resolver.resolveURI(null, "file:///etc/passwd")).isNull();
    }
}
