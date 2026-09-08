package com.grassland.marketplace.commerce;

import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.TaskRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 D98-01/D98-02：服务端发放的不透明推广链接（rlid）与 7 天 last-touch 归因。
 *
 * <ul>
 * <li>发放仅限资格成立的推荐官本人：该套餐推广任务上持有 accepted 报名（403 越权/无资格）；</li>
 * <li>每推荐官每任务至多一条现行链接（先查后插幂等；并发双插均有效，last-touch 兼容）；</li>
 * <li>{@link #resolveForOrder}：下单时服务端解析——链接级失效（无效/已终止/已过期/推广已结束/触达
 * 过窗）一律 422 + blockedReason 可解释；last-touch：消费者 7 天窗口内最后一次触达的 rlid 胜出，
 * 全程未登录链路以订单请求本身为触达事实（context=order）；链接有效但推荐官失去接单资格 =
 * 自然流量（与旧参数 C01 口径一致）；</li>
 * <li>触达、归因均为幂等事实行；金额与分成规则全部沿用 {@code CommerceService.createOrder} 冻结逻辑。</li>
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
	private final ReferralTouchRepository touches;
	private final TaskRepository tasks;
	private final CommerceRepository commerce;
	private final Duration linkTtl;
	private final long attributionWindowDays;

	public ReferralLinkService(ReferralLinkRepository links, ReferralTouchRepository touches, TaskRepository tasks,
			CommerceRepository commerce,
			@Value("${marketplace.promotion.link-ttl-days:90}") long linkTtlDays,
			@Value("${marketplace.promotion.attribution-window-days:7}") long attributionWindowDays) {
		this.links = links;
		this.touches = touches;
		this.tasks = tasks;
		this.commerce = commerce;
		this.linkTtl = Duration.ofDays(Math.max(linkTtlDays, 1));
		this.attributionWindowDays = Math.max(attributionWindowDays, 1);
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
			Instant touchedAt, String policyVersion, String basis) {
	}

	/** 归因解释读模型（§6）：三端（消费者/推荐官/治理台）字段一致。 */
	public record AttributionExplain(String orderId, boolean attributed, String recommenderAccountId,
			String referralLinkId, String shortCode, Instant touchedAt, long windowDays, String policyVersion,
			String basis, String reason) {
	}

	/** 治理台按 rlid 查全生命周期（§5.2）：发放/触达/归因订单/失效原因。 */
	public record ReferralLifecycle(ReferralLinkView link, long touchCount,
			List<ReferralTouchRepository.TouchRow> recentTouches, List<LifecycleOrder> orders) {
		public record LifecycleOrder(String orderId, String status, long priceCents, long recommenderAmountCents,
				Instant createdAt) {
		}
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
	 * 触达落行（D98-02）：消费者经 rlid 进入购买页（公开 GET 套餐详情）。登录态可解析则记账号，
	 * 否则 consumer_account_id 为 NULL（未登录触达也记）；链接不存在静默跳过（公开端点不抛错）。
	 */
	public Mono<Void> recordLandingTouch(String referralLinkId, Mono<Caller> optionalCaller) {
		String rlid = referralLinkId == null ? "" : referralLinkId.trim();
		if (rlid.isEmpty()) {
			return Mono.empty();
		}
		return links.findById(rlid)
				.flatMap(link -> optionalCaller.map(Caller::accountId).map(UUID::fromString)
						.onErrorComplete()
						.flatMap(consumer -> touches.insert(link.id(), consumer, "landing"))
						.switchIfEmpty(Mono.defer(() -> touches.insert(link.id(), null, "landing"))))
				.then();
	}

	/**
	 * 下单归因解析（D98-01/D98-02）：rlid → 触达窗口 → last-touch → 推荐官。
	 *
	 * <ul>
	 * <li>链接级失效（无效/已终止/已过期/推广已结束）→ 422（订单可无归因另行创建，客户端重试承接）；</li>
	 * <li>窗口判定：消费者（登录态）最近一次触达超过 7 天 → 422 attribution_window_expired；</li>
	 * <li>last-touch：窗口内最近触达的 rlid 胜出（可能与请求携带的 rlid 不同——「后触达胜出」）；</li>
	 * <li>全程未登录（无登录态触达）→ 订单请求本身即触达事实（context=order），touched_at=下单时刻；</li>
	 * <li>链接有效但推荐官失去接单资格 → recommenderAccountId=null（自然流量，C01 口径）。</li>
	 * </ul>
	 */
	public Mono<ReferralResolution> resolveForOrder(Caller caller, String packageId, String referralLinkId) {
		return loadActiveLink(referralLinkId.trim())
				.flatMap(link -> latestTouchContext(caller, link)
						.flatMap(ctx -> resolveTouchTarget(packageId, link, ctx)));
	}

	/** 触达上下文：last-touch 裁决（可能切换到另一条 rlid）或订单时触达兜底。 */
	private Mono<TouchContext> latestTouchContext(Caller caller, ReferralLinkRepository.ReferralLinkRow link) {
		return touches.findLatestByConsumer(UUID.fromString(caller.accountId()))
				.<TouchContext>flatMap(latest -> {
					if (latest.touchedAt().isBefore(Instant.now().minus(Duration.ofDays(attributionWindowDays)))) {
						long daysAgo = Math.max(Duration.between(latest.touchedAt(), Instant.now()).toDays(), 1);
						return Mono.error(new MarketplaceException(422,
								"推广链接触达已过归因窗口（上次触达约 " + daysAgo + " 天前，窗口 " + attributionWindowDays
										+ " 天），本次购买将不关联推荐官",
								"attribution_window_expired"));
					}
					return Mono.just(new TouchContext(latest.referralLinkId(), latest.touchedAt(), "last_touch"));
				})
				.switchIfEmpty(Mono.defer(() -> touches.insert(link.id(), UUID.fromString(caller.accountId()), "order")
						.map(touch -> new TouchContext(touch.referralLinkId(), touch.touchedAt(), "order_time"))));
	}

	private record TouchContext(String referralLinkId, Instant touchedAt, String basis) {
	}

	private Mono<ReferralResolution> resolveTouchTarget(String packageId, ReferralLinkRepository.ReferralLinkRow link,
			TouchContext ctx) {
		Mono<ReferralLinkRepository.ReferralLinkRow> target = ctx.referralLinkId().equals(link.id())
				? Mono.just(link)
				// last-touch 切到另一条 rlid：同样走链接级守卫（该链接死亡 → 422 可解释）。
				: loadActiveLink(ctx.referralLinkId());
		return target.flatMap(targetLink -> resolveActiveLink(packageId, targetLink)
				.map(res -> new ReferralResolution(targetLink.id(), res.recommenderAccountId(), targetLink.taskId(),
						ctx.touchedAt(), targetLink.policyVersion(), ctx.basis())));
	}

	/**
	 * 下单归因解析（链接级守卫部分）：rlid → 推荐官。链接级失效 422（订单可无归因另行创建，由客户端重试
	 * 语义承接）；链接有效但推荐官失去资格 → recommenderAccountId=null（自然流量，C01 口径）。
	 * 推广结束联动：链接任务不再是该套餐进行中推广（结束/取消/被替换）→ 422 promotion_ended。
	 */
	private Mono<ReferralLinkRepository.ReferralLinkRow> loadActiveLink(String referralLinkId) {
		return links.findById(referralLinkId)
				.switchIfEmpty(Mono.error(
						new MarketplaceException(422, "推广链接无效，本次购买将不关联推荐官", "link_invalid")))
				.flatMap(link -> {
					if ("ended".equals(link.effectiveStatus())) {
						return Mono.<ReferralLinkRepository.ReferralLinkRow>error(new MarketplaceException(422,
								"推广链接已被推荐官终止，本次购买将不关联推荐官", "link_ended"));
					}
					if ("expired".equals(link.effectiveStatus())) {
						return Mono.<ReferralLinkRepository.ReferralLinkRow>error(new MarketplaceException(422,
								"推广链接已过期（发放后 " + linkTtl.toDays() + " 天有效），本次购买将不关联推荐官",
								"link_expired"));
					}
					return Mono.just(link);
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
									link.policyVersion(), null));
				});
	}

	/**
	 * 归因解释（§6）：rlid 短码、触达时间、窗口口径、归因成立依据或不可归因原因。
	 * 事实来源 = 订单的 referral_link 来源归因行（V60 增列）；无该行 = 自然流量/未携链接。
	 */
	public Mono<AttributionExplain> explain(Order order) {
		return commerce.findReferralAttribution(order.id())
				.flatMap(fact -> links.findById(fact.referralLinkId())
						.map(link -> new AttributionExplain(order.id(), true, fact.recommenderAccountId(),
								fact.referralLinkId(), shortCode(fact.referralLinkId()), fact.touchedAt(),
								attributionWindowDays, link.policyVersion(), fact.reason(), null))
						.defaultIfEmpty(new AttributionExplain(order.id(), true, fact.recommenderAccountId(),
								fact.referralLinkId(), shortCode(fact.referralLinkId()), fact.touchedAt(),
								attributionWindowDays, POLICY_VERSION, fact.reason(), null)))
				.defaultIfEmpty(new AttributionExplain(order.id(), false, null, null, null, null,
						attributionWindowDays, POLICY_VERSION, "not_attributed",
						"订单创建时未经有效推广链接归因（自然流量）"));
	}

	/** 治理台按 rlid 查全生命周期（AC-98-10）：链接 + 触达记录 + 归因订单 + 失效原因。 */
	public Mono<ReferralLifecycle> lifecycle(String referralLinkId) {
		return links.findById(referralLinkId.trim())
				.switchIfEmpty(Mono.error(new MarketplaceException(404, "推广链接不存在")))
				.flatMap(link -> Mono.zip(touches.countByLink(link.id()), touches.listByLink(link.id(), 50).collectList(),
						commerce.listOrdersByReferralLink(link.id()).collectList())
						.map(tuple -> new ReferralLifecycle(ReferralLinkView.from(link), tuple.getT1(), tuple.getT2(),
								tuple.getT3())));
	}

	static String shortCode(String referralLinkId) {
		return referralLinkId.length() <= SHORT_CODE_LENGTH ? referralLinkId
				: referralLinkId.substring(0, SHORT_CODE_LENGTH) + "…";
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
