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

    public SmtpMailSender(@Autowired(required = false) JavaMailSender mailSender,
                          @Value("${spring.mail.properties.mail.from:noreply@grassland.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public boolean isConfigured() { return mailSender != null; }

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
