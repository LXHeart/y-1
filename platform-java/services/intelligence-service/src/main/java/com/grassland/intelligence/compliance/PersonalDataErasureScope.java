package com.grassland.intelligence.compliance;

/**
 * 个人注销清理的资源归属判定（任务书 #104 §3 D01 / §7.1）。
 *
 * <p>
 * 归属优先级：可证明的组织/共享引用 → 他人个人父对象 → 本人个人父对象 → 明确本人 owner 的无父孤儿。前两类保留；本人个人与 本人孤儿清理；无
 * owner 且父缺失的行不猜账号；多父链个人账号不一致属冲突——不删除并使 verify 失败（needs_review）。
 *
 * <p>
 * 本类只集中 SQL 片段与 kind 映射，供 {@link PersonalDataErasureRepository} 的批次
 * DELETE/UPDATE、残留核对与冲突核对 共用同一 resolver（BR-01）。所有片段参数一律绑定 {@code :a}，不拼接外部输入。
 */
final class PersonalDataErasureScope {

	private PersonalDataErasureScope() {
	}

	/**
	 * 父草稿不阻断删除：draft 不存在（孤儿，owner 另行证明）或属本人个人。 组织草稿与他人个人草稿均保护（D01 前两类）。
	 */
	static String draftParentPersonal(String column) {
		return "NOT EXISTS (SELECT 1 FROM creation_draft pd WHERE pd.id = " + column
				+ " AND (pd.organization_id IS NOT NULL OR pd.owner_account_id <> :a))";
	}

	/** 经视觉计划到草稿的父链不阻断删除（计划缺失时不阻断，孤儿由自身 owner 证明）。 */
	static String planDraftParentPersonal(String column) {
		return "NOT EXISTS (SELECT 1 FROM creation_visual_plan pp JOIN creation_draft pd ON pd.id = pp.draft_id"
				+ " WHERE pp.id = " + column + " AND (pd.organization_id IS NOT NULL OR pd.owner_account_id <> :a))";
	}

	/** 经导出到草稿的父链不阻断删除（公众号 sync 的第二父链）。 */
	static String exportDraftParentPersonal(String column) {
		return "NOT EXISTS (SELECT 1 FROM creation_export pe JOIN creation_draft pd ON pd.id = pe.draft_id"
				+ " WHERE pe.id = " + column + " AND (pd.organization_id IS NOT NULL OR pd.owner_account_id <> :a))";
	}

	/** 父分镜不阻断删除：分镜不存在（孤儿）或本人个人分镜；组织/他人分镜保护。 */
	static String storyboardParentPersonal(String column) {
		return "NOT EXISTS (SELECT 1 FROM video_storyboard ps WHERE ps.id = " + column
				+ " AND (ps.organization_id IS NOT NULL OR ps.account_id <> :a))";
	}

	/** 分镜被组织/他人草稿的工作区引用（正条件形式；{@link #storyboardNotOrgReferenced} 的补）。 */
	static String storyboardOrgReferenced(String storyboardColumn) {
		return "EXISTS (SELECT 1 FROM video_storyboard_workspace w JOIN creation_draft d ON d.id = w.draft_id"
				+ " WHERE w.storyboard_id = " + storyboardColumn
				+ " AND (d.organization_id IS NOT NULL OR d.owner_account_id <> :a))";
	}

	/** 分镜工作区把分镜挂到组织/他人草稿 → 该分镜是保留项目的关联对象，不能删（§7.1 storyboard 行）。 用于分镜自身的删除条件。 */
	static String storyboardNotOrgReferenced(String storyboardColumn) {
		return "NOT " + storyboardOrgReferenced(storyboardColumn);
	}

	/** 子表经 {@code storyboard_id} 引用的分镜处于可删范围（个人且无组织工作区引用；分镜缺失不阻断）。 */
	static String storyboardDeletableForChildren(String storyboardColumn) {
		return "NOT EXISTS (SELECT 1 FROM video_storyboard s WHERE s.id = " + storyboardColumn
				+ " AND (s.organization_id IS NOT NULL OR s.account_id <> :a OR "
				+ "EXISTS (SELECT 1 FROM video_storyboard_workspace w JOIN creation_draft d ON d.id = w.draft_id"
				+ " WHERE w.storyboard_id = s.id AND (d.organization_id IS NOT NULL OR d.owner_account_id <> :a))))";
	}

	/** 公众号 sync 行被保留（草稿或导出链指向组织/他人草稿）——其上传映射与 apply 行随之保留（§7.1 wechat 族）。 */
	static String wechatSyncRetained(String syncColumn) {
		return "EXISTS (SELECT 1 FROM creation_wechat_draft_sync s WHERE s.id = " + syncColumn + " AND ("
				+ "EXISTS (SELECT 1 FROM creation_draft d WHERE d.id = s.draft_id"
				+ " AND (d.organization_id IS NOT NULL OR d.owner_account_id <> :a))"
				+ " OR EXISTS (SELECT 1 FROM creation_export e JOIN creation_draft d2 ON d2.id = e.draft_id"
				+ " WHERE e.id = s.export_id AND (d2.organization_id IS NOT NULL OR d2.owner_account_id <> :a))))";
	}

	/**
	 * card_series_operation 的 A 个人范围（v1 无 draft/plan 由 owner 证明；v2 两条父链任一组织/他人即保留）。
	 */
	static String cardOperationPersonal(String alias) {
		return alias + ".owner_account_id = :a AND " + draftParentPersonal(alias + ".draft_id") + " AND "
				+ planDraftParentPersonal(alias + ".plan_id");
	}

	/** creation_visual_plan 的 A 个人范围（owner 本人且父草稿个人）。 */
	static String visualPlanPersonal(String alias) {
		return alias + ".owner_account_id = :a AND " + draftParentPersonal(alias + ".draft_id");
	}

	/**
	 * studio_apply 实际写入 kind 全集（#104 §7.1：从 StudioCommandStore.execute 与
	 * recordStudioApply 全部调用点枚举）。 微信绑定/校验/轮换/断开 →
	 * creation_wechat_account；微信同步建/对账/取消 →
	 * creation_wechat_draft_sync；visual-cancel →
	 * card_series_operation；plan-patch/plan-confirm/visual-adopt →
	 * creation_visual_plan。未知 kind 不猜归属。
	 */
	static final java.util.List<String> STUDIO_APPLY_KINDS = java.util.List.of("wechat-bind", "wechat-verify",
			"wechat-rotate", "wechat-disconnect", "wechat-sync-create", "wechat-sync-reconcile", "wechat-sync-cancel",
			"visual-cancel", "plan-patch", "plan-confirm", "visual-adopt");

	private static final String STUDIO_APPLY_KIND_LIST = STUDIO_APPLY_KINDS.stream().map(k -> "'" + k + "'")
			.reduce((l, r) -> l + ", " + r).orElse("''");

	/**
	 * studio_apply 的 A 个人范围：本人 apply 且被引用资源未被保留。 个人连接器类
	 * kind（绑定/校验/轮换/断开）指向个人凭据，直接可清； 同步/视觉/计划类 kind 在资源行已被保留（组织链）时保留 apply
	 * 审计；资源行已随前序步骤删除 → 孤儿 apply 由 owner 证明可清。
	 */
	static String studioApplyPersonal(String alias) {
		return alias + ".owner_account_id = :a AND (" + alias + ".kind IN ('wechat-bind', 'wechat-verify',"
				+ " 'wechat-rotate', 'wechat-disconnect')" + " OR (" + alias
				+ ".kind IN ('wechat-sync-create', 'wechat-sync-reconcile', 'wechat-sync-cancel')" + " AND NOT "
				+ wechatSyncRetained(alias + ".resource_id") + ")" + " OR (" + alias
				+ ".kind = 'visual-cancel' AND NOT " + "EXISTS (SELECT 1 FROM card_series_operation op WHERE op.id = "
				+ alias + ".resource_id AND NOT (" + cardOperationPersonal("op") + ")))" + " OR (" + alias
				+ ".kind IN ('plan-patch', 'plan-confirm', 'visual-adopt')" + " AND NOT "
				+ "EXISTS (SELECT 1 FROM creation_visual_plan p WHERE p.id = " + alias + ".resource_id AND NOT ("
				+ visualPlanPersonal("p") + "))))";
	}

	/** 未知 kind 的 apply 行：归属不明，不删且阻止 verify（D01「未知不猜」）。 */
	static String studioApplyUnknownKind(String alias) {
		return alias + ".owner_account_id = :a AND " + alias + ".kind NOT IN (" + STUDIO_APPLY_KIND_LIST + ")";
	}

	// ---------- 冲突核对（多父链个人账号不一致 → 不删 + verify 失败）----------

	/** 两条草稿父链（直接 draft 与经 plan 的 draft）均存在且个人账号不一致。 */
	static String dualDraftChainConflict(String alias, String draftColumn, String planColumn) {
		return "EXISTS (SELECT 1 FROM creation_draft d1 WHERE d1.id = " + alias + "." + draftColumn
				+ " AND d1.organization_id IS NULL)" + " AND EXISTS (SELECT 1 FROM creation_visual_plan p"
				+ " JOIN creation_draft d2 ON d2.id = p.draft_id WHERE p.id = " + alias + "." + planColumn
				+ " AND d2.organization_id IS NULL)" + " AND (SELECT d1.owner_account_id FROM creation_draft d1"
				+ " WHERE d1.id = " + alias + "." + draftColumn + ") <> (SELECT d2.owner_account_id"
				+ " FROM creation_visual_plan p JOIN creation_draft d2 ON d2.id = p.draft_id WHERE p.id = " + alias
				+ "." + planColumn + ")";
	}

	/** 草稿链与分镜链均存在且个人账号不一致（agent_plan / workspace 双链）。 */
	static String draftStoryboardChainConflict(String alias, String draftColumn, String storyboardColumn) {
		return "EXISTS (SELECT 1 FROM creation_draft d1 WHERE d1.id = " + alias + "." + draftColumn
				+ " AND d1.organization_id IS NULL)" + " AND EXISTS (SELECT 1 FROM video_storyboard s1 WHERE s1.id = "
				+ alias + "." + storyboardColumn + " AND s1.organization_id IS NULL)"
				+ " AND (SELECT d1.owner_account_id FROM creation_draft d1 WHERE d1.id = " + alias + "." + draftColumn
				+ ") <> (SELECT s1.account_id FROM video_storyboard s1 WHERE s1.id = " + alias + "." + storyboardColumn
				+ ")";
	}

	/** 草稿链与经导出的草稿链（公众号 sync）均存在且个人账号不一致。 */
	static String draftExportChainConflict(String alias) {
		return "EXISTS (SELECT 1 FROM creation_draft d1 WHERE d1.id = " + alias
				+ ".draft_id AND d1.organization_id IS NULL)" + " AND EXISTS (SELECT 1 FROM creation_export e"
				+ " JOIN creation_draft d2 ON d2.id = e.draft_id WHERE e.id = " + alias
				+ ".export_id AND d2.organization_id IS NULL)"
				+ " AND (SELECT d1.owner_account_id FROM creation_draft d1" + " WHERE d1.id = " + alias
				+ ".draft_id) <> (SELECT d2.owner_account_id FROM creation_export e"
				+ " JOIN creation_draft d2 ON d2.id = e.draft_id WHERE e.id = " + alias + ".export_id)";
	}

	/** 变体谱系（自身/父/根分镜）中存在的个人分镜账号数 > 1 → 冲突。 */
	static String variantLineageConflict(String alias) {
		return "(SELECT count(DISTINCT s.account_id) FROM video_storyboard s WHERE s.id IN (" + alias
				+ ".storyboard_id, " + alias + ".parent_storyboard_id, " + alias + ".root_storyboard_id)"
				+ " AND s.organization_id IS NULL) > 1";
	}

	/**
	 * 个人媒体被「非本人个人范围」的引用挂住（#104 §7.1 media 行）：组织/他人的视觉 artifact、分镜来源、 被保留公众号 sync
	 * 的上传映射。 用于 media_object/upload_staging 登记与 media_mark_deleting 行作用域（BR-01 同一
	 * resolver）； 正条件，调用方以 AND NOT (…) 排除。
	 */
	static String mediaOrgReferenceBlocked(String mediaColumn) {
		return "EXISTS (SELECT 1 FROM creation_visual_artifact va WHERE (va.original_media_id = " + mediaColumn
				+ " OR va.delivery_media_id = " + mediaColumn + ") AND NOT (va.owner_account_id = :a AND "
				+ draftParentPersonal("va.draft_id") + " AND " + planDraftParentPersonal("va.plan_id") + "))"
				+ " OR EXISTS (SELECT 1 FROM video_shot_media_source ms JOIN video_storyboard s"
				+ " ON s.id = ms.storyboard_id WHERE ms.media_id = " + mediaColumn
				+ " AND (s.organization_id IS NOT NULL OR s.account_id <> :a OR " + storyboardOrgReferenced("s.id")
				+ "))" + " OR EXISTS (SELECT 1 FROM creation_wechat_media_mapping m WHERE m.media_ref_id = "
				+ mediaColumn + " AND " + wechatSyncRetained("m.sync_id") + ")";
	}
}
