package com.grassland.identity.notify.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.notify.SmtpMailSender;
import com.grassland.identity.notify.mail.MailOutboxRepository.MailMessage;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * {@link MailOutboxPublisher} GreenMail 端到端 IT（GL-P1-NOTIFY-001，验证点 2）。
 *
 * <p>真 GreenMail 内存 SMTP + 真 R2DBC mail_outbox：证明 publisher 确实经 SMTP 发出邮件并 markSent，
 * 以及失败时按 {@code maxAttempts} 封顶死信。填 identity 邮件此前零测试的盲区。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MailOutboxPublisherGreenMailIT extends IdentityItSupport {

    private GreenMail greenMail;
    private MailOutboxRepository repo;
    private MailOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        db.sql("DELETE FROM mail_outbox").then().block();
        greenMail = new GreenMail(ServerSetupTest.SMTP);
        greenMail.start();

        repo = new MailOutboxRepository(db);
        SmtpMailSender smtp = new SmtpMailSender(javaMailSenderForGreenMail(), "from@test", "from@test");
        MailOutboxProperties props = new MailOutboxProperties(
                true, 100, 10, 2, 60_000, 5, 1_000, 30_000, 5_000);
        publisher = new MailOutboxPublisher(repo, smtp, new SimpleMeterRegistry(), props);
    }

    @AfterEach
    void tearDown() {
        if (greenMail != null) greenMail.stop();
    }

    @Test
    void sendsPendingMailViaSmtpAndMarksSent() throws Exception {
        repo.append(new MailMessage("evt-send", "to@test", "测试邀请", "你被邀请加入", "invitation")).block();

        publisher.publishPending();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(mailStatus("evt-send")).isEqualTo("sent");
            assertThat(greenMail.getReceivedMessages()).hasSize(1);
        });
        MimeMessage sent = greenMail.getReceivedMessages()[0];
        assertThat(sent.getSubject()).isEqualTo("测试邀请");
        assertThat(sent.getRecipients(RecipientType.TO)[0].toString()).contains("to@test");
    }

    @Test
    void deadLettersWhenSendAlwaysFails() {
        // maxAttempts=1：第一次失败即死信（避免退避等待，死信逻辑本身在 maxAttempts=5 下同构）
        SmtpMailSender failing = new SmtpMailSender(javaMailSenderForClosedPort(), "from@test", "from@test");
        MailOutboxProperties props = new MailOutboxProperties(
                true, 100, 10, 2, 60_000, 1, 1_000, 30_000, 2_000);
        MailOutboxPublisher failingPublisher = new MailOutboxPublisher(repo, failing, new SimpleMeterRegistry(), props);

        repo.append(new MailMessage("evt-dead", "to@test", "s", "b", "engagement")).block();

        failingPublisher.publishPending();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(mailStatus("evt-dead")).isEqualTo("dead"));
        assertThat(repo.deadCount().block()).isEqualTo(1);
    }

    // ---- helpers ----

    private JavaMailSenderImpl javaMailSenderForGreenMail() {
        JavaMailSenderImpl jms = new JavaMailSenderImpl();
        jms.setHost("127.0.0.1");
        jms.setPort(greenMail.getSmtp().getPort());
        jms.setProtocol("smtp");
        return jms;
    }

    /** 指向一个无人监听的端口 → send 抛异常，用于死信路径。 */
    private JavaMailSenderImpl javaMailSenderForClosedPort() {
        JavaMailSenderImpl jms = new JavaMailSenderImpl();
        jms.setHost("127.0.0.1");
        jms.setPort(1); // 无人监听 → connect 失败
        jms.setProtocol("smtp");
        return jms;
    }

    private String mailStatus(String eventId) {
        return db.sql("SELECT status FROM mail_outbox WHERE source_event_id = :evt")
                .bind("evt", eventId)
                .map(row -> row.get("status", String.class))
                .one().block();
    }
}
