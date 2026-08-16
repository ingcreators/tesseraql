package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * The addresses the gateway will accept forwarded headers from
 * (docs/suite-architecture.md decision 13).
 *
 * <p>An application that authenticates on a forwarded mTLS header trusts whoever set it, so the
 * header is only worth anything if a caller cannot set it for itself. {@code authentication.md}
 * puts that duty on the edge — "the edge must overwrite (or strip) the {@code forwardedHeader} on
 * every inbound request, and the runtime must not be reachable except through that edge" — and the
 * gateway cannot discharge it, because it cannot tell the edge's value from a caller's. Naming the
 * edge is what makes it able to: a request from a listed address carries the edge's assertion, and
 * a request from anywhere else carries a caller's and is stripped.
 *
 * <p><b>An empty list trusts nobody and strips nothing.</b> That reads backwards until the
 * alternative is spelled out: interpreting "nothing is trusted" as "strip from everyone" is
 * precisely the unconditional strip that made mTLS forwarded-header authentication unusable behind
 * a gateway, and it would restore that as the default. So an operator who names no edge gets the
 * relay's plain behaviour and the trust contract stays where it already was; an operator who names
 * one gets defence in depth on top of it.
 */
record TrustedProxies(List<Cidr> ranges) {

    /** TQL-APP-4004: a trusted-proxy range is not a valid CIDR block. */
    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.APP, 4004);

    static final TrustedProxies NONE = new TrustedProxies(List.of());

    TrustedProxies {
        ranges = List.copyOf(ranges);
    }

    /** Parses {@code 10.0.0.0/8,192.168.0.0/16} — blank entries and spacing are tolerated. */
    static TrustedProxies parse(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        List<Cidr> parsed = new ArrayList<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(Cidr.parse(trimmed));
            }
        }
        return new TrustedProxies(parsed);
    }

    /** Whether anything is trusted at all; an empty list leaves every request alone. */
    boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** Whether {@code address} — the peer of the connection, not a header — is a named edge. */
    boolean includes(String address) {
        if (address == null || ranges.isEmpty()) {
            return false;
        }
        byte[] candidate;
        try {
            // Numeric literal only: the peer address of a live connection always is one, and
            // resolving a name here would put a DNS lookup on the event loop.
            candidate = InetAddress.ofLiteral(address).getAddress();
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
        return ranges.stream().anyMatch(range -> range.contains(candidate));
    }

    /** One CIDR block, held as the network bytes and the prefix length that is significant. */
    record Cidr(byte[] network, int prefixBits) {

        static Cidr parse(String block) {
            int slash = block.indexOf('/');
            String host = slash < 0 ? block : block.substring(0, slash);
            byte[] address;
            try {
                address = InetAddress.getByName(host).getAddress();
            } catch (UnknownHostException | RuntimeException invalid) {
                throw new TqlException(INVALID, "Not an address in a trusted-proxy range: "
                        + block + ". Expected a CIDR block such as 10.0.0.0/8.");
            }
            // A bare address is the single host it names, which is what an operator naming one
            // edge means; /32 and /128 say the same thing more loudly.
            int bits = address.length * 8;
            if (slash < 0) {
                return new Cidr(address, bits);
            }
            int prefix;
            try {
                prefix = Integer.parseInt(block.substring(slash + 1).trim());
            } catch (NumberFormatException notANumber) {
                throw new TqlException(INVALID, "Not a prefix length in a trusted-proxy range: "
                        + block + ". Expected a CIDR block such as 10.0.0.0/8.");
            }
            if (prefix < 0 || prefix > bits) {
                throw new TqlException(INVALID, "Prefix length " + prefix + " is out of range for "
                        + block + "; an IPv" + (bits == 32 ? "4" : "6") + " block allows 0 to "
                        + bits + ".");
            }
            return new Cidr(address, prefix);
        }

        /** Whether {@code candidate} shares this block's significant bits. */
        boolean contains(byte[] candidate) {
            if (candidate.length != network.length) {
                // An IPv4 peer never matches an IPv6 block and the reverse; a deployment that
                // wants both names both.
                return false;
            }
            int wholeBytes = prefixBits / 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixBits % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
