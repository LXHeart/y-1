package com.grassland.marketplace.taskcatalog;

/**
 * 提交履约交付物的请求体。{@code contentUrl} 必填且须是 http(s) 链接——它是核实的主证据，
 * 收一个空串或「已发布」之类的自由文本等于没有凭证。{@code note} 可选。
 */
public record CreateSubmissionRequest(String contentUrl, String note) {

    public CreateSubmissionRequest {
        if (contentUrl == null || contentUrl.isBlank()) {
            throw new IllegalArgumentException("contentUrl is required");
        }
        contentUrl = contentUrl.trim();
        String lower = contentUrl.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("contentUrl must be an http(s) link");
        }
        if (note != null) {
            note = note.isBlank() ? null : note.trim();
        }
    }
}
