package com.grassland.intelligence.imageanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.imageanalysis.ImageAnalysisService.ImageAnalysisResult;
import com.grassland.intelligence.imageanalysis.ImageAnalysisService.UploadedImage;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

/** 图片评价校验与结果解析的单元测试（镜像 legacy uploadedImageListSchema + normalizeImageAnalysisResult）。 */
class ImageAnalysisServiceTest {

    private final ImageAnalysisService service = new ImageAnalysisService(
            mock(RoutedTextCompletionService.class), mock(FrozenTextExecutionService.class));

    // ---------------- validateAndEncode ----------------

    @Test
    void rejectsEmptyImageList() {
        assertThatThrownBy(() -> service.validateAndEncode(List.of()))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("请至少上传 1 张图片");
    }

    @Test
    void rejectsMoreThanSixImages() {
        assertThatThrownBy(() -> service.validateAndEncode(List.of(png(), png(), png(), png(), png(), png(), png())))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("最多上传 6 张图片");
    }

    @Test
    void rejectsUnsupportedMime() {
        assertThatThrownBy(() -> service.validateAndEncode(List.of(new UploadedImage("image/gif", "a.gif", pngBytes()))))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("仅支持 JPG、PNG、WebP 图片");
    }

    @Test
    void rejectsMagicByteMismatch() {
        // 声明 PNG 但字节是 JPEG magic → 不匹配
        UploadedImage fake = new UploadedImage("image/png", "fake.png", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0});
        assertThatThrownBy(() -> service.validateAndEncode(List.of(fake)))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("图片文件内容与类型不匹配");
    }

    @Test
    void encodesValidImagesToDataUrlsPreservingOrder() {
        List<String> urls = service.validateAndEncode(List.of(png("a.png"), jpeg("b.jpg")));
        assertThat(urls).hasSize(2);
        assertThat(urls.get(0)).startsWith("data:image/png;base64,");
        assertThat(urls.get(1)).startsWith("data:image/jpeg;base64,");
    }

    // ---------------- parseResult ----------------

    @Test
    void parseResultStripsCodeFenceAndReadsFields() {
        ImageAnalysisResult result = service.parseResult("```json\n{\"review\":\"好评\",\"title\":\"标题\",\"tags\":[\"鲜\"]}\n```");
        assertThat(result.review()).isEqualTo("好评");
        assertThat(result.title()).isEqualTo("标题");
        assertThat(result.tags()).containsExactly("鲜");
    }

    @Test
    void parseResultRejectsEmptyReview() {
        assertThatThrownBy(() -> service.parseResult("{\"review\":\"\"}"))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("图片评价生成服务返回了空结果");
    }

    @Test
    void parseResultRejectsNonObject() {
        assertThatThrownBy(() -> service.parseResult("\"just a string\""))
                .isInstanceOf(IntelligenceException.class)
                .hasMessage("图片评价生成服务返回了无效数据");
    }

    private static UploadedImage png() { return png("photo.png"); }

    private static UploadedImage png(String name) {
        return new UploadedImage("image/png", name, pngBytes());
    }

    private static UploadedImage jpeg(String name) {
        return new UploadedImage("image/jpeg", name, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0});
    }

    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01, 0x02};
    }
}
