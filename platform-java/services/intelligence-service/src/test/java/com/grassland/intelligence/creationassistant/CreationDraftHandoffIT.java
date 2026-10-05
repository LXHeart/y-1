package com.grassland.intelligence.creationassistant;

import static org.assertj.core.api.Assertions.assertThat;
import com.grassland.intelligence.IntelligenceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@SuppressWarnings("unchecked")
class CreationDraftHandoffIT extends IntelligenceItSupport {
    private String create(String account) {
        var response = client().post().uri("/api/creation-drafts").header("X-Grassland-Identity", sign(account, null))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("sourceType", "independent", "title", "商品介绍", "content", "这是第一版已确认的商品介绍，交接后保持不变。"))
            .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return ((Map<String,Object>)response.get("data")).get("id").toString();
    }
    private Map<String,Object> handoff(String account, String source, int version, String request) {
        var response = client().post().uri("/api/creation-drafts/"+source+"/handoffs").header("X-Grassland-Identity", sign(account,null))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("version",version,"target","video","requestId",request))
            .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String,Object>)response.get("data");
    }
    @Test void copiesSavedRevisionAndReplaysWithoutOverwritingTarget() {
        String account="handoff-"+UUID.randomUUID(), source=create(account), request=UUID.randomUUID().toString();
        Map<String,Object> first=handoff(account,source,1,request);
        client().put().uri("/api/creation-drafts/"+source).header("X-Grassland-Identity",sign(account,null))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion",1,"content","第二版正文"))
            .exchange().expectStatus().isOk();
        Map<String,Object> replay=handoff(account,source,1,request);
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(replay.get("content")).isEqualTo(first.get("content"));
        Map<String,Object> origin=(Map<String,Object>)((Map<String,Object>)first.get("workspace")).get("sourceWork");
        assertThat(origin).containsEntry("draftId",source).containsEntry("version",1);
        var other=handoff(account,source,2,UUID.randomUUID().toString());
        assertThat(other.get("id")).isNotEqualTo(first.get("id"));
        assertThat(other.get("content")).isEqualTo("第二版正文");
    }
    @Test void rejectsWrongOwnerMissingVersionAndForgedOrigin() {
        String account="handoff-"+UUID.randomUUID(), source=create(account);
        for (var input: java.util.List.of(Map.of("account","stranger","version",1),Map.of("account",account,"version",99))) {
            client().post().uri("/api/creation-drafts/"+source+"/handoffs")
                .header("X-Grassland-Identity",sign(input.get("account").toString(),null)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("version",input.get("version"),"target","video","requestId",UUID.randomUUID().toString()))
                .exchange().expectStatus().isNotFound();
        }
        var target=handoff(account,source,1,UUID.randomUUID().toString());
        client().put().uri("/api/creation-drafts/"+target.get("id")).header("X-Grassland-Identity",sign(account,null))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion",1,"workspace",Map.of("sourceWork",Map.of("version",99))))
            .exchange().expectStatus().isEqualTo(409);
        client().post().uri("/api/creation-drafts").header("X-Grassland-Identity",sign(account,null))
            .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("sourceType","independent","workspace",Map.of("sourceWork",Map.of("version",1))))
            .exchange().expectStatus().isBadRequest();
    }
}
