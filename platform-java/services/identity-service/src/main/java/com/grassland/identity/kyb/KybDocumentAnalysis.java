package com.grassland.identity.kyb;

import com.fasterxml.jackson.databind.JsonNode;

public record KybDocumentAnalysis(
        int schemaVersion,
        String documentType,
        double confidence,
        JsonNode fields,
        String provider,
        String model) {}

