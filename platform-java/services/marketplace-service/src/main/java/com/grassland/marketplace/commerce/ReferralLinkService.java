package com.grassland.marketplace.commerce;

import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 D98-01：服务端发放的不透明推广链接（rlid）。
 *
 * <ul>
 * <li>发放仅限资格成立的推荐官本人：该套餐推广任务上持有 accepted 报名（403 越权/无资格）；</li>
 * <li>每推荐官每任务至多一条现行链接（先查后插幂等；并发双插均有效，last-touch 兼容）；</li>
 * <li>{@link #resolveForOrder}：下单时服务端解析 rlid——链接级失效（无效/已终止/已过期/推广已结束）
 * 一律 422 + blockedReason 可解释；链接有效但推荐官失去接单资格 = 自然流量（与旧参数 C01 口径一致）；</li>
 * <li>金额与分成规则全部沿用既有 {@code CommerceService.createOrder} 冻结逻辑，本服务不碰钱。</li>
 * </ul>
 */
@Component
public class ReferralLinkService {

	/** 归因口径版本（D98-02 快照随行走）：last-touch、7 天窗口。 */
	static final String POLICY_VERSION = "last_touch_7d_v1";
	/** 展示用 rlid 短码长度（不透明 id 取前缀）。 */
	static final int SHORT_CODE_LENGTH = 8;
	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final int ID_LENGTH = 24;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final ReferralLinkRepository links;
	private final TaskRepository tasks;
	private final Duration linkTtl;

	public ReferralLinkService(ReferralLinkRepository links, TaskRepository tasks,
			@Value("${marketplace.promotion.link-ttl-days:90}") long linkTtlDays) {
		this.links = links;
		this.tasks = tasks;
		this.linkTtl = Duration.ofDays(Math.max(linkTtlDays, 1));
	}

	/** 发放视图（§6）：{referralLinkId, url, expiresAt, status…}。url 为站内相对路径（前端补 origin）。 */
	public record ReferralLinkView(String referralLinkId, String taskId, String packageId, String url, String status,
			String endedReason, Instant createdAt, Instant expiresAt, String policyVersion) {
		public String shortCode() {
			return referralLinkId.length() <= SHORT_CODE_LENGTH ? referralLinkId
					: referralLinkId.substring(0, SHORT_CODE_LENGTH) + "…";
		}

		static ReferralLinkView from(ReferralLinkRepository.ReferralLinkRow row) {
			String packageId = row.packageId();
			return new ReferralLinkView(row.id(), row.taskId(), packageId, landingUrl(packageId, row.id()),
					row.effectiveStatus(), row.endedReason(), row.createdAt(), row.expiresAt(), row.policyVersion());
		}
	}

	/** 下单解析结果：recommenderAccountId=null 表示链接有效但不可归因（自然流量）。 */
	public record ReferralResolution(String referralLinkId, String recommenderAccountId, String taskId,
			Instant touchedAt, String policyVersion) {
	}

	public Mono<ReferralLinkView> issue(Caller caller, String taskId) {
		if (taskId == null || taskId.isBlank()) {
			return Mono.error(new IllegalArgumentException("taskId 不能为空"));
		}
		String task = taskId.trim();
		return tasks.findPromotionTaskRef(task).switchIfEmpty(
				Mono.error(new MarketplaceException(404, "任务不存在或不是套餐推广任务"))).flatMap(ref -> {
			if (!ref.promotionActiveNow()) {
				return Mono.error(new MarketplaceException(409, "推广已结束或任务未在招募，不能生成推广链接"));
			}
			return tasks.hasAcceptedApplicationOnTask(task, caller.accountId()).flatMap(eligible -> {
				if (!eligible) {
					return Mono.error(new MarketplaceException(403, "仅持有该推广任务接单资格的推荐官本人可生成推广链接"));
				}
				return links.findCurrentByOwnerAndTask(java.util.UUID.fromString(caller.accountId()), task)
						.switchIfEmpty(Mono.defer(() -> links.insert(newRlid(),
								java.util.UUID.fromString(caller.accountId()), task, POLICY_VERSION,
								Instant.now().plus(linkTtl))
								// INSERT RETURNING 无 task join（package_id 为 NULL），回查补全以拼购买页 URL。
								.flatMap(saved -> links.findById(saved.id()))))
						.map(ReferralLinkView::from);
			});
		});
	}

	public Flux<ReferralLinkView> listMine(Caller caller) {
		return links.listByOwner(java.util.UUID.fromString(caller.accountId())).map(ReferralLinkView::from);
	}

	/** 本人失效（D98-01）：不可失效他人链接（按属主条件 UPDATE，0 行 → 幂等回查或 404）。 */
	public Mono<ReferralLinkView> endMine(Caller caller, String linkId) {
		java.util.UUID owner = java.util.UUID.fromString(caller.accountId());
		return links.endByOwner(linkId, owner)
				.switchIfEmpty(Mono.defer(() -> links.findById(linkId).flatMap(found -> found.recommenderAccountId()
						.equals(owner) && !"active".equals(found.effectiveStatus())
								// 归属本人但已非 active：重复终止幂等成功，回显现行状态。
								? Mono.just(found)
								: Mono.<ReferralLinkRepository.ReferralLinkRow>empty())))
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "推广链接不存在或无权操作")))
				.map(ReferralLinkView::from);
	}

	/**
	 * 下单归因解析（D98-01）：rlid → 推荐官。链接级失效 422（订单可无归因另行创建，由客户端重试
	 * 语义承接）；链接有效但推荐官失去资格 → recommenderAccountId=null（自然流量，C01 口径）。
	 * 推广结束联动：链接任务不再是该套餐进行中推广（结束/取消/被替换）→ 422 promotion_ended。
	 */
	public Mono<ReferralResolution> resolveForOrder(String packageId, String referralLinkId) {
		return links.findById(referralLinkId.trim())
				.switchIfEmpty(Mono.error(
						new MarketplaceException(422, "推广链接无效，本次购买将不关联推荐官", "link_invalid")))
				.<ReferralResolution>flatMap(link -> {
					if ("ended".equals(link.effectiveStatus())) {
						return Mono.error(new MarketplaceException(422, "推广链接已被推荐官终止，本次购买将不关联推荐官",
								"link_ended"));
					}
					if ("expired".equals(link.effectiveStatus())) {
						return Mono.error(new MarketplaceException(422,
								"推广链接已过期（发放后 " + linkTtl.toDays() + " 天有效），本次购买将不关联推荐官",
								"link_expired"));
					}
					return resolveActiveLink(packageId, link);
				});
	}

	private Mono<ReferralResolution> resolveActiveLink(String packageId, ReferralLinkRepository.ReferralLinkRow link) {
		return tasks.findActivePromotionTaskId(packageId)
				.switchIfEmpty(Mono.error(new MarketplaceException(422,
						"该套餐的推广已结束，链接不再归因，本次购买将不关联推荐官", "promotion_ended")))
				.flatMap(activeTaskId -> {
					if (!activeTaskId.equals(link.taskId())) {
						return Mono.error(new MarketplaceException(422,
								"该套餐的推广已结束或已更换，链接不再归因，本次购买将不关联推荐官",
								"promotion_ended"));
					}
					return tasks.hasAcceptedApplicationOnTask(link.taskId(),
							link.recommenderAccountId().toString()).map(eligible -> new ReferralResolution(link.id(),
									eligible ? link.recommenderAccountId().toString() : null, link.taskId(), null,
									link.policyVersion()));
				});
	}

	static String landingUrl(String packageId, String rlid) {
		// 链接固定挂根路径（DefaultLayout 的 ?view=commerce 兜底只认根路径白名单——#75 冒烟实锤）。
		return packageId == null ? "/?view=commerce&rlid=" + rlid
				: "/?view=commerce&package=" + packageId + "&rlid=" + rlid;
	}

	private static String newRlid() {
		StringBuilder id = new StringBuilder(ID_LENGTH);
		for (int i = 0; i < ID_LENGTH; i++) {
			id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
		}
		return id.toString();
	}
}
