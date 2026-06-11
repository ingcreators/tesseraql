package io.tesseraql.yaml.secret;

/** The built-in {@code env} provider: {@code ${secret.env.DB_PASSWORD}} reads the environment. */
public final class EnvSecretResolver implements SecretResolver {

    @Override
    public String provider() {
        return "env";
    }

    @Override
    public String resolve(String name) {
        return System.getenv(name);
    }
}
