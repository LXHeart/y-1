package com.grassland.intelligence.creationcanvas;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

/**
 * Authorizes stable references and returns metadata only; no signing, object
 * reads or model calls.
 */
@Component
public class CanvasReferenceAccess {
	private final DatabaseClient db;
	public CanvasReferenceAccess(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * Old unavailable references may remain in place, but cannot be newly connected
	 * to an execution target.
	 */
	public Mono<Void> validateChanges(String accountId, String previous, String next) {
		try {
			var json = new com.fasterxml.jackson.databind.ObjectMapper();
			var old = previous == null ? json.createObjectNode() : json.readTree(previous);
			var current = json.readTree(next);
			Map<String, com.fasterxml.jackson.databind.JsonNode> oldNodes = new LinkedHashMap<>();
			old.path("nodes").forEach(node -> oldNodes.put(node.path("id").asText(), node));
			Set<String> oldPairs = new LinkedHashSet<>();
			old.path("edges").forEach(
					edge -> oldPairs.add(edge.path("fromNodeId").asText() + "|" + edge.path("toNodeId").asText()));
			Set<String> newSources = new LinkedHashSet<>();
			current.path("edges").forEach(edge -> {
				String from = edge.path("fromNodeId").asText(), to = edge.path("toNodeId").asText();
				if (!oldPairs.contains(from + "|" + to))
					newSources.add(from);
			});
			Map<String, Set<UUID>> required = new LinkedHashMap<>();
			for (var node : current.path("nodes")) {
				if (!"media".equals(node.path("kind").asText()))
					continue;
				var existing = oldNodes.get(node.path("id").asText());
				boolean changed = existing == null || !existing.path("refId").equals(node.path("refId"))
						|| !existing.path("refType").equals(node.path("refType"));
				if (changed || newSources.contains(node.path("id").asText()))
					required.computeIfAbsent(node.path("refType").asText(), ignored -> new LinkedHashSet<>())
							.add(UUID.fromString(node.path("refId").asText()));
			}
			// At most two queries, independent of the number of reference nodes.
			return Flux.fromIterable(required.entrySet())
					.concatMap(group -> requireAll(accountId, group.getKey(), group.getValue())).then();
		} catch (Exception error) {
			return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "素材引用结构不可读"));
		}
	}

	private Mono<Void> requireAll(String accountId, String type, Set<UUID> ids) {
		String query = authorizedQuery(type).replace("=:id", " IN (:ids)");
		return db.sql("SELECT count(*) FROM (" + query + ") authorized_references").bind("ids", List.copyOf(ids))
				.bind("account", accountId).map(row -> row.get(0, Long.class)).one()
				.flatMap(count -> count == ids.size() ? Mono.empty() : Mono.error(unavailable()));
	}

	public Mono<Map<String, Object>> require(String accountId, String refType, String refId) {
		UUID id;
		try {
			id = UUID.fromString(refId);
		} catch (Exception error) {
			return Mono.error(unavailable());
		}
		if (!"media".equals(refType) && !"content-asset".equals(refType))
			return Mono.error(unavailable());
		return db.sql(authorizedQuery(refType)).bind("id", id).bind("account", accountId)
				.map(row -> Map.<String, Object>of("name", String.valueOf(row.get("name", String.class)), "mimeType",
						String.valueOf(row.get("mime_type", String.class)), "sizeBytes",
						row.get("size_bytes", Long.class), "analysisStatus", "not_analyzed"))
				.one().switchIfEmpty(Mono.error(unavailable()));
	}

	private static String authorizedQuery(String refType) {
		return "content-asset".equals(refType)
				? "SELECT a.title AS name,m.mime_type,m.size_bytes FROM content_asset a JOIN media_reference m ON m.id=a.media_reference_id "
						+ "WHERE a.id=:id AND a.deleted_at IS NULL AND a.status='active' AND (a.valid_until IS NULL OR a.valid_until>now()) "
						+ "AND m.deleted_at IS NULL AND m.status='active' AND (m.expires_at IS NULL OR m.expires_at>now()) "
						+ "AND (a.owner_account_id=:account OR a.library_type='public' OR EXISTS(SELECT 1 FROM content_asset_grant g "
						+ "WHERE g.asset_id=a.id AND g.grantee_account_id=:account AND g.grant_type='recommender_share' AND g.released_at IS NULL "
						+ "AND (g.lease_until IS NULL OR g.lease_until>now())))"
				: "SELECT purpose AS name,mime_type,size_bytes FROM media_reference WHERE id=:id AND owner_account_id=:account "
						+ "AND deleted_at IS NULL AND status='active' AND (expires_at IS NULL OR expires_at>now())";
	}

	public static IntelligenceException unavailable() {
		return new IntelligenceException(409, "CANVAS_MEDIA_UNAVAILABLE", "引用素材不可用，请重新选择");
	}
}
