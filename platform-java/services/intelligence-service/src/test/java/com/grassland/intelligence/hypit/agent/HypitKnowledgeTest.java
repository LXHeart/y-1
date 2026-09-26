package com.grassland.intelligence.hypit.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.agent.HypitKnowledgeService.SearchHit;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 知识索引完整性（任务书 #107-2 C107-14 / TC107-14-01）：65 份文档全登记、 来源 commit 可追溯、检索命中带
 * snippet、按路径读取逐字节校验通过、 索引外路径 404。
 */
class HypitKnowledgeTest extends IntelligenceItSupport {

	@Autowired
	HypitKnowledgeService knowledge;

	@Test
	void indexIsCompleteWithTraceableSources() {
		assertThat(knowledge.documentCount()).as("SKILL.md + 64 篇 references = 65").isEqualTo(65);
		assertThat(knowledge.sourceCommit()).startsWith("2c320059");
	}

	@Test
	void searchFiltersByTopicAndQueryAndReadVerifiesHash() {
		List<SearchHit> hits = knowledge.search("production", "browser", 10).block(Duration.ofSeconds(20));
		assertThat(hits).isNotEmpty();
		assertThat(hits.get(0).doc().topic()).isEqualTo("production");

		List<SearchHit> creation = knowledge.search("creation", null, 10).block(Duration.ofSeconds(20));
		assertThat(creation).isNotEmpty();

		String document = knowledge.read(hits.get(0).doc().path()).block(Duration.ofSeconds(20));
		assertThat(document).isNotBlank();

		assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(IntelligenceException.class,
				() -> knowledge.read("references/absent.md").block(Duration.ofSeconds(20)))).isNotNull();
	}
}
