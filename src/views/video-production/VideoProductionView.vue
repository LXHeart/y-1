<template>
  <div class="video-production gl-field">
    <nav class="steps-bar" aria-label="制作步骤">
      <div
        v-for="(s, i) in steps"
        :key="s.key"
        class="step-dot"
        :class="{
          'step-active': stage === s.key,
          'step-done': stepIndex(stage) > i,
        }"
      >
        <span class="step-num">{{ i + 1 }}</span>
        <span class="step-label">{{ s.label }}</span>
      </div>
    </nav>

    <!-- Step 1: Upload -->
    <section v-if="stage === 'upload'" class="stage-card gl-zone fade-in">
      <header class="card-head">
        <p class="eyebrow">第一步</p>
        <h2 class="card-title">上传素材 & 填写店铺信息</h2>
        <p class="field-note">上传 1-9 张店铺/产品照片，填写基本信息后生成推广脚本。</p>
      </header>

      <div class="upload-area">
        <input
          ref="fileInput"
          type="file"
          accept="image/*"
          multiple
          class="hidden-input"
          @change="handleFileSelect"
        />
        <label
          class="drop-zone"
          :class="{ 'drop-zone-active': isDragging }"
          @dragover.prevent="isDragging = true"
          @dragleave="isDragging = false"
          @drop.prevent="handleDrop"
          @click="($refs.fileInput as HTMLInputElement)?.click()"
        >
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 5v14M5 12l7-7 7 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span>拖拽图片到此处，或点击上传</span>
          <span class="field-note">{{ images.length }} / {{ MAX_IMAGES }} 张</span>
        </label>
      </div>

      <div v-if="images.length > 0" class="preview-grid">
        <div
          v-for="(img, idx) in images"
          :key="img.id"
          class="preview-item"
          draggable="true"
          @dragstart="dragIndex = idx"
          @dragover.prevent
          @drop="onDropReorder(idx)"
        >
          <img :src="img.dataUrl" :alt="img.name" class="preview-thumb" @click="openLightbox(img.dataUrl)" />
          <button type="button" class="preview-remove" @click="removeImage(img.id)" aria-label="删除图片">&times;</button>
          <span class="preview-order">{{ idx + 1 }}</span>
        </div>
      </div>

      <div class="form-grid">
        <div class="form-field">
          <label for="vp-shop-name">店铺名称 *</label>
          <input id="vp-shop-name" v-model="form.shopName" type="text" placeholder="例如：老王面馆" />
        </div>
        <div class="form-field">
          <label for="vp-industry">行业类型 *</label>
          <select id="vp-industry" v-model="form.industryType">
            <option v-for="t in industryTypes" :key="t" :value="t">{{ t }}</option>
          </select>
        </div>
        <div class="form-field">
          <label for="vp-platform">发布平台 *</label>
          <select id="vp-platform" v-model="form.targetPlatform">
            <option value="">请选择发布平台</option>
            <option v-for="item in videoPlatforms" :key="item.id" :value="item.id">{{ item.label }}</option>
          </select>
        </div>
        <div class="form-field">
          <label for="vp-address">店铺地址</label>
          <input id="vp-address" v-model="form.shopAddress" type="text" placeholder="选填" />
        </div>
        <div class="form-field">
          <label for="vp-style">视频风格 *</label>
          <select id="vp-style" v-model="form.videoStyle">
            <option v-for="s in videoStyles" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div class="form-field form-field-wide">
          <label for="vp-desc">店铺简介</label>
          <textarea id="vp-desc" v-model="form.shopDescription" rows="2" placeholder="简短描述店铺特色（选填）"></textarea>
        </div>
        <div class="form-field form-field-wide">
          <label for="vp-prompt">自定义要求</label>
          <textarea id="vp-prompt" v-model="form.customPrompt" rows="2" maxlength="1500" placeholder="对视频脚本有什么特殊要求？（选填）"></textarea>
        </div>
      </div>

      <VideoReferenceInput
        :reference-platform="referencePlatform"
        :reference-input="referenceInput"
        :hot-topic-input="hotTopicInput"
        :reference-cards="referenceCards"
        :has-selected-cards="hasSelectedReferenceCards"
        :applied="referenceApplied"
        :parse-loading="referenceParseLoading"
        @switch-platform="handleSwitchReferencePlatform"
        @update:reference-input="referenceInput = $event"
        @update:hot-topic-input="hotTopicInput = $event"
        @extract="handleExtractReference"
        @clear-reference="handleClearReference"
        @apply-to-prompt="applyReferenceToPrompt"
        @apply-hot-topic="applyHotTopicToPrompt"
        @toggle-card="toggleReferenceCard"
      >
        <template #parse-panels>
          <DouyinParsePanel
            v-if="referencePlatform === 'douyin'"
            :extracted-video="douyinExtractedVideo"
            :loading="douyinParseLoading"
            :error="douyinParseError"
            :analysis="douyinVideoAnalysis"
            :analysis-loading="douyinAnalysisLoading"
            :analysis-error="douyinAnalysisError"
            @retry="handleExtractReference"
            @retry-analysis="handleRetryDouyinAnalysis"
          />

          <BilibiliParsePanel
            v-else
            :extracted-video="bilibiliExtractedVideo"
            :loading="bilibiliParseLoading"
            :error="bilibiliParseError"
            :analysis="bilibiliVideoAnalysis"
            :analysis-loading="bilibiliAnalysisLoading"
            :analysis-error="bilibiliAnalysisError"
            @retry="handleExtractReference"
            @retry-analysis="handleRetryBilibiliAnalysis"
          />
        </template>
      </VideoReferenceInput>

      <div class="form-field form-field-wide duration-field">
        <label for="vp-duration">成片时长（{{ form.targetDurationSeconds }} 秒）</label>
        <input
          id="vp-duration"
          :value="form.targetDurationSeconds"
          type="range"
          min="15"
          max="180"
          step="5"
          data-test="target-duration"
          @input="handleDurationInput(($event.target as HTMLInputElement).value)"
        />
        <p class="field-note">
          15-180 秒，按 5 秒步进；计费按成片实际秒数一口价结算<template v-if="estimatedPriceCents !== null">
            （预计 {{ formatYuan(estimatedPriceCents) }} 起）</template>。
          <span v-if="isLandscape" class="resolution-tag" data-test="resolution-tag">B 站默认横版 16:9</span>
        </p>
        <p v-if="verticalDurationHint" class="field-note duration-hint" data-test="vertical-duration-hint">
          {{ verticalDurationHint }}
        </p>
      </div>

      <div v-if="error" class="error-hint">{{ error }}</div>

      <div class="action-row">
        <button
          class="btn-primary gl-btn-primary"
          :disabled="!canProceedToStoryboard || storyboardLoading"
          @click="generateStoryboard"
        >
          {{ storyboardLoading ? '生成中…' : '生成分镜' }}
        </button>
      </div>
    </section>

    <!-- Step 2: Storyboard Editing（任务书 #64 卡4） -->
    <section v-if="stage === 'storyboard'" class="stage-card gl-zone fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="goBackToUpload">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回修改
          </button>
          <p class="eyebrow">第二步</p>
        </div>
        <h2 class="card-title">编辑分镜</h2>
        <p class="field-note">
          AI 生成了 {{ shots.length }} 个镜头、合计约 {{ totalPlannedSeconds }} 秒（目标 {{ form.targetDurationSeconds }} 秒）。
          你可以逐镜编辑画面、旁白、运镜与锚定图。
        </p>
        <p v-if="referenceApplied" class="field-note reference-applied-note">已包含参考输入（参考视频分析 / 热点主题），见自定义要求。</p>
      </header>

      <div class="script-thumbnails">
        <img
          v-for="img in images"
          :key="img.id"
          :src="img.dataUrl"
          :alt="img.name"
          class="script-thumb"
          @click="openLightbox(img.dataUrl)"
        />
      </div>

      <div v-if="storyboardLoading" class="stream-area">
        <p class="field-note stream-badge">
          <span class="stream-dot"></span>
          分镜生成中，逐镜送达…
        </p>
        <div v-for="shot in shots" :key="shot.seq" class="shot-card" data-test="streaming-shot">
          <span class="shot-badge">第 {{ shot.seq }} 镜</span>
          <span class="shot-visual-preview">{{ shot.visual }}</span>
        </div>
      </div>

      <template v-else>
        <div
          v-for="(shot, index) in shots"
          :key="shot.seq"
          class="shot-card gl-tile"
          data-test="shot-card"
        >
          <div class="shot-head">
            <span class="shot-badge">第 {{ shot.seq }} 镜</span>
            <span class="field-note">{{ shot.plannedSeconds }} 秒</span>
            <button
              type="button"
              class="preview-remove"
              :aria-label="`删除第 ${shot.seq} 镜`"
              @click="removeShot(index)"
            >&times;</button>
          </div>
          <div class="shot-grid">
            <div class="form-field">
              <label :for="`shot-visual-${shot.seq}`">画面描述</label>
              <textarea
                :id="`shot-visual-${shot.seq}`"
                rows="2"
                :value="shot.visual"
                @change="updateShot(index, { visual: ($event.target as HTMLTextAreaElement).value })"
              ></textarea>
            </div>
            <div class="form-field">
              <label :for="`shot-narration-${shot.seq}`">旁白</label>
              <textarea
                :id="`shot-narration-${shot.seq}`"
                rows="2"
                :value="shot.narration"
                @change="updateShot(index, { narration: ($event.target as HTMLTextAreaElement).value })"
              ></textarea>
            </div>
            <div class="form-field">
              <label :for="`shot-move-${shot.seq}`">运镜</label>
              <select
                :id="`shot-move-${shot.seq}`"
                :value="shot.cameraMove"
                @change="updateShot(index, { cameraMove: ($event.target as HTMLSelectElement).value })"
              >
                <option v-for="move in cameraMoves" :key="move" :value="move">{{ move }}</option>
              </select>
            </div>
            <div class="form-field">
              <label :for="`shot-anchor-${shot.seq}`">锚定图</label>
              <select
                :id="`shot-anchor-${shot.seq}`"
                :value="shot.anchorImageIndex"
                @change="updateShot(index, { anchorImageIndex: Number(($event.target as HTMLSelectElement).value) })"
              >
                <option :value="0">无锚定图</option>
                <option v-for="(img, imgIndex) in images" :key="img.id" :value="imgIndex + 1">
                  第 {{ imgIndex + 1 }} 张
                </option>
              </select>
            </div>
            <div class="form-field">
              <label :for="`shot-seconds-${shot.seq}`">时长（4-6 秒）</label>
              <input
                :id="`shot-seconds-${shot.seq}`"
                type="number"
                min="4"
                max="6"
                step="1"
                :value="shot.plannedSeconds"
                @change="updateShot(index, { plannedSeconds: Number(($event.target as HTMLInputElement).value) })"
              />
            </div>
            <div class="form-field shot-anchor-thumb">
              <label>锚定图预览</label>
              <div class="anchor-preview">
                <img
                  v-if="shot.anchorUrl"
                  :src="shot.anchorUrl"
                  alt="AI 生成首帧"
                  class="script-thumb"
                  @click="openLightbox(shot.anchorUrl)"
                />
                <img
                  v-else-if="shot.anchorImageIndex > 0 && images[shot.anchorImageIndex - 1]"
                  :src="images[shot.anchorImageIndex - 1].dataUrl"
                  alt="锚定图"
                  class="script-thumb"
                  @click="openLightbox(images[shot.anchorImageIndex - 1].dataUrl)"
                />
                <span v-else class="field-note">无锚定图</span>
                <span v-if="shot.anchorUrl" class="anchor-badge" data-test="anchor-badge">AI 生成</span>
              </div>
              <button
                v-if="shot.anchorImageIndex === 0 && shot.id"
                type="button"
                class="btn-secondary btn-sm anchor-generate"
                :disabled="anchorGenerating[shot.id] || storyboardLoading"
                data-test="anchor-generate"
                @click="generateAnchorImage(shot.id)"
              >
                {{ anchorGenerating[shot.id] ? '生成中…' : 'AI 生成首帧' }}
              </button>
              <p v-if="shot.id && anchorErrors[shot.id]" class="field-note anchor-error">
                {{ anchorErrors[shot.id] }}
              </p>
            </div>
          </div>
        </div>

        <div class="action-row">
          <button type="button" class="btn-secondary" :disabled="!canAddShot" data-test="add-shot" @click="addShot">
            添加镜头
          </button>
          <button type="button" class="btn-secondary" :disabled="storyboardLoading" @click="generateStoryboard">
            重新生成分镜
          </button>
        </div>
      </template>

      <div v-if="error" class="error-hint">{{ error }}</div>

      <SafetyFindingsPanel
        v-if="safetyReport"
        :report="safetyReport"
        :text="narrationText"
        @updated="safetyReport = $event"
      />

      <div class="action-row">
        <button
          class="btn-primary gl-btn-primary"
          :disabled="storyboardLoading || shots.length === 0"
          data-test="begin-generation"
          @click="beginGeneration"
        >
          进入生成与挑选
        </button>
      </div>
    </section>

    <!-- Step 3: 生成与挑选（任务书 #64 卡9） -->
    <section v-if="stage === 'generate'" class="stage-card gl-zone fade-in">
      <header class="card-head">
        <div class="card-head-row">
          <button class="btn-back" type="button" @click="goBackToStoryboard">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            返回分镜
          </button>
          <p class="eyebrow">第三步</p>
        </div>
        <h2 class="card-title">生成与挑选</h2>
        <p class="field-note">
          每镜 {{ defaultTakeCount }} 个候选，挑一个进入合成；
          <span v-if="task">任务进度 {{ task.progress }}%</span>
        </p>
      </header>

      <p v-if="isSlideshowMode" class="field-note mode-notice" data-test="slideshow-notice">
        当前未配置视频生成模型，将以图文成片模式产出（图片轮播+运镜+配音+字幕）
      </p>
      <p v-if="ttsUnavailable" class="field-note mode-notice" data-test="tts-notice">
        配音模型未配置，成片将无配音
      </p>
      <div v-if="error || taskError" class="error-hint">{{ error || taskError }}</div>
      <div v-if="taskTerminal" class="error-hint" data-test="task-terminal">
        {{ task?.phase === 'cancelled'
          ? '任务已取消，预留积分已退回'
          : `任务失败：${task?.errorMessage || task?.errorCode || '未知原因'}` }}
      </div>

      <template v-if="task && !isSlideshowMode">
        <div
          v-for="shot in task.shots"
          :key="shot.id"
          class="shot-card gl-tile"
          data-test="pick-shot"
        >
          <div class="shot-head">
            <span class="shot-badge">第 {{ shot.seq }} 镜</span>
            <span class="field-note">{{ shotLabel(shot) }}</span>
            <button
              type="button"
              class="btn-secondary btn-sm"
              :disabled="composeSubmitting"
              data-test="regenerate-shot"
              @click="regenerateShot(shot.id)"
            >
              重抽
            </button>
          </div>

          <div class="take-matrix">
            <div
              v-for="take in shot.takes"
              :key="take.id"
              class="take-card"
              :class="{ 'take-selected': task.selection[shot.id] === take.id }"
              data-test="take-card"
            >
              <video
                v-if="take.url"
                :src="take.url"
                class="take-video"
                controls
                muted
                preload="metadata"
              ></video>
              <div v-else class="take-placeholder">
                <span>{{ takeStatusLabel(take.status) }}</span>
                <span v-if="take.errorMessage" class="field-note">{{ take.errorMessage }}</span>
              </div>
              <div
                v-if="take.score != null"
                class="take-score-row"
                :data-test="`take-score-${take.takeNo}`"
              >
                <span class="badge" :class="scoreBadgeClass(take.score)">质检 {{ take.score }}</span>
                <span
                  v-for="label in take.scoreLabels"
                  :key="label"
                  class="badge badge-neutral"
                  :data-test="`take-score-label-${take.takeNo}`"
                >{{ label }}</span>
              </div>
              <label class="take-pick" :class="{ 'take-pick-disabled': !take.selectable }">
                <input
                  type="radio"
                  :name="`shot-${shot.id}-take`"
                  :checked="task.selection[shot.id] === take.id"
                  :disabled="!take.selectable"
                  :data-test="`take-radio-${take.takeNo}`"
                  @change="selectTake(shot.id, take.id)"
                />
                采用
              </label>
            </div>
          </div>
        </div>

        <div class="action-row">
          <button type="button" class="btn-secondary" data-test="use-recommended" @click="useRecommendedSelection">
            一键采用推荐
          </button>
          <button
            class="btn-primary gl-btn-primary"
            :disabled="!selectionComplete || composeSubmitting || taskTerminal"
            data-test="compose-button"
            @click="composeTask"
          >
            {{ composeSubmitting ? '提交合成中…' : '合成成片' }}
          </button>
          <button type="button" class="btn-secondary" :disabled="composeSubmitting || taskTerminal" data-test="cancel-task" @click="cancelTask">
            取消任务
          </button>
        </div>
      </template>

      <template v-else-if="task && isSlideshowMode">
        <div class="action-row">
          <button
            class="btn-primary gl-btn-primary"
            :disabled="composeSubmitting || taskTerminal"
            data-test="compose-button"
            @click="composeTask"
          >
            {{ composeSubmitting ? '提交合成中…' : '合成成片（图文模式）' }}
          </button>
          <button type="button" class="btn-secondary" :disabled="composeSubmitting || taskTerminal" data-test="cancel-task" @click="cancelTask">取消任务</button>
        </div>
      </template>

      <p v-else class="field-note">任务创建中…</p>
    </section>

    <!-- Step 4: 合成成片（任务书 #64 卡9） -->
    <section v-if="stage === 'compose'" class="stage-card gl-zone fade-in">
      <header class="card-head">
        <p class="eyebrow">第四步</p>
        <h2 class="card-title">合成成片</h2>
      </header>

      <div v-if="task && task.phase === 'composing'" class="progress-area">
        <div class="progress-bar-track">
          <div class="progress-bar-fill" :style="{ width: task.progress + '%' }"></div>
        </div>
        <p class="field-note">{{ task.progress }}% — 正在合成成片（拼接/字幕/BGM）…</p>
      </div>

      <div v-else-if="task && task.phase === 'succeeded' && task.finalUrl" class="result-area">
        <video :src="task.finalUrl" controls class="result-video"></video>
        <p class="field-note">
          成片 {{ task.actualDurationSeconds }} 秒 · 预估 {{ task.estimatedCostCents }} 分 · 实结
          {{ task.actualCostCents }} 分（一口价按实际秒数多退少补）
        </p>
        <div class="action-row">
          <a :href="task.finalUrl" download class="btn-primary gl-btn-primary" target="_blank">下载成片</a>
          <button type="button" class="btn-secondary" data-test="download-srt" @click="downloadSubtitle">下载字幕（SRT）</button>
          <button class="btn-secondary" @click="handleResetAll">新建视频</button>
        </div>
      </div>

      <div v-else-if="task" class="result-area">
        <p class="error-hint">{{ task.errorMessage || taskError || '成片未就绪' }}</p>
        <div class="action-row">
          <button class="btn-secondary" @click="goBackToStoryboard">返回分镜</button>
          <button class="btn-secondary" @click="handleResetAll">新建视频</button>
        </div>
      </div>
    </section>

    <!-- 历史任务（任务书 #64 卡9，参考 VideoRecreationPanel 手风琴） -->
    <section v-if="stage !== 'upload'" class="history-section gl-zone" aria-labelledby="history-heading">
      <div class="card-head-row">
        <div>
          <p class="eyebrow">历史任务</p>
          <h3 id="history-heading" class="card-title">生成记录</h3>
        </div>
        <div class="action-row">
          <button type="button" class="btn-secondary btn-sm" :disabled="historyLoading" data-test="history-toggle" @click="toggleHistory">
            {{ historyExpanded ? '收起' : '展开' }}
          </button>
          <button v-if="historyExpanded" type="button" class="btn-secondary btn-sm" :disabled="historyLoading" @click="loadHistory(1)">
            刷新
          </button>
        </div>
      </div>

      <template v-if="historyExpanded">
        <p v-if="historyError" class="error-hint">{{ historyError }}</p>
        <p v-else-if="historyLoading && history.items.length === 0" class="field-note">正在加载历史任务…</p>
        <p v-else-if="history.items.length === 0" class="field-note">还没有成片任务。</p>
        <div v-else class="history-list">
          <article v-for="item in history.items" :key="item.id" class="history-item" data-test="history-item">
            <div class="history-row">
              <span class="shot-badge">{{ item.mode === 'slideshow' ? '图文' : '视频' }}</span>
              <strong>{{ phaseLabel(item.phase) }}</strong>
              <span class="field-note">
                {{ item.targetDurationSeconds }} 秒档
                <template v-if="item.actualDurationSeconds"> · 实际 {{ item.actualDurationSeconds }} 秒</template>
                <template v-if="item.createdAt"> · {{ formatHistoryTime(item.createdAt) }}</template>
              </span>
            </div>
            <p v-if="item.errorMessage" class="field-note">{{ item.errorMessage }}</p>
          </article>
        </div>
        <p class="field-note">共 {{ history.total }} 条</p>
      </template>
    </section>

    <Teleport to="body">
      <div v-if="lightboxSrc" class="lightbox-overlay" @click="closeLightbox">
        <img :src="lightboxSrc" class="lightbox-img" @click.stop />
        <button class="lightbox-close" @click="closeLightbox" aria-label="关闭">&times;</button>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AI_PLATFORM_DEFINITIONS } from '../../config/ai-platform-capabilities'
import BilibiliParsePanel from '../../components/BilibiliParsePanel.vue'
import DouyinParsePanel from '../../components/DouyinParsePanel.vue'
import VideoReferenceInput from './components/VideoReferenceInput.vue'
import { useBilibiliParse } from '../../composables/useBilibiliParse'
import { useBilibiliVideoAnalysis } from '../../composables/useBilibiliVideoAnalysis'
import { useDouyinParse } from '../../composables/useDouyinParse'
import { useDouyinVideoAnalysis } from '../../composables/useDouyinVideoAnalysis'
import { useVideoProduction } from '../../composables/useVideoProduction'
import { clampTargetDuration } from '../../composables/useVideoProduction'
import { formatYuan } from '../../lib/money'
import SafetyFindingsPanel from '../../components/SafetyFindingsPanel.vue'
import { buildVideoAnalysisDisplayCards } from '../../types/video-recreation'
import type { CreationHandoff } from '../../types/ai-creation'
import type { IndustryType, VideoStyle } from '../../types/video-production'
import { CAMERA_MOVES } from '../../types/video-production'

const props = defineProps<{
  creationHandoff?: CreationHandoff | null
}>()

const {
  stage, images, form, shots, safetyReport,
  storyboardLoading, error, task, taskError, composeSubmitting,
  history, historyLoading, historyError,
  canProceedToStoryboard, canAddShot, totalPlannedSeconds, narrationText,
  isSlideshowMode, ttsUnavailable, selectionComplete, taskTerminal,
  isLandscape, verticalDurationHint, estimatedPriceCents,
  anchorGenerating, anchorErrors, generateAnchorImage,
  addImages, removeImage, reorderImage,
  generateStoryboard, updateShot, removeShot, addShot, referenceShotStructure,
  goBackToUpload, beginGeneration, goBackToStoryboard,
  selectTake, useRecommendedSelection, regenerateShot, composeTask, cancelTask,
  downloadSubtitle, loadHistory,
  reset, bindCreationContext,
} = useVideoProduction()

const cameraMoves = CAMERA_MOVES
const defaultTakeCount = 2

/** 滑杆输入钳制（#65 卡1：非 5 倍数就近取档、越界封顶 15-180）。 */
function handleDurationInput(raw: string): void {
  const parsed = Number(raw)
  if (Number.isFinite(parsed)) {
    form.value = { ...form.value, targetDurationSeconds: clampTargetDuration(parsed) }
  }
}

const historyExpanded = ref(false)

function toggleHistory(): void {
  historyExpanded.value = !historyExpanded.value
  if (historyExpanded.value && history.value.items.length === 0) {
    void loadHistory(1)
  }
}

function takeStatusLabel(status: string): string {
  return { queued: '排队中', submitted: '已提交', processing: '生成中', succeeded: '已完成',
    failed: '失败', cancelled: '已取消' }[status] || status
}

/** 质检角标色阶（任务书 #66 D2）：≥80 优、60-79 提示、<60 风险——复用既有 badge token。 */
function scoreBadgeClass(score: number): string {
  if (score >= 80) return 'badge-success'
  if (score >= 60) return 'badge-warning'
  return 'badge-danger'
}

function shotLabel(shot: { takes: Array<{ status: string }> }): string {
  const active = shot.takes.filter((take) => take.status !== 'succeeded' && take.status !== 'failed'
    && take.status !== 'cancelled')
  return active.length > 0 ? '生成中' : '已完成'
}

function phaseLabel(phase: string): string {
  return { queued: '排队', generating: '生成中', voicing: '配音中', composing: '合成中',
    succeeded: '已完成', failed: '失败', cancelled: '已取消' }[phase] || phase
}

function formatHistoryTime(value: string): string {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN')
}

const hydratedCreationRevision = ref<number | null>(null)

type ReferencePlatform = 'douyin' | 'bilibili'

interface ReferenceCardOption {
  key: string
  label: string
  content: string
  selected: boolean
}

const referencePlatform = ref<ReferencePlatform>('douyin')
const referenceInput = ref('')
const hotTopicInput = ref('')
const referenceCards = ref<ReferenceCardOption[]>([])
const referenceApplied = ref(false)

const {
  extractedVideo: douyinExtractedVideo,
  loading: douyinParseLoading,
  error: douyinParseError,
  extractVideo: extractDouyinVideo,
  reset: resetDouyinParse,
} = useDouyinParse()

const {
  analysis: douyinVideoAnalysis,
  loading: douyinAnalysisLoading,
  error: douyinAnalysisError,
  analyzeVideo: analyzeDouyinVideo,
  reset: resetDouyinAnalysis,
} = useDouyinVideoAnalysis()

const {
  extractedVideo: bilibiliExtractedVideo,
  loading: bilibiliParseLoading,
  error: bilibiliParseError,
  extractVideo: extractBilibiliVideo,
  reset: resetBilibiliParse,
} = useBilibiliParse()

const {
  analysis: bilibiliVideoAnalysis,
  loading: bilibiliAnalysisLoading,
  error: bilibiliAnalysisError,
  analyzeVideo: analyzeBilibiliVideo,
  reset: resetBilibiliAnalysis,
} = useBilibiliVideoAnalysis()

watch(() => props.creationHandoff, (handoff) => {
  if (!handoff || handoff.targetView !== 'video-production' || hydratedCreationRevision.value === handoff.revision) return
  hydratedCreationRevision.value = handoff.revision
  reset()
  bindCreationContext(handoff.source.type === 'task', handoff.contextSnapshotId)
  clearOptionalInputState()
  const promptParts = [
    handoff.prefill?.topic ? `创作主题：${handoff.prefill.topic}` : '',
    handoff.prefill?.instructions || '',
  ].filter(Boolean)
  form.value = {
    ...form.value,
    targetPlatform: handoff.platformId,
    shopName: handoff.prefill?.storeName || '',
    shopAddress: handoff.prefill?.address || '',
    shopDescription: handoff.prefill?.storeDescription || '',
    customPrompt: promptParts.join('\n'),
  }
}, { immediate: true })

const MAX_IMAGES = 9
// 朋友圈的视频形式是 video-text（PRD §4.4），同样落到视频制作。
const videoPlatforms = AI_PLATFORM_DEFINITIONS.filter((item) =>
  item.forms.some((form) => form.id === 'video' || form.id === 'video-text'))

const steps = [
  { key: 'upload' as const, label: '上传素材' },
  { key: 'storyboard' as const, label: '编辑分镜' },
  { key: 'generate' as const, label: '生成与挑选' },
  { key: 'compose' as const, label: '合成成片' },
]

const industryTypes: IndustryType[] = ['餐饮', '零售', '美业', '健身', '教育培训', '其他']
const videoStyles: VideoStyle[] = ['烟火纪实', '治愈清新', '高级暗调', '数字人口播', '复古胶片']

const isDragging = ref(false)
const dragIndex = ref<number | null>(null)
const lightboxSrc = ref('')

function stepIndex(s: typeof stage.value): number {
  return steps.findIndex((step) => step.key === s)
}

function handleFileSelect(event: Event): void {
  const input = event.target as HTMLInputElement
  if (input.files) {
    addImages(Array.from(input.files))
    input.value = ''
  }
}

function handleDrop(event: DragEvent): void {
  isDragging.value = false
  if (event.dataTransfer?.files) {
    addImages(Array.from(event.dataTransfer.files))
  }
}

function onDropReorder(toIndex: number): void {
  if (dragIndex.value !== null && dragIndex.value !== toIndex) {
    reorderImage(dragIndex.value, toIndex)
  }
  dragIndex.value = null
}

function openLightbox(src: string): void {
  lightboxSrc.value = src
}

function closeLightbox(): void {
  lightboxSrc.value = ''
}

// ---- 可选输入方式：参考视频链接 / 热点主题 ----

const referenceParseLoading = computed(() => {
  return referencePlatform.value === 'douyin' ? douyinParseLoading.value : bilibiliParseLoading.value
})

const activeReferenceAnalysis = computed(() => {
  return referencePlatform.value === 'douyin' ? douyinVideoAnalysis.value : bilibiliVideoAnalysis.value
})

const hasSelectedReferenceCards = computed(() => referenceCards.value.some((card) => card.selected))

function toggleReferenceCard(key: string): void {
  const card = referenceCards.value.find((c) => c.key === key)
  if (card) card.selected = !card.selected
}

watch(activeReferenceAnalysis, (analysis) => {
  referenceCards.value = buildVideoAnalysisDisplayCards(analysis)
    .filter((card) => !card.isFallback)
    .map((card) => ({ key: card.key, label: card.label, content: card.content, selected: true }))
})

function handleSwitchReferencePlatform(platform: ReferencePlatform): void {
  referencePlatform.value = platform
}

async function handleExtractReference(): Promise<void> {
  referenceCards.value = []
  referenceApplied.value = false

  if (referencePlatform.value === 'douyin') {
    resetDouyinAnalysis()
    const data = await extractDouyinVideo(referenceInput.value)
    if (!data) return
    await analyzeDouyinVideo(data.proxyVideoUrl)
    return
  }

  resetBilibiliAnalysis()
  const data = await extractBilibiliVideo(referenceInput.value)
  if (!data) return
  await analyzeBilibiliVideo(data.proxyVideoUrl)
}

async function handleRetryDouyinAnalysis(): Promise<void> {
  const proxyVideoUrl = douyinExtractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) return
  await analyzeDouyinVideo(proxyVideoUrl)
}

async function handleRetryBilibiliAnalysis(): Promise<void> {
  const proxyVideoUrl = bilibiliExtractedVideo.value?.proxyVideoUrl
  if (!proxyVideoUrl) return
  await analyzeBilibiliVideo(proxyVideoUrl)
}

function handleClearReference(): void {
  referenceInput.value = ''
  referenceCards.value = []
  referenceShotStructure.value = null
  resetDouyinAnalysis()
  resetBilibiliAnalysis()
  resetDouyinParse()
  resetBilibiliParse()
}

function appendToCustomPrompt(text: string): void {
  const existing = form.value.customPrompt.trim()
  form.value.customPrompt = existing ? `${existing}\n${text}` : text
}

function applyReferenceToPrompt(): void {
  const selectedCards = referenceCards.value.filter((card) => card.selected)
  if (selectedCards.length === 0) return

  const referenceText = [
    '参考视频分析产出（仅为创作建议）：',
    ...selectedCards.map((card) => `【${card.label}】\n${card.content}`),
  ].join('\n')

  appendToCustomPrompt(referenceText)
  referenceApplied.value = true
  // 任务书 #66 E1：结构化参考随「带入」透传（仅参考节奏与结构；热点话题带入不携带）
  const analysis = activeReferenceAnalysis.value
  referenceShotStructure.value = analysis?.shotStructure?.length
    ? { shotStructure: analysis.shotStructure, hookAtSeconds: analysis.hookAtSeconds }
    : null
}

function applyHotTopicToPrompt(): void {
  const topic = hotTopicInput.value.trim()
  if (!topic) return
  appendToCustomPrompt(`创作主题：${topic}`)
  referenceApplied.value = true
}

function clearOptionalInputState(): void {
  referenceInput.value = ''
  hotTopicInput.value = ''
  referenceCards.value = []
  referenceApplied.value = false
  referenceShotStructure.value = null
  resetDouyinAnalysis()
  resetBilibiliAnalysis()
  resetDouyinParse()
  resetBilibiliParse()
}

function handleResetAll(): void {
  reset()
  clearOptionalInputState()
}
</script>

<style scoped>
.video-production {
  max-width: 800px;
  margin: 0 auto;
  padding: var(--space-lg) var(--space-md);
}

.steps-bar {
  display: flex;
  justify-content: center;
  gap: var(--space-xl);
  margin-bottom: var(--space-lg);
}

.step-dot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  opacity: 0.4;
  transition: opacity 0.3s;
}

.step-active {
  opacity: 1;
}

.step-done {
  opacity: 0.7;
}

.step-num {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-pill);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  background: var(--color-surface-strong);
  border: 1px solid var(--color-border);
  transition: background 0.3s, border-color 0.3s, color 0.3s;
}

/* 激活态用淡底描边而非实心紫：与表单区的权重失衡来自步骤条过度抢眼（视觉审查 ⑮） */
.step-active .step-num {
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  border-color: color-mix(in srgb, var(--color-accent) 45%, transparent);
  color: var(--color-accent);
}

.step-done .step-num {
  background: color-mix(in srgb, var(--color-success) 14%, transparent);
  border-color: color-mix(in srgb, var(--color-success) 35%, transparent);
  color: var(--color-success);
}

.step-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.step-active .step-label {
  color: var(--color-text);
  font-weight: 600;
}

.upload-area {
  margin-bottom: var(--space-md);
}

.hidden-input {
  display: none;
}

.drop-zone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px;
  border: 2px dashed var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
  color: var(--color-text-muted);
  font-size: 14px;
}

.drop-zone:hover,
.drop-zone-active {
  border-color: var(--color-accent);
  background: color-mix(in srgb, var(--color-accent) 5%, transparent);
}

.preview-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
  margin-bottom: var(--space-md);
}

.preview-item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  cursor: grab;
}

.preview-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preview-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 18px;
  height: 18px;
  border-radius: var(--radius-pill);
  background: var(--color-overlay);
  color: var(--color-on-accent);
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  line-height: 1;
}

.preview-order {
  position: absolute;
  bottom: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: var(--radius-pill);
  background: var(--color-overlay);
  color: var(--color-on-accent);
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.input-methods {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
  padding: 12px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border);
}

.input-method-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  color: inherit;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  padding: 0;
}

.input-method-toggle:hover {
  color: var(--color-accent);
}

.toggle-caret {
  color: var(--color-text-muted);
  font-size: 12px;
}

.reference-area,
.topic-area {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 10px;
  padding: 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-furrow);
}

.reference-platform-switch {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-sm);
  background: var(--surface-hover);
  width: fit-content;
}

.reference-platform-tab {
  padding: 4px 14px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-muted);
  font-size: 13px;
  cursor: pointer;
}

.reference-platform-tab-active {
  background: var(--color-accent);
  color: var(--color-on-accent);
}

.reference-input,
.topic-input {
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--surface-hover);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
}

.reference-input:focus,
.topic-input:focus {
  outline: none;
  border-color: var(--color-accent);
}

.action-row-start {
  justify-content: flex-start;
}

.reference-apply {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
}

.reference-card-option {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  cursor: pointer;
}

.reference-applied-hint,
.reference-applied-note {
  color: color-mix(in srgb, var(--color-success) 90%, transparent);
}

.topic-row {
  display: flex;
  gap: 8px;
}

.topic-input {
  flex: 1;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-field label {
  font-size: 13px;
  color: var(--color-text-muted);
}

.form-field input,
.form-field select,
.form-field textarea {
  min-height: 38px;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--surface-hover);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
}

.form-field input:focus,
.form-field select:focus,
.form-field textarea:focus {
  outline: none;
  border-color: var(--color-accent);
}

.form-field-wide {
  grid-column: 1 / -1;
}

.script-thumbnails {
  display: flex;
  gap: var(--space-xs);
  margin-bottom: var(--space-md);
  overflow-x: auto;
}

.script-thumb {
  width: 56px;
  height: 56px;
  object-fit: cover;
  border-radius: var(--radius-sm);
  cursor: pointer;
  flex-shrink: 0;
}

.stream-area {
  position: relative;
  margin-bottom: var(--space-md);
}

.stream-textarea {
  width: 100%;
  padding: 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  background: var(--surface-hover);
  color: inherit;
  font-size: 14px;
  font-family: inherit;
  line-height: 1.6;
  resize: vertical;
}

.stream-textarea:focus {
  outline: none;
  border-color: var(--color-accent);
}

.stream-loading {
  border-color: var(--color-accent);
}

.stream-badge {
  position: absolute;
  top: 8px;
  right: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-accent);
}

.stream-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-pill);
  background: var(--color-accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.progress-area {
  margin-bottom: var(--space-md);
}

.progress-bar-track {
  height: 6px;
  border-radius: var(--radius-xs);
  background: var(--color-border-hover);
  overflow: hidden;
  margin-bottom: 8px;
}

.progress-bar-fill {
  height: 100%;
  border-radius: var(--radius-xs);
  background: var(--color-accent);
  transition: width 0.3s ease;
}

.result-area {
  text-align: center;
}

.result-video {
  width: 100%;
  max-width: 480px;
  border-radius: var(--radius-md);
  margin-bottom: var(--space-md);
}

.error-hint {
  color: var(--color-danger);
  font-size: 13px;
  margin-bottom: var(--space-sm);
}

.action-row {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  text-decoration: none;
}

.btn-back {
  display: flex;
  align-items: center;
  gap: 4px;
  background: none;
  border: none;
  color: var(--color-text-muted);
  font-size: 13px;
  cursor: pointer;
  padding: 0;
}

.btn-back:hover {
  color: inherit;
}

.card-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.card-head {
  margin-bottom: var(--space-md);
}

.eyebrow {
  font-size: 12px;
  color: var(--color-accent);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}

.field-note {
  font-size: 13px;
  color: var(--color-text-muted);
}

.lightbox-overlay {
  position: fixed;
  inset: 0;
  background: var(--color-overlay);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  cursor: pointer;
}

.lightbox-img {
  max-width: 90vw;
  max-height: 90vh;
  border-radius: var(--radius-sm);
  cursor: default;
}

.lightbox-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-pill);
  background: var(--color-border-hover);
  color: var(--color-on-accent);
  border: none;
  font-size: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.fade-in {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

/* 任务书 #64 卡4：逐镜卡片（参考 CardSeriesPanel 形态，样式走 .gl-field/.gl-tile 全局层 + 少量布局） */
.shot-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  margin-bottom: var(--space-md);
}

.shot-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.shot-head .field-note {
  margin: 0 0 0 auto;
}

.shot-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  background: color-mix(in srgb, var(--color-accent) 16%, transparent);
  color: var(--color-accent);
}

.shot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-sm) var(--space-md);
}

.shot-grid .form-field.wide-col {
  grid-column: 1 / -1;
}

.shot-visual-preview {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.duration-field input[type='range'] {
  width: 100%;
  accent-color: var(--color-accent);
}

/* #65 卡3：分辨率标签 / 竖版时长软提示 / 补图角标 */
.resolution-tag {
  display: inline-flex;
  align-items: center;
  margin-left: var(--space-xs);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  background: color-mix(in srgb, var(--color-accent) 12%, transparent);
  color: var(--color-accent);
}

.duration-hint {
  color: var(--color-warning, var(--color-text-muted));
}

.anchor-preview {
  position: relative;
  display: inline-flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.anchor-badge {
  position: absolute;
  bottom: 4px;
  left: 4px;
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  font-size: var(--text-xs);
  font-weight: 600;
  background: var(--color-overlay);
  color: var(--color-on-accent);
}

.anchor-generate {
  align-self: flex-start;
}

.anchor-error {
  color: var(--color-danger);
}

.mode-notice {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  background: color-mix(in srgb, var(--color-warning, var(--color-accent)) 14%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-warning, var(--color-accent)) 35%, transparent);
}

/* 卡9：take 矩阵 / 成片结果 / 历史区 */
.take-matrix {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--space-sm);
}

.take-score-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-xs);
}

.take-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  padding: var(--space-xs);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
}

.take-card.take-selected {
  border-color: var(--color-accent);
}

.take-video {
  width: 100%;
  aspect-ratio: 9 / 16;
  max-height: 260px;
  object-fit: contain;
  background: var(--color-surface-strong);
  border-radius: var(--radius-sm);
}

.take-placeholder {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  aspect-ratio: 9 / 16;
  max-height: 260px;
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
}

.take-pick {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-sm);
}

.take-pick-disabled {
  color: var(--color-text-secondary);
  opacity: 0.6;
}

.history-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-md);
  border-radius: var(--radius-lg);
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.history-item {
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
}

.history-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.btn-sm {
  font-size: var(--text-xs);
  padding: 4px 10px;
}

@media (max-width: 720px) {
  .shot-grid {
    grid-template-columns: 1fr;
  }
}
</style>
