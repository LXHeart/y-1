package com.grassland.intelligence.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.article.ArticlePrompts.Mode;
import com.grassland.intelligence.article.ArticlePrompts.Platform;
import com.grassland.intelligence.creationstyle.CreationStyleSkill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ArticlePrompts} 平台解析 + 各类 prompt/用户消息组装（忠实移植 legacy） + 知乎回答/文章双模式（任务书
 * #62）。
 */
class ArticlePromptsTest {

	@Test
	@DisplayName("Platform.fromKey：大小写不敏感；未知/缺失 → wechat")
	void platformFromKey() {
		assertThat(Platform.fromKey("wechat")).isEqualTo(Platform.WECHAT);
		assertThat(Platform.fromKey("ZHIHU")).isEqualTo(Platform.ZHIHU);
		assertThat(Platform.fromKey("xiaohongshu")).isEqualTo(Platform.XIAOHONGSHU);
		assertThat(Platform.fromKey(null)).isEqualTo(Platform.WECHAT);
		assertThat(Platform.fromKey("")).isEqualTo(Platform.WECHAT);
		assertThat(Platform.fromKey("twitter")).isEqualTo(Platform.WECHAT);
	}

	@Test
	@DisplayName("各类 system prompt 按平台取，未知平台回退 wechat")
	void systemPromptsByPlatform() {
		assertThat(ArticlePrompts.titlesSystem(Platform.WECHAT).content()).contains("标题策划师");
		assertThat(ArticlePrompts.outlineSystem(Platform.ZHIHU).content()).contains("知乎");
		assertThat(ArticlePrompts.contentSystem(Platform.XIAOHONGSHU).content()).contains("小红书");
		assertThat(ArticlePrompts.titlesSystem(null).content()).contains("标题策划师");
	}

	@Test
	@DisplayName("用户消息按 legacy 格式组装")
	void userMessages() {
		ChatMessage titles = ArticlePrompts.titlesUser("职场");
		assertThat(titles.content()).isEqualTo("主题：职场");

		ChatMessage outline = ArticlePrompts.outlineUser("职场", "打工人的清晨");
		assertThat(outline.content()).isEqualTo("主题：职场\n标题：打工人的清晨");

		ChatMessage content = ArticlePrompts.contentUser("职场", "打工人的清晨", "一、开头\n二、展开");
		assertThat(content.content()).contains("主题：职场").contains("标题：打工人的清晨").contains("大纲：").contains("一、开头");
	}

	@Test
	@DisplayName("小红书 content prompt：结尾最后一行要求输出话题标签行（任务书 #60）")
	void xiaohongshuContentPromptRequiresHashtagLine() {
		String system = ArticlePrompts.contentSystem(Platform.XIAOHONGSHU).content();
		assertThat(system).contains("最后一行输出话题标签");
		assertThat(system).contains("#职场干货");
		assertThat(system).contains("3-5 个");
	}

	// ---------- 任务书 #62：知乎双模式 ----------

	/** 六段全文常量（与 ArticlePrompts 逐字节对齐；prompt 是产品资产，全文断言防误改）。 */
	private static final String ZHIHU_ARTICLE_TITLES = """
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
			}""";

	private static final String ZHIHU_ARTICLE_OUTLINE = """
			你是一位专业的知乎专栏文章结构策划师。请根据用户提供的主题和选定的标题，生成一份详细的文章大纲。

			要求：
			- 使用 Markdown 格式，小标题分层
			- 按知乎体三段配比规划：
			  · 首段结论层（全文 10-15%）：开头 50-120 字直接给出全文核心结论或答案
			  · 主体论证层（65-75%）：3-5 个分论点，每点标注配什么证据（数据/案例/亲身经历）；每约 800 字安排一个可独立截图传播的金句
			  · 收尾层（15-20%）：避坑细节或边界说明（提升收藏）+ 一个开放性问题（引发评论）
			- 每个要点 1-2 句话，标注该节预期字数
			- 总字数规划 1000-3000 字

			直接输出大纲内容，不要输出任何额外说明。""";

	private static final String ZHIHU_ARTICLE_CONTENT = """
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

			直接输出文章内容，不要输出任何额外说明。""";

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

	@Test
	@DisplayName("知乎文章体三段全文（mode=ARTICLE）")
	void zhihuArticleModePromptsMatchVerbatim() {
		assertThat(ArticlePrompts.titlesSystem(Platform.ZHIHU, Mode.ARTICLE, null).content())
				.isEqualTo(ZHIHU_ARTICLE_TITLES);
		assertThat(ArticlePrompts.outlineSystem(Platform.ZHIHU, Mode.ARTICLE).content())
				.isEqualTo(ZHIHU_ARTICLE_OUTLINE);
		assertThat(ArticlePrompts.contentSystem(Platform.ZHIHU, Mode.ARTICLE, null, null).content())
				.isEqualTo(ZHIHU_ARTICLE_CONTENT);
		// JSON 输出格式段仍在（titles 解析依赖它）
		assertThat(ZHIHU_ARTICLE_TITLES).contains("\"titles\": [").contains("\"hook\"");
	}

	@Test
	@DisplayName("知乎回答体三段全文（mode=ANSWER）")
	void zhihuAnswerModePromptsMatchVerbatim() {
		assertThat(ArticlePrompts.titlesSystem(Platform.ZHIHU, Mode.ANSWER, null).content())
				.isEqualTo(ZHIHU_ANSWER_TITLES);
		assertThat(ArticlePrompts.outlineSystem(Platform.ZHIHU, Mode.ANSWER).content()).isEqualTo(ZHIHU_ANSWER_OUTLINE);
		assertThat(ArticlePrompts.contentSystem(Platform.ZHIHU, Mode.ANSWER, null, null).content())
				.isEqualTo(ZHIHU_ANSWER_CONTENT);
		// 开头候选复用 titles 载荷：JSON 格式段必须保留（否则前端候选解析崩）
		assertThat(ZHIHU_ANSWER_TITLES).contains("\"titles\": [").contains("\"title\": \"开头段文本\"");
	}

	@Test
	@DisplayName("mode 缺省（null）与 ARTICLE 同解，且与旧无 mode 重载逐字节一致")
	void nullModeFallsBackToArticle() {
		assertThat(ArticlePrompts.titlesSystem(Platform.ZHIHU, null, null).content()).isEqualTo(ZHIHU_ARTICLE_TITLES)
				.isEqualTo(ArticlePrompts.titlesSystem(Platform.ZHIHU).content());
		assertThat(ArticlePrompts.outlineSystem(Platform.ZHIHU, null).content()).isEqualTo(ZHIHU_ARTICLE_OUTLINE)
				.isEqualTo(ArticlePrompts.outlineSystem(Platform.ZHIHU).content());
		assertThat(ArticlePrompts.contentSystem(Platform.ZHIHU, null, null, null).content())
				.isEqualTo(ZHIHU_ARTICLE_CONTENT).isEqualTo(ArticlePrompts.contentSystem(Platform.ZHIHU).content());
	}

	@Test
	@DisplayName("回归红线：wechat/xiaohongshu 传 ANSWER 忽略 mode，与 ARTICLE 逐字节相同")
	void otherPlatformsIgnoreAnswerMode() {
		for (Platform platform : new Platform[]{Platform.WECHAT, Platform.XIAOHONGSHU}) {
			assertThat(ArticlePrompts.titlesSystem(platform, Mode.ANSWER, null).content())
					.isEqualTo(ArticlePrompts.titlesSystem(platform, Mode.ARTICLE, null).content());
			assertThat(ArticlePrompts.outlineSystem(platform, Mode.ANSWER).content())
					.isEqualTo(ArticlePrompts.outlineSystem(platform, Mode.ARTICLE).content());
			assertThat(ArticlePrompts.contentSystem(platform, Mode.ANSWER, null, null).content())
					.isEqualTo(ArticlePrompts.contentSystem(platform, Mode.ARTICLE, null, null).content());
		}
	}

	// ---------- 任务书 #69 卡B：抖音一等平台 ----------

	@Test
	@DisplayName("douyin 一等平台：fromKey 解析 + 三段模板专属内容（不再借道小红书）")
	void douyinFirstClassPlatform() {
		assertThat(Platform.fromKey("douyin")).isEqualTo(Platform.DOUYIN);
		assertThat(ArticlePrompts.titlesSystem(Platform.DOUYIN).content()).contains("抖音图集标题策划师").contains("55 字")
				.contains("前 10 字").doesNotContain("小红书");
		assertThat(ArticlePrompts.outlineSystem(Platform.DOUYIN).content()).contains("抖音图集文案策划师").contains("开场钩子")
				.contains("15-300 字");
		assertThat(ArticlePrompts.contentSystem(Platform.DOUYIN).content()).contains("抖音图集文案写手").contains("2-5 个")
				.contains("独立成行");
		// 抖音与知乎双模式正交：douyin 传 ANSWER 仍走图集体（mode 仅知乎分叉）
		assertThat(ArticlePrompts.titlesSystem(Platform.DOUYIN, Mode.ANSWER, null).content())
				.isEqualTo(ArticlePrompts.titlesSystem(Platform.DOUYIN, Mode.ARTICLE, null).content());
	}

	@Test
	@DisplayName("回答体 + 风格注入：注入段照 #57 语义追加在回答体 base 之后")
	void answerModeStillAcceptsStyleInjection() {
		var formula = new CreationStyleSkill.SkillPrompt("疑问型", "标题是一个具体问题");
		String titles = ArticlePrompts.titlesSystem(Platform.ZHIHU, Mode.ANSWER, formula).content();
		assertThat(titles).startsWith(ZHIHU_ANSWER_TITLES).contains("【标题套路：疑问型】").contains("标题是一个具体问题");

		var genre = new CreationStyleSkill.SkillPrompt("观点评论文", "议论文结构");
		var style = new CreationStyleSkill.SkillPrompt("理性分析流", "结论先行");
		String content = ArticlePrompts.contentSystem(Platform.ZHIHU, Mode.ANSWER, genre, style).content();
		assertThat(content).startsWith(ZHIHU_ANSWER_CONTENT);
		assertThat(content.indexOf("【内容体裁：观点评论文】")).isLessThan(content.indexOf("【文风口吻：理性分析流】"));
	}

	@Test
	@DisplayName("回答体用户消息拼接（§4.1）：问题/补充说明/选定开头/大纲")
	void answerModeUserMessages() {
		assertThat(ArticlePrompts.answerTitlesUser("大厂为什么裁员", null).content()).isEqualTo("问题：大厂为什么裁员");
		assertThat(ArticlePrompts.answerTitlesUser("大厂为什么裁员", "  ").content()).isEqualTo("问题：大厂为什么裁员");
		assertThat(ArticlePrompts.answerTitlesUser("大厂为什么裁员", "关注 35 岁问题").content())
				.isEqualTo("问题：大厂为什么裁员\n补充说明：关注 35 岁问题");

		assertThat(ArticlePrompts.answerOutlineUser("大厂为什么裁员", "我在大厂做了 8 年 HR").content())
				.isEqualTo("问题：大厂为什么裁员\n选定开头：我在大厂做了 8 年 HR");

		assertThat(ArticlePrompts.answerContentUser("大厂为什么裁员", "我在大厂做了 8 年 HR", "一、结论\n二、论证").content())
				.isEqualTo("问题：大厂为什么裁员\n开头：我在大厂做了 8 年 HR\n\n大纲：\n一、结论\n二、论证");
	}
}
