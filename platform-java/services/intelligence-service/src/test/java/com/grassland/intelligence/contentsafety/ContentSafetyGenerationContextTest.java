package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContentSafetyGenerationContextTest {

    @Test
    void frozenSnapshotSuppliesOriginalityScopeAndFirstStoreCategory() {
        UUID id = UUID.randomUUID();
        CreationContextSnapshot snapshot = new CreationContextSnapshot(
                id, "owner", "organization", "task", "application", 3,
                "douyin", "video", Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("categories", List.of("美食", "餐饮")), Instant.now());

        OriginalityChecker.Context context = ContentSafetyService.generationContext(snapshot);

        assertThat(context.ownerAccountId()).isEqualTo("owner");
        assertThat(context.taskId()).isEqualTo("task");
        assertThat(context.applicationId()).isEqualTo("application");
        assertThat(context.platform()).isEqualTo("douyin");
        assertThat(context.contentForm()).isEqualTo("video");
        assertThat(context.sourceKind()).isEqualTo("generation");
        assertThat(ContentSafetyService.industryFromSnapshot(snapshot)).isEqualTo("美食");
    }

    @Test
    void absentOrMalformedBrandingCategoryProducesNoIndustryOverlay() {
        CreationContextSnapshot snapshot = new CreationContextSnapshot(
                UUID.randomUUID(), "owner", null, "task", "application", 1,
                "zhihu", "graphic", Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("categories", "not-a-list"), Instant.now());

        assertThat(ContentSafetyService.industryFromSnapshot(snapshot)).isNull();
        assertThat(ContentSafetyService.industryFromSnapshot(null)).isNull();
    }
}
