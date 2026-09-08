package io.tesseraql.s3;

import io.tesseraql.core.blob.BlobStore;
import io.tesseraql.yaml.blob.BlobStoreProvider;
import io.tesseraql.yaml.config.AppConfig;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The {@code s3} {@link BlobStoreProvider} (roadmap Phase 30 slice 2): builds an {@link S3BlobStore}
 * from {@code tesseraql.object-storage.*} config. The endpoint/region/pathStyle/checksumMode knobs
 * make one client work against AWS S3 and every S3-compatible store; credentials resolve lazily
 * through the SecretResolvers ({@code ${secret.*}}); the {@code allowedBuckets} list is the
 * deny-by-default egress allow-list.
 */
public final class S3BlobStoreProvider implements BlobStoreProvider {

    /**
     * Only for the bucket map, whose leaf name is a declaration the operator chose and so cannot
     * be a literal here. Every fixed key below is spelled in full instead: the configuration index
     * scans sources for literals, so a key composed from this constant is a key an operator
     * cannot find on the reference page.
     */
    private static final String BUCKETS = "tesseraql.object-storage.buckets";

    @Override
    public String provider() {
        return "s3";
    }

    @Override
    public BlobStore create(AppConfig config, Path appHome) {
        String endpoint = config.getString("tesseraql.object-storage.s3.endpoint").orElse(null);
        String region = config.getString("tesseraql.object-storage.s3.region").orElse("us-east-1");
        boolean pathStyle = config.getString("tesseraql.object-storage.s3.pathStyle")
                .map(Boolean::parseBoolean).orElse(false);
        String checksumMode = config.getString("tesseraql.object-storage.s3.checksumMode")
                .orElse(null);
        String accessKey = config.getString("tesseraql.object-storage.s3.credentials.accessKey")
                .orElse(null);
        String secretKey = config.getString("tesseraql.object-storage.s3.credentials.secretKey")
                .orElse(null);

        Map<String, String> buckets = new LinkedHashMap<>();
        if (config.navigate(BUCKETS) instanceof Map<?, ?> declared) {
            declared.forEach((name, settings) -> buckets.put(String.valueOf(name),
                    config.getString(BUCKETS + "." + name + ".bucket")
                            .orElse(String.valueOf(name))));
        }
        Set<String> allowedBuckets = new LinkedHashSet<>();
        Object egressList = config.navigate("tesseraql.object-storage.allowedBuckets");
        if (egressList instanceof List<?> declared) {
            declared.forEach(value -> allowedBuckets.add(String.valueOf(value)));
        }

        S3ClientBuilder client = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(region))
                .forcePathStyle(pathStyle);
        S3Presigner.Builder presigner = S3Presigner.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null && !endpoint.isBlank()) {
            client.endpointOverride(URI.create(endpoint));
            presigner.endpointOverride(URI.create(endpoint));
        }
        if (accessKey != null && secretKey != null) {
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey));
            client.credentialsProvider(credentials);
            presigner.credentialsProvider(credentials);
        }
        if ("when-required".equalsIgnoreCase(checksumMode)) {
            client.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }

        return new S3BlobStore(client.build(), presigner.build(), buckets, allowedBuckets);
    }
}
