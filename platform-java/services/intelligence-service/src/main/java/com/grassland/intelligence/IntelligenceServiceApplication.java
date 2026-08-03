package com.grassland.intelligence;

import com.grassland.intelligence.bilibili.BilibiliFetchProperties;
import com.grassland.intelligence.bilibili.BilibiliProxyTokenProperties;
import com.grassland.intelligence.douyin.DouyinFetchProperties;
import com.grassland.intelligence.douyin.DouyinHotItemsProperties;
import com.grassland.intelligence.douyin.DouyinProxyTokenProperties;
import com.grassland.intelligence.event.OutboxProperties;
import com.grassland.intelligence.mediaplatform.LegacyMediaProxyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties({OutboxProperties.class, DouyinHotItemsProperties.class, LegacyMediaProxyProperties.class, BilibiliProxyTokenProperties.class, BilibiliFetchProperties.class, DouyinProxyTokenProperties.class, DouyinFetchProperties.class})
@SpringBootApplication
public class IntelligenceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IntelligenceServiceApplication.class, args);
    }
}
