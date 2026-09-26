package com.grassland.intelligence.hypit.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Hypit 知识检索（任务书 #107-2 C107-14 / K11）：索引与原文档同源打包 （resources/hypit/knowledge/，由
 * platform-hypit/knowledge/build-index.mjs 生成， 65 份
 * path/sha256/title/topic/sourceCommit 登记）。检索按 topic/关键词过滤， 按路径读取并逐字节校验
 * sha256——索引与正文任何漂移都拒绝服务，不静默降级。 Prompt 只注入命中文档的相关片段（14.2：不每轮塞全仓库）。
 */
@Service
public class HypitKnowledgeService {

	private static final ObjectMapper JSON = new ObjectMapper();

	public record KnowledgeDoc(String path, String title, String topic, String sha256, String sourceCommit) {
	}

	public record SearchHit(KnowledgeDoc doc, String snippet) {
	}

	private volatile List<KnowledgeDoc> index;
	private volatile String indexCommit;

	private List<KnowledgeDoc> loadIndex() {
		List<KnowledgeDoc> cached = index;
		if (cached != null) {
			return cached;
		}
		try {
			String raw = new ClassPathResource("hypit/knowledge/index.json").getContentAsString(StandardCharsets.UTF_8);
			JsonNode root = JSON.readTree(raw);
			indexCommit = root.path("sourceCommit").asText();
			List<KnowledgeDoc> docs = new ArrayList<>();
			for (JsonNode node : root.path("documents")) {
				docs.add(new KnowledgeDoc(node.path("path").asText(), node.path("title").asText(),
						node.path("topic").asText(), node.path("sha256").asText(), node.path("sourceCommit").asText()));
			}
			index = List.copyOf(docs);
			return index;
		} catch (IOException error) {
			throw new IllegalStateException("knowledge index unreadable", error);
		}
	}

	/** 检索：topic 过滤 + 关键词（title/path 命中优先，正文 snippet 附带前 200 字）。 */
	public Mono<List<SearchHit>> search(String topic, String query, int limit) {
		return Mono.fromCallable(() -> {
			String needle = query == null ? "" : query.toLowerCase();
			List<SearchHit> hits = new ArrayList<>();
			for (KnowledgeDoc doc : loadIndex()) {
				if (topic != null && !topic.isBlank() && !topic.equals(doc.topic())) {
					continue;
				}
				boolean titleHit = !needle.isEmpty() && doc.title().toLowerCase().contains(needle);
				boolean pathHit = !needle.isEmpty() && doc.path().toLowerCase().contains(needle);
				String snippet = null;
				if (!needle.isEmpty() && !titleHit && !pathHit) {
					String body = readDocumentText(doc.path());
					int at = body.toLowerCase().indexOf(needle);
					if (at < 0) {
						continue;
					}
					snippet = body.substring(Math.max(0, at - 40), Math.min(body.length(), at + needle.length() + 160));
				}
				hits.add(new SearchHit(doc, snippet));
				if (hits.size() >= Math.min(Math.max(limit, 1), 50)) {
					break;
				}
			}
			return hits;
		});
	}

	/** 按路径读取原文并校验 sha256（缺一篇即校验失败——14.1 红线的运行期面）。 */
	public Mono<String> read(String path) {
		return Mono.fromCallable(() -> {
			for (KnowledgeDoc doc : loadIndex()) {
				if (doc.path().equals(path)) {
					String text = readDocumentText(path);
					String actual = HexFormat.of().formatHex(
							MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
					if (!actual.equals(doc.sha256())) {
						throw new IntelligenceException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
								"hypit_knowledge_drift", "知识文档与索引 hash 不一致：" + path);
					}
					return text;
				}
			}
			throw new IntelligenceException(404, "hypit_not_found", "知识文档不在索引中：" + path);
		});
	}

	public String sourceCommit() {
		loadIndex();
		return indexCommit;
	}

	public int documentCount() {
		return loadIndex().size();
	}

	private static String readDocumentText(String path) {
		try {
			return new ClassPathResource("hypit/knowledge/" + path).getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new IntelligenceException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "hypit_knowledge_missing",
					"索引登记的文档缺失：" + path);
		}
	}
}
