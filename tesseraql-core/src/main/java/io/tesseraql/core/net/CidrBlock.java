package io.tesseraql.core.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * One CIDR block, held as the network bytes and the prefix length that is significant.
 *
 * <p>Three places in the framework decide something from an address and a range: the gateway's
 * trusted-proxy list (docs/stack-architecture.md decision 13), the sign-in allow-list and a
 * grant's network condition (docs/access-governance.md structural decision 8). They ask the same
 * question — is this address inside that block — and answering it three times would be three
 * chances to answer it differently, so the arithmetic lives here once.
 *
 * <p><b>Parsing refuses with {@link IllegalArgumentException}, not a framework error code.</b>
 * The refusals mean different things to their callers — a malformed proxy range is a boot
 * failure, a malformed condition value is a rejected administrator input — and each caller
 * raises its own code with its own message. A shared code here would have to be one or the
 * other, and it would be wrong half the time.
 */
public record CidrBlock(byte[] network, int prefixBits) {

    public CidrBlock {
        network = network.clone();
    }

    /** Parses {@code 10.0.0.0/8,192.168.0.0/16} — blank entries and spacing are tolerated. */
    public static List<CidrBlock> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<CidrBlock> parsed = new ArrayList<>();
        for (String entry : value.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(parse(trimmed));
            }
        }
        return List.copyOf(parsed);
    }

    /** Parses one block, such as {@code 10.0.0.0/8} or the single host {@code 203.0.113.7}. */
    public static CidrBlock parse(String block) {
        int slash = block.indexOf('/');
        String host = slash < 0 ? block : block.substring(0, slash);
        byte[] address;
        try {
            address = InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException | RuntimeException invalid) {
            throw new IllegalArgumentException("Not an address: " + block
                    + ". Expected a CIDR block such as 10.0.0.0/8.");
        }
        // A bare address is the single host it names, which is what naming one machine means;
        // /32 and /128 say the same thing more loudly.
        int bits = address.length * 8;
        if (slash < 0) {
            return new CidrBlock(address, bits);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(block.substring(slash + 1).trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("Not a prefix length: " + block
                    + ". Expected a CIDR block such as 10.0.0.0/8.");
        }
        if (prefix < 0 || prefix > bits) {
            throw new IllegalArgumentException("Prefix length " + prefix + " is out of range for "
                    + block + "; an IPv" + (bits == 32 ? "4" : "6") + " block allows 0 to "
                    + bits + ".");
        }
        return new CidrBlock(address, prefix);
    }

    /** Whether {@code address} — a numeric literal — is inside any of {@code blocks}. */
    public static boolean anyContains(List<CidrBlock> blocks, String address) {
        if (address == null || blocks.isEmpty()) {
            return false;
        }
        byte[] candidate;
        try {
            // Numeric literal only: the peer address of a live connection always is one, and
            // resolving a name here would put a DNS lookup on the request path.
            candidate = InetAddress.ofLiteral(address).getAddress();
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
        for (CidrBlock block : blocks) {
            if (block.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public byte[] network() {
        return network.clone();
    }

    /** Whether {@code candidate} shares this block's significant bits. */
    public boolean contains(byte[] candidate) {
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
