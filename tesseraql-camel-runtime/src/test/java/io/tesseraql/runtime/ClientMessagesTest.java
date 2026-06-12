package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientMessagesTest {

    @TempDir
    Path home;

    @Test
    void japaneseModuleImportsTheKitsLocalePackThenAppEntries() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("ja.yml"),
                "users.provision.unknown-user: 指定されたユーザーは存在しません。\n");

        String script = new String(new ClientMessages(home, "en").script("ja"),
                StandardCharsets.UTF_8);

        assertThat(script)
                .startsWith("import { setMessages } from "
                        + "\"/assets/vendor/hypermedia-components__core/dist/hc.behaviors.min.js\"")
                // The kit's official pack (hc 0.1.1) loads first...
                .contains("import pack from "
                        + "\"/assets/vendor/hypermedia-components__core/dist/locales/ja.js\"")
                .contains("setMessages(pack);")
                // ...then the app's own entries layer over it (later merges win).
                .contains("\"users.provision.unknown-user\":\"指定されたユーザーは存在しません。\"");
        assertThat(script.indexOf("setMessages(pack)"))
                .isLessThan(script.indexOf("users.provision.unknown-user"));
    }

    @Test
    void regionalTagsImportTheBareLanguagePack() {
        String script = new String(new ClientMessages(home, "en").script("ja-JP"),
                StandardCharsets.UTF_8);

        assertThat(script).contains("dist/locales/ja.js");
    }

    @Test
    void englishModuleCarriesOnlyAppEntries() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("en.yml"), "greeting: Hello\n");

        String script = new String(new ClientMessages(home, "en").script("en"),
                StandardCharsets.UTF_8);

        // English is the kit's built-in default: no pack ships, no import emitted.
        assertThat(script).contains("\"greeting\":\"Hello\"")
                .doesNotContain("dist/locales/");
    }

    @Test
    void missingOrInvalidLocaleFallsToTheAppDefault() {
        ClientMessages messages = new ClientMessages(home, "ja");
        assertThat(messages.normalize(null)).isEqualTo("ja");
        assertThat(messages.normalize("not_a_tag")).isEqualTo("ja");
        assertThat(messages.normalize("ja-JP")).isEqualTo("ja-JP");
    }
}
