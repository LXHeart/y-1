package com.grassland.intelligence.homepage;

import java.util.List;

/** 热点分组（60s 按平台分组）。 */
public record HotItemGroup(String platform, String label, List<HotItem> items) {}
