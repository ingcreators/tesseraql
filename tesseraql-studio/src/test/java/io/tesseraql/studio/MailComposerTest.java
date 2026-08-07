package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.studio.MailComposer.Block;
import io.tesseraql.studio.MailComposer.Composition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The composer grammar's round-trip rule (docs/html-email.md D4): parse accepts exactly
 * what write emits — including the helpdesk example's shape — and rejects everything
 * else, so the composer can never lossily rewrite a hand-authored template.
 */
class MailComposerTest {

    private static final String HELPDESK_SHAPE = """
            <!-- The assignment notice as HTML mail. -->
            <div th:replace="~{tql/email/hc-email-layout :: hcLayout('Ticket assigned',
                |Ticket ${payload.ticket} was assigned to you|, ~{:: content})}">
              <div th:fragment="content">
                <div th:replace="~{tql/email/hc-email :: hcHeading('Ticket assigned')}"></div>
                <div th:replace="~{tql/email/hc-email :: hcText(|Ticket "${payload.ticket}" was assigned to you.|)}"></div>
                <div th:replace="~{tql/email/hc-email :: hcBadge(|Priority: ${payload.priority}|)}"></div>
                <div th:replace="~{tql/email/hc-email :: hcSeparator}"></div>
                <div th:replace="~{tql/email/hc-email :: hcFooter(|Sent by ${event.app} (event ${event.id})|)}"></div>
              </div>
            </div>
            """;

    @Test
    void parsesTheCanonicalShape() {
        Composition composition = MailComposer.parse(HELPDESK_SHAPE).orElseThrow();

        assertThat(composition.title()).isEqualTo("'Ticket assigned'");
        assertThat(composition.preheader())
                .isEqualTo("|Ticket ${payload.ticket} was assigned to you|");
        assertThat(composition.blocks()).extracting(Block::fragment)
                .containsExactly("hcHeading", "hcText", "hcBadge", "hcSeparator", "hcFooter");
        // Quotes and ${…} inside a |…| literal belong to the argument, verbatim.
        assertThat(composition.blocks().get(1).args())
                .containsExactly("|Ticket \"${payload.ticket}\" was assigned to you.|");
        assertThat(composition.blocks().get(3).args()).isEmpty();
    }

    @Test
    void writeParseRoundTripsIncludingMultiArgBlocks() {
        Composition composition = new Composition("'Order confirmed'", "|Order ${payload.id}|",
                List.of(new Block("hcButton", List.of("${payload.url}", "'Open order'")),
                        new Block("hcAlertInfo", List.of("'Heads up'", "'Ships tomorrow.'")),
                        new Block("hcSeparator", List.of())));

        assertThat(MailComposer.parse(MailComposer.write(composition)))
                .contains(composition);
    }

    @Test
    void starterRoundTrips() {
        Composition starter = MailComposer.starter();
        assertThat(MailComposer.parse(MailComposer.write(starter))).contains(starter);
    }

    @Test
    void rejectsAnythingOutsideTheGrammar() {
        // Hand-authored Thymeleaf, plain text, foreign fragments, markup between blocks —
        // all fall back to the source editor instead of risking a lossy rewrite.
        assertThat(MailComposer.parse(null)).isEmpty();
        assertThat(MailComposer.parse("Hello [(${payload.name})]")).isEmpty();
        assertThat(MailComposer.parse("<html><body>custom</body></html>")).isEmpty();
        assertThat(MailComposer.parse(HELPDESK_SHAPE
                .replace("tql/email/hc-email ::", "templates/my-fragments ::"))).isEmpty();
        assertThat(MailComposer.parse(HELPDESK_SHAPE
                .replace("<div th:fragment=\"content\">",
                        "<div th:fragment=\"content\"><p>stray</p>")))
                .isEmpty();
    }

    @Test
    void paletteReadsTheBundledLibrarySignatures() {
        Map<String, List<String>> palette = MailComposer.palette();

        assertThat(palette).containsKeys("hcButton", "hcHeading", "hcText", "hcSeparator",
                "hcKvTable", "hcFooter");
        assertThat(palette.get("hcButton")).containsExactly("href", "label");
        assertThat(palette.get("hcSeparator")).isEmpty();
        assertThat(palette.get("hcAlertInfo")).containsExactly("title", "text");
    }
}
