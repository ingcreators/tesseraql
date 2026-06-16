package io.tesseraql.cli.modules;

import io.tesseraql.yaml.config.AppConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the declared {@code tesseraql.modules} from app config and edits {@code config/tesseraql.yml}
 * to add a coordinate (the {@code tesseraql modules add} helper). The edit is a minimal, in-place
 * line insertion so the rest of the user's config — comments and formatting — is preserved.
 */
public final class ModulesYaml {

    /** The dotted config key holding the opt-in module list. */
    public static final String MODULES_KEY = "tesseraql.modules";

    private ModulesYaml() {
    }

    /** The declared module coordinates, with env interpolation applied; empty when none. */
    public static List<ModuleCoordinate> declared(AppConfig config) {
        Object node = config.navigate(MODULES_KEY);
        if (!(node instanceof List<?> list)) {
            return List.of();
        }
        List<ModuleCoordinate> coordinates = new ArrayList<>();
        for (Object entry : list) {
            if (entry != null) {
                coordinates.add(ModuleCoordinate.parse(config.resolve(entry.toString())));
            }
        }
        return coordinates;
    }

    /**
     * Returns {@code yaml} with {@code coordinate} added under the top-level {@code tesseraql.modules}
     * list. If the {@code modules:} block is absent it is created as the first child of
     * {@code tesseraql:}; if {@code coordinate} is already listed the text is returned unchanged.
     */
    public static String addModule(String yaml, String coordinate) {
        String item = "    - " + coordinate;
        List<String> lines = new ArrayList<>(List.of(yaml.split("\n", -1)));
        int tesseraqlIndex = indexOfLine(lines, 0, "tesseraql:");
        if (tesseraqlIndex < 0) {
            throw new IllegalStateException(
                    "config/tesseraql.yml has no top-level 'tesseraql:' mapping");
        }
        int modulesIndex = indexOfLine(lines, tesseraqlIndex + 1, "  modules:");
        if (modulesIndex < 0) {
            lines.add(tesseraqlIndex + 1, item);
            lines.add(tesseraqlIndex + 1, "  modules:");
            return String.join("\n", lines);
        }
        int insertAt = modulesIndex + 1;
        for (int i = modulesIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("    - ")) {
                if (line.strip().equals("- " + coordinate)) {
                    return yaml;
                }
                insertAt = i + 1;
            } else {
                break;
            }
        }
        lines.add(insertAt, item);
        return String.join("\n", lines);
    }

    private static int indexOfLine(List<String> lines, int from, String exact) {
        for (int i = from; i < lines.size(); i++) {
            if (lines.get(i).stripTrailing().equals(exact)) {
                return i;
            }
        }
        return -1;
    }
}
