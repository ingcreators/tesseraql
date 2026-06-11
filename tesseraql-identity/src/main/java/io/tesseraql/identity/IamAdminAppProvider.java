package io.tesseraql.identity;

import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSourceProvider;
import io.tesseraql.yaml.apps.ClasspathAppSource;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * Contributes the bundled IAM admin console app (design ch. 10): a yaml/sql/template tree served
 * under {@code /_tesseraql/admin/users}, rendering the identity SQL contracts. Mounted automatically
 * when this jar is on the classpath; disable with {@code tesseraql.apps.iam-admin.enabled: false}.
 */
public final class IamAdminAppProvider implements AppSourceProvider {

    @Override
    public List<AppSource> appSources(AppConfig config) {
        return List.of(new ClasspathAppSource(
                "iam-admin", "tesseraql/apps/iam-admin", getClass().getClassLoader()));
    }
}
