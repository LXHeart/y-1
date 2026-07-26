package com.grassland.identity.invitation;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 端到端验证「按邮箱邀请组织成员」（org 侧 {@link OrganizationInvitationController} + 被邀请人侧 {@link MyInvitationController}）。
 *
 * <p>覆盖：完整邀请→接受→成为成员；对未注册邮箱的响应与已注册**完全一致**（不泄露账号是否存在）；
 * 重复邀请 409；非 owner 403；他人不可冒领（404）；过期 410；撤销/谢绝；已是成员时幂等。
 */
class InvitationControllerIT extends IdentityItSupport {

    @Test
    void inviteeAcceptsAndBecomesMember() {
        var owner = seedAccount("inv-owner@example.com");
        String orgId = createOrg(owner.cookie(), "邀请主体");
        var invitee = seedAccount("inv-invitee@example.com");

        invite(orgId, owner.cookie(), "inv-invitee@example.com", "member")
                .expectStatus().isCreated().expectBody()
                .jsonPath("$.data.email").isEqualTo("inv-invitee@example.com")
                .jsonPath("$.data.role").isEqualTo("member")
                .jsonPath("$.data.status").isEqualTo("pending")
                .jsonPath("$.data.expired").isEqualTo(false);

        // 被邀请人自己看得到（带组织名）——不需要邀请人告知任何 id
        client().get().uri("/api/me/invitations")
                .header("Cookie", "y1.sid=" + invitee.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].organizationName").isEqualTo("邀请主体")
                .jsonPath("$.data[0].role").isEqualTo("member");

        String invitationId = firstInvitationId(invitee.cookie());
        client().post().uri("/api/me/invitations/" + invitationId + "/accept")
                .header("Cookie", "y1.sid=" + invitee.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.role").isEqualTo("member")
                .jsonPath("$.data.alreadyMember").isEqualTo(false);

        // 真的进了成员表
        client().get().uri("/api/organizations/" + orgId + "/memberships")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(2);

        // 邀请已消费：被邀请人列表清空，org 侧看到 accepted
        client().get().uri("/api/me/invitations")
                .header("Cookie", "y1.sid=" + invitee.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);
        client().get().uri("/api/organizations/" + orgId + "/invitations")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data[0].status").isEqualTo("accepted");
    }

    /**
     * 核心安全属性：邀请一个**从未注册**的邮箱，响应与邀请已注册邮箱逐字一致（201 + 同样字段），
     * 因此本端点不能被用来判定某邮箱是否有账号。
     */
    @Test
    void inviteRevealsNothingAboutWhetherEmailHasAccount() {
        var owner = seedAccount("inv-probe-owner@example.com");
        String orgId = createOrg(owner.cookie(), "枚举防护主体");
        seedAccount("inv-registered@example.com");

        invite(orgId, owner.cookie(), "inv-registered@example.com", "member")
                .expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("pending")
                .jsonPath("$.data.emailSent").isEqualTo(false);

        invite(orgId, owner.cookie(), "nobody-here@example.com", "member")
                .expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("pending")
                .jsonPath("$.data.emailSent").isEqualTo(false);
    }

    @Test
    void duplicatePendingInviteReturns409() {
        var owner = seedAccount("inv-dup-owner@example.com");
        String orgId = createOrg(owner.cookie(), "重复邀请主体");

        invite(orgId, owner.cookie(), "inv-dup@example.com", "member").expectStatus().isCreated();
        invite(orgId, owner.cookie(), "inv-dup@example.com", "admin").expectStatus().isEqualTo(409);
    }

    @Test
    void nonOwnerCannotInvite() {
        var owner = seedAccount("inv-authz-owner@example.com");
        String orgId = createOrg(owner.cookie(), "邀请鉴权主体");
        var admin = seedAccount("inv-authz-admin@example.com");
        addMember(orgId, owner.cookie(), admin.accountId(), "admin").expectStatus().isCreated();

        invite(orgId, admin.cookie(), "inv-someone@example.com", "member").expectStatus().isForbidden();
        // 但 admin 是成员，能看列表
        client().get().uri("/api/organizations/" + orgId + "/invitations")
                .header("Cookie", "y1.sid=" + admin.cookie()).exchange().expectStatus().isOk();
    }

    /** 邀请 id 泄露也无法被他人冒领：邮箱不匹配一律 404（不用 403，避免确认「这个 id 是真邀请」）。 */
    @Test
    void otherAccountCannotAcceptSomeoneElsesInvitation() {
        var owner = seedAccount("inv-steal-owner@example.com");
        String orgId = createOrg(owner.cookie(), "冒领防护主体");
        seedAccount("inv-target@example.com");
        var attacker = seedAccount("inv-attacker@example.com");
        String invitationId = inviteAndGetId(orgId, owner.cookie(), "inv-target@example.com", "member");

        client().post().uri("/api/me/invitations/" + invitationId + "/accept")
                .header("Cookie", "y1.sid=" + attacker.cookie()).exchange()
                .expectStatus().isNotFound();
        client().get().uri("/api/me/invitations")
                .header("Cookie", "y1.sid=" + attacker.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    void expiredInvitationIsHiddenAndCannotBeAccepted() {
        var owner = seedAccount("inv-exp-owner@example.com");
        String orgId = createOrg(owner.cookie(), "过期邀请主体");
        var invitee = seedAccount("inv-expired@example.com");
        String invitationId = inviteAndGetId(orgId, owner.cookie(), "inv-expired@example.com", "member");

        db.sql("UPDATE organization_invitation SET expires_at = now() - interval '1 day'"
                + " WHERE id = CAST(:id AS uuid)").bind("id", invitationId).then().block();

        client().get().uri("/api/me/invitations")
                .header("Cookie", "y1.sid=" + invitee.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);
        client().post().uri("/api/me/invitations/" + invitationId + "/accept")
                .header("Cookie", "y1.sid=" + invitee.cookie()).exchange()
                .expectStatus().isEqualTo(410);
    }

    @Test
    void ownerRevokesPendingInvitation() {
        var owner = seedAccount("inv-rev-owner@example.com");
        String orgId = createOrg(owner.cookie(), "撤销邀请主体");
        var invitee = seedAccount("inv-revoked@example.com");
        String invitationId = inviteAndGetId(orgId, owner.cookie(), "inv-revoked@example.com", "member");

        client().delete().uri("/api/organizations/" + orgId + "/invitations/" + invitationId)
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isOk();

        client().get().uri("/api/me/invitations")
                .header("Cookie", "y1.sid=" + invitee.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.length()").isEqualTo(0);
        client().post().uri("/api/me/invitations/" + invitationId + "/accept")
                .header("Cookie", "y1.sid=" + invitee.cookie()).exchange()
                .expectStatus().isEqualTo(409);
        // 再撤销 → 409（已终态）
        client().delete().uri("/api/organizations/" + orgId + "/invitations/" + invitationId)
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void inviteeDeclines() {
        var owner = seedAccount("inv-dec-owner@example.com");
        String orgId = createOrg(owner.cookie(), "谢绝邀请主体");
        var invitee = seedAccount("inv-decliner@example.com");
        String invitationId = inviteAndGetId(orgId, owner.cookie(), "inv-decliner@example.com", "member");

        client().post().uri("/api/me/invitations/" + invitationId + "/decline")
                .header("Cookie", "y1.sid=" + invitee.cookie()).exchange().expectStatus().isOk();

        client().get().uri("/api/organizations/" + orgId + "/invitations")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data[0].status").isEqualTo("declined");
        // 谢绝后还能重新邀请（终态不占 partial unique）
        invite(orgId, owner.cookie(), "inv-decliner@example.com", "member").expectStatus().isCreated();
    }

    /** 已经是成员的人接受邀请：不报错，邀请照样消费，alreadyMember=true 如实告知。 */
    @Test
    void acceptingWhenAlreadyMemberIsIdempotent() {
        var owner = seedAccount("inv-idem-owner@example.com");
        String orgId = createOrg(owner.cookie(), "幂等接受主体");
        var member = seedAccount("inv-already@example.com");
        addMember(orgId, owner.cookie(), member.accountId(), "member").expectStatus().isCreated();
        String invitationId = inviteAndGetId(orgId, owner.cookie(), "inv-already@example.com", "admin");

        client().post().uri("/api/me/invitations/" + invitationId + "/accept")
                .header("Cookie", "y1.sid=" + member.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.alreadyMember").isEqualTo(true);
    }

    @Test
    void rejectsInvalidEmailAndOwnerRole() {
        var owner = seedAccount("inv-bad-owner@example.com");
        String orgId = createOrg(owner.cookie(), "非法入参主体");

        invite(orgId, owner.cookie(), "not-an-email", "member").expectStatus().is4xxClientError();
        invite(orgId, owner.cookie(), "inv-ok@example.com", "owner").expectStatus().is4xxClientError();
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        client().get().uri("/api/me/invitations").exchange().expectStatus().isUnauthorized();
    }

    // ---------- helpers ----------

    private WebTestClient.ResponseSpec invite(String orgId, String cookie, String email, String role) {
        return client().post().uri("/api/organizations/" + orgId + "/invitations")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}")
                .exchange();
    }

    private WebTestClient.ResponseSpec addMember(String orgId, String cookie, String accountId, String role) {
        return client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"accountId\":\"" + accountId + "\",\"role\":\"" + role + "\"}")
                .exchange();
    }

    @SuppressWarnings("unchecked")
    private String inviteAndGetId(String orgId, String cookie, String email, String role) {
        Map<String, Object> body = invite(orgId, cookie, email, role)
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) body.get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String firstInvitationId(String cookie) {
        Map<String, Object> body = client().get().uri("/api/me/invitations")
                .header("Cookie", "y1.sid=" + cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("data");
        return (String) list.get(0).get("id");
    }
}
