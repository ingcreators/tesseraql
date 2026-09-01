package io.tesseraql.compiler.binding;

/**
 * The bell's unread badge, built in exactly one place: {@link ShellChrome}
 * pre-renders it into the reserved {@code _inbox.badge} model variable for the shell, and
 * the framework's {@code /_tesseraql/events} stream carries the same string as the
 * {@code inbox:badge} SSE payload (docs/inbox.md, "Live badge") — so a pushed update and a
 * page reload render identically.
 *
 * <p>The fragment owns the count in both channels the upstream unread-badge contract
 * demands (docs/hc-recipe-alignment.md): the visual badge is {@code aria-hidden}
 * presentation, and a visually hidden {@code (N)} rides beside it into the bell's
 * accessible name — the shell's own hidden "Notifications" text supplies the name's stem,
 * so the SSE payload stays locale-free. Zero renders silence, and the count caps at
 * {@code 99+} (the customary chrome cap; display and accessible name always tell the same
 * truth).
 */
public final class InboxBadge {

    private static final int CAP = 99;

    private InboxBadge() {
    }

    /** The badge fragment; an all-read inbox clears the badge with an empty payload. */
    public static String html(int unread) {
        if (unread <= 0) {
            return "";
        }
        String count = unread > CAP ? CAP + "+" : String.valueOf(unread);
        return "<span class=\"hc-badge\" aria-hidden=\"true\">" + count + "</span>"
                + "<span class=\"hc-sr-only\">(" + count + ")</span>";
    }
}
