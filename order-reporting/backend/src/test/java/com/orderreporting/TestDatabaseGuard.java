package com.orderreporting;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import java.net.URI;

/** Runs before Flyway/DataSource creation. No development database is accepted. */
public class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override public void initialize(ConfigurableApplicationContext context){
        String url=context.getEnvironment().getRequiredProperty("spring.datasource.url");
        URI uri=URI.create(url.substring("jdbc:".length()));
        if(!"mysql".equals(uri.getScheme()) || !"/order_reporting_test".equals(uri.getPath()))
            throw new IllegalStateException("Tests require the dedicated order_reporting_test database");
    }
}
