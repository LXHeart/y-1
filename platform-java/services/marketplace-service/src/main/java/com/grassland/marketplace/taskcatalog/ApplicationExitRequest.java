package com.grassland.marketplace.taskcatalog;

/**
 * 推荐官退出报名请求体（任务书 #96 §6 POST /exit）。{@code kind}：no_fault（缺省，无责自助退出）/
 * negotiated（协商退出，随 C96-02 里程碑结算开放）。{@code reason} 退出说明，可空。
 * 全字段包装类型（Jackson 反序列化缺字段不炸，house rule）。
 */
public record ApplicationExitRequest(String kind, String reason) {
    public static final String KIND_NO_FAULT = "no_fault";
    public static final String KIND_NEGOTIATED = "negotiated";

    /** 缺省 no_fault；显式传未知值在服务层 400。 */
    public String kindOrDefault() {
        return kind == null || kind.isBlank() ? KIND_NO_FAULT : kind.trim().toLowerCase();
    }
}
