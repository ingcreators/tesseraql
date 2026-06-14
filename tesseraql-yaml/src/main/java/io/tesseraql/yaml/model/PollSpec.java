package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Locale;

/**
 * A {@code poll:} trigger on a {@code file-import} job (roadmap Phase 26): the runtime watches a
 * source directory and feeds every file it finds into the job's {@code import:} pipeline, instead
 * of waiting for an HTTP upload. The source is a local directory or a remote SFTP/FTPS server;
 * the underlying Camel consumer stays an implementation detail, not user API.
 *
 * <p>A remote source's host must be allow-listed under
 * {@code tesseraql.connectors.poll.allowedHosts} (deny by default), and its {@code credential}
 * names an entry in {@code tesseraql.connectors.poll.credentials} whose secrets resolve through
 * the SecretResolver SPI — so a job never carries a credential.
 *
 * @param source     {@code local}, {@code sftp}, or {@code ftps}
 * @param host       the remote host (sftp/ftps); ignored for a local source
 * @param port       the remote port (defaults to 22 for sftp, 21 for ftps)
 * @param path       the directory to poll (a local filesystem path, or the remote directory)
 * @param credential a named credential under {@code tesseraql.connectors.poll.credentials}
 *                   (remote sources); a local source needs none
 * @param include    a filename glob filter, e.g. {@code *.csv} (default: every file)
 * @param delay      the poll interval, e.g. {@code 60s} (default 60s)
 * @param move       the sub-directory a successfully ingested file is moved to (default {@code .done})
 * @param moveFailed the sub-directory a file that could not be ingested is moved to (default {@code .error})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PollSpec(
        String source,
        String host,
        Integer port,
        String path,
        String credential,
        String include,
        String delay,
        String move,
        String moveFailed) {

    /** The source kind in lower case ({@code local}/{@code sftp}/{@code ftps}). */
    public String effectiveSource() {
        return source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether the source is a remote server (needs a host, allow-list, and credential). */
    public boolean isRemote() {
        String kind = effectiveSource();
        return "sftp".equals(kind) || "ftps".equals(kind);
    }

    /** The poll interval, defaulting to 60s. */
    public String effectiveDelay() {
        return delay == null || delay.isBlank() ? "60s" : delay;
    }

    /** The processed-file sub-directory, defaulting to {@code .done}. */
    public String effectiveMove() {
        return move == null || move.isBlank() ? ".done" : move;
    }

    /** The failed-file sub-directory, defaulting to {@code .error}. */
    public String effectiveMoveFailed() {
        return moveFailed == null || moveFailed.isBlank() ? ".error" : moveFailed;
    }
}
