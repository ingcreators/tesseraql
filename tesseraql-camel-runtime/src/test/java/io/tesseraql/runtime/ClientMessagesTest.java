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
    void japaneseModuleMergesKitTranslationsAndAppCatalog() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("ja.yml"),
                "users.provision.unknown-user: 指定されたユーザーは存在しません。\n");

        String script = new String(new ClientMessages(home, "en").script("ja"),
                StandardCharsets.UTF_8);

        assertThat(script)
                .startsWith("import { setMessages } from "
                        + "\"/assets/vendor/hypermedia-components__core/dist/hc.behaviors.min.js\"")
                .contains("setMessages(")
                // The kit's built-in strings translate...
                .contains("\"confirm.cancel\":\"キャンセル\"")
                // ...and the app's own keys ride along for installFieldErrors.
                .contains("\"users.provision.unknown-user\":\"指定されたユーザーは存在しません。\"");
    }

    @Test
    void englishModuleCarriesOnlyAppEntries() throws Exception {
        Path messages = Files.createDirectories(home.resolve("messages"));
        Files.writeString(messages.resolve("en.yml"), "greeting: Hello\n");

        String script = new String(new ClientMessages(home, "en").script("en"),
                StandardCharsets.UTF_8);

        // The kit's English defaults need no override; only app entries ship.
        assertThat(script).contains("\"greeting\":\"Hello\"")
                .doesNotContain("confirm.cancel");
    }

    @Test
    void missingOrInvalidLocaleFallsToTheAppDefault() {
        ClientMessages messages = new ClientMessages(home, "ja");
        assertThat(messages.normalize(null)).isEqualTo("ja");
        assertThat(messages.normalize("not_a_tag")).isEqualTo("ja");
        assertThat(messages.normalize("ja-JP")).isEqualTo("ja-JP");
    }
}
