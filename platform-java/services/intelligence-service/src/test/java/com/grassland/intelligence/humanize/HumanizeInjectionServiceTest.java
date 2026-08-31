package com.grassland.intelligence.humanize;

import static org.assertj.core.api.Assertions.assertThat;
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
}
