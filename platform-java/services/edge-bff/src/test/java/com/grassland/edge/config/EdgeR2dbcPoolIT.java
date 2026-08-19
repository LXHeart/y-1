package com.grassland.edge.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

/** Verifies bounded concurrent acquisition against the same pool used by the Edge runtime. */
@Testcontainers
class EdgeR2dbcPoolIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void servesConcurrentReadOnlyQueriesThroughTheBoundedPool() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.r2dbc.url", r2dbcUrl());
        ConnectionFactory factory = new EdgeR2dbcConfig().connectionFactory(
                environment, 1, 4, 30, 120, 10);
        assertThat(factory).isInstanceOf(ConnectionPool.class);

        DatabaseClient client = DatabaseClient.create(factory);
        Flux<Integer> queries = Flux.fromStream(IntStream.range(0, 24).boxed())
                .flatMap(ignored -> client.sql("SELECT 1")
                        .map(row -> row.get(0, Integer.class))
                        .one(), 24);
        assertThat(queries.collectList().block()).hasSize(24).allMatch(value -> value == 1);
        ((ConnectionPool) factory).dispose();
    }

    private static String r2dbcUrl() {
        return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + POSTGRES.getDatabaseName();
    }
}
