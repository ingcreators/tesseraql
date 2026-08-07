package io.tesseraql.yaml.template;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code tql/email/*} fragment signatures (docs/notifications.md "HTML mail"), parsed
 * from the library file that will actually resolve at render: the app's shadow copy under
 * {@code templates/tql/email/} when present (customization-ladder L2), else the bundled
 * classpath library. Shared by the mail wiring lint (docs/pages-and-mail-lints.md D2) and
 * Studio's mail composer palette.
 */
public final class EmailFragments {

    /** The two library files the {@code tql/email} namespace serves. */
    public static final String LIBRARY = "hc-email";
    public static final String LAYOUT = "hc-email-layout";

    private static final Pattern SIGNATURE = Pattern
            .compile("th:fragment=\"(\\w+)(?:\\(([^)]*)\\))?\"");
    private static final String CLASSPATH_PREFIX = "tesseraql/templates/tql/email/";

    private EmailFragments() {
    }

    /** Fragment name to parameter names for a library file, honoring the app's shadow. */
    public static Map<String, List<String>> signatures(Path appHome, String library) {
        Path shadow = appHome.resolve("templates/tql/email/" + library + ".html").normalize();
        if (Files.isRegularFile(shadow)) {
            try {
                return parse(Files.readString(shadow));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return bundled(library);
    }

    /** Fragment name to parameter names for the bundled classpath library file. */
    public static Map<String, List<String>> bundled(String library) {
        String resource = CLASSPATH_PREFIX + library + ".html";
        try (InputStream in = EmailFragments.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Parses {@code th:fragment} signatures out of a library file's markup. */
    public static Map<String, List<String>> parse(String html) {
        Map<String, List<String>> signatures = new LinkedHashMap<>();
        Matcher matcher = SIGNATURE.matcher(html);
        while (matcher.find()) {
            List<String> params = matcher.group(2) == null || matcher.group(2).isBlank()
                    ? List.of()
                    : List.of(matcher.group(2).split(",\\s*"));
            signatures.put(matcher.group(1), params);
        }
        return signatures;
    }
}
