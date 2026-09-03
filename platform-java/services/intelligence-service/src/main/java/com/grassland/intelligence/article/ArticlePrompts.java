package com.grassland.intelligence.article;

import com.grassland.intelligence.ai.ChatMessage;
import java.util.Map;

/**
 * 文章生成 prompt（忠实移植 legacy {@code qwen-provider.ts} 的
 * ARTICLE_TITLES/OUTLINE/CONTENT_PROMPTS， 3 类 × 4 平台 = 12 段）。迁入草场 intelligence
 * 后单一真相源在此。
 *
 * <p>
 * 平台：{@code wechat}/{@code zhihu}/{@code xiaohongshu}（legacy 一致，默认 wechat）+
 * {@code douyin}（任务书 #69 卡B 升格为一等 platform 值——此前借道 xiaohongshu 契约， 仅前端
 * isDouyinMode 区分；打通后与格式规则契约/词库 overlay/风格 skill 目录三层口径统一）。 用户消息格式与 legacy
 * 对齐（titles=主题；outline=主题+标题；content=主题+标题+大纲）。
 */
final class ArticlePrompts {

	/** 文章平台（legacy 字符串小写；default wechat）。抖音为一等 platform 值（任务书 #69 卡B）。 */
	public enum Platform {
		WECHAT("wechat"), ZHIHU("zhihu"), XIAOHONGSHU("xiaohongshu"), DOUYIN("douyin");

		private final String key;

		Platform(String key) {
			this.key = key;
		}

		/** 按 legacy 字符串解析（小写、未知/缺失→wechat）。 */
		static Platform fromKey(String raw) {
			if (raw == null) {
				return WECHAT;
			}
			String k = raw.trim().toLowerCase();
			for (Platform p : values()) {
				if (p.key.equals(k)) {
					return p;
				}
			}
			return WECHAT;
		}
	}

	/**
	 * 内容模式（任务书 #62）：{@link #ARTICLE} = 独立文章（现状，默认）；{@link #ANSWER} = 挂在 已有问题下的知乎回答。
	 *
	 * <p>
	 * <b>只有 {@link Platform#ZHIHU} 分叉</b>——wechat/xiaohongshu 忽略 mode，行为逐字节不变
	 * （回归红线）。双模式靠本枚举区分，<b>不在 {@link Platform} 加枚举值</b>：platform 值 `zhihu`
	 * 是公开契约的一部分，拆值会波及任务、草稿、格式规则全链路。
	 */
	public enum Mode {
		ARTICLE, ANSWER;

		/** null → ARTICLE（缺省即文章模式，灰度天然安全）。 */
		static Mode orDefault(Mode mode) {
			return mode == null ? ARTICLE : mode;
		}
	}
	private static final Map<Platform, String> TITLES = Map.of(Platform.WECHAT, """
			你是一位专业的微信公众号爆款标题策划师。根据用户提供的主题，生成 5 个有吸引力的文章标题选项。

			要求：
			- 标题要能引起读者好奇心和点击欲望
			- 风格多样化：疑问句、数字列表、故事感、对比冲突、情感共鸣等
			- 适合微信公众号阅读场景，标题直接决定打开率
			- 每个标题附带一行 hook 说明（简短描述为什么这个标题有效）

			你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
			{
			  "titles": [
			    {"title": "标题文字", "hook": "这个标题有效的原因"}
			  ]
			}""", Platform.ZHIHU, """
			你是一位专业的知乎专栏文章标题策划师。根据用户提供的主题，生成 5 个有吸引力的文章标题选项。

			要求：
			- 标题同时是搜索关键词：包含读者会搜索的具体名词，不写纯悬念空话
			- 每个标题不超过 25 字，上限 30 字
			- 风格多样化：数字型、疑问型、如何型、指南型、故事型、反常识型等
			- 标题承诺的内容正文必须能兑现——知乎读者点进来发现没干货会直接点「反对」
			- 每个标题附带一行 hook 说明（这个标题吸引谁、为什么有效）

			你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
			{
			  "titles": [
			    {"title": "标题文字", "hook": "这个标题有效的原因"}
			  ]
			}""", Platform.XIAOHONGSHU, """
			你是一位专业的小红书爆款笔记标题策划师。根据用户提供的主题，生成 5 个有吸引力的笔记标题选项。

			要求：
			- 标题要有强烈种草感和个人体验感
			- 风格多样化：种草安利、踩雷避坑、合集盘点、对比测评等
			- 适合小红书社区，简短有力，直接击中用户需求
			- 适当使用 emoji 增加视觉吸引力，但不过度
			- 每个标题附带一行 hook 说明（简短描述为什么这个标题有效）

			你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
			{
			  "titles": [
			    {"title": "标题文字", "hook": "这个标题有效的原因"}
			  ]
			}""", Platform.DOUYIN, """
			你是一位专业的抖音图集标题策划师。根据用户提供的主题，生成 5 个有吸引力的图集标题选项。

			要求：
			- 标题即封面首屏文案，适配图集体裁
			- 强开场：前 10 字抛出冲突、悬念或利益点，让人停下滑动的手
			- 每个标题不超过 55 字
			- 不堆砌话题词——话题标签在正文里，标题专心抓人
			- 每个标题附带一行 hook 说明（一句话说明为什么这个标题抓人）

			你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
			{
			  "titles": [
			    {"title": "标题文字", "hook": "这个标题有效的原因"}
			  ]
			}""");

	private static final Map<Platform, String> OUTLINE = Map.of(Platform.WECHAT, """
			你是一位专业的微信公众号文章结构策划师。请根据用户提供的主题和选定的标题，生成一份详细的文章大纲。

			要求：
			- 使用 Markdown 格式
			- 包含 3-5 个主要章节，每章有 2-4 个要点
			- 结构清晰，层层递进
			- 每个要点简明扼要，1-2 句话
			- 开头要有引人入胜的引入，结尾要有有力的总结

			直接输出大纲内容，不要输出任何额外说明。""", Platform.ZHIHU, """
			你是一位专业的知乎专栏文章结构策划师。请根据用户提供的主题和选定的标题，生成一份详细的文章大纲。

			要求：
			- 使用 Markdown 格式，小标题分层
			- 按知乎体三段配比规划：
			  · 首段结论层（全文 10-15%）：开头 50-120 字直接给出全文核心结论或答案
			  · 主体论证层（65-75%）：3-5 个分论点，每点标注配什么证据（数据/案例/亲身经历）；每约 800 字安排一个可独立截图传播的金句
			  · 收尾层（15-20%）：避坑细节或边界说明（提升收藏）+ 一个开放性问题（引发评论）
			- 每个要点 1-2 句话，标注该节预期字数
			- 总字数规划 1000-3000 字

			直接输出大纲内容，不要输出任何额外说明。""", Platform.XIAOHONGSHU, """
			你是一位专业的小红书笔记结构策划师。请根据用户提供的主题和选定的标题，生成一份简洁的笔记大纲。

			要求：
			- 使用简洁的要点列表格式
			- 笔记偏短，不需要太复杂的结构
			- 包含 3-5 个核心要点或推荐理由
			- 每个要点一句话，口语化、有画面感
			- 开头要有吸引注意的引入（场景/痛点/惊喜），结尾要有行动号召
			- 适合在 500-1000 字内展开的内容量

			直接输出大纲内容，不要输出任何额外说明。""", Platform.DOUYIN, """
			你是一位专业的抖音图集文案策划师。请根据用户提供的主题和选定的标题，生成一份简洁的图集文案大纲。

			要求：
			- 按「开场钩子 → 卖点逐条 → 互动引导」三段式规划，每段标注建议图序（如「图1」「图2-3」）
			- 开场钩子：一句话点明冲突或利益，对应封面首图
			- 卖点逐条展开：每条卖点对应一张图，一条一句话
			- 互动引导：结尾一句评论/收藏/关注引导
			- 正文总量控制在 15-300 字，不写长段落

			直接输出大纲内容，不要输出任何额外说明。""");

	private static final Map<Platform, String> CONTENT = Map.of(Platform.WECHAT, """
			你是一位专业的微信公众号爆款文章写手。请根据用户提供的主题、标题和编辑后的大纲，撰写一篇完整的文章。

			要求：
			- 使用 Markdown 格式，章节标题使用 ##
			- 语言生动有感染力，避免干巴巴的说明文风格
			- 每个章节内容充实，结合具体案例或数据
			- 适当使用加粗标记关键信息
			- 总字数 1500-3000 字
			- 结尾要有总结和行动号召

			直接输出文章内容，不要输出任何额外说明。""", Platform.ZHIHU, """
			你是一位专业的知乎专栏作者。请根据用户提供的主题、标题和编辑后的大纲，撰写一篇完整的文章。

			要求：
			- 使用 Markdown 格式，小标题用 ##，适当加粗关键信息
			- 角色感：以第一人称「我」行文，开头用一两句真实可信的身份或经历交代（如「我做这行 8 年」），有立场不骑墙
			- 开头 50-120 字必须给出核心结论，不铺垫不预热
			- 每句话都要有信息增量：观点配具体数据、案例或亲历细节；删掉「显著提升」「大幅优化」这类没有数字支撑的概括词
			- 语气理性但不冰冷，可以有个人态度；不用「谢邀」等年代感开场白
			- 每约 800 字有一个表达凝练、适合截图传播的金句（加粗）
			- 结尾：避坑要点或边界说明 + 一个开放性问题
			- 总字数 1000-3000 字
			- 如内容涉及你与主题的利益关系，在开头或结尾用一行「利益相关：…」如实声明

			直接输出文章内容，不要输出任何额外说明。""", Platform.XIAOHONGSHU, """
			你是一位专业的小红书种草笔记写手。请根据用户提供的主题、标题和编辑后的大纲，撰写一篇完整的笔记。

			要求：
			- 使用口语化的聊天语气，像在跟闺蜜/好朋友分享
			- 适当使用 emoji 增加氛围感，但不过度（每段 1-2 个即可）
			- 多用短句和换行，段落之间留白，方便手机阅读
			- 多写具体的体验细节和使用感受，少写空泛的形容词
			- 可以用分隔线（---）划分不同部分
			- 总字数 500-1000 字，不要写太长
			- 结尾加一行总结推荐和互动引导（如"姐妹们冲！""你们觉得呢？"）
			- 最后一行输出话题标签：3-5 个以 # 开头的标签（如 #职场干货 #通勤穿搭），用空格分隔，标签须与笔记主题强相关

			直接输出笔记内容，不要输出任何额外说明。""", Platform.DOUYIN, """
			你是一位专业的抖音图集文案写手。请根据用户提供的主题、标题和编辑后的大纲，撰写一份完整的图集发布文案。

			要求：
			- 短句式、口语化，像跟朋友安利一样把卖点放在前面说
			- 每条卖点一行，配一条具体理由或细节，不写长段落、不书面化
			- 多用换行留白，方便配图分条展示
			- 结尾带一句互动引导（评论/收藏/关注，选一个自然的）
			- 最后另起一行输出话题标签：2-5 个以 # 开头的标签（如 #探店 #美食推荐）独立成行，标签须与内容强相关

			直接输出文案内容，不要输出任何额外说明。""");

	// ---------- 知乎回答体三段（任务书 #62 §4.1）----------
	//
	// 回答挂在已有问题下：问题本身即标题，读者是否读完由首屏前 100 字决定。因此 titles 载荷被
	// 复用为「开头候选」——title 字段语义 = 开头段文本（不新增响应结构，前端候选渲染沿用同一组件）。

	private static final String ZHIHU_ANSWER_TITLES = """
			你是一位专业的知乎高赞回答写手。用户将在某个已有问题下发布回答——问题本身即标题，决定读者是否读完的是首屏前 100 字。请根据用户提供的问题，生成 5 个回答开头候选。

			要求：
			- 每个开头 60-120 字，是完整可直接续写的一段话
			- 每个开头必须完成一件事：给读者一个继续读下去的理由——先亮结论、抛反常识判断、或用亲历场景切入，三选一
			- 禁止铺垫：「随着…的发展」「这个问题我关注很久了」一类全部禁止
			- 第一人称行文；开头或紧随其后应有可信度交代（资历/经历）
			- 每个候选附一行 hook 说明（它抓住了哪类读者）

			你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
			{
			  "titles": [
			    {"title": "开头段文本", "hook": "这个开头有效的原因"}
			  ]
			}""";

	private static final String ZHIHU_ANSWER_OUTLINE = """
			你是一位专业的知乎回答结构策划师。请根据用户提供的问题和选定的开头，生成一份回答大纲。

			要求：
			- 使用 Markdown 格式
			- 按知乎体三段配比规划：
			  · 首屏结论层（10-15%）：选定开头 + 必要时一句话补全答案
			  · 主体论证层（65-75%）：3-5 个分论点，先亮观点再给证据（数据/案例/亲历），每点标注证据类型；每约 800 字埋一个可截图金句
			  · 收尾层（15-20%）：避坑或边界说明 + 开放性互动
			- 每个要点 1-2 句话
			- 大纲必须回应问题本身：每个分论点都要扣题，不做体系化的跑题展开

			直接输出大纲内容，不要输出任何额外说明。""";

	private static final String ZHIHU_ANSWER_CONTENT = """
			你是一位专业的知乎高赞回答写手。请根据用户提供的问题、选定的开头和编辑后的大纲，撰写一篇完整的回答。

			要求：
			- 使用 Markdown 格式，适当加粗关键信息
			- 第一人称「我」，有资历或经历交代；涉利益关系用一行「利益相关：…」如实声明
			- 回答问题本身：读者带着问题来，首屏即答案，论证层层展开不绕
			- 每句有信息增量，观点配数据、案例或亲历细节；删概括空话
			- 理性但不冰冷，有明确立场，不骑墙
			- 每约 800 字一个可截图传播的金句（加粗）
			- 结尾：避坑要点或边界说明 + 一个开放性问题
			- 总字数 1000-3000 字

			直接输出回答内容，不要输出任何额外说明。""";

	private ArticlePrompts() {
	}

	static ChatMessage titlesSystem(Platform platform) {
		return titlesSystem(platform, Mode.ARTICLE, null);
	}

	static ChatMessage outlineSystem(Platform platform) {
		return outlineSystem(platform, Mode.ARTICLE);
	}

	static ChatMessage contentSystem(Platform platform) {
		return contentSystem(platform, Mode.ARTICLE, null, null);
	}

	/** outline system（任务书 #62：知乎 ANSWER 走回答体，其余平台忽略 mode）。 */
	static ChatMessage outlineSystem(Platform platform, Mode mode) {
		return ChatMessage.system(isZhihuAnswer(platform, mode) ? ZHIHU_ANSWER_OUTLINE : prompt(OUTLINE, platform));
	}

	/**
	 * 回答体判据（任务书 #62 全局约束 2）：<b>仅</b>「平台=知乎 且 mode=ANSWER」二者同时成立时成立。 判据之外的任何组合都按
	 * ARTICLE 现状处理，不新增模糊回退。
	 */
	private static boolean isZhihuAnswer(Platform platform, Mode mode) {
		return platform == Platform.ZHIHU && Mode.orDefault(mode) == Mode.ANSWER;
	}

	/**
	 * titles system + 标题套路注入段（任务书 #57 决策 D）：追加进同一条 system 消息文本， 不新增 system 消息（BYOK
	 * 任意端点对多条 system 兼容性不可假设）。 注入段带优先级句——小红书 base 的「风格多样化」要求与「全候选遵循同一套路」冲突时以本段为准。
	 */
	static ChatMessage titlesSystem(Platform platform,
			com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt formula) {
		return titlesSystem(platform, Mode.ARTICLE, formula);
	}

	/** titles system + 标题套路注入（任务书 #62 扩展 mode；注入段文本与 #57 语义原样不变）。 */
	static ChatMessage titlesSystem(Platform platform, Mode mode,
			com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt formula) {
		String base = isZhihuAnswer(platform, mode) ? ZHIHU_ANSWER_TITLES : prompt(TITLES, platform);
		if (formula == null) {
			return ChatMessage.system(base);
		}
		return ChatMessage.system(base + "\n\n【标题套路：" + formula.name() + "】\n" + "在满足上述输出格式要求的前提下，全部 5 个候选标题都必须遵循以下套路"
				+ "（与前文「风格多样化」的要求冲突时，以本段为准）：\n" + formula.promptContent());
	}

	/**
	 * content system + 体裁/文风注入段（任务书 #57 决策 D）：体裁在前、文风在后，各一段； 文风段带优先级句——须能覆盖小红书 base
	 * 默认的「闺蜜口吻」语气。
	 */
	static ChatMessage contentSystem(Platform platform,
			com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt genre,
			com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt style) {
		return contentSystem(platform, Mode.ARTICLE, genre, style);
	}

	/** content system + 体裁/文风注入（任务书 #62 扩展 mode；注入段文本与 #57 语义原样不变）。 */
	static ChatMessage contentSystem(Platform platform, Mode mode,
			com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt genre,
			com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt style) {
		StringBuilder sb = new StringBuilder(
				isZhihuAnswer(platform, mode) ? ZHIHU_ANSWER_CONTENT : prompt(CONTENT, platform));
		if (genre != null) {
			sb.append("\n\n【内容体裁：").append(genre.name()).append("】\n").append("正文必须遵循以下体裁结构要求：\n")
					.append(genre.promptContent());
		}
		if (style != null) {
			sb.append("\n\n【文风口吻：").append(style.name()).append("】\n").append("全文语言风格必须遵循以下口吻要求（与前文默认语气冲突时，以本段为准）：\n")
					.append(style.promptContent());
		}
		return ChatMessage.system(sb.toString());
	}

	/** 取平台 prompt；null/未知平台回退 wechat（{@code Map.of} 不允许 null 键，故先归一）。 */
	private static String prompt(Map<Platform, String> map, Platform platform) {
		Platform p = (platform == null) ? Platform.WECHAT : platform;
		return map.getOrDefault(p, map.get(Platform.WECHAT));
	}

	/** titles 用户消息：{@code 主题：{topic}}。 */
	static ChatMessage titlesUser(String topic) {
		return ChatMessage.user("主题：" + topic);
	}

	/** outline 用户消息：{@code 主题：{topic}\n标题：{title}}。 */
	static ChatMessage outlineUser(String topic, String title) {
		return ChatMessage.user("主题：" + topic + "\n标题：" + title);
	}

	/** content 用户消息：{@code 主题：{topic}\n标题：{title}\n\n大纲：\n{outline}}。 */
	static ChatMessage contentUser(String topic, String title, String outline) {
		return ChatMessage.user("主题：" + topic + "\n标题：" + title + "\n\n大纲：\n" + outline);
	}

	// ---------- 回答体用户消息（任务书 #62 §4.1）----------

	/**
	 * 回答体 titles 用户消息：{@code 问题：{question}}，topic 非空时追加
	 * {@code \n补充说明：{topic}}（回答模式的 topic 语义是「补充说明」而非主题）。
	 */
	static ChatMessage answerTitlesUser(String question, String topic) {
		String text = "问题：" + question;
		if (topic != null && !topic.isBlank()) {
			text = text + "\n补充说明：" + topic.trim();
		}
		return ChatMessage.user(text);
	}

	/** 回答体 outline 用户消息：{@code 问题：{question}\n选定开头：{opening}}。 */
	static ChatMessage answerOutlineUser(String question, String opening) {
		return ChatMessage.user("问题：" + question + "\n选定开头：" + opening);
	}

	/** 回答体 content 用户消息：{@code 问题：{question}\n开头：{opening}\n\n大纲：\n{outline}}。 */
	static ChatMessage answerContentUser(String question, String opening, String outline) {
		return ChatMessage.user("问题：" + question + "\n开头：" + opening + "\n\n大纲：\n" + outline);
	}
}
