package com.grassland.intelligence.creationvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.grassland.intelligence.imageanalysis.StylePreferencesService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class CreationVoiceLearningTest {
	@Test
	void strictArrayRejectsMalformedOversizedAndNonTextCandidates() {
		for (String text : List.of("", "[] {}", "[] garbage", "```json\n[]\n```", "{}", "[12]", "[null]", "[\"\"]",
				"[\"" + "x".repeat(301) + "\"]", "[" + "\"x\",".repeat(10) + "\"x\"]")) {
			assertThatThrownBy(() -> StylePreferencesService.parseVoiceCandidates(text)).hasMessageContaining("格式无效");
		}
		assertThat(StylePreferencesService.parseVoiceCandidates("[\" 短句 \",\"短句\"]")).containsExactly("短句");
		assertThat(StylePreferencesService.parseVoiceCandidates("[]")).isEmpty();
	}

	@Test
	void sameTextSkipsModelAndPersistence() {
		var repository = mock(com.grassland.intelligence.imageanalysis.StylePreferencesRepository.class);
		var routed = mock(com.grassland.intelligence.ai.run.RoutedTextCompletionService.class);
		var service = new StylePreferencesService(repository, routed);
		assertThat(service.extractVoiceCandidates("a", null, "原文", "原文", "zhihu", "article").block()).isEmpty();
		verifyNoInteractions(repository, routed);
	}

	@Test
	void invalidReasonAndUnknownFieldsNeverReachGateOrModel() {
		var repo = mock(CreationVoiceRepository.class);
		var learning = mock(StylePreferencesService.class);
		var service = new CreationVoiceService(repo, learning);
		assertThatThrownBy(() -> service.preview("a", null, "consumer", Map.of("reason", "fact")))
				.hasMessageContaining("请求");
		verifyNoInteractions(repo, learning);
	}

	@Test
	void freezingWhileModelRunsDiscardsCandidatesAndNeverWrites() {
		var repo = mock(CreationVoiceRepository.class);
		var learning = mock(StylePreferencesService.class);
		var result = Sinks.<List<String>>one();
		when(repo.requireActive("a")).thenReturn(Mono.empty(), Mono.error(CreationVoiceTypes.barrier("frozen")));
		when(learning.extractVoiceCandidates(anyString(), anyString(), anyString(), anyString(), anyString(),
				anyString())).thenReturn(result.asMono());
		var future = new CreationVoiceService(repo, learning).preview("a", "org", "consumer",
				Map.of("original", "原文", "edited", "修改", "reason", "style", "platform", "zhihu", "genre", "article"))
				.toFuture();
		result.tryEmitValue(List.of("短句"));
		assertThatThrownBy(future::join)
				.hasCauseInstanceOf(com.grassland.intelligence.security.IntelligenceException.class);
		verify(repo, times(2)).requireActive("a");
		verifyNoMoreInteractions(repo);
	}
}
