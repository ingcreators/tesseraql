package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Locale;

/**
 * A {@code push:} pipeline step (docs/analytics-experience.md): delivers a produced file — a
 * transfer the [export step](docs/jobs.md) published — to a partner drop directory, local or
 * remote SFTP/FTPS. The outbound mirror of the {@code poll:} trigger, under the mirrored
 * policy block: a remote target's host must be allow-listed under
 * {@code tesseraql.connectors.push.allowedHosts} (deny by default) and its {@code credential}
 * names an entry under {@code tesseraql.connectors.push.credentials}, so a job never carries
 * a credential. The client that carries the transfer stays an implementation detail, not user API.
 *
 * @param transport  {@code local}, {@code sftp}, or {@code ftps}
 * @param host       the remote host (sftp/ftps); ignored for a local target
 * @param port       the remote port (defaults to 22 for sftp, 21 for ftps)
 * @param path       the directory to deliver into (a local path under an
 *                   {@code allowedPaths} root, or the remote directory)
 * @param credential a named credential under {@code tesseraql.connectors.push.credentials}
 *                   (remote targets); a local target needs none
 * @param file       a context path resolving to the transfer id whose produced file is
 *                   delivered — typically {@code steps.<id>.transferId} from an export step
 * @param as         optional delivered filename ({@code {dotted.path}} placeholders resolve
 *                   against the job context); default: the transfer's own filename
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PushSpec(
        String transport,
        String host,
        Integer port,
        String path,
        String credential,
        String file,
        String as) {

    /** The transport kind in lower case ({@code local}/{@code sftp}/{@code ftps}). */
    public String effectiveTransport() {
        return transport == null ? "" : transport.trim().toLowerCase(Locale.ROOT);
    }

    /** Whether the target is a remote server (needs a host, allow-list, and credential). */
    public boolean isRemote() {
        String kind = effectiveTransport();
        return "sftp".equals(kind) || "ftps".equals(kind);
    }
}
