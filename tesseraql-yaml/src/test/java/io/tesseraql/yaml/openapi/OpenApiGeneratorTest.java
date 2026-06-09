package io.tesseraql.yaml.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class OpenApiGeneratorTest {

    private static AppManifest exampleApp() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    @Test
    void generatesPathsMethodsAndSecurity() {
        String json = new OpenApiGenerator().toJson(exampleApp());

        assertThat(json).contains("\"openapi\" : \"3.0.3\"");
        assertThat(json).contains("\"/api/users\"").contains("\"operationId\" : \"users.search\"");
        assertThat(json).contains("\"bearerAuth\"");
        assertThat(json).contains("\"name\" : \"q\"").contains("\"in\" : \"query\"");
    }

    @Test
    void pathParametersAreIncluded() {
        String json = new OpenApiGenerator().toJson(exampleApp());
        // POST /api/admin/users/{id}/disable declares a path parameter id.
        assertThat(json).contains("\"name\" : \"id\"").contains("\"in\" : \"path\"");
    }

    @Test
    void outputIsDeterministic() {
        AppManifest manifest = exampleApp();
        OpenApiGenerator generator = new OpenApiGenerator();
        assertThat(generator.toJson(manifest)).isEqualTo(generator.toJson(manifest));
    }
}
