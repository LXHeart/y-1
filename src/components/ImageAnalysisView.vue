<template>
  <div class="image-analysis">
    <section class="image-shell">
      <article class="control-card glass-card">
        <header class="section-head">
          <div>
            <p class="section-kicker">图片评价</p>
            <h2 class="section-title">上传图片后生成更自然的评价文案</h2>
          </div>
          <p class="section-note">支持 JPG、PNG、WebP，最多 6 张，每张不超过 5 MB。</p>
        </header>

        <label
          class="drop-zone"
          :class="{ 'drop-zone-active': isDragging }"
          for="image-analysis-input"
          tabindex="0"
          @dragenter.prevent="isDragging = true"
          @dragover.prevent
          @dragleave.prevent="isDragging = false"
          @drop.prevent="handleDrop"
          @keydown.enter.prevent="openFilePicker"
          @keydown.space.prevent="openFilePicker"
        >
          <div class="drop-zone-icon" aria-hidden="true">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
              <path d="M12 5v14M5 12l7-7 7 7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <div class="drop-zone-copy">
            <p class="drop-zone-title">点击上传或拖入图片</p>
            <p class="drop-zone-text">建议先放主图，再补充细节图，生成结果会更完整。</p>
          </div>
          <input
            id="image-analysis-input"
            ref="fileInput"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            class="sr-only"
            @change="handleFileInput"
          >
        </label>

        <p v-if="uploadError" class="error-text">{{ uploadError }}</p>

        <div v-if="images.length" class="selected-images">
          <div class="selected-images-head">
            <p class="selected-images-title">已选图片</p>
            <p class="selected-images-count">{{ images.length }}/6</p>
          </div>
          <ul class="thumb-list">
            <li v-for="(img, i) in images" :key="i" class="thumb-item" @click="previewImage(i)">
              <img :src="img.preview" :alt="`图片 ${i + 1}`" class="thumb-img" />
              <button class="thumb-remove" type="button" @click.stop="removeImage(i)" aria-label="删除图片">&times;</button>
            </li>
          </ul>
        </div>

        <div class="field-block">
          <div class="field-block-head">
            <p class="field-block-title">生成偏好</p>
            <p class="field-block-copy">先选目标平台，再决定大致字数。填 0 则不限制字数。</p>
          </div>

          <div class="settings-row">
            <div class="platform-toggle" role="tablist" aria-label="评价平台">
              <button
                type="button"
                class="platform-btn"
                :class="{ 'platform-btn-active': platform === 'taobao' }"
                :disabled="loading"
                @click="platform = 'taobao'"
              >淘宝</button>
              <button
                type="button"
                class="platform-btn"
                :class="{ 'platform-btn-active': platform === 'dianping' }"
                :disabled="loading"
                @click="platform = 'dianping'"
              >大众点评</button>
            </div>

            <label class="field-group-inline">
              <span class="field-label">目标字数</span>
              <input
                v-model.number="reviewLength"
                class="field-input-sm"
                type="number"
                min="0"
                max="300"
                step="1"
                inputmode="numeric"
                :disabled="loading"
              >
            </label>
          </div>
        </div>

        <div class="field-block">
          <div class="field-block-head">
            <p class="field-block-title">补充感受</p>
            <p class="field-block-copy">可补充你想强调的细节，比如包装、分量、口感、服务体验。</p>
          </div>

          <textarea
            v-model="feelings"
            class="field-textarea"
            rows="3"
            maxlength="200"
            placeholder="例如：包装挺干净、分量看着很足、实物比图片还精致…"
            :disabled="loading"
          ></textarea>
        </div>

        <div class="action-row">
          <button
            class="btn-primary"
            :disabled="loading || images.length === 0"
            @click="startGeneration"
          >
            {{ loading ? '生成中…' : '生成评价' }}
          </button>
          <button class="btn-secondary" :disabled="loading" @click="handleReset">
            清空
          </button>
          <button v-if="generationStage === 'drafting'" class="btn-secondary" @click="cancelAnalysis">
            取消
          </button>
        </div>
      </article>

      <section class="preview-column">
        <section v-if="generationStage === 'drafting'" class="progress-card glass-card fade-in">
          <header class="result-head">
            <div>
              <p class="section-kicker">生成进度</p>
              <h3 class="result-title">正在逐步整理图片评价文案</h3>
            </div>
            <span v-if="currentProgress?.attempt && currentProgress?.totalAttempts" class="progress-count">
              {{ currentProgress.attempt }}/{{ currentProgress.totalAttempts }}
            </span>
          </header>

          <p class="status-copy">
            {{ currentProgress?.message || '正在准备生成…' }}
          </p>

          <ol class="progress-list">
            <li
              v-for="(item, index) in progressEvents"
              :key="`${item.stage}-${item.attempt ?? index}-${index}`"
              class="progress-item"
            >
              <span class="progress-dot" aria-hidden="true"></span>
              <div class="progress-copy">
                <div class="progress-line">
                  <p class="progress-title">{{ getStageLabel(item.stage) }}</p>
                  <span v-if="getEventDurationLabel(item)" class="progress-duration">{{ getEventDurationLabel(item) }}</span>
                </div>
                <p class="progress-text">{{ item.message }}</p>
              </div>
            </li>
          </ol>
        </section>

        <section v-else-if="showStepLoading" class="progress-card glass-card fade-in">
          <header class="result-head">
            <div>
              <p class="section-kicker">生成进度</p>
              <h3 class="result-title">{{ loadingLabel }}</h3>
            </div>
          </header>
          <p class="status-copy step-loading-copy">
            <svg class="spin-icon" width="20" height="20" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
            {{ loadingDescription }}
          </p>
        </section>

        <section v-else-if="error && !result" class="status-card status-card-error glass-card fade-in">
          <p class="status-title">生成失败</p>
          <p class="status-copy">{{ error }}</p>
        </section>

        <section v-else-if="result && isStepReview" class="result-card glass-card fade-in">
          <header class="result-head">
            <div>
              <p class="section-kicker">{{ stepLabel }}</p>
              <h3 class="result-title">{{ stepDescription }}</h3>
            </div>
            <div v-if="!isEditing" class="result-actions">
              <button class="btn-secondary" type="button" @click="startEditing">编辑</button>
            </div>
          </header>

          <p v-if="error" class="error-text">{{ error }}</p>

          <div v-if="saveStyleSuccess && !isEditing" class="save-style-success">
            <p>风格偏好已保存，下次生成评价时会自动应用你的个人风格。</p>
          </div>

          <div v-if="isEditing" class="result-block" :class="{ 'edit-saving': savingStyle }">
            <div v-if="result.title !== undefined" class="edit-field">
              <label class="result-label" for="edit-title-step">标题</label>
              <input id="edit-title-step" v-model="editTitle" class="field-input-sm edit-input-full" placeholder="输入标题" :disabled="savingStyle">
            </div>
            <div class="edit-field">
              <label class="result-label" for="edit-review-step">评价内容</label>
              <textarea id="edit-review-step" v-model="editReview" class="field-textarea" rows="6" :disabled="savingStyle"></textarea>
            </div>
            <div v-if="editTags.length > 0 || result.tags" class="edit-field">
              <label class="result-label">标签</label>
              <div class="edit-tags">
                <span v-for="(tag, i) in editTags" :key="i" class="edit-tag-item">
                  {{ tag }}
                  <button class="edit-tag-remove" type="button" :disabled="savingStyle" @click="removeEditTag(i)">&times;</button>
                </span>
                <input
                  v-model="newTagInput"
                  class="field-input-sm edit-tag-input"
                  placeholder="添加标签"
                  :disabled="savingStyle"
                  @keydown.enter.prevent="addEditTag"
                >
              </div>
            </div>
            <div class="edit-actions">
              <button class="btn-primary" :disabled="savingStyle" @click="handleApplyEditsLocally">直接保存</button>
              <button class="btn-save-style" :disabled="savingStyle" @click="handleSaveStyleMemory">
                <svg v-if="savingStyle" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                {{ savingStyle ? '保存中…' : '记忆风格并保存' }}
              </button>
              <button class="btn-secondary" :disabled="savingStyle" @click="cancelEditing">取消</button>
            </div>
            <p v-if="saveStyleError" class="error-text">{{ saveStyleError }}</p>
          </div>

          <div v-else class="result-block">
            <h4 v-if="result.title" class="result-label">标题</h4>
            <p v-if="result.title" class="result-text result-emphasis">{{ result.title }}</p>
            <h4 class="result-label">评价内容</h4>
            <p class="result-text">{{ result.review }}</p>
          </div>

          <div v-if="!isEditing" class="step-actions">
            <div v-if="generationStage === 'draft-review'" class="step-nav">
              <button class="btn-primary" @click="proceedToOptimize">下一步：润色优化</button>
            </div>
            <div v-else-if="generationStage === 'optimize-review'" class="step-nav">
              <button class="btn-primary" @click="proceedToStyleRefine">下一步：风格偏好优化</button>
            </div>
          </div>

          <section v-if="!isEditing && hasGenerationSteps" class="result-steps">
            <div class="result-steps-head">
              <p class="result-label">生成步骤</p>
            </div>
            <ol class="progress-list">
              <li
                v-for="(item, index) in progressEvents"
                :key="`step-${item.stage}-${item.attempt ?? index}-${index}`"
                class="progress-item"
                :class="{ 'progress-item-clickable': !!stepResults[item.stage] }"
                @click="stepResults[item.stage] && selectStepResult(item.stage)"
              >
                <span class="progress-dot" aria-hidden="true"></span>
                <div class="progress-copy">
                  <div class="progress-line">
                    <p class="progress-title">{{ getStageLabel(item.stage) }}</p>
                    <span v-if="getEventDurationLabel(item)" class="progress-duration">{{ getEventDurationLabel(item) }}</span>
                  </div>
                  <p class="progress-text">{{ item.message }}</p>
                </div>
              </li>
            </ol>
          </section>

          <div v-if="!isEditing && result.tags && result.tags.length" class="result-tags-wrap">
            <h4 class="result-label">标签</h4>
            <div class="result-tags">
              <span v-for="tag in result.tags" :key="tag" class="result-tag">{{ tag }}</span>
            </div>
          </div>
        </section>

        <section v-else-if="result" class="result-card glass-card fade-in">
          <header class="result-head">
            <div>
              <p class="section-kicker">输出结果</p>
              <h3 class="result-title">已生成可直接复制的评价文案</h3>
            </div>
            <div class="result-actions">
              <button v-if="!isEditing" class="btn-copy" @click="copyReview">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                {{ copyLabel }}
              </button>
              <button v-if="!isEditing && hasGenerationSteps" class="btn-secondary" type="button" @click="toggleGenerationSteps">
                {{ generationStepToggleLabel }}
              </button>
              <button v-if="!isEditing" class="btn-secondary" type="button" @click="startEditing">编辑</button>
              <button class="btn-export-feishu" :disabled="exporting || isEditing" @click="handleExportToFeishu">
                <svg v-if="exporting" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                {{ exporting ? '导出中…' : '导出到飞书' }}
              </button>
              <button v-if="!isEditing" class="btn-secondary" type="button" :disabled="loadingPreferences" @click="toggleStylePreferences">
                {{ showStylePreferences ? '收起风格偏好' : '查看风格偏好' }}
              </button>
            </div>
          </header>

          <div v-if="exportError" class="export-error">
            <p>{{ exportError }}</p>
          </div>

          <div v-if="exportedDocUrl" class="export-success">
            <p>已导出到飞书文档：<a :href="exportedDocUrl" target="_blank" rel="noopener">{{ exportedDocTitle }}</a></p>
            <button class="btn-copy-link" @click="copyDocLink">{{ copyLinkLabel }}</button>
          </div>

          <!-- Edit mode -->
          <div v-if="isEditing" class="result-block" :class="{ 'edit-saving': savingStyle }">
            <div v-if="result.title !== undefined" class="edit-field">
              <label class="result-label" for="edit-title">标题</label>
              <input id="edit-title" v-model="editTitle" class="field-input-sm edit-input-full" placeholder="输入标题" :disabled="savingStyle">
            </div>
            <div class="edit-field">
              <label class="result-label" for="edit-review">评价内容</label>
              <textarea id="edit-review" v-model="editReview" class="field-textarea" rows="6" :disabled="savingStyle"></textarea>
            </div>
            <div v-if="editTags.length > 0 || result.tags" class="edit-field">
              <label class="result-label">标签</label>
              <div class="edit-tags">
                <span v-for="(tag, i) in editTags" :key="i" class="edit-tag-item">
                  {{ tag }}
                  <button class="edit-tag-remove" type="button" :disabled="savingStyle" @click="removeEditTag(i)">&times;</button>
                </span>
                <input
                  v-model="newTagInput"
                  class="field-input-sm edit-tag-input"
                  placeholder="添加标签"
                  :disabled="savingStyle"
                  @keydown.enter.prevent="addEditTag"
                >
              </div>
            </div>
            <div class="edit-actions">
              <button class="btn-primary" :disabled="savingStyle" @click="handleApplyEditsLocally">直接保存</button>
              <button class="btn-save-style" :disabled="savingStyle" @click="handleSaveStyleMemory">
                <svg v-if="savingStyle" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                {{ savingStyle ? '保存中…' : '记忆风格并保存' }}
              </button>
              <button class="btn-secondary" :disabled="savingStyle" @click="cancelEditing">取消</button>
            </div>
            <p v-if="saveStyleError" class="error-text">{{ saveStyleError }}</p>
          </div>

          <!-- Display mode -->
          <div v-else class="result-block">
            <h4 v-if="result.title" class="result-label">标题</h4>
            <p v-if="result.title" class="result-text result-emphasis">{{ result.title }}</p>

            <h4 class="result-label">评价内容</h4>
            <p class="result-text">{{ result.review }}</p>
          </div>

          <section v-if="showGenerationSteps && hasGenerationSteps" class="result-steps">
            <div class="result-steps-head">
              <p class="result-label">本次生成步骤</p>
              <p v-if="result.runId" class="result-steps-run-id">运行 ID：{{ result.runId }}</p>
            </div>
            <ol class="progress-list">
              <li
                v-for="(item, index) in progressEvents"
                :key="`result-${item.stage}-${item.attempt ?? index}-${index}`"
                class="progress-item"
                :class="{ 'progress-item-clickable': !!stepResults[item.stage] }"
                @click="stepResults[item.stage] && selectStepResult(item.stage)"
              >
                <span class="progress-dot" aria-hidden="true"></span>
                <div class="progress-copy">
                  <div class="progress-line">
                    <p class="progress-title">{{ getStageLabel(item.stage) }}</p>
                    <span v-if="getEventDurationLabel(item)" class="progress-duration">{{ getEventDurationLabel(item) }}</span>
                  </div>
                  <p class="progress-text">{{ item.message }}</p>
                </div>
              </li>
            </ol>
          </section>

          <div v-if="saveStyleSuccess && !isEditing" class="save-style-success">
            <p>风格偏好已保存，下次生成评价时会自动应用你的个人风格。</p>
          </div>

          <div v-if="!isEditing && result.tags && result.tags.length" class="result-tags-wrap">
            <h4 class="result-label">标签</h4>
            <div class="result-tags">
              <span v-for="tag in result.tags" :key="tag" class="result-tag">{{ tag }}</span>
            </div>
          </div>
        </section>

        <section v-else class="empty-card glass-card">
          <p class="section-kicker">等待生成</p>
          <h2 class="empty-title">上传图片后，这里会显示评价结果</h2>
          <p class="empty-copy">生成完成后可直接复制标题、正文和标签，用于淘宝或大众点评发布。</p>
        </section>
      </section>
    </section>

    <Teleport to="body">
      <div v-if="showOversizedDialog" class="oversized-overlay" role="dialog" aria-modal="true">
        <div class="oversized-modal glass-card">
          <header class="oversized-head">
            <p class="section-kicker">图片过大</p>
            <h3 class="oversized-title">以下图片超过 5 MB 限制</h3>
          </header>

          <ul class="oversized-list">
            <li v-for="file in oversizedFiles" :key="file.name" class="oversized-item">
              <span class="oversized-name">{{ file.name }}</span>
              <span class="oversized-size">{{ formatFileSize(file.size) }}</span>
            </li>
          </ul>

          <div class="oversized-actions">
            <button class="btn-primary" :disabled="compressing" @click="compressOversizedImages">
              <svg v-if="compressing" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
              {{ compressing ? '压缩中…' : '自动压缩' }}
            </button>
            <button class="btn-secondary" :disabled="compressing" @click="removeOversizedImages">跳过这些图片</button>
            <button class="btn-secondary" :disabled="compressing" @click="cancelOversizedImages">取消</button>
          </div>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div
        v-if="previewIndex !== null"
        class="preview-overlay"
        role="dialog"
        aria-modal="true"
        tabindex="-1"
        @click="closePreview"
        @keydown="handlePreviewKeydown"
      >
        <img
          :src="images[previewIndex]?.preview"
          :alt="`图片 ${previewIndex + 1}`"
          class="preview-img"
          @click.stop
        />
        <button class="preview-close" type="button" aria-label="关闭预览" @click.stop="closePreview">&times;</button>
        <div v-if="images.length > 1" class="preview-nav">
          <button
            class="preview-nav-btn"
            type="button"
            :disabled="previewIndex <= 0"
            aria-label="上一张"
            @click.stop="previewIndex = (previewIndex ?? 1) - 1"
          >&lsaquo;</button>
          <span class="preview-count">{{ (previewIndex ?? 0) + 1 }} / {{ images.length }}</span>
          <button
            class="preview-nav-btn"
            type="button"
            :disabled="previewIndex >= images.length - 1"
            aria-label="下一张"
            @click.stop="previewIndex = (previewIndex ?? 0) + 1"
          >&rsaquo;</button>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div v-if="showStylePreferences" class="preferences-overlay" role="dialog" aria-modal="true" @click="toggleStylePreferences">
        <div class="preferences-modal glass-card" @click.stop>
          <header class="preferences-modal-head">
            <div>
              <p class="section-kicker">风格偏好</p>
              <h3 class="preferences-modal-title">我的风格偏好</h3>
            </div>
            <button class="preferences-modal-close" type="button" @click="toggleStylePreferences">&times;</button>
          </header>
          <div v-if="loadingPreferences" class="style-preferences-loading">加载中…</div>
          <div v-else-if="stylePreferences.length === 0" class="style-preferences-empty">
            <p>暂无保存的风格偏好。编辑评价后选择"记忆风格并保存"即可积累个人风格。</p>
          </div>
          <template v-else>
            <!-- Optimize preview mode -->
            <template v-if="optimizedPreferences">
              <div class="optimize-diff-header">
                <p class="optimize-diff-title">优化预览（{{ stylePreferences.length }} 条 → {{ optimizedPreferences.length }} 条）</p>
              </div>
              <div class="optimize-diff-section">
                <p class="optimize-diff-label optimize-diff-removed">将被替换的旧规则</p>
                <ul class="optimize-diff-list">
                  <li v-for="(rule, i) in stylePreferences" :key="'old-' + i" class="optimize-diff-item optimize-diff-item-old">
                    <span class="optimize-diff-marker">-</span>
                    <span>{{ rule }}</span>
                  </li>
                </ul>
              </div>
              <div class="optimize-diff-section">
                <p class="optimize-diff-label optimize-diff-added">优化后的新规则</p>
                <ul class="optimize-diff-list">
                  <li v-for="(rule, i) in optimizedPreferences" :key="'new-' + i" class="optimize-diff-item optimize-diff-item-new">
                    <span class="optimize-diff-marker">+</span>
                    <span>{{ rule }}</span>
                  </li>
                </ul>
              </div>
              <div class="optimize-diff-actions">
                <button class="btn-primary" :disabled="savingPreference" @click="confirmOptimizedPreferences">
                  <svg v-if="savingPreference" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                  {{ savingPreference ? '保存中…' : '确认替换' }}
                </button>
                <button class="btn-secondary" :disabled="savingPreference" @click="cancelOptimizePreferences">取消</button>
              </div>
            </template>

            <!-- Normal list mode -->
            <template v-else>
              <div class="optimize-actions">
                <button class="btn-secondary" :disabled="optimizingPreferences" @click="optimizePreferences">
                  <svg v-if="optimizingPreferences" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/></svg>
                  {{ optimizingPreferences ? '优化中…' : '自动优化' }}
                </button>
                <span class="preference-count-hint">{{ stylePreferences.length }} / 100</span>
              </div>
              <p v-if="optimizeError" class="error-text">{{ optimizeError }}</p>
              <ul class="style-preferences-list">
                <li v-for="(rule, i) in paginatedPreferences" :key="paginatedStartIndex + i" class="style-preference-item">
                  <template v-if="editingPreferenceIndex === paginatedStartIndex + i">
                    <input
                      v-model="editingPreferenceValue"
                      class="field-input-sm preference-edit-input"
                      :disabled="savingPreference"
                      @keydown.enter.prevent="confirmEditingPreference"
                    >
                    <button class="btn-secondary btn-sm" :disabled="savingPreference" @click="confirmEditingPreference">确认</button>
                    <button class="btn-secondary btn-sm" @click="cancelEditingPreference">取消</button>
                  </template>
                  <template v-else>
                    <span class="preference-text">{{ rule }}</span>
                    <div class="preference-actions">
                      <button class="preference-action-btn" type="button" :disabled="optimizingPreferences" @click="startEditingPreference(paginatedStartIndex + i)" aria-label="编辑">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                      </button>
                      <button class="preference-action-btn preference-delete-btn" type="button" :disabled="optimizingPreferences" @click="deleteStylePreference(paginatedStartIndex + i)" aria-label="删除">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                      </button>
                    </div>
                  </template>
                </li>
              </ul>
              <div v-if="totalPreferencePages > 1" class="preference-pagination">
                <button class="btn-secondary btn-sm" :disabled="preferencePage <= 1" @click="preferencePage = Math.max(1, preferencePage - 1)">上一页</button>
                <span class="preference-page-info">{{ preferencePage }} / {{ totalPreferencePages }}</span>
                <button class="btn-secondary btn-sm" :disabled="preferencePage >= totalPreferencePages" @click="preferencePage = Math.min(totalPreferencePages, preferencePage + 1)">下一页</button>
              </div>
            </template>
          </template>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div v-if="selectedStepResult" class="step-result-overlay" role="dialog" aria-modal="true" @click="clearStepResult">
        <div class="step-result-modal glass-card" @click.stop>
          <header class="step-result-modal-head">
            <div>
              <p class="section-kicker">{{ getStageLabel(selectedStepResult.stage as ImageAnalysisProgressStage) }}</p>
              <h3 class="step-result-modal-title">该步骤生成的内容</h3>
            </div>
            <button class="preferences-modal-close" type="button" @click="clearStepResult">&times;</button>
          </header>
          <div class="step-result-content result-block">
            <h4 v-if="selectedStepResult.result.title" class="result-label">标题</h4>
            <p v-if="selectedStepResult.result.title" class="result-text result-emphasis">{{ selectedStepResult.result.title }}</p>
            <h4 class="result-label">评价内容</h4>
            <p class="result-text">{{ selectedStepResult.result.review }}</p>
          </div>
          <div v-if="selectedStepResult.result.tags?.length" class="result-tags-wrap">
            <h4 class="result-label">标签</h4>
            <div class="result-tags">
              <span v-for="tag in selectedStepResult.result.tags" :key="tag" class="result-tag">{{ tag }}</span>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useImageAnalysis } from '../composables/useImageAnalysis'
import type { ImageAnalysisProgressEvent, ImageAnalysisProgressStage } from '../types/image-analysis'

const {
  images,
  result,
  reviewLength,
  feelings,
  platform,
  loading,
  generationStage,
  error,
  progressEvents,
  currentProgress,
  exporting,
  exportError,
  exportedDocUrl,
  exportedDocTitle,
  isEditing,
  editTitle,
  editReview,
  editTags,
  savingStyle,
  saveStyleError,
  saveStyleSuccess,
  stylePreferences,
  hasStylePreferences,
  loadingPreferences,
  showStylePreferences,
  oversizedFiles,
  showOversizedDialog,
  compressing,
  preferencePage,
  totalPreferencePages,
  paginatedPreferences,
  paginatedStartIndex,
  editingPreferenceIndex,
  editingPreferenceValue,
  savingPreference,
  optimizingPreferences,
  optimizedPreferences,
  optimizeError,
  addFiles,
  removeImage,
  cancel,
  reset,
  exportToFeishu,
  startGeneration,
  proceedToOptimize,
  proceedToStyleRefine,
  completeWithoutStyleRefine,
  startEditing,
  cancelEditing,
  applyEditsLocally,
  saveStyleMemory,
  loadStylePreferences,
  toggleStylePreferences,
  compressOversizedImages,
  removeOversizedImages,
  cancelOversizedImages,
  stepResults,
  selectedStepResult,
  selectStepResult,
  clearStepResult,
  deleteStylePreference,
  startEditingPreference,
  confirmEditingPreference,
  cancelEditingPreference,
  optimizePreferences,
  confirmOptimizedPreferences,
  cancelOptimizePreferences,
} = useImageAnalysis()

const isDragging = ref(false)
const uploadError = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const copyLabel = ref('复制文案')
const copyLinkLabel = ref('复制链接')
const showGenerationSteps = ref(false)
const newTagInput = ref('')
const now = ref(Date.now())
const previewIndex = ref<number | null>(null)
let nowTimer: number | null = null

function previewImage(index: number): void {
  previewIndex.value = index
}

function closePreview(): void {
  previewIndex.value = null
}

function handlePreviewKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    closePreview()
  } else if (event.key === 'ArrowLeft' && previewIndex.value !== null && previewIndex.value > 0) {
    previewIndex.value -= 1
  } else if (event.key === 'ArrowRight' && previewIndex.value !== null && previewIndex.value < images.value.length - 1) {
    previewIndex.value += 1
  }
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

async function handleExportToFeishu(): Promise<void> {
  await exportToFeishu()
}

function addEditTag(): void {
  const tag = newTagInput.value.trim()
  if (!tag) return
  editTags.value = [...editTags.value, tag]
  newTagInput.value = ''
}

function flushPendingTag(): void {
  addEditTag()
}

function removeEditTag(index: number): void {
  editTags.value = editTags.value.filter((_, i) => i !== index)
}

async function handleSaveStyleMemory(): Promise<void> {
  flushPendingTag()
  await saveStyleMemory()
}

function handleApplyEditsLocally(): void {
  flushPendingTag()
  applyEditsLocally()
}

watch(generationStage, (stage) => {
  if (stage === 'drafting') {
    showGenerationSteps.value = false
  }
  if (stage === 'optimize-review' && !stylePreferences.value.length && !loadingPreferences.value) {
    loadStylePreferences()
  }
})

async function copyDocLink(): Promise<void> {
  if (!exportedDocUrl.value) return
  try {
    await navigator.clipboard.writeText(exportedDocUrl.value)
    copyLinkLabel.value = '已复制'
    setTimeout(() => { copyLinkLabel.value = '复制链接' }, 2000)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = exportedDocUrl.value
    ta.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    copyLinkLabel.value = '已复制'
    setTimeout(() => { copyLinkLabel.value = '复制链接' }, 2000)
  }
}

function buildReviewText(): string {
  if (!result.value) return ''
  const parts: string[] = []
  if (result.value.title) parts.push(result.value.title)
  if (result.value.review) parts.push(result.value.review)
  if (result.value.tags?.length) parts.push(result.value.tags.join(' '))
  return parts.join('\n\n')
}

async function copyReview(): Promise<void> {
  const text = buildReviewText()
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copyLabel.value = '已复制'
    setTimeout(() => { copyLabel.value = '复制文案' }, 2000)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.cssText = 'position:fixed;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
    copyLabel.value = '已复制'
    setTimeout(() => { copyLabel.value = '复制文案' }, 2000)
  }
}

function openFilePicker(): void {
  fileInput.value?.click()
}

function handleFileInput(event: Event): void {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) return
  uploadError.value = addFiles(Array.from(input.files)) ?? ''
  input.value = ''
}

function handleDrop(event: DragEvent): void {
  isDragging.value = false
  const files = event.dataTransfer?.files
  if (!files?.length) return
  uploadError.value = addFiles(Array.from(files)) ?? ''
}

function handleReset(): void {
  isDragging.value = false
  uploadError.value = ''
  showGenerationSteps.value = false
  reset()
}

function cancelAnalysis(): void {
  showGenerationSteps.value = false
  cancel()
}

function formatDuration(durationMs: number): string {
  return `${(durationMs / 1000).toFixed(1)}s`
}

function getEventDurationLabel(event: ImageAnalysisProgressEvent): string {
  if (typeof event.durationMs === 'number') {
    return `耗时 ${formatDuration(event.durationMs)}`
  }

  if (!loading.value || !event.startedAt || event.completedAt) {
    return ''
  }

  const startedAtMs = Date.parse(event.startedAt)
  if (Number.isNaN(startedAtMs)) {
    return ''
  }

  return `进行中 · ${formatDuration(Math.max(0, now.value - startedAtMs))}`
}

const hasGenerationSteps = computed(() => progressEvents.value.length > 0)
const generationStepToggleLabel = computed(() => showGenerationSteps.value ? '收起生成步骤' : '查看生成步骤')

const isStepReview = computed(() => ['draft-review', 'optimize-review'].includes(generationStage.value))
const showStepLoading = computed(() => ['optimizing', 'style-refining'].includes(generationStage.value))
const stepLabel = computed(() => generationStage.value === 'draft-review' ? '初稿结果' : '润色结果')
const stepDescription = computed(() => generationStage.value === 'draft-review' ? '初稿已生成，可编辑后继续' : '润色完成，可编辑后继续')
const loadingLabel = computed(() => generationStage.value === 'optimizing' ? '正在润色优化…' : '正在风格偏好优化…')
const loadingDescription = computed(() => generationStage.value === 'optimizing' ? '正在润色评价文案，请稍候…' : '正在根据风格偏好优化，请稍候…')

function toggleGenerationSteps(): void {
  showGenerationSteps.value = !showGenerationSteps.value
}

function startNowTicker(): void {
  if (nowTimer !== null) {
    window.clearInterval(nowTimer)
  }

  nowTimer = window.setInterval(() => {
    now.value = Date.now()
  }, 200)
}

function stopNowTicker(): void {
  if (nowTimer !== null) {
    window.clearInterval(nowTimer)
    nowTimer = null
  }
}

onMounted(() => {
  startNowTicker()
})

watch(previewIndex, async (val) => {
  if (val !== null) {
    await nextTick()
    ;(document.querySelector('.preview-overlay') as HTMLElement | null)?.focus()
  }
})

onBeforeUnmount(() => {
  stopNowTicker()
})

function getStageLabel(stage: ImageAnalysisProgressStage): string {
  if (stage === 'prepare') return '准备中'
  if (stage === 'draft') return '初稿生成'
  if (stage === 'optimize') return '润色优化'
  if (stage === 'style-refine') return '风格偏好优化'
  return '已完成'
}
</script>

<style scoped>
.image-analysis {
  display: grid;
  gap: var(--space-lg);
}

.image-shell {
  display: grid;
  grid-template-columns: minmax(320px, 420px) minmax(0, 1fr);
  gap: var(--space-lg);
  align-items: start;
}

.control-card,
.preview-column,
.result-card,
.empty-card,
.status-card,
.progress-card {
  display: grid;
  gap: 16px;
}

.control-card {
  position: sticky;
  top: var(--space-md);
}

.section-head,
.field-block,
.field-block-head,
.result-head,
.result-block,
.result-tags-wrap,
.result-steps,
.result-steps-head {
  display: grid;
  gap: 10px;
}

.section-head {
  gap: 8px;
}

.section-kicker,
.result-label,
.selected-images-title,
.field-block-title {
  margin: 0;
  font-size: 0.75rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--color-text-muted);
  font-weight: 600;
}

.section-title,
.result-title,
.empty-title,
.status-title {
  margin: 0;
  color: var(--color-text);
}

.section-title {
  font-size: 1.14rem;
  line-height: 1.25;
}

.section-note,
.drop-zone-text,
.selected-images-count,
.field-block-copy,
.empty-copy,
.status-copy {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.86rem;
  line-height: 1.55;
}

.drop-zone {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  min-height: 112px;
  padding: 18px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--surface-page);
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.drop-zone:hover,
.drop-zone-active {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.drop-zone-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
}

.drop-zone-copy {
  display: grid;
  gap: 4px;
}

.drop-zone-title {
  margin: 0;
  color: var(--color-text);
  font-size: 0.96rem;
  font-weight: 600;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}

.selected-images {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.selected-images-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.thumb-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.thumb-item {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid var(--color-border);
  background: var(--surface-card);
}

.thumb-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb-remove {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 22px;
  height: 22px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(11, 14, 20, 0.72);
  color: white;
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}

.settings-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.platform-toggle {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.platform-btn {
  min-height: 36px;
  padding: 0 14px;
  border: none;
  border-radius: calc(var(--radius-md) - 4px);
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: 0.84rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.platform-btn-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.platform-btn:not(.platform-btn-active):hover {
  background: var(--color-surface-hover);
}

.platform-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.field-group-inline {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.field-label {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.field-input-sm,
.field-textarea {
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
  color: var(--color-text);
  font: inherit;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.field-input-sm {
  width: 86px;
  min-height: 38px;
  padding: 0 10px;
  border-radius: var(--radius-md);
}

.field-textarea {
  width: 100%;
  min-height: 88px;
  padding: 12px 14px;
  resize: vertical;
  line-height: 1.6;
  border-radius: var(--radius-lg);
}

.field-input-sm:focus,
.field-textarea:focus {
  outline: none;
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.action-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary,
.btn-copy {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}

.btn-primary {
  background: var(--color-accent);
  color: white;
  border: none;
}

.btn-primary:hover:not(:disabled) {
  background: var(--color-accent-2);
  transform: translateY(-1px);
}

.btn-secondary,
.btn-copy {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.btn-secondary:hover:not(:disabled),
.btn-copy:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.result-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.btn-export-feishu {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.btn-export-feishu:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
  transform: translateY(-1px);
}

.btn-export-feishu:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.btn-copy-link {
  padding: 4px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-card);
  color: var(--color-text-secondary);
  font-size: 0.78rem;
  font-weight: 600;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out);
}

.btn-copy-link:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
}

.export-error {
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(239, 107, 107, 0.28);
  background: rgba(239, 107, 107, 0.08);
}

.export-error p {
  margin: 0;
  color: var(--color-danger);
  font-size: 0.85rem;
}

.export-success {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(72, 187, 120, 0.28);
  background: rgba(72, 187, 120, 0.08);
  flex-wrap: wrap;
}

.export-success p {
  margin: 0;
  color: var(--color-text);
  font-size: 0.85rem;
}

.export-success a {
  color: var(--color-accent);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.export-success a:hover {
  color: var(--color-accent-2);
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.preview-column {
  min-width: 0;
}

.result-head {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
}

.result-card {
  align-content: start;
}

.result-title {
  font-size: 1.08rem;
}

.result-block {
  padding: 16px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.result-text {
  margin: 0;
  color: var(--color-text);
  line-height: 1.75;
  white-space: pre-wrap;
}

.result-emphasis {
  font-weight: 600;
}

.result-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.result-tag {
  display: inline-flex;
  align-items: center;
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.8rem;
}

.empty-card,
.status-card,
.progress-card {
  min-height: 320px;
  align-content: start;
}

.empty-copy,
.status-copy {
  max-width: 46ch;
}

.status-card-error {
  border-color: rgba(239, 107, 107, 0.28);
  background: rgba(239, 107, 107, 0.08);
}

.progress-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 48px;
  min-height: 32px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.8rem;
  font-weight: 600;
}

.progress-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}

.progress-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  padding: 12px 14px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.progress-dot {
  width: 9px;
  height: 9px;
  margin-top: 6px;
  border-radius: 999px;
  background: var(--color-accent);
  box-shadow: 0 0 0 6px rgba(114, 132, 248, 0.12);
}

.progress-copy {
  display: grid;
  gap: 4px;
}

.progress-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.progress-title,
.progress-text {
  margin: 0;
}

.progress-duration,
.result-steps-run-id {
  color: var(--color-text-muted);
  font-size: 0.78rem;
  line-height: 1.4;
}

.progress-title {
  color: var(--color-text);
  font-size: 0.9rem;
  font-weight: 600;
}

.progress-text {
  color: var(--color-text-secondary);
  font-size: 0.84rem;
  line-height: 1.55;
}

.error-text {
  margin: 0;
  color: var(--color-danger);
  font-size: 0.85rem;
}

.edit-field {
  display: grid;
  gap: 6px;
}

.edit-input-full {
  width: 100%;
}

.edit-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.edit-tag-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  font-size: 0.8rem;
}

.edit-tag-remove {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  padding: 0;
}

.edit-tag-remove:hover {
  color: var(--color-danger);
}

.edit-tag-input {
  width: 100px;
  min-height: 32px;
  font-size: 0.8rem;
}

.edit-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}

.btn-save-style {
  min-height: 40px;
  padding: 0 16px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  background: var(--color-accent);
  color: white;
  border: none;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.btn-save-style:hover:not(:disabled) {
  background: var(--color-accent-2);
  transform: translateY(-1px);
}

.btn-save-style:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.save-style-success {
  display: flex;
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  border: 1px solid rgba(72, 187, 120, 0.28);
  background: rgba(72, 187, 120, 0.08);
}

.save-style-success p {
  margin: 0;
  color: var(--color-text);
  font-size: 0.85rem;
}

.edit-saving {
  position: relative;
  pointer-events: none;
  opacity: 0.7;
}

.edit-saving .edit-actions {
  pointer-events: auto;
  opacity: 1;
}

.style-preferences-section {
  display: grid;
  gap: 10px;
}

.style-preferences-loading,
.style-preferences-empty p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
  line-height: 1.5;
}

.style-preferences-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
}

.style-preference-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  font-size: 0.84rem;
  line-height: 1.5;
}

.preference-text {
  flex: 1;
  min-width: 0;
  color: var(--color-text);
}

.preference-actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.preference-action-btn {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.preference-action-btn:hover {
  color: var(--color-text);
  background: var(--surface-card);
}

.preference-delete-btn:hover {
  color: var(--color-danger);
  background: rgba(239, 107, 107, 0.08);
}

.preference-edit-input {
  flex: 1;
  min-width: 0;
}

.btn-sm {
  min-height: 28px;
  padding: 0 10px;
  font-size: 0.78rem;
}

.preference-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 8px;
}

.preference-page-info {
  color: var(--color-text-muted);
  font-size: 0.78rem;
  font-weight: 600;
}

.step-loading-copy {
  display: flex;
  align-items: center;
  gap: 10px;
}

.step-actions {
  display: grid;
  gap: 12px;
}

.step-nav {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.progress-item-clickable {
  cursor: pointer;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.progress-item-clickable:hover {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
}

.preferences-overlay,
.step-result-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  background: var(--color-overlay);
  padding: var(--space-md);
}

.preferences-modal,
.step-result-modal {
  width: min(560px, 100%);
  max-height: 85vh;
  display: grid;
  gap: 16px;
  padding: 24px;
  overflow-y: auto;
}

.preferences-modal-head,
.step-result-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.preferences-modal-title,
.step-result-modal-title {
  margin: 0;
  color: var(--color-text);
  font-size: 1rem;
}

.preferences-modal-close {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  flex-shrink: 0;
  transition: color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out);
}

.preferences-modal-close:hover {
  color: var(--color-text);
  background: var(--surface-card);
}

.step-result-content {
  max-height: 50vh;
  overflow-y: auto;
}

@media (max-width: 980px) {
  .image-shell {
    grid-template-columns: 1fr;
  }

  .control-card {
    position: static;
  }
}

@media (max-width: 720px) {
  .drop-zone,
  .result-head {
    grid-template-columns: 1fr;
  }

  .btn-primary,
  .btn-secondary,
  .btn-copy,
  .btn-export-feishu {
    width: 100%;
  }
}

.oversized-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  background: var(--color-overlay);
  padding: var(--space-md);
}

.oversized-modal {
  width: min(420px, 100%);
  padding: 24px;
  gap: 16px;
  max-height: 90vh;
  overflow-y: auto;
}

.oversized-head {
  display: grid;
  gap: 6px;
}

.oversized-title {
  margin: 0;
  color: var(--color-text);
  font-size: 1rem;
}

.oversized-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 6px;
  max-height: 200px;
  overflow-y: auto;
}

.oversized-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.oversized-name {
  color: var(--color-text);
  font-size: 0.84rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 65%;
}

.oversized-size {
  color: var(--color-text-muted);
  font-size: 0.78rem;
  font-weight: 600;
  flex-shrink: 0;
}

.oversized-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.optimize-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.preference-count-hint {
  color: var(--color-text-muted);
  font-size: 0.78rem;
  font-weight: 600;
}

.optimize-diff-header {
  display: grid;
  gap: 6px;
}

.optimize-diff-title {
  margin: 0;
  color: var(--color-text);
  font-size: 0.9rem;
  font-weight: 600;
}

.optimize-diff-section {
  display: grid;
  gap: 6px;
}

.optimize-diff-label {
  margin: 0;
  font-size: 0.78rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.optimize-diff-removed {
  color: var(--color-danger);
}

.optimize-diff-added {
  color: #48bb78;
}

.optimize-diff-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 4px;
  max-height: 200px;
  overflow-y: auto;
}

.optimize-diff-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 10px;
  border-radius: var(--radius-md);
  font-size: 0.84rem;
  line-height: 1.5;
}

.optimize-diff-item-old {
  background: rgba(239, 107, 107, 0.08);
  color: var(--color-text-secondary);
  text-decoration: line-through;
}

.optimize-diff-item-new {
  background: rgba(72, 187, 120, 0.08);
  color: var(--color-text);
}

.optimize-diff-marker {
  flex-shrink: 0;
  font-weight: 700;
  width: 14px;
  text-align: center;
}

.optimize-diff-item-old .optimize-diff-marker {
  color: var(--color-danger);
}

.optimize-diff-item-new .optimize-diff-marker {
  color: #48bb78;
}

.optimize-diff-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.thumb-item {
  cursor: pointer;
}

.thumb-item:hover .thumb-img {
  opacity: 0.85;
  transition: opacity var(--duration-fast) var(--ease-out);
}

.preview-overlay {
  position: fixed;
  inset: 0;
  z-index: 1100;
  display: grid;
  place-items: center;
  background: rgba(0, 0, 0, 0.85);
  padding: var(--space-md);
}

.preview-img {
  max-width: 90vw;
  max-height: 80vh;
  object-fit: contain;
  border-radius: var(--radius-lg);
  user-select: none;
}

.preview-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(0, 0, 0, 0.5);
  color: white;
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.preview-close:hover {
  background: rgba(0, 0, 0, 0.7);
}

.preview-nav {
  position: absolute;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 16px;
}

.preview-nav-btn {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(0, 0, 0, 0.5);
  color: white;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
}

.preview-nav-btn:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.7);
}

.preview-nav-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

.preview-count {
  color: rgba(255, 255, 255, 0.7);
  font-size: 0.84rem;
  font-weight: 600;
}
</style>
