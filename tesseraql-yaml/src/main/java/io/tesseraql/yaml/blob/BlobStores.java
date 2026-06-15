package io.tesseraql.yaml.blob;

import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.core.blob.FileBlobStore;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * Selects the {@link BlobStore} backing attachments by the configured
 * {@code tesseraql.object-storage.provider} (roadmap Phase 30). The built-in {@code file} provider
 * (the default) is the local {@link FileBlobStore}; any other provider — e.g. {@code s3} from the
 * opt-in {@code tesseraql-s3} module — is discovered via {@link ServiceLoader} and selected by its
 * {@link BlobStoreProvider#provider()} id, the same PdfEngine/FileCodec idiom the rest of the
 * framework uses to swap an implementation by config.
 */
public final class BlobStores {

    /** TQL-YAML-1108: the configured object-storage provider is not on the classpath. */
    private static final TqlErrorCode UNKNOWN_PROVIDER = new TqlErrorCode(TqlDomain.YAML, 1108);

    private BlobStores() {
    }

    /** Builds the configured store, defaulting to the local file store. */
    public static BlobStore create(AppConfig config, Path appHome) {
        String provider = config.getString("tesseraql.object-storage.provider").orElse("file");
        if (provider.isBlank() || "file".equalsIgnoreCase(provider)) {
            return new FileBlobStore(appHome.resolve("work/blob/tesseraql"));
        }
        for (BlobStoreProvider candidate : ServiceLoader.load(BlobStoreProvider.class)) {
            if (provider.equalsIgnoreCase(candidate.provider())) {
                return candidate.create(config, appHome);
            }
        }
        throw new TqlException(UNKNOWN_PROVIDER, "No object-storage provider '" + provider
                + "' on the classpath; is the tesseraql-" + provider + " module installed?");
    }
}
