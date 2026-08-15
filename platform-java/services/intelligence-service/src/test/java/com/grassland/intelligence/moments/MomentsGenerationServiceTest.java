package com.grassland.intelligence.moments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.moments.MomentsGenerationService.Caption;
import com.grassland.intelligence.moments.MomentsGenerationService.MomentsResult;
import com.grassland.intelligence.moments.MomentsGenerationService.OrderSuggestion;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 朋友圈生成编排：素材图校验（base64 → data URL + magic byte）、JSON 结果解析、SSE 帧。 */
@ExtendWith(MockitoExtension.class)
class MomentsGenerationServiceTest {

    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
    private static final byte[] JPEG_MAGIC = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0};

    @Mock
    private AiCapabilityAdapter ai;
    @Mock
    private com.grassland.intelligence.ai.run.FrozenTextExecutionService frozenText;

    private MomentsGenerationService service;

    @BeforeEach
    void setUp() {
        service = new MomentsGenerationService(ai, frozenText);
    }

    // ---------------- 素材图校验 ----------------

    @Test
    void acceptsZeroImages() {
        assertThat(service.validateAndEncode(List.of())).isEmpty();
    }

    @Test
    void rejectsMoreThanNineImages() {
        assertThatThrownBy(() -> service.validateAndEncode(
                Collections.nCopies(10, dataUrl("image/png", PNG_MAGIC))))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("最多上传 9 张图片");
    }

    @Test
    void normalizesWhitelistedDataUrl() {
        List<String> encoded = service.validateAndEncode(List.of(dataUrl("image/png", PNG_MAGIC)));
        assertThat(encoded).containsExactly(dataUrl("image/png", PNG_MAGIC));
    }

    @Test
    void treatsBareBase64AsJpeg() {
        String bare = ENCODER.encodeToString(JPEG_MAGIC);
        assertThat(service.validateAndEncode(List.of(bare)))
                .containsExactly(dataUrl("image/jpeg", JPEG_MAGIC));
    }

    @Test
    void rejectsMagicByteMismatch() {
        assertThatThrownBy(() -> service.validateAndEncode(List.of(dataUrl("image/png", JPEG_MAGIC))))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("图片文件内容与类型不匹配");
    }

    @Test
    void rejectsNonWhitelistedMime() {
        assertThatThrownBy(() -> service.validateAndEncode(List.of(dataUrl("image/gif", "GIF89a".getBytes()))))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("仅支持 JPG、PNG、WebP 图片");
    }

    @Test
    void rejectsOversizedImage() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(JPEG_MAGIC, 0, big, 0, JPEG_MAGIC.length);
        assertThatThrownBy(() -> service.validateAndEncode(List.of(dataUrl("image/jpeg", big))))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("单张图片不能超过 5 MB");
    }

    @Test
    void rejectsInvalidBase64() {
        assertThatThrownBy(() -> service.validateAndEncode(List.of("data:image/jpeg;base64,%%%")))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("图片文件内容与类型不匹配");
    }

    // ---------------- JSON 结果解析 ----------------

    @Test
    void parsesCopyOrderAndCaptions() {
        MomentsResult result = service.parseResult("""
                {"copy":"开业大吉，周末来店里坐坐","imageOrder":[{"index":2,"reason":"招牌先出"},{"index":1,"reason":"环境承接"}],"captions":[{"index":1,"text":"门店环境"},{"index":2,"text":"招牌菜"}]}
                """);
        assertThat(result.copy()).isEqualTo("开业大吉，周末来店里坐坐");
        assertThat(result.imageOrder()).containsExactly(new OrderSuggestion(2, "招牌先出"), new OrderSuggestion(1, "环境承接"));
        assertThat(result.captions()).containsExactly(new Caption(1, "门店环境"), new Caption(2, "招牌菜"));
    }

    @Test
    void stripsCodeFenceBeforeParsing() {
        MomentsResult result = service.parseResult("""
                ```json
                {"copy":" fenced ","imageOrder":[],"captions":[]}
                ```
                """);
        assertThat(result.copy()).isEqualTo("fenced");
    }

    @Test
    void toleratesMissingOrderAndCaptions() {
        MomentsResult result = service.parseResult("{\"copy\":\"仅文案\"}");
        assertThat(result.imageOrder()).isEmpty();
        assertThat(result.captions()).isEmpty();
    }

    @Test
    void rejectsResultWithoutCopy() {
        assertThatThrownBy(() -> service.parseResult("{\"imageOrder\":[],\"captions\":[]}"))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("朋友圈内容生成服务返回了空结果");
    }

    @Test
    void rejectsUnparseableContent() {
        assertThatThrownBy(() -> service.parseResult("随便一段话"))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("朋友圈内容生成服务返回了无法解析的内容");
    }

    // ---------------- 生成事件流 ----------------

    @Test
    void generateEmitsProgressThenResultFrames() {
        when(ai.completeText(any())).thenReturn(Mono.just(
                "{\"copy\":\"开业大吉\",\"imageOrder\":[],\"captions\":[]}"));

        StepVerifier.create(service.generate(List.of(), MomentsStyle.EVENT, "开业", null))
                .assertNext(frame -> assertThat(frame).contains("\"type\":\"progress\""))
                .assertNext(frame -> assertThat(frame)
                        .contains("\"type\":\"result\"")
                        .contains("开业大吉")
                        .contains("\"imageOrder\":[]")
                        .contains("\"captions\":[]"))
                .verifyComplete();
    }

    @Test
    void generateSendsSystemAndMultimodalUserMessages() {
        when(ai.completeText(any())).thenReturn(Mono.just("{\"copy\":\"ok\"}"));

        StepVerifier.create(service.generate(
                        List.of(dataUrl("image/png", PNG_MAGIC)), MomentsStyle.LIFESTYLE, "主题", "感受"))
                .expectNextCount(2)
                .verifyComplete();

        ArgumentCaptor<TextCompletionCommand> captor = ArgumentCaptor.forClass(TextCompletionCommand.class);
        verify(ai).completeText(captor.capture());
        TextCompletionCommand command = captor.getValue();
        assertThat(command.messages()).hasSize(2);
        assertThat(command.messages().get(0).content()).contains("生活化");
        ChatMessage user = command.messages().get(1);
        assertThat(user.multimodal()).isTrue();
        assertThat(user.parts()).hasSize(2);
        assertThat(user.parts().get(0)).isInstanceOf(ContentPart.Image.class);
        assertThat(((ContentPart.Image) user.parts().get(0)).url()).startsWith("data:image/png;base64,");
        assertThat(user.parts().get(1)).isInstanceOf(ContentPart.Text.class);
        assertThat(((ContentPart.Text) user.parts().get(1)).text()).contains("主题");
    }

    @Test
    void generateWithoutImagesSendsPlainTextUserMessage() {
        when(ai.completeText(any())).thenReturn(Mono.just("{\"copy\":\"纯文本\"}"));

        StepVerifier.create(service.generate(List.of(), MomentsStyle.EVENT, "主题", null))
                .expectNextCount(2)
                .verifyComplete();

        ArgumentCaptor<TextCompletionCommand> captor = ArgumentCaptor.forClass(TextCompletionCommand.class);
        verify(ai).completeText(captor.capture());
        ChatMessage user = captor.getValue().messages().get(1);
        assertThat(user.multimodal()).isFalse();
        assertThat(user.content()).contains("主题");
    }

    @Test
    void generateEmitsProgressThenPropagatesUpstreamFailure() {
        when(ai.completeText(any())).thenReturn(Mono.error(new RuntimeException("upstream down")));

        StepVerifier.create(service.generate(List.of(), MomentsStyle.LIFESTYLE, "主题", null))
                .assertNext(frame -> assertThat(frame).contains("\"type\":\"progress\""))
                .expectError()
                .verify();
    }

    private static String dataUrl(String mime, byte[] bytes) {
        return "data:" + mime + ";base64," + ENCODER.encodeToString(bytes);
    }

    private static String dataUrl(String mime, String payload) {
        return "data:" + mime + ";base64," + payload;
    }
}
