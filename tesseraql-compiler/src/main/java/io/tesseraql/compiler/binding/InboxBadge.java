package io.tesseraql.compiler.binding;

/**
 * The bell's unread badge, built in exactly one place: {@link HtmlResponseRenderer}
 * pre-renders it into the reserved {@code _inbox.badge} model variable for the shell, and
 * the framework's {@code /_tesseraql/events} stream carries the same string as the
 * {@code inbox:badge} SSE payload (docs/inbox.md, "Live badge") — so a pushed update and a
 * page reload render identically.
 */
public final class InboxBadge {

    private InboxBadge() {
    }

    /** The badge fragment; an all-read inbox clears the badge with an empty payload. */
    public static String html(int unread) {
        return unread <= 0 ? "" : "<span class=\"hc-badge\">" + unread + "</span>";
    }
}
