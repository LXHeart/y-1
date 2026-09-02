package com.grassland.intelligence.orchestration;

import io.opentracing.Tracer;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把 Spring Boot 的 OTel tracer 桥进 Temporal client/worker 边界（照 marketplace/trust 同款）。 */
@Configuration
public class TemporalTracingConfig {

    @Bean
    OpenTracingOptions temporalOpenTracingOptions(Tracer tracer) {
        return OpenTracingOptions.newBuilder().setTracer(tracer).build();
    }

    @Bean
    WorkflowClientInterceptor temporalWorkflowClientTracingInterceptor(OpenTracingOptions options) {
        return new OpenTracingClientInterceptor(options);
    }

    @Bean
    WorkerInterceptor temporalWorkerTracingInterceptor(OpenTracingOptions options) {
        return new OpenTracingWorkerInterceptor(options);
    }
}
