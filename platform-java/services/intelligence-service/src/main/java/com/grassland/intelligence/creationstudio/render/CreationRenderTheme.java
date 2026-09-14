package com.grassland.intelligence.creationstudio.render;

/**
 * 任务书 #101 C101-16：排版主题枚举（§6.7）。 只有 standard／compact 两个固定主题；主题只改样式（字号／行距／间距），
 * 不改内容与文字。样式内联于渲染片段，不引外链字体或第三方 CDN。
 */
public enum CreationRenderTheme {

	STANDARD(
			"""
					.creation-render{font-family:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;font-size:16px;line-height:1.75;color:#0d253d;background-color:#ffffff;max-width:720px;margin:0 auto;padding:8px 0;}
					.creation-render h1{font-size:24px;line-height:1.4;margin:24px 0 12px;}
					.creation-render h2{font-size:20px;line-height:1.4;margin:20px 0 10px;}
					.creation-render h3{font-size:16px;margin:16px 0 8px;}
					.creation-render p{margin:12px 0;}
					.creation-render figure{margin:16px 0;text-align:center;}
					.creation-render figure img{max-width:100%;height:auto;border-radius:8px;}
					.creation-render figcaption{font-size:13px;color:#5f6f84;margin-top:6px;}
					.creation-render pre{background:#eef2f8;border-radius:8px;padding:12px;overflow-x:auto;font-size:13px;line-height:1.6;}
					.creation-render code{font-family:Inter,-apple-system,BlinkMacSystemFont,sans-serif;}
					.creation-render table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px;}
					.creation-render th,.creation-render td{border:1px solid #dde4ee;padding:8px 10px;text-align:left;}
					.creation-render blockquote{margin:12px 0;padding:8px 14px;border-left:3px solid #748399;color:#273951;}
					.creation-render .render-refs{font-size:13px;color:#5f6f84;border-top:1px solid #dde4ee;margin-top:20px;padding-top:8px;}
					.creation-render .render-unbound{font-size:13px;color:#8b5709;background:#fff4de;border-radius:6px;padding:6px 10px;}"""),

	COMPACT("""
			.creation-render{font-family:Inter,-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC',sans-serif;font-size:14px;line-height:1.55;color:#0d253d;background-color:#ffffff;max-width:680px;margin:0 auto;padding:4px 0;}
			.creation-render h1{font-size:20px;line-height:1.35;margin:16px 0 8px;}
			.creation-render h2{font-size:16px;line-height:1.35;margin:14px 0 6px;}
			.creation-render h3{font-size:16px;margin:10px 0 5px;}
			.creation-render p{margin:8px 0;}
			.creation-render figure{margin:10px 0;text-align:center;}
			.creation-render figure img{max-width:100%;height:auto;border-radius:6px;}
			.creation-render figcaption{font-size:13px;color:#5f6f84;margin-top:4px;}
			.creation-render pre{background:#eef2f8;border-radius:6px;padding:10px;overflow-x:auto;font-size:13px;line-height:1.5;}
			.creation-render code{font-family:Inter,-apple-system,BlinkMacSystemFont,sans-serif;}
			.creation-render table{border-collapse:collapse;width:100%;margin:8px 0;font-size:13px;}
			.creation-render th,.creation-render td{border:1px solid #dde4ee;padding:6px 8px;text-align:left;}
			.creation-render blockquote{margin:8px 0;padding:6px 12px;border-left:3px solid #748399;color:#273951;}
			.creation-render .render-refs{font-size:13px;color:#5f6f84;border-top:1px solid #dde4ee;margin-top:14px;padding-top:6px;}
			.creation-render .render-unbound{font-size:13px;color:#8b5709;background:#fff4de;border-radius:6px;padding:5px 8px;}""");

	private final String css;

	CreationRenderTheme(String css) {
		this.css = css;
	}

	public String css() {
		return css;
	}

	/**
	 * Apply only this fixed theme's declarations; no style tag or user CSS reaches
	 * the document.
	 */
	public void applyTo(org.jsoup.nodes.Element root) {
		for (String rule : css.split("}")) {
			int split = rule.indexOf('{');
			if (split < 0)
				continue;
			String selector = rule.substring(0, split).strip().replace("figure img", ".render-media img")
					.replace("figure", ".render-media").replace("figcaption", ".render-media p");
			String declarations = rule.substring(split + 1).strip();
			if (selector.equals(".creation-render"))
				root.attr("style", declarations);
			else
				root.select(selector).forEach(element -> element.attr("style", element.attr("style") + declarations));
		}
	}

	public static CreationRenderTheme of(String value) {
		return "compact".equals(value) ? COMPACT : STANDARD;
	}
}
