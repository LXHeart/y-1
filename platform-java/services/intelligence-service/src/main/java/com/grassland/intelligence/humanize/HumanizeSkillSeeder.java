package com.grassland.intelligence.humanize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 去AI味 skill 启动种子（任务书 #61）：表空才按 {@code /contracts/humanize-skills.json} 全量插入（3
 * 条）。best-effort（照 {@code CreationStyleSkillSeeder} 姿态）： 失败打 WARN 不阻断启动。JSON
 * 解析用<b>自持</b> Jackson 实例（intelligence 无 ObjectMapper bean）。
 * {@code humanize_config} 不种（无行=未激活，admin 治理台主动开启）。
 */
@Component
public class HumanizeSkillSeeder {

	private static final Logger log = LoggerFactory.getLogger(HumanizeSkillSeeder.class);
	private static final Duration BLOCK = Duration.ofSeconds(20);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HumanizeSkillRepository repository;

	public HumanizeSkillSeeder(HumanizeSkillRepository repository) {
		this.repository = repository;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void seedOnStartup() {
		try {
			Long count = repository.count().block(BLOCK);
			if (count != null && count > 0) {
				return;
			}
			List<SeedSkill> seeds = loadSeed();
			List<Mono<Void>> inserts = new ArrayList<>(seeds.size());
			for (SeedSkill seed : seeds) {
				inserts.add(repository.insertSeed(seed.code(), seed.displayName(), seed.description(),
						seed.promptContent(), seed.sourceRepo(), seed.sourceLicense()));
			}
			Flux.concat(inserts).then().block(BLOCK);
			log.info("Seeded humanize skills: {} rows", seeds.size());
		} catch (Exception e) {
			log.warn("Humanize skill seed skipped (best-effort): {}", e.getMessage());
		}
	}

	private static List<SeedSkill> loadSeed() {
		try (var stream = HumanizeSkillSeeder.class.getClassLoader()
				.getResourceAsStream("contracts/humanize-skills.json")) {
			if (stream == null) {
				throw new IllegalStateException("contracts/humanize-skills.json missing from classpath"
						+ "（检查 build.gradle.kts processResources copySpec 是否登记）");
			}
			JsonNode root = MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
			List<SeedSkill> seeds = new ArrayList<>();
			for (JsonNode node : root.path("skills")) {
				seeds.add(new SeedSkill(node.path("code").asText(), node.path("displayName").asText(),
						node.path("description").asText(""), node.path("promptContent").asText(),
						node.path("sourceRepo").asText(""), node.path("sourceLicense").asText("MIT")));
			}
			if (seeds.isEmpty()) {
				throw new IllegalStateException("humanize-skills.json skills 为空");
			}
			return seeds;
		} catch (Exception e) {
			throw new IllegalStateException("去AI味 skill 种子加载失败", e);
		}
	}

	private record SeedSkill(String code, String displayName, String description, String promptContent,
			String sourceRepo, String sourceLicense) {
	}
}
