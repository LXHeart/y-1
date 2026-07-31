package com.grassland.identity.notification;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通知文案与分类（纯函数，无副作用）。草场 Slice 12 Stage 2。
 *
 * <p>把「事件类型 → 面向人的标题/正文/跳转」集中在一处，便于审阅与本地化。
 * <b>不把他人邮箱写进别人的通知</b>——通知 body 只描述「发生了什么事」，具体对象（哪个组织、哪个任务）
 * 放 {@code payload} 供前端渲染，避免成为信息泄露通道。
 *
 * <p>{@link #template} 返回 {@code null} 表示该事件不产生通知（非关注类型）。
 */
public final class NotificationTemplates {

    static final String LINK_INVITATIONS = "/me/invitations";
    static final String LINK_PERMISSION = "/me/organizations";

    private NotificationTemplates() {}

    /**
     * @return 该事件的静态文案模板；{@code null} = 不产生通知（消费者据此判 IGNORED，不写 inbox）
     */
    static Template template(String eventType, JsonNode payload) {
        return switch (eventType) {
            case "MembershipInvited" -> new Template(
                    NotificationCategory.INVITATION, "你收到了一份组织邀请",
                    "有一个组织邀请你加入，点击查看", LINK_INVITATIONS, orgPayload(payload));
            case "MembershipInvitationAccepted", "MembershipInvitationDeclined" ->
                    new Template(
                            NotificationCategory.INVITATION, "你的邀请已有回应",
                            "你发出的组织邀请已被接受或谢绝，点击查看", LINK_INVITATIONS, orgPayload(payload));
            case "MembershipInvitationRevoked" -> new Template(
                    NotificationCategory.INVITATION, "你的邀请已被撤销",
                    "发给你的组织邀请已被撤销", null, orgPayload(payload));
            case "MembershipGranted" -> new Template(
                    NotificationCategory.INVITATION, "你已加入组织",
                    "你已被加入一个组织", LINK_INVITATIONS, grantedPayload(payload));
            case "PermissionRequested" -> new Template(
                    NotificationCategory.PERMISSION, "收到商家权限升级申请",
                    "有成员申请升级商家权限，待你审核", LINK_PERMISSION, orgPayload(payload));
            case "PermissionReviewed" -> new Template(
                    NotificationCategory.PERMISSION, "你的权限申请已审核",
                    "你的商家权限升级申请已有审核结果", LINK_PERMISSION, orgPayload(payload));
            default -> null;
        };
    }

    private static Map<String, Object> orgPayload(JsonNode payload) {
        return singleKeyPayload(payload, "organizationId");
    }

    private static Map<String, Object> grantedPayload(JsonNode payload) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfText(map, payload, "organizationId");
        putIfText(map, payload, "role");
        return map;
    }

    /** 只取一个文本字段进 payload（组织视图渲染用），缺字段则空 map。 */
    private static Map<String, Object> singleKeyPayload(JsonNode payload, String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfText(map, payload, key);
        return map;
    }

    private static void putIfText(Map<String, Object> map, JsonNode payload, String key) {
        JsonNode node = payload.get(key);
        if (node != null && node.isTextual() && !node.asText().isBlank()) {
            map.put(key, node.asText());
        }
    }

    /** 静态文案模板（不含收件人——收件人由 resolver 异步解析后与模板合成 {@link NotificationSpec}）。 */
    record Template(
            NotificationCategory category,
            String title,
            String body,
            String linkPath,
            Map<String, Object> payload) {}
}
