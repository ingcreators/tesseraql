package io.tesseraql.yaml.blob;

import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Path;

/**
 * A pluggable {@link BlobStore} backend selected by {@code tesseraql.object-storage.provider}
 * (roadmap Phase 30). Discovered via {@link java.util.ServiceLoader}, the PdfEngine/FileCodec idiom:
 * the opt-in {@code tesseraql-s3} module contributes the {@code s3} provider, so an app stores blobs
 * in S3 by adding the jar and flipping the config — no DSL touches the bytes.
 */
public interface BlobStoreProvider {

    /** The provider id matched against {@code tesseraql.object-storage.provider} (e.g. {@code s3}). */
    String provider();

    /** Builds the store from config (credentials resolve lazily through the SecretResolvers). */
    BlobStore create(AppConfig config, Path appHome);
}
