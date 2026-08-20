package io.tesseraql.core.net;

import java.net.InetAddress;

/**
 * The bare host out of an address a request presented.
 *
 * <p>The two sources disagree about shape. An {@code X-Forwarded-For} entry is normally a bare
 * address; the peer of a Vert.x connection arrives as {@code host:port}, because that is what a
 * socket address renders as — and for IPv6 it renders without brackets, so
 * {@code 0:0:0:0:0:0:0:1:52344} is a real value this has to read.
 *
 * <p>That mattered the moment an address became something the framework <em>decides</em> with
 * rather than something it merely records (docs/access-governance.md structural decision 8): a
 * value carrying a port is inside no CIDR block at all, so a sign-in allow-list naming the
 * loopback network would have refused the loopback. Recording the bare host is also the better
 * answer for the surface that already displayed it — an ephemeral source port told a person
 * reviewing their sessions nothing.
 */
public final class PresentedAddress {

    private PresentedAddress() {
    }

    /**
     * The host part, or the value unchanged when it is not an address with a port.
     *
     * <p>A hostname is returned as it stands. Nothing here resolves one: the callers compare
     * against numeric blocks, where an unresolvable value simply matches nothing, and a DNS
     * lookup on the request path would be a far worse answer than that.
     */
    public static String hostOf(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.charAt(0) == '[') {
            int close = trimmed.indexOf(']');
            return close < 0 ? trimmed : trimmed.substring(1, close);
        }
        if (isLiteral(trimmed)) {
            return trimmed;
        }
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon <= 0 || lastColon == trimmed.length() - 1) {
            return trimmed;
        }
        String port = trimmed.substring(lastColon + 1);
        for (int i = 0; i < port.length(); i++) {
            if (port.charAt(i) < '0' || port.charAt(i) > '9') {
                return trimmed;
            }
        }
        String host = trimmed.substring(0, lastColon);
        return isLiteral(host) ? host : trimmed;
    }

    private static boolean isLiteral(String value) {
        try {
            InetAddress.ofLiteral(value);
            return true;
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
    }
}
