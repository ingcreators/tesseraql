package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The badge fragment's three states (docs/hc-recipe-alignment.md, unread-badge): zero is
 * silence, a count renders the aria-hidden badge plus the hidden "(N)" that joins the
 * bell's accessible name, and past the cap both tell the same "99+" truth.
 */
class InboxBadgeTest {

    @Test
    void zeroRendersSilence() {
        assertThat(InboxBadge.html(0)).isEmpty();
        assertThat(InboxBadge.html(-1)).isEmpty();
    }

    @Test
    void aCountRendersTheBadgeAndTheHiddenName() {
        assertThat(InboxBadge.html(3)).isEqualTo(
                "<span class=\"hc-badge\" aria-hidden=\"true\">3</span>"
                        + "<span class=\"hc-sr-only\">(3)</span>");
    }

    @Test
    void pastTheCapBothChannelsSayNinetyNinePlus() {
        assertThat(InboxBadge.html(100)).isEqualTo(
                "<span class=\"hc-badge\" aria-hidden=\"true\">99+</span>"
                        + "<span class=\"hc-sr-only\">(99+)</span>");
        assertThat(InboxBadge.html(99)).contains(">99<");
    }
}
