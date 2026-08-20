package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.net.CidrBlock;
import java.util.List;

/**
 * The addresses the gateway will accept forwarded headers from
 * (docs/stack-architecture.md decision 13).
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
 *
 * <p>The block arithmetic itself is {@link CidrBlock}, shared with the sign-in allow-list and
 * grant network conditions (docs/access-governance.md structural decision 8). The refusal stays
 * here, because a malformed range in <em>this</em> setting is a boot failure and TQL-APP-4004 is
 * what says so.
 */
record TrustedProxies(List<CidrBlock> ranges) {

    /** TQL-APP-4004: a trusted-proxy range is not a valid CIDR block. */
    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.APP, 4004);

    static final TrustedProxies NONE = new TrustedProxies(List.of());

    TrustedProxies {
        ranges = List.copyOf(ranges);
    }

    /** Parses {@code 10.0.0.0/8,192.168.0.0/16} — blank entries and spacing are tolerated. */
    static TrustedProxies parse(String value) {
        try {
            return new TrustedProxies(CidrBlock.parseList(value));
        } catch (IllegalArgumentException invalid) {
            throw new TqlException(INVALID, "Not a trusted-proxy range: " + invalid.getMessage());
        }
    }

    /** Whether anything is trusted at all; an empty list leaves every request alone. */
    boolean isEmpty() {
        return ranges.isEmpty();
    }

    /** Whether {@code address} — the peer of the connection, not a header — is a named edge. */
    boolean includes(String address) {
        return CidrBlock.anyContains(ranges, address);
    }
}
