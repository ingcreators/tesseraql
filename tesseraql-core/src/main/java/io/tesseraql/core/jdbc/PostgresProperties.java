package io.tesseraql.core.jdbc;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * The driver properties TesseraQL adds to a PostgreSQL connection (docs/connection-liveness.md):
 * who the connection is — {@code ApplicationName}, so {@code pg_stat_activity} can tell one
 * application's pool from another's and a dead node's leftovers can be picked out — and TCP
 * keepalive with TesseraQL's timings ({@link KeepaliveSocketFactory}), so a vanished database
 * host fails a statement in about a minute instead of never.
 *
 * <p>A parameter the JDBC URL declares wins over one passed alongside it — the driver reads the
 * URL last — so whatever an operator wrote in {@code jdbcUrl} stands, and nothing here has to
 * look for it.
 */
public final class PostgresProperties {

    /** The label a CLI or Maven plugin command's short-lived connection carries. */
    public static final String TOOL = "tesseraql/tool";

    /** PostgreSQL keeps at most {@code NAMEDATALEN - 1} bytes of {@code application_name}. */
    static final int APPLICATION_NAME_MAX_BYTES = 63;

    private static final String POOL_PREFIX = "tesseraql-";

    private PostgresProperties() {
    }

    /** Whether {@code jdbcUrl} is PostgreSQL's, the one driver these properties are for. */
    public static boolean applies(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql:");
    }

    /**
     * Hands {@code sink} the properties for a connection to {@code jdbcUrl} labelled
     * {@code label}, or nothing when the URL is not PostgreSQL's: who the connection is, and TCP
     * keepalive with TesseraQL's timings (docs/connection-liveness.md decision 2). The driver
     * sets {@code SO_KEEPALIVE} from {@code tcpKeepAlive} itself, over whatever a factory set, so
     * both are needed. A URL that names its own {@code socketFactory} keeps it.
     */
    public static void apply(String jdbcUrl, String label, BiConsumer<String, String> sink) {
        if (!applies(jdbcUrl)) {
            return;
        }
        sink.accept("ApplicationName", applicationName(label));
        sink.accept("tcpKeepAlive", "true");
        sink.accept("socketFactory", KeepaliveSocketFactory.class.getName());
    }

    /**
     * The label of a pool: {@code tesseraql/<app>/<pool>}, or {@code tesseraql/<pool>} for one
     * the stack owns ({@code app} null). {@code <pool>} is the HikariCP pool name without its
     * {@code tesseraql-} prefix — {@code main}, {@code main-jobs}, {@code tenant-acme},
     * {@code stack-framework}. An application's name cannot contain {@code /}, so the segments
     * stay readable.
     */
    public static String poolLabel(String app, String poolName) {
        String pool = poolName.startsWith(POOL_PREFIX)
                ? poolName.substring(POOL_PREFIX.length())
                : poolName;
        return app == null ? "tesseraql/" + pool : "tesseraql/" + app + "/" + pool;
    }

    /** The label of {@code tesseraql job run} for {@code app}: {@code tesseraql/<app>/job-run}. */
    public static String jobRunLabel(String app) {
        return "tesseraql/" + app + "/job-run";
    }

    /**
     * {@code label} as PostgreSQL will keep it: printable ASCII, at most 63 bytes. A character
     * outside printable ASCII is percent-encoded as UTF-8 — {@code 受注} arrives as
     * {@code %E5%8F%97%E6%B3%A8}, the wire form the front door routes the name by — where the
     * server would otherwise replace it. A longer value is cut at a character boundary, so no
     * escape and no character is split.
     */
    static String applicationName(String label) {
        StringBuilder out = new StringBuilder();
        for (int at = 0; at < label.length();) {
            int codePoint = label.codePointAt(at);
            at += Character.charCount(codePoint);
            String piece = codePoint >= 0x20 && codePoint <= 0x7E
                    ? String.valueOf((char) codePoint)
                    : percentEncoded(codePoint);
            if (out.length() + piece.length() > APPLICATION_NAME_MAX_BYTES) {
                break;
            }
            out.append(piece);
        }
        return out.toString();
    }

    private static String percentEncoded(int codePoint) {
        java.util.HexFormat hex = java.util.HexFormat.of().withUpperCase();
        StringBuilder escaped = new StringBuilder();
        for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
            escaped.append('%').append(hex.toHexDigits(b));
        }
        return escaped.toString();
    }
}
