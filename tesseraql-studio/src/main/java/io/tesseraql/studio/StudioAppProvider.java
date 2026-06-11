package io.tesseraql.studio;

import io.tesseraql.yaml.apps.AppSource;
import io.tesseraql.yaml.apps.AppSourceProvider;
import io.tesseraql.yaml.apps.ClasspathAppSource;
import io.tesseraql.yaml.config.AppConfig;
import java.util.List;

/**
 * Contributes the bundled Studio UI app (design ch. 16, 32): a yaml/template tree served under
 * {@code /_tesseraql/studio/ui}, rendering the {@code studio.*} service providers. Mounted only
 * when Studio is enabled ({@code tesseraql.studio.enabled}, default true); additionally disable
 * with {@code tesseraql.apps.studio.enabled: false}.
 */
public final class StudioAppProvider implements AppSourceProvider {

    @Override
    public List<AppSource> appSources(AppConfig config) {
        boolean studioEnabled = config.getString("tesseraql.studio.enabled")
                .map(Boolean::parseBoolean).orElse(true);
        if (!studioEnabled) {
            return List.of();
        }
        return List.of(new ClasspathAppSource(
                "studio", "tesseraql/apps/studio", getClass().getClassLoader()));
    }
}
