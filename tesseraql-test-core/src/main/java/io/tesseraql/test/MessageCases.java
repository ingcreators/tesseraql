package io.tesseraql.test;

import io.tesseraql.test.TestSuite.TestCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The {@code messages} case kind: catalog keys resolved for a locale, one row per key. */
final class MessageCases {

    private final SuiteContext context;

    MessageCases(SuiteContext context) {
        this.context = context;
    }

    /**
     * Resolves message-catalog keys for a locale and returns them as rows (roadmap Phase 22):
     * one row per key with {@code key}, {@code locale}, and {@code text} columns. Lookup reads
     * the app's {@code messages/<locale>.yml} catalogs with the same exact-tag-then-bare-language
     * walk as the runtime; an unresolvable key yields a null {@code text}, so an expectation on
     * it fails visibly.
     */
    List<Map<String, Object>> evaluate(TestCase test) {
        TestSuite.MessagesTarget target = test.messages();
        if (target.locale() == null || target.locale().isBlank()) {
            throw new IllegalArgumentException("A messages case needs a messages.locale tag");
        }
        io.tesseraql.yaml.i18n.MessageCatalog catalog = io.tesseraql.yaml.i18n.MessageCatalog
                .load(context.appHome().resolve("messages"));
        String tag = java.util.Locale.forLanguageTag(target.locale().trim()).toLanguageTag();
        List<String> keys = target.keys() == null || target.keys().isEmpty()
                ? catalog.forLocale(tag).keySet().stream().sorted().toList()
                : target.keys();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : keys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("locale", tag);
            row.put("text", catalog.resolve(tag, key));
            rows.add(row);
        }
        return rows;
    }
}
