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
}
