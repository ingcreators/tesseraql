package io.tesseraql.yaml.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Admission NOTEs (docs/duckdb.md): where the analytics engine talks beyond the app tree. */
class AdmissionNotesTest {

    @Test
    void surfacesRemoteLakesRemotesAndWriteAttaches(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/main
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        extensions: [ducklake, postgres, httpfs]
                        attach:
                          - { datasource: main, as: app, mode: readwrite }
                        lake:
                          catalog: main
                          data: s3://acme-lake/inv/
                          endpoint: minio.internal:9000
                          credentials: { keyId: k, secret: s }
                        remotes:
                          drops:
                            url: s3://acme-lake/drops/
                            endpoint: minio.internal:9000
                            credentials: { keyId: k, secret: s }
                """);

        AdmissionProfile.Report report = AdmissionProfile.check(dir);

        assertThat(report.notes()).anyMatch(n -> n.code().equals("NOTE-ATTACH-READWRITE")
                && n.reason().contains("'main'"));
        assertThat(report.notes()).anyMatch(n -> n.code().equals("NOTE-REMOTE-LAKE")
                && n.reason().contains("s3://acme-lake/inv/")
                && n.reason().contains("minio.internal:9000"));
        assertThat(report.notes()).anyMatch(n -> n.code().equals("NOTE-REMOTE-READ")
                && n.reason().contains("${remote.drops}")
                && n.reason().contains("s3://acme-lake/drops/"));
    }
}
