package io.tesseraql.yaml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The scaffold⇄consumer drift test (docs/config-consumers.md): every configuration key the
 * {@code tesseraql new} templates emit must have a registered consumer, and each registered
 * consumer file must actually mention the key's leaf segment — "wire it or don't emit it",
 * enforced against the real templates so the class of emitted-but-dead config
 * (the retired {@code security.defaults.api/htmx}, the unread {@code camel.components}) is
 * unrepresentable from here on.
 */
class ScaffoldedConfigKeysTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Path REPO_ROOT = Paths.get("..").toAbsolutePath().normalize();

    @Test
    void everyScaffoldedConfigKeyHasARegisteredConsumer() throws Exception {
        List<String> leaves = new ArrayList<>();
        for (ScaffoldedFile file : new AppScaffolder().scaffold("drift-check")) {
            if (!file.path().startsWith("config/") || !file.path().endsWith(".yml")
                    || file.path().equals("config/menu.yml")) {
                continue;
            }
            Map<String, Object> tree = YAML.readValue(file.content(),
                    new TypeReference<Map<String, Object>>() {
                    });
            collect("", tree, leaves);
        }
        assertThat(leaves).isNotEmpty();
        for (String leaf : leaves) {
            assertThat(registered(leaf))
                    .as("scaffolded config key '%s' has no consumer in ScaffoldedConfigKeys —"
                            + " wire it or don't emit it (docs/config-consumers.md)", leaf)
                    .isTrue();
        }
    }

    @Test
    void everyRegisteredConsumerActuallyMentionsItsKey() throws Exception {
        for (Map.Entry<String, String> entry : ScaffoldedConfigKeys.CONSUMERS.entrySet()) {
            Path source = REPO_ROOT.resolve(entry.getValue());
            assertThat(source).as(entry.getKey()).isRegularFile();
            // Case-insensitive: db.main.url reaches its consumer as the jdbcUrl mapping.
            String leaf = entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1)
                    .toLowerCase(java.util.Locale.ROOT);
            assertThat(Files.readString(source).toLowerCase(java.util.Locale.ROOT))
                    .as("registered consumer %s does not mention '%s' — the registry is lying",
                            entry.getValue(), leaf)
                    .contains(leaf);
        }
    }

    /** A leaf is covered when it, or any dotted ancestor, is registered. */
    private static boolean registered(String leaf) {
        String path = leaf;
        while (true) {
            if (ScaffoldedConfigKeys.CONSUMERS.containsKey(path)) {
                return true;
            }
            int dot = path.lastIndexOf('.');
            if (dot < 0) {
                return false;
            }
            path = path.substring(0, dot);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collect(String prefix, Map<String, Object> tree, List<String> leaves) {
        tree.forEach((key, value) -> {
            String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> map) {
                collect(path, (Map<String, Object>) map, leaves);
            } else {
                leaves.add(path);
            }
        });
    }
}
