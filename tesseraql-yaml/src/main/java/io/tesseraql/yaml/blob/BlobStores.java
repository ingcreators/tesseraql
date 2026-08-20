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

    /** Builds the configured store from the providers this thread's classloader can see. */
    public static BlobStore create(AppConfig config, Path appHome) {
        return create(config, appHome, null);
    }

    /**
     * Builds the configured store, defaulting to the local file store, discovering providers
     * through {@code loader} — a hosted runtime passes its own module loader, so an application
     * that declares {@code io.tesseraql:tesseraql-s3} in {@code tesseraql.modules} is served by
     * the jar that declaration resolved (docs/module-scope.md structural decision 2).
     *
     * <p>Without the loader this read the thread context classloader, which in a hosted runtime is
     * the process classpath: the module channel could not supply a blob store at all, and an
     * application that declared one was told its provider was missing. A framework surface still
     * has no module channel of its own — the stack surface runtime's application is bundled on the
     * classpath — so a provider for it belongs on the base classpath
     * (docs/module-channel.md decision 6).
     */
    public static BlobStore create(AppConfig config, Path appHome, ClassLoader loader) {
        String provider = config.getString("tesseraql.object-storage.provider").orElse("file");
        if (provider.isBlank() || "file".equalsIgnoreCase(provider)) {
            return new FileBlobStore(appHome.resolve("work/blob/tesseraql"));
        }
        for (BlobStoreProvider candidate : loader == null
                ? ServiceLoader.load(BlobStoreProvider.class)
                : ServiceLoader.load(BlobStoreProvider.class, loader)) {
            if (provider.equalsIgnoreCase(candidate.provider())) {
                return candidate.create(config, appHome);
            }
        }
        throw new TqlException(UNKNOWN_PROVIDER, "No object-storage provider '" + provider
                + "' on the classpath; is the tesseraql-" + provider + " module installed?");
    }
}
