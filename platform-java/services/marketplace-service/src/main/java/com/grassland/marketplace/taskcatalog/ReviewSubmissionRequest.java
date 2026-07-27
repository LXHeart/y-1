package com.grassland.marketplace.taskcatalog;

/**
 * 退回交付物的请求体。{@code note} 是退回原因，可选但强烈建议填——
 * 不写原因的退回对推荐官等于「重做，但不告诉你哪里不对」。
 */
public record ReviewSubmissionRequest(String note) {
    public ReviewSubmissionRequest {
        if (note != null) {
            note = note.isBlank() ? null : note.trim();
        }
    }
}
