package com.grassland.intelligence.creationstudio;

import com.grassland.intelligence.security.IntelligenceException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/** Opaque, bounded keyset over immutable creation time and ID. */
public record StudioCursor(OffsetDateTime createdAt, UUID id) {
	public static StudioCursor parse(String value) {
		if (value == null)
			return new StudioCursor(null, null);
		try {
			if (value.isBlank() || value.length() > 256 || !value.matches("[A-Za-z0-9_-]+"))
				throw new IllegalArgumentException();
			String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
			if (parts.length != 3 || !"1".equals(parts[0]))
				throw new IllegalArgumentException();
			UUID id = UUID.fromString(parts[2]);
			if (!id.toString().equals(parts[2]))
				throw new IllegalArgumentException();
			return new StudioCursor(OffsetDateTime.parse(parts[1]), id);
		} catch (Exception failure) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "cursor 不合法");
		}
	}
	public static String encode(OffsetDateTime at, UUID id) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString(("1|" + at + "|" + id).getBytes(StandardCharsets.UTF_8));
	}
}
