package io.tesseraql.yaml.flags;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlagsSpecTest {

    @Test
    void anAppWithoutTheFileHasNoFlags(@TempDir Path appHome) {
        assertThat(FlagsSpec.load(appHome).isEmpty()).isTrue();
        assertThat(FlagsSpec.load(appHome).values()).isEmpty();
    }

    @Test
    void loadsTypedFlagValues(@TempDir Path appHome) throws Exception {
        writeFlags(appHome, "flags:\n  beta: true\n  maxItems: 10\n  banner: Hello\n");

        var values = FlagsSpec.load(appHome).values();
        assertThat(values.get("beta")).isEqualTo(true);
        assertThat(values.get("maxItems")).isEqualTo(10);
        assertThat(values.get("banner")).isEqualTo("Hello");
    }

    @Test
    void liveReReadsOnlyWhenTheFileChanges(@TempDir Path appHome) throws Exception {
        assertThat(FlagsSpec.live(appHome).isEmpty()).isTrue();

        writeFlags(appHome, "flags:\n  beta: true\n");
        FlagsSpec first = FlagsSpec.live(appHome);
        assertThat(first.values()).containsEntry("beta", true);
        assertThat(FlagsSpec.live(appHome)).isSameAs(first);

        writeFlags(appHome, "flags:\n  beta: false\n  extra: 1\n");
        FlagsSpec updated = FlagsSpec.live(appHome);
        assertThat(updated).isNotSameAs(first);
        assertThat(updated.values()).containsEntry("beta", false).containsEntry("extra", 1);
    }

    @Test
    void toYamlRoundTripsThroughLoad(@TempDir Path appHome) throws Exception {
        String yaml = FlagsSpec.toYaml(java.util.Map.of("beta", true, "n", 5));
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/flags.yml"), yaml);

        var values = FlagsSpec.load(appHome).values();
        assertThat(values).containsEntry("beta", true).containsEntry("n", 5);
        assertThat(yaml).doesNotStartWith("---");
    }

    private static void writeFlags(Path appHome, String yaml) throws Exception {
        Files.createDirectories(appHome.resolve("config"));
        Files.writeString(appHome.resolve("config/flags.yml"), yaml);
    }
}
