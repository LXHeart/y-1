package com.grassland.bootstrap;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class DatabaseBootstrapApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(DatabaseBootstrapApplication.class, args);
        SpringApplication.exit(context);
    }

    @Bean
    DataSource dataSource(Environment environment) {
        String databaseUrl = environment.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException("database-bootstrap needs DATABASE_URL");
        }
        DatabaseUrl parts = DatabaseUrl.parse(databaseUrl);
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(parts.jdbcUrl());
        dataSource.setUser(parts.user());
        dataSource.setPassword(parts.password());
        return dataSource;
    }

    @Bean
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/bootstrap")
                .table("database_bootstrap_flyway_schema")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    @Bean
    ApplicationRunner migrateAndVerify(Flyway flyway, DatabaseSchemaVerifier verifier, Environment environment) {
        return (ApplicationArguments ignored) -> {
            int maxAttempts = environment.getProperty("bootstrap.max-attempts", Integer.class, 30);
            long retryDelayMs = environment.getProperty("bootstrap.retry-delay-ms", Long.class, 2_000L);
            SQLException lastFailure = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try (Connection ignoredConnection = verifier.dataSource().getConnection()) {
                    lastFailure = null;
                    break;
                } catch (SQLException failure) {
                    lastFailure = failure;
                    if (attempt == maxAttempts) {
                        throw failure;
                    }
                    Thread.sleep(retryDelayMs);
                }
            }
            if (lastFailure != null) {
                throw lastFailure;
            }
            flyway.migrate();
            verifier.verify();
        };
    }
}
