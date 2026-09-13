package com.grassland.intelligence.articleimage;

/** 对前端返回的生成图片形状。 */
/**
 * 任务书 #101 C101-08：增加可选 mediaId（确定性原图的持久媒体 ID，恢复链路用）； 旧构造与 wire 字段不变——mediaId 为
 * null 时序列化缺省，旧客户端不受影响。
 */
public record GeneratedImageResponse(String imageUrl, String revisedPrompt, java.util.UUID mediaId) {
	public GeneratedImageResponse(String imageUrl, String revisedPrompt) {
		this(imageUrl, revisedPrompt, null);
	}
}
