package com.grassland.identity.notification;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 事务邮件文案（GL-P1-NOTIFY-001）。{@link NotificationTemplates} 的姐妹——**委托其拿文案**，
 * 只做「该事件是否走邮件」的类别过滤，保证站内通知与邮件文案零漂移。
 *
 * <p><b>高价值子集</b>（已定决策）：邀请/履约/争议/资金 发邮件；{@link NotificationCategory#PERMISSION 权限审批}
 * 与系统类不发（权限审批频次高、价值低，避免邮箱噪音）。{@link #mailTemplate} 返回 {@code null} = 不入队邮件。
 *
 * <p>验证码不经过本通道（用户在等，保持 {@code SmtpMailSender} 同步直发）。邮件正文用站内通知的 title/body
 * （面向人的描述性文字），不含可点链接——邮件纯文本，跳转需域名配置，留给站内通知与前端。
 */
public final class MailTemplates {

    private MailTemplates() {}

    /**
     * @return 该事件的邮件文案；{@code null} = 不入队邮件（非关注类型，或 PERMISSION 类）
     */
    public static MailTemplate mailTemplate(String eventType, JsonNode payload) {
        NotificationTemplates.Template template = NotificationTemplates.template(eventType, payload);
        if (template == null) {
            return null; // 事件本身不产生通知 → 也不发邮件
        }
        if (template.category() == NotificationCategory.PERMISSION) {
            return null; // 权限审批：高价值子集排除
        }
        return new MailTemplate(
                template.category().name().toLowerCase(),
                template.title(),
                template.body());
    }

    /** 渲染好的邮件（category 便于按类别开关/偏好；不含收件人——由 enqueuer 解析后合成）。 */
    public record MailTemplate(String category, String subject, String body) {}
}
