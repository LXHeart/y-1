package com.grassland.intelligence.creationassistant;

import java.util.Locale;

/**
 * 草稿内容模式（任务书 #62，V59 列 {@code creation_draft.content_mode}）。
 *
 * <ul>
 * <li>{@link #ARTICLE} — 独立文章（默认；所有存量草稿与非知乎平台恒为此值）。</li>
 * <li>{@link #ANSWER} — 知乎回答（挂在已有问题下，问题即标题）。</li>
 * </ul>
 *
 * <p>
 * 草稿必须记住模式：否则跨设备恢复后回答草稿退化成文章，问题文本也一并丢失。
 */
public enum DraftContentMode {
	ARTICLE("article"), ANSWER("answer");

	private final String db;

	DraftContentMode(String db) {
		this.db = db;
	}

	public String db() {
		return db;
	}

	/** 按数据库/请求值解析；null/非法值返回 null（由调用方决定 400 或默认 ARTICLE）。 */
	public static DraftContentMode fromDb(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		for (DraftContentMode mode : values()) {
			if (mode.db.equals(normalized)) {
				return mode;
			}
		}
		return null;
	}

	/** null/空 → ARTICLE（缺省即文章模式，与列 DEFAULT 一致）。 */
	public static DraftContentMode orDefault(String value) {
		if (value == null || value.isBlank()) {
			return ARTICLE;
		}
		return fromDb(value);
	}
}
