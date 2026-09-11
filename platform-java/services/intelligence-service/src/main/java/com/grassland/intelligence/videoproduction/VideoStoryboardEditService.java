package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 分镜编辑闸（任务书 #100 C100-03，API-02～04/06、§7.2）：所有内容/集合写入口共享同一分镜行锁——
 * 事务内 {@code SELECT FOR UPDATE} → 校验 draft 与期望版本（CAS）→ 应用写入 → 实际变化才提升
 * edit_version。旧客户端不带版本兼容放行（同样提升版本）；网络/模型/积分调用不得进入本服务的锁事务。
 *
 * <p>visual→prompt 同步（§6.2 本任务明确改变的生成正确性行为）：显式修改 visual 时生成用 prompt
 * 同步为新 visual；只改旁白/时长/运镜不覆盖 prompt。
 */
@Service
public class VideoStoryboardEditService {

    /** §6.1 cameraMove 受控枚举（批量编辑严格校验用；与 DirectorPanel 下拉一致）。 */
    public static final Set<String> CAMERA_MOVES = Set.of("固定机位", "缓慢推近", "缓慢拉远", "左右横移", "跟随运镜",
            "环绕", "俯拍下摇", "仰拍上摇", "特写切换", "手持感轻晃", "升降镜头", "旋转");

    static final int BATCH_MAX = 12;
    private static final int VISUAL_MAX = 4000;
    private static final int NARRATION_MAX = 4000;

    private final VideoStoryboardRepository storyboards;
    private final VideoShotRepository shots;
    private final TransactionalOperator transactions;

    public VideoStoryboardEditService(VideoStoryboardRepository storyboards, VideoShotRepository shots,
            TransactionalOperator transactions) {
        this.storyboards = storyboards;
        this.shots = shots;
        this.transactions = transactions;
    }

    /** 编辑结果（API-03/04/06 响应增量）：新版本与受影响镜头。 */
    public record EditOutcome(UUID storyboardId, long editVersion, List<String> updatedShotIds) {
    }

    /** 锁内写入的统一回执：changed=false（无实际变化）不提升版本。 */
    public record EditWrite(boolean changed, List<String> updatedShotIds) {

        public static EditWrite of(String... updatedShotIds) {
            return new EditWrite(true, List.of(updatedShotIds));
        }

        public static final EditWrite UNCHANGED = new EditWrite(false, List.of());
    }

    /** 带值回执（增删镜头等需要在响应里携带锁内产物时用）。 */
    public record EditWritePayload<T>(T value, EditWrite write) {
    }

    public record EditOutcomeWith<T>(EditOutcome outcome, T value) {
    }

    /**
     * 带值版编辑闸：与 {@link #inEditLock} 同闸同事务，锁内写入可携带任意结果
     * （如新增的镜头行/删除统计）供调用方装配响应。
     */
    public <T> Mono<EditOutcomeWith<T>> inEditLockWithValue(String accountId, UUID storyboardId,
            Long expectedEditVersion, String committedMessage,
            Function<VideoStoryboard, Mono<EditWritePayload<T>>> work) {
        Mono<EditOutcomeWith<T>> locked = storyboards.lockById(storyboardId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "分镜不存在")))
                .flatMap(storyboard -> {
                    if (storyboard.isCommitted()) {
                        return Mono.error(new IntelligenceException(409, committedMessage));
                    }
                    if (expectedEditVersion != null && expectedEditVersion != storyboard.editVersion()) {
                        return Mono.error(new IntelligenceException(409, "分镜已被他人修改，请刷新后重试"));
                    }
                    return work.apply(storyboard).flatMap(payload -> resolve(storyboard, storyboardId, payload.write())
                            .map(outcome -> new EditOutcomeWith<>(outcome, payload.value())));
                });
        return transactions.transactional(locked);
    }

    private Mono<EditOutcome> resolve(VideoStoryboard storyboard, UUID storyboardId, EditWrite write) {
        if (!write.changed()) {
            return Mono.just(new EditOutcome(storyboardId, storyboard.editVersion(), write.updatedShotIds()));
        }
        return storyboards.bumpEditVersion(storyboardId)
                .map(version -> new EditOutcome(storyboardId, version, write.updatedShotIds()));
    }

    /** API-06 批量内容编辑请求（严格校验：expectedEditVersion 必填、1～12 项、§6.1 字段限制）。 */
    public record BatchContentPatch(UUID shotId, String visual, String narration, Integer plannedSeconds,
            String cameraMove, Integer anchorImageIndex) {
    }

    public record BatchEditRequest(Long expectedEditVersion, List<BatchContentPatch> patches) {
    }

    /**
     * API-06：PATCH /storyboards/{id}/content。全部校验通过后同一事务原子应用；任一项失败整批不写；
     * committed 拒绝；版本不匹配 409；无实际变化不提升版本。
     */
    public Mono<EditOutcome> editBatch(String accountId, UUID storyboardId, BatchEditRequest request) {
        if (request == null || request.expectedEditVersion() == null || request.expectedEditVersion() < 1) {
            return Mono.error(new IntelligenceException(400, "expectedEditVersion 必须为正整数"));
        }
        if (request.patches() == null || request.patches().size() < 1 || request.patches().size() > BATCH_MAX) {
            return Mono.error(new IntelligenceException(400, "编辑批次须为 1-" + BATCH_MAX + " 项"));
        }
        Set<UUID> patchShotIds = new HashSet<>();
        for (BatchContentPatch patch : request.patches()) {
            if (patch == null || patch.shotId() == null) {
                return Mono.error(new IntelligenceException(400, "编辑项缺少 shotId"));
            }
            if (!patchShotIds.add(patch.shotId())) {
                return Mono.error(new IntelligenceException(400, "编辑批次存在重复镜头"));
            }
        }
        return inEditLock(accountId, storyboardId, request.expectedEditVersion(), locked -> shots
                .findByStoryboard(storyboardId).collectList().flatMap(shotList -> {
                    Map<UUID, VideoShot> byId = new LinkedHashMap<>();
                    shotList.forEach(shot -> byId.put(shot.id(), shot));
                    for (UUID shotId : patchShotIds) {
                        if (!byId.containsKey(shotId)) {
                            return Mono.error(new IntelligenceException(404, "镜头不存在或不属于该分镜"));
                        }
                    }
                    int imageCount = payloadImageCount(locked);
                    return Flux.concat(request.patches().stream()
                                    .map(patch -> applyStrictPatch(byId.get(patch.shotId()), patch, imageCount)).toList())
                            .collectList()
                            .map(updated -> updated.stream().filter(id -> id != null).toList())
                            .map(updatedIds -> updatedIds.isEmpty() ? EditWrite.UNCHANGED
                                    : EditWrite.of(updatedIds.toArray(String[]::new)));
                }));
    }

    /**
     * API-03：PUT /shots/{shotId}/content（旧端点兼容归一 + 可选版本）。缺省字段沿用行上原值、
     * null/空白归一、时长钳 4-6；显式修改 visual 时 prompt 同步为新 visual。
     */
    public Mono<ShotContentResult> updateShotContent(String accountId, UUID shotId,
            VideoProductionController.ShotContentRequest body) {
        AtomicReference<Integer> plannedOut = new AtomicReference<>();
        return shots.findByIdForAccount(shotId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "镜头不存在")))
                .flatMap(shot -> inEditLock(accountId, shot.storyboardId(),
                        body == null ? null : body.expectedEditVersion(),
                        locked -> Mono.defer(() -> {
                            String visual = body == null || body.visual() == null || body.visual().isBlank()
                                    ? shot.visual()
                                    : body.visual().trim();
                            String narration = body == null || body.narration() == null
                                    ? shot.narration()
                                    : body.narration().trim();
                            int plannedSeconds = body == null || body.plannedSeconds() == null
                                    ? shot.plannedSeconds()
                                    : Math.min(6, Math.max(4, body.plannedSeconds()));
                            String cameraMove = body == null || body.cameraMove() == null
                                    || body.cameraMove().isBlank()
                                    ? shot.cameraMove()
                                    : body.cameraMove().trim();
                            int anchorImageIndex = body == null || body.anchorImageIndex() == null
                                    ? shot.anchorImageIndex()
                                    : body.anchorImageIndex();
                            boolean visualChanged = body != null && body.visual() != null
                                    && !body.visual().isBlank() && !visual.equals(shot.visual());
                            String prompt = visualChanged ? visual : shot.prompt();
                            boolean changed = !visual.equals(shot.visual()) || !narration.equals(shot.narration())
                                    || plannedSeconds != shot.plannedSeconds()
                                    || !cameraMove.equals(shot.cameraMove())
                                    || anchorImageIndex != shot.anchorImageIndex();
                            plannedOut.set(plannedSeconds);
                            if (!changed) {
                                return Mono.just(EditWrite.UNCHANGED);
                            }
                            return shots.updateContent(shotId, visual, narration, plannedSeconds, cameraMove,
                                    anchorImageIndex, prompt).flatMap(updated -> updated
                                            ? Mono.just(EditWrite.of(shotId.toString()))
                                            : Mono.error(new IntelligenceException(404, "镜头不存在")));
                        })))
                .map(outcome -> new ShotContentResult(outcome.editVersion(), outcome.updatedShotIds(),
                        plannedOut.get()));
    }

    /** 单镜编辑结果（原响应保留 shotId/plannedSeconds，新增 editVersion/updatedShotIds）。 */
    public record ShotContentResult(long editVersion, List<String> updatedShotIds, Integer plannedSeconds) {
    }

    /**
     * API-04 集合/分组写入口的共用闸：事务内锁分镜行 → 校验 draft 与期望版本 → 执行调用方写入 →
     * 实际变化才提升 edit_version。调用方写入禁止包含网络/模型/积分调用（§7.2）。
     */
    public Mono<EditOutcome> inEditLock(String accountId, UUID storyboardId, Long expectedEditVersion,
            Function<VideoStoryboard, Mono<EditWrite>> work) {
        return inEditLock(accountId, storyboardId, expectedEditVersion, "分镜已提交成片，不能编辑", work);
    }

    public Mono<EditOutcome> inEditLock(String accountId, UUID storyboardId, Long expectedEditVersion,
            String committedMessage, Function<VideoStoryboard, Mono<EditWrite>> work) {
        Mono<EditOutcome> locked = storyboards.lockById(storyboardId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "分镜不存在")))
                .flatMap(storyboard -> {
                    if (storyboard.isCommitted()) {
                        return Mono.error(new IntelligenceException(409, committedMessage));
                    }
                    if (expectedEditVersion != null && expectedEditVersion != storyboard.editVersion()) {
                        return Mono.error(new IntelligenceException(409, "分镜已被他人修改，请刷新后重试"));
                    }
                    return work.apply(storyboard).flatMap(write -> resolve(storyboard, storyboardId, write));
                });
        return transactions.transactional(locked);
    }

    /** §6.1 严格字段校验 + 写入；返回 shotId（无实际变化返回 null，不计入 updated）。 */
    private Mono<String> applyStrictPatch(VideoShot shot, BatchContentPatch patch, int imageCount) {
        boolean hasField = patch.visual() != null || patch.narration() != null || patch.plannedSeconds() != null
                || patch.cameraMove() != null || patch.anchorImageIndex() != null;
        if (!hasField) {
            return Mono.error(new IntelligenceException(400, "编辑项至少包含一个内容字段"));
        }
        String visual = patch.visual() == null ? shot.visual() : patch.visual().trim();
        if (patch.visual() != null && (visual.isEmpty() || visual.codePointCount(0, visual.length()) > VISUAL_MAX)) {
            return Mono.error(new IntelligenceException(400, "画面描述须为 1-" + VISUAL_MAX + " 字"));
        }
        String narration = patch.narration() == null ? shot.narration() : patch.narration().trim();
        if (patch.narration() != null && narration.codePointCount(0, narration.length()) > NARRATION_MAX) {
            return Mono.error(new IntelligenceException(400, "旁白最多 " + NARRATION_MAX + " 字"));
        }
        int plannedSeconds = patch.plannedSeconds() == null ? shot.plannedSeconds() : patch.plannedSeconds();
        if (patch.plannedSeconds() != null && (plannedSeconds < 4 || plannedSeconds > 6)) {
            return Mono.error(new IntelligenceException(400, "时长须为 4-6 秒"));
        }
        String cameraMove = patch.cameraMove() == null ? shot.cameraMove() : patch.cameraMove().trim();
        if (patch.cameraMove() != null && !CAMERA_MOVES.contains(cameraMove)) {
            return Mono.error(new IntelligenceException(400, "运镜取值不在受控列表"));
        }
        int anchorImageIndex = patch.anchorImageIndex() == null ? shot.anchorImageIndex() : patch.anchorImageIndex();
        if (patch.anchorImageIndex() != null && (anchorImageIndex < 0 || anchorImageIndex > imageCount)) {
            return Mono.error(new IntelligenceException(400, "锚定图序号须为 0-" + imageCount));
        }
        boolean changed = !visual.equals(shot.visual()) || !narration.equals(shot.narration())
                || plannedSeconds != shot.plannedSeconds() || !cameraMove.equals(shot.cameraMove())
                || anchorImageIndex != shot.anchorImageIndex();
        if (!changed) {
            return Mono.just(null);
        }
        boolean visualChanged = patch.visual() != null && !visual.equals(shot.visual());
        String prompt = visualChanged ? visual : shot.prompt();
        return shots.updateContent(shot.id(), visual, narration, plannedSeconds, cameraMove, anchorImageIndex, prompt)
                .flatMap(updated -> updated ? Mono.just(shot.id().toString())
                        : Mono.error(new IntelligenceException(404, "镜头不存在")));
    }

    /** request_payload 图片数（anchorImageIndex 上界；解析失败按 0——只拒绝显式正序号）。 */
    static int payloadImageCount(VideoStoryboard storyboard) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(storyboard.requestPayload()).path("images");
            if (!node.isArray()) {
                return 0;
            }
            int count = 0;
            for (var image : node) {
                if (!image.asText("").isEmpty()) {
                    count += 1;
                }
            }
            return count;
        } catch (Exception error) {
            return 0;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IntelligenceException(500, "序列化失败");
        }
    }
}
