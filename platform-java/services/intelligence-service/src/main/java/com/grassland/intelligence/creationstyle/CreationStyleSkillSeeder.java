package com.grassland.intelligence.creationstyle;

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

/**
 * 创作 style skill 启动种子（任务书 #57 决策 C）：表空才按
 * {@code /contracts/creation-style-skills.json} 全量插入（29 条 = 9 标题套路 + 11 体裁 + 9
 * 文风，其中 7 条知乎专属——任务书 #62）。
 *
 * <p>
 * best-effort（照 {@code PlatformModelConfigSeeder} 姿态）：失败打 WARN 不阻断启动； admin
 * 后续经治理台 CRUD 修订，种子不回写文件。种子 JSON 解析用<b>自持</b> Jackson 实例 （intelligence 无
 * ObjectMapper bean，注入即炸整个上下文）。
 */
@Component
public class CreationStyleSkillSeeder {

	private static final Logger log = LoggerFactory.getLogger(CreationStyleSkillSeeder.class);
	private static final Duration BLOCK = Duration.ofSeconds(20);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final CreationStyleSkillRepository repository;

	public CreationStyleSkillSeeder(CreationStyleSkillRepository repository) {
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
			var inserts = new ArrayList<reactor.core.publisher.Mono<Void>>(seeds.size());
			for (SeedSkill seed : seeds) {
				inserts.add(repository.insertSeed(seed.category(), seed.code(), seed.name(), seed.description(),
						seed.promptContent(), seed.sortOrder(), seed.applicablePlatforms()));
			}
			reactor.core.publisher.Flux.concat(inserts).then().block(BLOCK);
			log.info("Seeded creation style skills: {} rows ({} title formulas / {} genres / {} styles)", seeds.size(),
					seeds.stream().filter(s -> s.category() == CreationStyleSkillCategory.TITLE_FORMULA).count(),
					seeds.stream().filter(s -> s.category() == CreationStyleSkillCategory.GENRE).count(),
					seeds.stream().filter(s -> s.category() == CreationStyleSkillCategory.STYLE).count());
		} catch (Exception e) {
			log.warn("Creation style skill seed skipped (best-effort): {}", e.getMessage());
		}
	}

	private static List<SeedSkill> loadSeed() {
		try (var stream = CreationStyleSkillSeeder.class.getClassLoader()
				.getResourceAsStream("contracts/creation-style-skills.json")) {
			if (stream == null) {
				throw new IllegalStateException("contracts/creation-style-skills.json missing from classpath"
						+ "（检查 build.gradle.kts processResources copySpec 是否登记）");
			}
			JsonNode root = MAPPER.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
			List<SeedSkill> seeds = new ArrayList<>();
			for (JsonNode node : root.path("skills")) {
				CreationStyleSkillCategory category = CreationStyleSkillCategory
						.fromKey(node.path("category").asText(""));
				if (category == null) {
					throw new IllegalStateException(
							"creation-style-skills.json 未知 category: " + node.path("category").asText());
				}
				seeds.add(new SeedSkill(category, node.path("code").asText(), node.path("name").asText(),
						node.path("description").asText(""), node.path("promptContent").asText(),
						node.path("sortOrder").asInt(0), readPlatforms(node)));
			}
			if (seeds.isEmpty()) {
				throw new IllegalStateException("creation-style-skills.json skills 为空");
			}
			return seeds;
		} catch (Exception e) {
			throw new IllegalStateException("创作风格种子加载失败", e);
		}
	}

	/** 种子行；{@code applicablePlatforms} 空列表 = 全平台通用（任务书 #62）。 */
	private record SeedSkill(CreationStyleSkillCategory category, String code, String name, String description,
			String promptContent, int sortOrder, List<String> applicablePlatforms) {
	}

	/** 解析 {@code applicablePlatforms} 数组；缺失/非数组/空 → 空列表（通用）。 */
	private static List<String> readPlatforms(JsonNode node) {
		JsonNode arr = node.path("applicablePlatforms");
		if (!arr.isArray()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (JsonNode item : arr) {
			String value = item.asText("").trim();
			if (!value.isEmpty() && !out.contains(value)) {
				out.add(value);
			}
		}
		return List.copyOf(out);
	}
}
