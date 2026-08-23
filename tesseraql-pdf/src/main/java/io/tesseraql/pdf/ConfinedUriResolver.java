package io.tesseraql.pdf;

import com.openhtmltopdf.extend.FSUriResolver;
import java.net.URI;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Deny-by-default resource resolution for the openhtmltopdf engine: a template may reference
 * {@code data:} URIs and files inside the app resource root - nothing else. Network fetches
 * during an export would make output depend on remote state (and leak requests), and file
 * access outside the root would breach the app-home trust boundary the template engine already
 * enforces, so both resolve to nothing.
 */
final class ConfinedUriResolver implements FSUriResolver {

    private static final Logger LOG = Logger.getLogger(ConfinedUriResolver.class.getName());

    private final io.tesseraql.core.files.ConfinedPath root;

    ConfinedUriResolver(Path root) {
        this.root = root == null ? null : io.tesseraql.core.files.ConfinedPath.under(root);
    }

    @Override
    public String resolveURI(String baseUri, String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        if (uri.startsWith("data:")) {
            return uri;
        }
        String resolved = resolve(baseUri, uri);
        if (resolved == null) {
            LOG.warning(() -> "Blocked template resource '" + uri
                    + "' - only data: URIs and files inside the app resource root resolve");
        }
        return resolved;
    }

    private String resolve(String baseUri, String uri) {
        try {
            URI target = URI.create(uri);
            if (!target.isAbsolute()) {
                if (baseUri == null || baseUri.isBlank()) {
                    return null;
                }
                target = URI.create(baseUri).resolve(target);
            }
            if (!"file".equals(target.getScheme()) || root == null) {
                return null;
            }
            return root.confine(Path.of(target))
                    .map(file -> file.toUri().toString())
                    .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
