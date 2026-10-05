package com.grassland.intelligence.humanize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.credits.CreditFeature;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
@DisplayName("HumanizeInjectionService")
class HumanizeInjectionServiceTest {

	private static final String RULE = "RULE-BODY-XYZ";

	@Mock
	HumanizeSkillRepository repository;

	@InjectMocks
	HumanizeInjectionService service;

	private static HumanizeSkill activeSkill() {
		return new HumanizeSkill(UUID.randomUUID(), "shuorenhua", "说人话", "", RULE, "", "MIT", true, 0, null, null);
	}

	private void activated() {
		when(repository.findActiveSkill()).thenReturn(Mono.just(activeSkill()));
	}

	@Test
	@DisplayName("白名单外的 feature 原样返回且不查库")
	void skipsNonCreativeFeatureWithoutQueryingDb() {
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		List<ChatMessage> result = service.injectForFeature(messages, CreditFeature.VIDEO_ANALYSIS).block();

		assertThat(result).isEqualTo(messages);
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("未激活任何 skill 时原样返回")
	void returnsMessagesUnchangedWhenNoActiveSkill() {
		when(repository.findActiveSkill()).thenReturn(Mono.empty());
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		List<ChatMessage> result = service.injectForFeature(messages, CreditFeature.ARTICLE_GENERATION).block();

		assertThat(result).containsExactlyElementsOf(messages);
	}

	@Test
	@DisplayName("feature 为 null 视为创作型并注入")
	void treatsNullFeatureAsCreative() {
		activated();
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		List<ChatMessage> result = service.injectForFeature(messages, null).block();

		assertThat(result).hasSize(2);
		assertThat(result.getFirst().content()).startsWith("S").contains(HumanizeInjectionService.SEGMENT_APPENDED)
				.endsWith(RULE);
	}

	@Test
	@DisplayName("含一条 system 时追加到该 system 尾部且 user 消息不变")
	void appendsToSingleSystemMessage() {
		activated();
		List<ChatMessage> messages = List.of(ChatMessage.system("BASE"), ChatMessage.user("U1"),
				ChatMessage.user("U2"));

		List<ChatMessage> result = service.injectCreative(messages).block();

		assertThat(result).hasSize(3);
		assertThat(result.getFirst().role()).isEqualTo("system");
		assertThat(result.getFirst().content()).isEqualTo("BASE" + HumanizeInjectionService.SEGMENT_APPENDED + RULE);
		assertThat(result.get(1)).isEqualTo(messages.get(1));
		assertThat(result.get(2)).isEqualTo(messages.get(2));
	}

	@Test
	@DisplayName("含多条 system 时只注入最后一条")
	void appendsToLastSystemMessageOnly() {
		activated();
		List<ChatMessage> messages = List.of(ChatMessage.system("A"), ChatMessage.system("B"), ChatMessage.user("C"));

		List<ChatMessage> result = service.injectCreative(messages).block();

		assertThat(result).hasSize(3);
		assertThat(result.getFirst().content()).isEqualTo("A");
		assertThat(result.get(1).content()).isEqualTo("B" + HumanizeInjectionService.SEGMENT_APPENDED + RULE);
		assertThat(result.get(2)).isEqualTo(messages.get(2));
	}

	@Test
	@DisplayName("无 system 消息时在头部插入新 system 且原消息保持原位")
	void insertsStandaloneSystemWhenAbsent() {
		activated();
		ChatMessage multimodal = ChatMessage.user(List.of(ContentPart.text("x")));
		List<ChatMessage> messages = List.of(ChatMessage.user("U1"), multimodal);

		List<ChatMessage> result = service.injectCreative(messages).block();

		assertThat(result).hasSize(3);
		assertThat(result.getFirst().role()).isEqualTo("system");
		assertThat(result.getFirst().content()).isEqualTo(HumanizeInjectionService.SEGMENT_STANDALONE + RULE);
		assertThat(result.get(1)).isEqualTo(messages.getFirst());
		assertThat(result.get(2)).isEqualTo(multimodal);
		assertThat(result.get(2).content()).isNull();
		assertThat(result.get(2).parts()).containsExactly(ContentPart.text("x"));
	}

	@Test
	@DisplayName("读库异常时 fail-open 原样返回不抛错")
	void failsOpenOnRepositoryError() {
		when(repository.findActiveSkill()).thenReturn(Mono.error(new RuntimeException("db down")));
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		List<ChatMessage> result = service.injectCreative(messages).block();

		assertThat(result).containsExactlyElementsOf(messages);
	}

	@Test
	@DisplayName("append 完整保留 promptContent 不截断")
	void appendKeepsPromptContentIntact() {
		String longRule = "第一条规则\n第二条规则\n".repeat(50);
		List<ChatMessage> messages = List.of(ChatMessage.system("BASE"), ChatMessage.user("U"));

		List<ChatMessage> result = HumanizeInjectionService.append(messages, longRule);

		assertThat(result.getFirst().content()).contains(longRule).endsWith(longRule);
	}

	// ---------- 任务书 #108 C-01：优先级消歧 + 同次注入元数据（TC-C01-002，§6.6）----------

	@Test
	@DisplayName("注入段不再声明「最高优先级」，改为事实/身份/明确任务要求优先")
	void segmentDropsHighestPriorityWording() {
		assertThat(HumanizeInjectionService.SEGMENT_APPENDED).doesNotContain("最高优先级");
		assertThat(HumanizeInjectionService.SEGMENT_STANDALONE).doesNotContain("最高优先级");
		assertThat(HumanizeInjectionService.SEGMENT_APPENDED).doesNotContain("以本段为准");
		// 保护边界保留：事实/输出结构原样执行、不新增未确认经历、不改正负方向
		assertThat(HumanizeInjectionService.SEGMENT_APPENDED).contains("只约束语言风格").contains("不得新增未经确认的身份")
				.contains("不得改变评价的正负方向").contains("以后者为准");
	}

	private static String sha256Hex(String value) {
		try {
			byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(hash);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("已激活：同一次查库同时产出注入内容与 applied 元数据（单次 findActiveSkill）")
	void appliedMetadataComesFromSameSingleRead() {
		activated();
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		HumanizeInjectionService.Injection result = service.injectCreativeDetailed(messages).block();

		assertThat(result.messages()).hasSize(2);
		assertThat(result.messages().getFirst().content())
				.isEqualTo("S" + HumanizeInjectionService.SEGMENT_APPENDED + RULE);
		HumanizeInjectionService.Metadata meta = result.metadata();
		assertThat(meta.policyVersion()).isEqualTo(HumanizeInjectionService.VOICE_POLICY_VERSION);
		assertThat(meta.status()).isEqualTo("applied");
		assertThat(meta.activeCode()).isEqualTo("shuorenhua");
		assertThat(meta.version()).isEqualTo(0);
		assertThat(meta.contentHash()).isEqualTo(sha256Hex(RULE));
		// 同次读取：一次 detailed 注入只查一次库（不二次查库拼版本）
		verify(repository, times(1)).findActiveSkill();
	}

	@Test
	@DisplayName("未激活：disabled 元数据且消息原样")
	void disabledMetadataWhenNoActiveSkill() {
		when(repository.findActiveSkill()).thenReturn(Mono.empty());
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		HumanizeInjectionService.Injection result = service.injectCreativeDetailed(messages).block();

		assertThat(result.messages()).containsExactlyElementsOf(messages);
		assertThat(result.metadata().status()).isEqualTo("disabled");
		assertThat(result.metadata().activeCode()).isNull();
		assertThat(result.metadata().version()).isNull();
		assertThat(result.metadata().contentHash()).isNull();
		assertThat(result.metadata().policyVersion()).isEqualTo(HumanizeInjectionService.VOICE_POLICY_VERSION);
	}

	@Test
	@DisplayName("读库异常：fail-open 原样返回且元数据标 degraded")
	void degradedMetadataOnRepositoryError() {
		when(repository.findActiveSkill()).thenReturn(Mono.error(new RuntimeException("db down")));
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		HumanizeInjectionService.Injection result = service.injectCreativeDetailed(messages).block();

		assertThat(result.messages()).containsExactlyElementsOf(messages);
		assertThat(result.metadata().status()).isEqualTo("degraded");
		assertThat(result.metadata().activeCode()).isNull();
	}

	@Test
	@DisplayName("白名单外：detailed 入口原样返回、metadata=null 且不查库")
	void nonCreativeFeatureDetailedSkipsWithoutMetadata() {
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		HumanizeInjectionService.Injection result = service
				.injectForFeatureDetailed(messages, CreditFeature.VIDEO_ANALYSIS).block();

		assertThat(result.messages()).isEqualTo(messages);
		assertThat(result.metadata()).isNull();
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("创作型 feature 走 detailed：applied 元数据与注入同时给出（同次读取）")
	void creativeFeatureDetailedCarriesMetadata() {
		activated();
		List<ChatMessage> messages = List.of(ChatMessage.system("S"));

		HumanizeInjectionService.Injection result = service
				.injectForFeatureDetailed(messages, CreditFeature.MOMENTS_GENERATION).block();

		assertThat(result.metadata().status()).isEqualTo("applied");
		assertThat(result.messages().getFirst().content()).endsWith(RULE);
		verify(repository, times(1)).findActiveSkill();
	}

	@Test
	@DisplayName("旧签名委托：injectCreative/injectForFeature 返回的消息与 detailed 同源")
	void legacySignaturesDelegateToDetailed() {
		activated();
		List<ChatMessage> messages = List.of(ChatMessage.system("S"), ChatMessage.user("U"));

		List<ChatMessage> legacy = service.injectCreative(messages).block();
		List<ChatMessage> legacyFeature = service.injectForFeature(messages, null).block();
		HumanizeInjectionService.Injection detailed = service.injectCreativeDetailed(messages).block();

		assertThat(legacy).isEqualTo(detailed.messages());
		assertThat(legacyFeature).isEqualTo(detailed.messages());
	}
}
