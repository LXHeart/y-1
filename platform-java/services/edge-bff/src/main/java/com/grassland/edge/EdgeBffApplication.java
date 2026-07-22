package com.grassland.edge;

import com.grassland.edge.proxy.EdgeRoutingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EdgeRoutingProperties.class)
public class EdgeBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(EdgeBffApplication.class, args);
    }
}
