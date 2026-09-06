package com.grassland.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 任务书 #86 C-01：免登 token 载荷编解码单元（纯 JUnit，无容器）。
 * 载荷格式 {@code accountId|source|audience}——管道分隔、三段非空；任何其他形态
 * （含旧格式裸 accountId、两段、四段、空段、null）按无效处理。
 */
class CrossAppTokenStoreTest {
	private static final String ACCOUNT = "0f0e0d0c-1111-2222-3333-444455556666";

	@Test
	void payloadRoundTripPreservesAllThreeSegments() {
		String encoded = CrossAppTokenStore.encodePayload(ACCOUNT, "grassland", "ai");

		assertThat(encoded).isEqualTo(ACCOUNT + "|grassland|ai");
		var payload = CrossAppTokenStore.parsePayload(encoded).orElseThrow();
		assertThat(payload.accountId()).isEqualTo(ACCOUNT);
		assertThat(payload.source()).isEqualTo("grassland");
		assertThat(payload.audience()).isEqualTo("ai");
	}

	@Test
	void legacyBareAccountIdIsRejected() {
		assertThat(CrossAppTokenStore.parsePayload(ACCOUNT)).isEmpty();
	}

	@Test
	void wrongSegmentCountIsRejected() {
		assertThat(CrossAppTokenStore.parsePayload(ACCOUNT + "|grassland")).isEmpty();
		assertThat(CrossAppTokenStore.parsePayload(ACCOUNT + "|grassland|ai|extra")).isEmpty();
	}

	@Test
	void blankSegmentIsRejected() {
		assertThat(CrossAppTokenStore.parsePayload("|grassland|ai")).isEmpty();
		assertThat(CrossAppTokenStore.parsePayload(ACCOUNT + "|  |ai")).isEmpty();
		assertThat(CrossAppTokenStore.parsePayload(ACCOUNT + "|grassland|")).isEmpty();
	}

	@Test
	void nullValueIsRejected() {
		assertThat(CrossAppTokenStore.parsePayload(null)).isEmpty();
	}
}
