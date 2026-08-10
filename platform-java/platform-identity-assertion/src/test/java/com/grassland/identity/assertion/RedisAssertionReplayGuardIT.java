package com.grassland.identity.assertion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** 两个独立连接模拟两个服务副本，共享 Redis 后同一 jti 只能消费一次。 */
class RedisAssertionReplayGuardIT {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory replicaOne;
    private LettuceConnectionFactory replicaTwo;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @BeforeEach
    void createReplicaConnections() {
        replicaOne = connectionFactory();
        replicaTwo = connectionFactory();
    }

    @AfterEach
    void closeReplicaConnections() {
        replicaOne.destroy();
        replicaTwo.destroy();
    }

    @Test
    void sameJtiIsConsumedOnceAcrossReplicas() {
        var firstReplica = guard(replicaOne, "test:replay:");
        var secondReplica = guard(replicaTwo, "test:replay:");
        Instant expiry = Instant.now().plusSeconds(60);

        assertThat(firstReplica.consumeOnceReactive("shared-jti", expiry).block()).isTrue();
        assertThat(secondReplica.consumeOnceReactive("shared-jti", expiry).block()).isFalse();
    }

    @Test
    void keyPrefixesIsolateDeployments() {
        var blue = guard(replicaOne, "test:blue:");
        var green = guard(replicaTwo, "test:green:");
        Instant expiry = Instant.now().plusSeconds(60);

        assertThat(blue.consumeOnceReactive("same-jti", expiry).block()).isTrue();
        assertThat(green.consumeOnceReactive("same-jti", expiry).block()).isTrue();
    }

    private LettuceConnectionFactory connectionFactory() {
        var config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        var factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return factory;
    }

    private RedisAssertionReplayGuard guard(LettuceConnectionFactory factory, String prefix) {
        return new RedisAssertionReplayGuard(new ReactiveStringRedisTemplate(factory), prefix);
    }
}
