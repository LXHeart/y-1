package com.grassland.intelligence.creationstudio;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * Read actual exported bytes for independent assertions and the simulated
 * channel's readback.
 */
final class StudioTestFiles {
	static Map<String, byte[]> unzip(byte[] bytes) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			java.util.zip.ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null)
				files.put(entry.getName(), zip.readAllBytes());
		} catch (Exception failure) {
			throw new AssertionError(failure);
		}
		return files;
	}
	static String html(byte[] bytes, Map<String, Object> manifest) {
		String html = new String(
				"application/zip".equals(manifest.get("contentType")) ? unzip(bytes).get("article.html") : bytes,
				StandardCharsets.UTF_8);
		var document = org.jsoup.Jsoup.parse(html);
		document.outputSettings().prettyPrint(false);
		for (Object raw : (List<?>) manifest.getOrDefault("mediaFiles", List.of())) {
			var file = (Map<?, ?>) raw;
			for (var image : document.select("img"))
				if (image.attr("src").equals(file.get("path")))
					image.attr("src", "/api/media/" + file.get("mediaId"));
		}
		return document.body().html();
	}
}
