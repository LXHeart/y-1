package com.grassland.intelligence.config;

import io.r2dbc.spi.Parameter;
import io.r2dbc.spi.Parameters;

/** Typed nullable values for DatabaseClient bindings without Spring's retired Parameter adapter. */
public final class R2dbcBindings {

    private R2dbcBindings() {}

    public static Parameter nullable(Object value, Class<?> nullType) {
        return value != null ? Parameters.in(value) : Parameters.in(nullType);
    }
}
