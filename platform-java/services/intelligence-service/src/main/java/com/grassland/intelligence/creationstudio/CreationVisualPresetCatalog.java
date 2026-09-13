package com.grassland.intelligence.creationstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务书 #101 C101-01：视觉预设目录（contracts/creation-visual-presets.v1.json，前后端单源）。
 *
 * <p>
 * 迁入既有 12 风格 × 8 布局 × 3 配色 + 6 preset（全部旧 ID 保留）。服务端经 processResources
 * 加载同一契约，禁止再维护一份手工 Java 样式表。palette.backgroundHex 仅用于生成交付图补边，属于内容数据，不作为应用 UI
 * 颜色。
 */
public final class CreationVisualPresetCatalog {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Contract CONTRACT = loadContract();

	static final String VERSION = CONTRACT.version();

	private CreationVisualPresetCatalog() {
	}

	public record Style(String id, String label, String prompt) {
	}

	public record Layout(String id, String label, String prompt, String textLayout) {
	}

	public record Palette(String id, String label, String prompt, String backgroundHex) {
	}

	public record Preset(String id, String label, String styleId, String layoutId, String paletteId) {
	}

	public static String version() {
		return VERSION;
	}

	public static Style style(String id) {
		for (Style style : CONTRACT.styles()) {
			if (style.id().equals(id)) {
				return style;
			}
		}
		return null;
	}

	public static Layout layout(String id) {
		for (Layout layout : CONTRACT.layouts()) {
			if (layout.id().equals(id)) {
				return layout;
			}
		}
		return null;
	}

	public static Palette palette(String id) {
		for (Palette palette : CONTRACT.palettes()) {
			if (palette.id().equals(id)) {
				return palette;
			}
		}
		return null;
	}

	public static Preset preset(String id) {
		for (Preset preset : CONTRACT.presets()) {
			if (preset.id().equals(id)) {
				return preset;
			}
		}
		return null;
	}

	/** 交付图补边色；未知 palette 返回 null（调用方按非法样式 ID 拒绝，不猜测默认色）。 */
	public static String paletteBackgroundHex(String paletteId) {
		Palette palette = palette(paletteId);
		return palette == null ? null : palette.backgroundHex();
	}

	private static Contract loadContract() {
		try (InputStream stream = CreationVisualPresetCatalog.class.getClassLoader()
				.getResourceAsStream("contracts/creation-visual-presets.v1.json")) {
			if (stream == null) {
				throw new IllegalStateException("Missing contracts/creation-visual-presets.v1.json");
			}
			JsonNode root = MAPPER.readTree(stream);
			String version = root.path("version").asText();
			if (version.isBlank()) {
				throw new IllegalStateException("Invalid creation visual preset contract");
			}
			List<Style> styles = new java.util.ArrayList<>();
			List<Layout> layouts = new java.util.ArrayList<>();
			List<Palette> palettes = new java.util.ArrayList<>();
			List<Preset> presets = new java.util.ArrayList<>();
			for (JsonNode node : root.path("styles")) {
				styles.add(
						new Style(node.path("id").asText(), node.path("label").asText(), node.path("prompt").asText()));
			}
			for (JsonNode node : root.path("layouts")) {
				layouts.add(new Layout(node.path("id").asText(), node.path("label").asText(),
						node.path("prompt").asText(), node.path("textLayout").asText()));
			}
			for (JsonNode node : root.path("palettes")) {
				palettes.add(new Palette(node.path("id").asText(), node.path("label").asText(),
						node.path("prompt").asText(), node.path("backgroundHex").asText()));
			}
			for (JsonNode node : root.path("presets")) {
				presets.add(new Preset(node.path("id").asText(), node.path("label").asText(),
						node.path("styleId").asText(), node.path("layoutId").asText(),
						node.path("paletteId").isNull() ? null : node.path("paletteId").asText()));
			}
			requireUniqueIds("style", styles.stream().map(Style::id).toList());
			requireUniqueIds("layout", layouts.stream().map(Layout::id).toList());
			requireUniqueIds("palette", palettes.stream().map(Palette::id).toList());
			requireUniqueIds("preset", presets.stream().map(Preset::id).toList());
			if (styles.isEmpty() || layouts.isEmpty() || palettes.isEmpty() || presets.isEmpty()) {
				throw new IllegalStateException("Incomplete creation visual preset contract");
			}
			return new Contract(version, List.copyOf(styles), List.copyOf(layouts), List.copyOf(palettes),
					List.copyOf(presets));
		} catch (IOException error) {
			throw new IllegalStateException("Cannot load creation visual preset contract", error);
		}
	}

	private static void requireUniqueIds(String kind, List<String> ids) {
		Set<String> seen = new HashSet<>();
		for (String id : ids) {
			if (id.isBlank() || !seen.add(id)) {
				throw new IllegalStateException("Invalid or duplicate " + kind + " id: " + id);
			}
		}
	}

	private record Contract(String version, List<Style> styles, List<Layout> layouts, List<Palette> palettes,
			List<Preset> presets) {
	}
}
