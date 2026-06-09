package io.tesseraql.runtime.spring;

import io.tesseraql.runtime.TesseraqlRuntime;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Hosts the TesseraQL runtime inside a Spring {@code ApplicationContext} (design ch. 19.1), so its
 * lifecycle is managed by Spring: the runtime is started when the context refreshes and stopped when
 * it closes.
 *
 * <p>The app home is read from {@code tesseraql.app.home} and the HTTP port from
 * {@code tesseraql.runtime.port} (default 8080). The runtime loads its own datasource and route
 * configuration from the app home, exactly as the Camel Main runtime does.
 */
@Configuration
public class TesseraqlRuntimeConfiguration {

    @Bean(destroyMethod = "close")
    public TesseraqlRuntime tesseraqlRuntime(Environment environment) {
        Path appHome = Path.of(environment.getRequiredProperty("tesseraql.app.home"));
        int port = environment.getProperty("tesseraql.runtime.port", Integer.class, 8080);
        return TesseraqlRuntime.start(appHome, port);
    }
}
