package com.grassland.intelligence.articleimage;

import java.util.List;

/** Legacy 文章配图提示词。 */
final class ArticleImagePrompts {

	private ArticleImagePrompts() {
	}

	static String recommendation(String content, String outline, String platform) {
		String platformLabel = switch (platform) {
			case "zhihu" -> "知乎";
			case "xiaohongshu" -> "小红书";
			case "douyin" -> "抖音";
			default -> "微信公众号";
		};
		String outlineSection = outline == null ? "" : "\n文章大纲：\n" + outline;
		return """
				你是一位专业的中文内容编辑和视觉策划师。请根据文章内容，推荐 3-4 张配图（含 1 张封面图 + 2-3 张正文插图），用于丰富文章的视觉表现力。

				要求：
				- 结合平台：%s
				- 第 1 张是封面图，position 填「封面」
				- 其余为正文插图，position 填写插入位置，例如「正文第一段后」「正文中间」「正文后半部分」
				- description 写图片要表达的视觉内容
				- searchKeywords 写中文搜图关键词，便于从网上搜图
				- prompt 写可直接用于 AI 生图的中文提示词
				- 只能返回 JSON，不要返回额外说明

				返回格式：
				{
				  "recommendedCount": 3,
				  "placements": [
				    {
				      "position": "封面",
				      "description": "用于作为文章头图的概念图",
				      "searchKeywords": "职场沟通 商务 插画",
				      "prompt": "现代商务风格插画，展示高效沟通场景，蓝白色调"
				    },
				    {
				      "position": "正文第一段后",
				      "description": "配图内容描述",
				      "searchKeywords": "相关搜图关键词",
				      "prompt": "AI 生图提示词"
				    }
				  ]
				}

				文章正文：
				%s%s""".formatted(platformLabel, content, outlineSection);
	}

	static String referenceDescription() {
		return "请详细描述这张图片的视觉内容，包括：主体、构图、色调、风格、氛围。简洁精准地描述，用于辅助AI生图。不要输出多余说明。";
	}

	static String enhance(String prompt, List<String> descriptions) {
		StringBuilder references = new StringBuilder();
		for (int index = 0; index < descriptions.size(); index++) {
			if (index > 0) {
				references.append('\n');
			}
			references.append("素材").append(index + 1).append(": ").append(descriptions.get(index));
		}
		return "[参考素材]\n" + references + "\n\n[创作要求]\n" + prompt;
	}
}
