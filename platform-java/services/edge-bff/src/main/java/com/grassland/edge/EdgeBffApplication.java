package com.grassland.edge;

import com.grassland.edge.proxy.EdgeRoutingProperties;
import com.grassland.edge.security.EdgeSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 排除 {@link R2dbcAutoConfiguration}：edge-bff 自管 ConnectionFactory（{@code EdgeR2dbcConfig}，按
 * {@code edge.identity.from-database-url} opt-in）。未启用直读 session 时纯代理启动，无 R2DBC URL 也不报错。
 * DatabaseClient 由 {@code R2dbcDataAutoConfiguration} 在 ConnectionFactory bean 存在时自动创建。
 */
@SpringBootApplication(exclude = R2dbcAutoConfiguration.class)
@EnableConfigurationProperties({EdgeRoutingProperties.class, EdgeSecurityProperties.class})
public class EdgeBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(EdgeBffApplication.class, args);
    }
}
