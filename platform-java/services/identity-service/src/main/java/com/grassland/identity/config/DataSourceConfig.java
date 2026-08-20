package com.grassland.identity.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.grassland.database.FlywayBootstrap;

/**
 * 从 DATABASE_URL 派生 JDBC DataSource + 手动 Flyway（骨架单源在 platform-database，
 * 2026-08-20 下沉；本类只声明服务方言：条件属性名 / 历史表 / locations 覆盖 / 锁口径）。
 *
 * <p>与 R2dbcConnectionFactoryConfig 并存：业务读写走 R2DBC，schema 迁移走 JDBC
 * （Flyway 不支持 R2DBC）；Spring Boot FlywayAutoConfiguration 在纯 R2DBC 应用下不触发。
 * historyTable=flyway_schema_history；identity 用 Flyway 默认历史表名（历史遗留，其余服务为 <svc>_flyway_schema）。
 */
@Configuration
@ConditionalOnProperty(name = "identity.datasource.from-database-url", havingValue = "true")
public class DataSourceConfig {

    @Bean
    DataSource dataSource(Environment env) {
        return FlywayBootstrap.dataSource(env.getProperty("DATABASE_URL"), "identity-service");
    }

    /** initMethod=migrate：bean 初始化即迁移；baseline 兼容非空 legacy 库。 */
    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource, Environment env) {
        return FlywayBootstrap.flyway(
                dataSource,
                "flyway_schema_history",
                env.getProperty("identity.flyway.locations", "classpath:db/migration"),
                false);
    }
}
