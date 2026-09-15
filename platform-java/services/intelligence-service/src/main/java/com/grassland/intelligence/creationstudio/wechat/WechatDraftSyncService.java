package com.grassland.intelligence.creationstudio.wechat;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.creationassistant.CreationDraft;
import com.grassland.intelligence.creationassistant.CreationDraftRepository;
import com.grassland.intelligence.creationstudio.plan.PlanJson;
import com.grassland.intelligence.creationstudio.StudioCommandStore;
import com.grassland.intelligence.creationstudio.StudioCursor;
import com.grassland.intelligence.creationstudio.render.CreationRenderService;
import com.grassland.intelligence.creationstudio.render.CreationExportRepository;
import com.grassland.intelligence.creationstudio.render.CreationImageProcessor;
import com.grassland.intelligence.creationstudio.wechat.WechatAccountRepository.AccountRow;
import com.grassland.intelligence.creationstudio.wechat.WechatApiClient.WechatArticle;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository.MediaMappingRow;
import com.grassland.intelligence.creationstudio.wechat.WechatDraftSyncRepository.SyncRow;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.orchestration.WechatDraftWorkflowStarter;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * 任务书 #101 C101-21（API101-26~31、§6.8）：公众号草稿同步。
 *
 * <p>
 * 任务书 #103 C103-21：创建冻结链已搬移至 {@link WechatSyncPreparationService}、workflow 推进与
 * 候选/核实/取消已搬移至 {@link WechatSyncExecutionService}；本类保留 wire 层（命令/候选 record、
 * list 读取、响应体）并以委托保持既有 API（Controller/activity/IT 调用方零改动），行为零变更。
 *
 * <p>
 * 创建时冻结完整 payload（标题/正文 HTML/摘要/作者/来源链接/评论选项/封面与图片顺序）； 同账号＋draftVersion＋
 * payloadHash 复用活动记录；上传映射按 连接＋凭据版本＋内容hash＋用途 缓存； 持久 submitting 后由同一次 advance
 * 完成唯一 draft/add——崩溃/重放看到悬置标记一律 unknown，禁止盲重发（R101-15）； 回读 draft/get 受控比对（文本
 * token＋图片顺序＋封面）一致才 succeeded。Token 失效有界刷新一次后重试一次； 上传/只读最多重试 2 次；候选搜索 20/页 ≤100
 * 条、总截止 20s、Redis 缓存 30s；每同步总时限 10min。
 */
@Service
public class WechatDraftSyncService {

	private final WechatSyncPreparationService preparation;
	private final WechatSyncExecutionService execution;

	public WechatDraftSyncService(WechatSyncPreparationService preparation, WechatSyncExecutionService execution) {
		this.preparation = preparation;
		this.execution = execution;
	}

	// ---------- 任务书 #103 C103-21：准备/执行已按职责搬移，facade 委托保持既有公共 API ----------

	public Mono<SyncRow> create(Caller caller, CreateCommand command) {
		return preparation.create(caller, command);
	}

	public Mono<Boolean> advance(UUID syncId) {
		return execution.advance(syncId);
	}

	public Mono<CandidateResult> candidates(Caller caller, UUID syncId) {
		return execution.candidates(caller, syncId);
	}

	public Mono<SyncRow> reconcile(Caller caller, UUID syncId, UUID requestId, int expectedVersion,
			String externalDraftMediaId) {
		return execution.reconcile(caller, syncId, requestId, expectedVersion, externalDraftMediaId);
	}

	public Mono<SyncRow> cancel(Caller caller, UUID syncId, UUID requestId, int expectedVersion) {
		return execution.cancel(caller, syncId, requestId, expectedVersion);
	}

	public Mono<SyncRow> get(Caller caller, UUID syncId) {
		return preparation.get(caller, syncId);
	}

	public record CreateCommand(UUID requestId, UUID accountId, int expectedAccountVersion, UUID draftId,
			int draftVersion, UUID exportId, String author, String contentSourceUrl, int needOpenComment,
			int onlyFansCanComment) {
	}

	public record Candidate(String externalDraftMediaId, String title, String updatedAt, boolean contentMatches) {
	}

	public record CandidateResult(List<Candidate> items, int searchedCount, boolean hasMore) {
	}

	public Mono<Map<String, Object>> list(Caller caller, UUID draftId, int limit, String cursor) {
		return preparation.list(caller, draftId, limit, cursor);
	}

	static String normalizeText(String html) {
		return org.jsoup.Jsoup.parseBodyFragment(html == null ? "" : html).text().replace('\u00a0', ' ').strip();
	}

	// ---- 响应体（§6.3 WechatDraftSync；完整快照永不返回；实现见 preparation） ----

	public Map<String, Object> toBody(SyncRow row) {
		return preparation.toBody(row);
	}

}
