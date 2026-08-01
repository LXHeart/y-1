package com.grassland.identity.notify;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpMailSender {
    private final JavaMailSender mailSender;
    private final String from;

    /**
     * 发件人解析：{@code SMTP_FROM} → SMTP 登录账号 → 占位地址。
     *
     * <p>两处修正（实测「邮件没发出去」的根因）：
     * <ul>
     *   <li>原来读的 {@code spring.mail.properties.mail.from} <b>这个 key 根本不存在</b>——
     *       application.yml 里配的是 {@code mail.smtp.from}，于是永远落到占位地址；</li>
     *   <li>QQ/163 等 SMTP 要求发件人 <b>必须等于认证账号</b>，发 {@code noreply@grassland.local}
     *       会被服务器直接断开连接（表现为 {@code MessagingException: Exception reading response}）。
     *       故 SMTP_FROM 未配时回落到 {@code spring.mail.username}，而不是占位地址。</li>
     * </ul>
     */
    public SmtpMailSender(@Autowired(required = false) JavaMailSender mailSender,
                          @Value("${SMTP_FROM:}") String configuredFrom,
                          @Value("${spring.mail.username:}") String smtpUsername) {
        this.mailSender = mailSender;
        this.from = firstNonBlank(configuredFrom, smtpUsername, "noreply@grassland.local");
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    public boolean isConfigured() { return mailSender != null; }

    /**
     * 通用发送（GL-P1-NOTIFY-001）：供 {@code MailOutboxPublisher} 发送已渲染好的邮件。
     *
     * <p>阻塞调用（{@link JavaMailSender#send} 是阻塞 API），须在弹性线程（publisher 的
     * {@code Schedulers.boundedElastic}）上执行。失败抛异常，调用方据此退避重试或死信。
     */
    public void send(String to, String subject, String body) {
        if (mailSender == null) throw new IllegalStateException("邮件服务未配置");
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        mailSender.send(msg);
    }

    public void sendVerificationCode(String to, String code) {
        if (mailSender == null) throw new IllegalStateException("邮件服务未配置");
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("草场 - 邮箱验证码");
        msg.setText("您的验证码是：" + code + "，5分钟内有效。");
        mailSender.send(msg);
    }

    /**
     * 组织成员邀请通知。正文**不含**任何一次性凭据——接受邀请要求用该邮箱对应的账号登录后自行操作，
     * 故邮件泄露不会导致邀请被冒领（详见 {@code MyInvitationController}）。
     */
    public void sendOrganizationInvitation(String to, String organizationName, String role) {
        if (mailSender == null) throw new IllegalStateException("邮件服务未配置");
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("草场 - 邀请加入「" + organizationName + "」");
        msg.setText("您被邀请以「" + roleLabel(role) + "」身份加入草场组织「" + organizationName + "」。\n"
                + "请用本邮箱对应的草场账号登录，在「草场工作台 → 我的邀请」中接受或谢绝。");
        mailSender.send(msg);
    }

    private static String roleLabel(String role) {
        return "admin".equalsIgnoreCase(role) ? "管理员" : "成员";
    }
}
