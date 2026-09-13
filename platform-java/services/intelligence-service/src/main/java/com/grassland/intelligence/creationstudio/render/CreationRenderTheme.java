package com.grassland.intelligence.creationstudio.render;

/**
 * 任务书 #101 C101-16：排版主题枚举（§6.7）。 只有 standard／compact 两个固定主题；主题只改样式（字号／行距／间距），
 * 不改内容与文字。样式内联于渲染片段，不引外链字体或第三方 CDN。
 */
public enum CreationRenderTheme {

	STANDARD(
			"""
					.creation-render{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;font-size:16px;line-height:1.75;color:#1f2329;max-width:720px;margin:0 auto;padding:8px 0;}
					.creation-render h1{font-size:24px;line-height:1.4;margin:24px 0 12px;}
					.creation-render h2{font-size:20px;line-height:1.4;margin:20px 0 10px;}
					.creation-render h3{font-size:17px;margin:16px 0 8px;}
					.creation-render p{margin:12px 0;}
					.creation-render figure{margin:16px 0;text-align:center;}
					.creation-render figure img{max-width:100%;height:auto;border-radius:8px;}
					.creation-render figcaption{font-size:13px;color:#6b7280;margin-top:6px;}
					.creation-render pre{background:#f6f7f9;border-radius:8px;padding:12px;overflow-x:auto;font-size:13px;line-height:1.6;}
					.creation-render code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;}
					.creation-render table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px;}
					.creation-render th,.creation-render td{border:1px solid #e5e7eb;padding:8px 10px;text-align:left;}
					.creation-render blockquote{margin:12px 0;padding:8px 14px;border-left:3px solid #d1d5db;color:#4b5563;}
					.creation-render .render-refs{font-size:13px;color:#6b7280;border-top:1px solid #e5e7eb;margin-top:20px;padding-top:8px;}
					.creation-render .render-unbound{font-size:13px;color:#92400e;background:#fef3c7;border-radius:6px;padding:6px 10px;}"""),

	COMPACT("""
			.creation-render{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Microsoft YaHei',sans-serif;font-size:14px;line-height:1.55;color:#1f2329;max-width:680px;margin:0 auto;padding:4px 0;}
			.creation-render h1{font-size:19px;line-height:1.35;margin:16px 0 8px;}
			.creation-render h2{font-size:17px;line-height:1.35;margin:14px 0 6px;}
			.creation-render h3{font-size:15px;margin:10px 0 5px;}
			.creation-render p{margin:8px 0;}
			.creation-render figure{margin:10px 0;text-align:center;}
			.creation-render figure img{max-width:100%;height:auto;border-radius:6px;}
			.creation-render figcaption{font-size:12px;color:#6b7280;margin-top:4px;}
			.creation-render pre{background:#f6f7f9;border-radius:6px;padding:10px;overflow-x:auto;font-size:12px;line-height:1.5;}
			.creation-render code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;}
			.creation-render table{border-collapse:collapse;width:100%;margin:8px 0;font-size:13px;}
			.creation-render th,.creation-render td{border:1px solid #e5e7eb;padding:6px 8px;text-align:left;}
			.creation-render blockquote{margin:8px 0;padding:6px 12px;border-left:3px solid #d1d5db;color:#4b5563;}
			.creation-render .render-refs{font-size:12px;color:#6b7280;border-top:1px solid #e5e7eb;margin-top:14px;padding-top:6px;}
			.creation-render .render-unbound{font-size:12px;color:#92400e;background:#fef3c7;border-radius:6px;padding:5px 8px;}""");

	private final String css;

	CreationRenderTheme(String css) {
		this.css = css;
	}

	public String css() {
		return css;
	}

	public static CreationRenderTheme of(String value) {
		return "compact".equals(value) ? COMPACT : STANDARD;
	}
}
