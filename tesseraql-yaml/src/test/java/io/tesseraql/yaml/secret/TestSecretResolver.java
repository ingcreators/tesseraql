package io.tesseraql.yaml.secret;

/** Registered via META-INF/services in test resources to exercise ServiceLoader discovery. */
public final class TestSecretResolver implements SecretResolver {

    @Override
    public String provider() {
        return "test";
    }

    @Override
    public String resolve(String name) {
        return "fixed".equals(name) ? "from-test-provider" : null;
    }
}
