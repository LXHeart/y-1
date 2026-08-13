package com.grassland.trust.config;

import io.opentracing.Tracer;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.opentracing.OpenTracingClientInterceptor;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingWorkerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bridges Spring Boot's OTel-backed tracer into Temporal client and worker boundaries. */
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
