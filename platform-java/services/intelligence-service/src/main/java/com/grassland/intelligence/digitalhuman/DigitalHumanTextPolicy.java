package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.contentsafety.ContentSafetyService;
import com.grassland.intelligence.contentsafety.SafetyReport;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 文本输出策略（任务书 #105D C105D-03 / 共享契约 K08）：人设句段切分与 TTS 前安全检查。
 *
 * <p>
 * 分段：句段 ≤120 码点，优先中英文句末（。！？!?；;…）切分，连续无句末到 120 也切；流末（endOfStream） 冲刷残余。检查：本地词库
 * {@code checkShallow} 在 TTS <b>前</b>执行——severity=high 的句段拒绝 （422
 * {@code dh_content_blocked}，已派发费用按实际状态结算）；带<b>前一段尾 32 码点</b>做跨段检测，
 * 后段不重复输出尾巴；medium/low 不阻断；未知 severity 按策略不可用拒绝，不发明 blocked 级别。
 */
@Component
public class DigitalHumanTextPolicy {

	/** K08：句段 ≤120 码点。 */
	public static final int MAX_SEGMENT_CODE_POINTS = 120;
	/** K08：跨段检测回看前一段尾 32 码点。 */
	public static final int CROSS_LOOKBACK_CODE_POINTS = 32;

	private static final String SENTENCE_ENDERS = "。！？!?；;…";

	private final ContentSafetyService safety;

	public DigitalHumanTextPolicy(ContentSafetyService safety) {
		this.safety = safety;
	}

	/** K07.1 SpeechTextSegment：已审查、尚未输出的文字段（segmentIndex/contentEpoch 由调用方编排）。 */
	public record SpeechTextSegment(int segmentIndex, String text, long contentEpoch) {
	}

	/**
	 * 切分待播文本：返回 0..n 段；endOfStream=false 时只输出「确定已完整」的句末段（不足一句的残余留给
	 * 调用方继续累积）；endOfStream=true 时冲刷残余为末段。段落互不重叠（后段不重复前段尾巴）。
	 */
	public List<String> split(String pendingText, boolean endOfStream) {
		List<String> segments = new ArrayList<>();
		if (pendingText == null || pendingText.isEmpty()) {
			return segments;
		}
		int cpCount = pendingText.codePointCount(0, pendingText.length());
		int start = 0; // 码点偏移
		int segmentLength = 0;
		int index = 0;
		int cp;
		for (; index < cpCount; index++) {
			cp = pendingText.codePointAt(pendingText.offsetByCodePoints(0, index));
			segmentLength++;
			if (SENTENCE_ENDERS.indexOf(cp) >= 0 && segmentLength <= MAX_SEGMENT_CODE_POINTS) {
				segments.add(sliceByCodePoints(pendingText, start, segmentLength));
				start = index + 1;
				segmentLength = 0;
			} else if (segmentLength == MAX_SEGMENT_CODE_POINTS) {
				// 连续无句末到 120：硬切（同样要审查）。
				segments.add(sliceByCodePoints(pendingText, start, segmentLength));
				start = index + 1;
				segmentLength = 0;
			}
		}
		if (segmentLength > 0 && endOfStream) {
			segments.add(sliceByCodePoints(pendingText, start, segmentLength));
		}
		return segments;
	}

	/** 任务书签名：split + 编号与 contentEpoch。 */
	public List<SpeechTextSegment> segment(String pendingText, boolean endOfStream, int nextSegmentIndex,
			long contentEpoch) {
		List<SpeechTextSegment> indexed = new ArrayList<>();
		int index = nextSegmentIndex;
		for (String text : split(pendingText, endOfStream)) {
			indexed.add(new SpeechTextSegment(index++, text, contentEpoch));
		}
		return indexed;
	}

	/**
	 * TTS 前安全检查：{@code previousTail} 为前一段尾（≤32 码点）用于跨段命中；high 或未知 severity → 422
	 * dh_content_blocked；medium/low 放行（提示由展示层处理，不上报正文）。
	 */
	public void check(String previousTail, String segmentText) {
		String probe = previousTail == null || previousTail.isEmpty()
				? segmentText
				: tailByCodePoints(previousTail, CROSS_LOOKBACK_CODE_POINTS) + segmentText;
		SafetyReport report;
		try {
			report = safety.checkShallow(probe);
		} catch (Exception unavailable) {
			// 策略不可用即拒绝（不静默放行）。
			throw new IntelligenceException(503, "dh_runtime_unavailable", "内容安全策略暂不可用。");
		}
		for (SafetyReport.Finding finding : report.findings()) {
			String severity = finding.severity() == null ? "" : finding.severity().toLowerCase();
			if ("high".equals(severity)) {
				throw new IntelligenceException(422, "dh_content_blocked", "该内容不符合平台规范，已停止播报。");
			}
			if (!"medium".equals(severity) && !"low".equals(severity)) {
				// 未知 severity：按策略不可用拒绝（现有词库只有 high/medium/low，不发明级别）。
				throw new IntelligenceException(503, "dh_runtime_unavailable", "内容安全策略暂不可用。");
			}
		}
	}

	// ---------- 私有 ----------

	private static String sliceByCodePoints(String text, int startCp, int lengthCp) {
		int from = text.offsetByCodePoints(0, startCp);
		int to = text.offsetByCodePoints(0, startCp + lengthCp);
		return text.substring(from, to);
	}

	private static String tailByCodePoints(String text, int cp) {
		int total = text.codePointCount(0, text.length());
		if (total <= cp) {
			return text;
		}
		return text.substring(text.offsetByCodePoints(0, total - cp));
	}
}
