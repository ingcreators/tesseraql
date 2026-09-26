package io.tesseraql.core.jdbc;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * The driver properties TesseraQL adds to a connection (docs/connection-liveness.md), chosen by
 * the URL's driver: who the connection is, so the database's session views can tell one
 * application's pool from another's and a dead node's leftovers can be picked out, and TCP
 * keepalive with TesseraQL's timings where the driver lets them be set, so a vanished database
 * host fails a statement in about a minute instead of never.
 *
 * <table>
 * <caption>What each driver is given</caption>
 * <tr><th>URL</th><th>The name</th><th>Keepalive</th></tr>
 * <tr><td>{@code jdbc:postgresql:}</td><td>{@code ApplicationName}</td>
 * <td>{@code tcpKeepAlive} and {@link KeepaliveSocketFactory}</td></tr>
 * <tr><td>{@code jdbc:sqlserver:}</td><td>{@code applicationName}</td>
 * <td>none: the driver sets 30 s and 1 s itself</td></tr>
 * <tr><td>{@code jdbc:oracle:}</td><td>{@code v$session.program}</td>
 * <td>{@code oracle.net.keepAlive} and its three timings</td></tr>
 * <tr><td>{@code jdbc:mariadb:}</td><td>{@code connectionAttributes}</td>
 * <td>the three timings; keepalive is already on</td></tr>
 * <tr><td>{@code jdbc:mysql:}</td><td>{@code connectionAttributes}</td>
 * <td>none: no property sets the timings, so the host's apply</td></tr>
 * </table>
 *
 * <p>A key the JDBC URL declares is never added. Two of these drivers (MySQL's, SQL Server's) let
 * a passed property override the URL's, so without that rule TesseraQL would silently replace
 * what an operator wrote; with it, the URL wins for every driver.
 */
public final class ConnectionProperties {

    /** The label a CLI or Maven plugin command's short-lived connection carries. */
    public static final String TOOL = "tesseraql/tool";

    /** PostgreSQL keeps at most {@code NAMEDATALEN - 1} bytes, the least any driver states. */
    static final int APPLICATION_NAME_MAX_BYTES = 63;

    private static final String POOL_PREFIX = "tesseraql-";

    private static final String IDLE = String.valueOf(KeepaliveSocketFactory.IDLE_SECONDS);
    private static final String INTERVAL = String.valueOf(KeepaliveSocketFactory.INTERVAL_SECONDS);
    private static final String COUNT = String.valueOf(KeepaliveSocketFactory.COUNT);

    private ConnectionProperties() {
    }

    /**
     * Hands {@code sink} the properties for a connection to {@code jdbcUrl} labelled
     * {@code label}: nothing for a driver not listed above, and nothing whose key the URL
     * declares.
     */
    public static void apply(String jdbcUrl, String label, BiConsumer<String, String> sink) {
        if (jdbcUrl == null) {
            return;
        }
        BiConsumer<String, String> unlessDeclared = (key, value) -> {
            if (!declares(jdbcUrl, key)) {
                sink.accept(key, value);
            }
        };
        String name = applicationName(label);
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            unlessDeclared.accept("ApplicationName", name);
            // The driver sets SO_KEEPALIVE from tcpKeepAlive itself, over whatever the factory
            // set, so both are needed.
            unlessDeclared.accept("tcpKeepAlive", "true");
            unlessDeclared.accept("socketFactory", KeepaliveSocketFactory.class.getName());
        } else if (jdbcUrl.startsWith("jdbc:sqlserver:")) {
            unlessDeclared.accept("applicationName", name);
        } else if (jdbcUrl.startsWith("jdbc:oracle:")) {
            unlessDeclared.accept("v$session.program", name);
            unlessDeclared.accept("oracle.net.keepAlive", "true");
            unlessDeclared.accept("oracle.net.TCP_KEEPIDLE", IDLE);
            unlessDeclared.accept("oracle.net.TCP_KEEPINTERVAL", INTERVAL);
            unlessDeclared.accept("oracle.net.TCP_KEEPCOUNT", COUNT);
        } else if (jdbcUrl.startsWith("jdbc:mariadb:")) {
            unlessDeclared.accept("connectionAttributes", "program_name:" + name);
            unlessDeclared.accept("tcpKeepIdle", IDLE);
            unlessDeclared.accept("tcpKeepInterval", INTERVAL);
            unlessDeclared.accept("tcpKeepCount", COUNT);
        } else if (jdbcUrl.startsWith("jdbc:mysql:")) {
            unlessDeclared.accept("connectionAttributes", "program_name:" + name);
        }
    }

    /**
     * Whether {@code jdbcUrl} declares {@code key} as a parameter — after {@code ?} or {@code &},
     * or after {@code ;} in SQL Server's form — ignoring case, as the drivers that let a passed
     * property win would read it.
     */
    static boolean declares(String jdbcUrl, String key) {
        return Pattern.compile("[?&;]\\s*" + Pattern.quote(key) + "\\s*=",
                Pattern.CASE_INSENSITIVE).matcher(jdbcUrl).find();
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
     * {@code label} as every driver above can carry it: printable ASCII, at most 63 bytes, and
     * without {@code :} or {@code ,}, which separate {@code connectionAttributes}. Anything else is
     * percent-encoded as UTF-8 — {@code 受注} arrives as {@code %E5%8F%97%E6%B3%A8}, the wire form
     * the front door routes the name by. A longer value is cut at a character boundary, so no
     * escape and no character is split.
     */
    static String applicationName(String label) {
        StringBuilder out = new StringBuilder();
        for (int at = 0; at < label.length();) {
            int codePoint = label.codePointAt(at);
            at += Character.charCount(codePoint);
            boolean plain = codePoint >= 0x20 && codePoint <= 0x7E && codePoint != ':'
                    && codePoint != ',';
            String piece = plain ? String.valueOf((char) codePoint) : percentEncoded(codePoint);
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
