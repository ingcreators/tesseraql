package io.tesseraql.security.net;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.net.CidrBlock;
import java.util.List;

/**
 * The deployment's sign-in allow-list (docs/access-governance.md structural decision 8, layer A):
 * {@code tesseraql.security.network.allow} names the networks a session may be established from,
 * and a sign-in presenting any other address is refused before a session exists.
 *
 * <p><b>This is the layer that actually holds.</b> A grant-level network condition is evaluated
 * per request against the address the edge presented and can only ever narrow what a caller may
 * do; this one runs at the single moment a deployment can refuse outright, and refusing there
 * means no session, no cookie, and nothing to carry forward. The two are named as two layers in
 * the design for exactly that reason.
 *
 * <p><b>An empty list admits everybody</b>, which is the shipped behaviour and stays the default:
 * a deployment that names no network has not asked for this control, and reading "no networks
 * listed" as "no network may sign in" would lock every existing deployment out on upgrade.
 *
 * <p>The address judged is the presented one — {@code X-Forwarded-For} when there is one, else
 * the peer — because that is the address the deployment's own edge puts there. A deployment that
 * cannot trust that header cannot trust this control either, and {@code authentication.md} is
 * where the duty to overwrite it at the edge is written down.
 */
public record SignInAllowList(List<CidrBlock> networks) {

    /** TQL-SEC-4149: the sign-in came from a network this deployment does not admit. */
    public static final TqlErrorCode NOT_ADMITTED = new TqlErrorCode(TqlDomain.SEC, 4149);

    /** The unrestricted list: every network may sign in, the shipped default. */
    public static final SignInAllowList EVERYWHERE = new SignInAllowList(List.of());

    public SignInAllowList {
        networks = List.copyOf(networks);
    }

    /** Parses {@code 10.0.0.0/8,203.0.113.7}; blank means everywhere. */
    public static SignInAllowList parse(String value) {
        try {
            return new SignInAllowList(CidrBlock.parseList(value));
        } catch (IllegalArgumentException invalid) {
            throw new TqlException(NOT_ADMITTED,
                    "tesseraql.security.network.allow: " + invalid.getMessage());
        }
    }

    /** Whether this deployment restricts where a session may be established from at all. */
    public boolean restricts() {
        return !networks.isEmpty();
    }

    /**
     * Admits the address or refuses the sign-in.
     *
     * <p>A restricted deployment with <em>no</em> address to judge is refused too. A request
     * that reached an HTTP server has a peer, so an absent address means the deployment is
     * behind something that presents none — and admitting the unjudgeable would make the
     * control skippable by whatever produced that state.
     */
    public void admit(String address) {
        if (!restricts()) {
            return;
        }
        if (!CidrBlock.anyContains(networks, address)) {
            throw new TqlException(NOT_ADMITTED, "Sign-in is not allowed from this network");
        }
    }
}
