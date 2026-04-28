<template>
  <Teleport to="body">
    <div v-show="visible" class="settings-overlay">
      <div class="settings-modal glass-card" role="dialog" aria-modal="true" aria-labelledby="settings-title">
        <header class="settings-header">
          <div class="settings-header-copy">
            <p class="settings-kicker">账号设置</p>
            <h2 id="settings-title" class="settings-title">分析设置</h2>
            <p class="settings-subtitle">按功能分别维护服务地址、密钥和模型；保存后由后端代管，不会在浏览器里直连第三方服务。</p>
          </div>
          <button class="settings-close-btn" type="button" aria-label="关闭" @click="handleClose">
            &times;
          </button>
        </header>

        <nav class="settings-tabs" role="tablist" aria-label="设置分类">
          <button
            class="settings-tab"
            :class="{ 'settings-tab-active': settingsTab === 'video' }"
            :aria-selected="settingsTab === 'video'"
            type="button"
            @click="settingsTab = 'video'"
          >
            视频分析
          </button>
          <button
            class="settings-tab"
            :class="{ 'settings-tab-active': settingsTab === 'image' }"
            :aria-selected="settingsTab === 'image'"
            type="button"
            @click="settingsTab = 'image'"
          >
            图片评价
          </button>
          <button
            class="settings-tab"
            :class="{ 'settings-tab-active': settingsTab === 'article' }"
            :aria-selected="settingsTab === 'article'"
            type="button"
            @click="settingsTab = 'article'"
          >
            爆款文章
          </button>
          <button
            class="settings-tab"
            :class="{ 'settings-tab-active': settingsTab === 'imageGeneration' }"
            :aria-selected="settingsTab === 'imageGeneration'"
            type="button"
            @click="settingsTab = 'imageGeneration'"
          >
            图片生成
          </button>
          <button
            class="settings-tab"
            :class="{ 'settings-tab-active': settingsTab === 'homepage' }"
            :aria-selected="settingsTab === 'homepage'"
            type="button"
            @click="settingsTab = 'homepage'"
          >
            首页热点
          </button>
        </nav>

        <div class="settings-body">
          <section v-show="settingsTab === 'video'" class="settings-panel" role="tabpanel">
            <div class="settings-section-head">
              <div>
                <p class="settings-section-kicker">视频分析</p>
                <h3 class="settings-section-title">选择视频分析引擎</h3>
              </div>
              <p class="settings-section-note">视频分析支持 Coze 工作流或大模型兼容接口；接入其他视频理解模型时，需要兼容当前请求格式。</p>
            </div>

            <div class="provider-switch" role="radiogroup" aria-label="视频分析引擎选择">
              <button
                class="provider-tab"
                :class="{ 'provider-tab-active': videoProvider === 'coze' }"
                type="button"
                @click="videoProvider = 'coze'"
              >
                Coze 工作流
              </button>
              <button
                class="provider-tab"
                :class="{ 'provider-tab-active': videoProvider === 'qwen' }"
                type="button"
                @click="videoProvider = 'qwen'"
              >
                大模型兼容接口
              </button>
            </div>

            <div class="settings-group">
              <div class="settings-group-head">
                <h4 class="settings-group-title">连接配置</h4>
                <p class="settings-group-copy">先填服务地址，再补充当前引擎所需的密钥。</p>
              </div>

              <div class="settings-fields">
                <label class="settings-label" for="video-base-url">服务地址</label>
                <input
                  id="video-base-url"
                  v-model="videoBaseUrl"
                  class="settings-input"
                  type="url"
                  inputmode="url"
                  :placeholder="videoProvider === 'coze' ? 'https://example.com/run' : 'https://dashscope.aliyuncs.com/compatible-mode/v1'"
                  autocomplete="off"
                  spellcheck="false"
                >

                <template v-if="videoProvider === 'coze'">
                  <label class="settings-label" for="video-api-token">API Token</label>
                  <p v-if="props.settings.features.video.apiToken" class="settings-secret-hint">
                    已保存，留空保持不变；输入空格后保存可清空。
                  </p>
                  <div class="token-row">
                    <input
                      id="video-api-token"
                      v-model="videoApiToken"
                      class="settings-input"
                      :type="showVideoApiToken ? 'text' : 'password'"
                      placeholder="留空则保持现有 Token"
                      autocomplete="off"
                      spellcheck="false"
                    >
                    <button class="btn-secondary btn-sm" type="button" @click="showVideoApiToken = !showVideoApiToken">
                      {{ showVideoApiToken ? '隐藏' : '显示' }}
                    </button>
                  </div>
                </template>

                <template v-else>
                  <label class="settings-label" for="video-api-key">API Key</label>
                  <p v-if="props.settings.features.video.apiKey" class="settings-secret-hint">
                    已保存，留空保持不变；输入空格后保存可清空。
                  </p>
                  <div class="token-row">
                    <input
                      id="video-api-key"
                      v-model="videoApiKey"
                      class="settings-input"
                      :type="showVideoApiKey ? 'text' : 'password'"
                      placeholder="留空则保持现有 Key"
                      autocomplete="off"
                      spellcheck="false"
                    >
                    <button class="btn-secondary btn-sm" type="button" @click="showVideoApiKey = !showVideoApiKey">
                      {{ showVideoApiKey ? '隐藏' : '显示' }}
                    </button>
                  </div>
                </template>
              </div>
            </div>

            <div v-if="videoProvider === 'qwen'" class="settings-group">
              <div class="settings-group-head settings-group-head-inline">
                <div>
                  <h4 class="settings-group-title">模型配置</h4>
                  <p class="settings-group-copy">可直接输入模型，也可以从服务端拉取候选列表。</p>
                </div>
                <button
                  class="btn-fetch-models"
                  type="button"
                  :disabled="videoState.loading || !canFetchVideoModels"
                  @click="handleFetchModels('video')"
                >
                  <svg v-if="videoState.loading" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none">
                    <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/>
                  </svg>
                  {{ videoState.loading ? '获取中…' : '刷新列表' }}
                </button>
              </div>

              <p v-if="videoState.error" class="settings-error settings-inline-status">{{ videoState.error }}</p>

              <div class="settings-fields">
                <label class="settings-label" for="video-model">模型</label>

                <div v-if="videoState.availableModels.length && !useCustomVideoModel" class="model-row">
                  <select id="video-model" v-model="videoModel" class="settings-input model-select">
                    <option value="" disabled>请选择模型</option>
                    <option v-for="m in videoState.availableModels" :key="m.id" :value="m.id">{{ m.id }}</option>
                    <option value="__custom__">自定义输入…</option>
                  </select>
                  <button
                    class="btn-secondary btn-sm"
                    type="button"
                    :disabled="videoState.verifying || !videoModel.trim()"
                    @click="handleVerifyModel('video')"
                  >
                    {{ videoState.verifying ? '验证中' : '测试' }}
                  </button>
                </div>
                <div v-else class="model-row">
                  <input
                    id="video-model"
                    v-model="videoModel"
                    class="settings-input"
                    type="text"
                    placeholder="qwen3.5-flash"
                    autocomplete="off"
                    spellcheck="false"
                    @focus="useCustomVideoModel = true"
                  >
                  <button
                    class="btn-secondary btn-sm"
                    type="button"
                    :disabled="videoState.verifying || !videoModel.trim()"
                    @click="handleVerifyModel('video')"
                  >
                    {{ videoState.verifying ? '验证中' : '测试' }}
                  </button>
                </div>
              </div>

              <p v-if="videoState.verifyResult === 'success'" class="verify-success settings-inline-status">模型可用</p>
              <p v-if="videoState.verifyResult === 'error'" class="settings-error settings-inline-status">{{ videoState.verifyError }}</p>
            </div>
          </section>

          <section v-show="settingsTab === 'image'" class="settings-panel" role="tabpanel">
            <div class="settings-section-head">
              <div>
                <p class="settings-section-kicker">图片评价</p>
                <h3 class="settings-section-title">配置图片理解模型</h3>
              </div>
              <p class="settings-section-note">图片评价默认使用 Qwen 兼容接口。</p>
            </div>

            <div class="settings-group">
              <div class="settings-group-head">
                <h4 class="settings-group-title">连接配置</h4>
                <p class="settings-group-copy">设置图片评价接口地址与访问密钥。</p>
              </div>

              <div class="settings-fields">
                <label class="settings-label" for="image-base-url">服务地址</label>
                <input
                  id="image-base-url"
                  v-model="imageBaseUrl"
                  class="settings-input"
                  type="url"
                  inputmode="url"
                  placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
                  autocomplete="off"
                  spellcheck="false"
                >

                <label class="settings-label" for="image-api-key">API Key</label>
                <p v-if="props.settings.features.image.apiKey" class="settings-secret-hint">
                  已保存，留空保持不变；输入空格后保存可清空。
                </p>
                <div class="token-row">
                  <input
                    id="image-api-key"
                    v-model="imageApiKey"
                    class="settings-input"
                    :type="showImageApiKey ? 'text' : 'password'"
                    placeholder="留空则保持现有 Key"
                    autocomplete="off"
                    spellcheck="false"
                  >
                  <button class="btn-secondary btn-sm" type="button" @click="showImageApiKey = !showImageApiKey">
                    {{ showImageApiKey ? '隐藏' : '显示' }}
                  </button>
                </div>
              </div>
            </div>

            <div class="settings-group">
              <div class="settings-group-head settings-group-head-inline">
                <div>
                  <h4 class="settings-group-title">模型配置</h4>
                  <p class="settings-group-copy">如果已有模型列表，可直接选择后测试连通性。</p>
                </div>
                <button
                  class="btn-fetch-models"
                  type="button"
                  :disabled="imageState.loading || !canFetchImageModels"
                  @click="handleFetchModels('image')"
                >
                  <svg v-if="imageState.loading" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none">
                    <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/>
                  </svg>
                  {{ imageState.loading ? '获取中…' : '刷新列表' }}
                </button>
              </div>

              <p v-if="imageState.error" class="settings-error settings-inline-status">{{ imageState.error }}</p>

              <div class="settings-fields">
                <label class="settings-label" for="image-model">模型</label>

                <div v-if="imageState.availableModels.length && !useCustomImageModel" class="model-row">
                  <select id="image-model" v-model="imageModel" class="settings-input model-select">
                    <option value="" disabled>请选择模型</option>
                    <option v-for="m in imageState.availableModels" :key="m.id" :value="m.id">{{ m.id }}</option>
                    <option value="__custom__">自定义输入…</option>
                  </select>
                  <button
                    class="btn-secondary btn-sm"
                    type="button"
                    :disabled="imageState.verifying || !imageModel.trim()"
                    @click="handleVerifyModel('image')"
                  >
                    {{ imageState.verifying ? '验证中' : '测试' }}
                  </button>
                </div>
                <div v-else class="model-row">
                  <input
                    id="image-model"
                    v-model="imageModel"
                    class="settings-input"
                    type="text"
                    placeholder="qwen-vl-max"
                    autocomplete="off"
                    spellcheck="false"
                    @focus="useCustomImageModel = true"
                  >
                  <button
                    class="btn-secondary btn-sm"
                    type="button"
                    :disabled="imageState.verifying || !imageModel.trim()"
                    @click="handleVerifyModel('image')"
                  >
                    {{ imageState.verifying ? '验证中' : '测试' }}
                  </button>
                </div>
              </div>

              <p v-if="imageState.verifyResult === 'success'" class="verify-success settings-inline-status">模型可用</p>
              <p v-if="imageState.verifyResult === 'error'" class="settings-error settings-inline-status">{{ imageState.verifyError }}</p>
            </div>

            <div class="settings-group">
              <div class="settings-group-head">
                <h4 class="settings-group-title">飞书导出</h4>
                <p class="settings-group-copy">配置飞书应用凭证后，可以将图片评价结果导出到飞书文档。</p>
              </div>

              <div class="settings-fields">
                <label class="settings-label" for="feishu-app-id">App ID</label>
                <input
                  id="feishu-app-id"
                  v-model="feishuAppId"
                  class="settings-input"
                  type="text"
                  placeholder="飞书应用 App ID"
                  autocomplete="off"
                  spellcheck="false"
                >

                <label class="settings-label" for="feishu-app-secret">App Secret</label>
                <p v-if="props.settings.integrations?.feishu?.appSecret" class="settings-secret-hint">
                  已保存，留空保持不变；输入空格后保存可清空。
                </p>
                <div class="token-row">
                  <input
                    id="feishu-app-secret"
                    v-model="feishuAppSecret"
                    class="settings-input"
                    :type="showFeishuAppSecret ? 'text' : 'password'"
                    placeholder="留空则保持现有 Secret"
                    autocomplete="off"
                    spellcheck="false"
                  >
                  <button class="btn-secondary btn-sm" type="button" @click="showFeishuAppSecret = !showFeishuAppSecret">
                    {{ showFeishuAppSecret ? '隐藏' : '显示' }}
                  </button>
                </div>

                <label class="settings-label" for="feishu-folder-token">文档夹 Token（可选）</label>
                <input
                  id="feishu-folder-token"
                  v-model="feishuFolderToken"
                  class="settings-input"
                  type="text"
                  placeholder="留空则创建到默认位置"
                  autocomplete="off"
                  spellcheck="false"
                >
              </div>
            </div>
          </section>

          <section v-show="settingsTab === 'article'" class="settings-panel" role="tabpanel">
            <div class="settings-section-head">
              <div>
                <p class="settings-section-kicker">爆款文章</p>
                <h3 class="settings-section-title">配置文章生成模型</h3>
              </div>
              <p class="settings-section-note">文章流程当前仅支持 Qwen 大模型。</p>
            </div>

            <div class="settings-group">
              <div class="settings-group-head">
                <h4 class="settings-group-title">连接配置</h4>
                <p class="settings-group-copy">文章标题、大纲和正文会共用这一组接口配置。</p>
              </div>

              <div class="settings-fields">
                <label class="settings-label" for="article-base-url">服务地址</label>
                <input
                  id="article-base-url"
                  v-model="articleBaseUrl"
                  class="settings-input"
                  type="url"
                  inputmode="url"
                  placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
                  autocomplete="off"
                  spellcheck="false"
                >

                <label class="settings-label" for="article-api-key">API Key</label>
                <p v-if="props.settings.features.article.apiKey" class="settings-secret-hint">
                  已保存，留空保持不变；输入空格后保存可清空。
                </p>
                <div class="token-row">
                  <input
                    id="article-api-key"
                    v-model="articleApiKey"
                    class="settings-input"
                    :type="showArticleApiKey ? 'text' : 'password'"
                    placeholder="留空则保持现有 Key"
                    autocomplete="off"
                    spellcheck="false"
                  >
                  <button class="btn-secondary btn-sm" type="button" @click="showArticleApiKey = !showArticleApiKey">
                    {{ showArticleApiKey ? '隐藏' : '显示' }}
                  </button>
                </div>
              </div>
            </div>

            <div class="settings-group">
              <div class="settings-group-head settings-group-head-inline">
                <div>
                  <h4 class="settings-group-title">模型配置</h4>
                  <p class="settings-group-copy">支持拉取可用模型列表，也支持手动录入模型名。</p>
                </div>
                <button
                  class="btn-fetch-models"
                  type="button"
                  :disabled="articleState.loading || !canFetchArticleModels"
                  @click="handleFetchModels('article')"
                >
                  <svg v-if="articleState.loading" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none">
                    <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/>
                  </svg>
                  {{ articleState.loading ? '获取中…' : '刷新列表' }}
                </button>
              </div>

              <p v-if="articleState.error" class="settings-error settings-inline-status">{{ articleState.error }}</p>

              <div class="settings-fields">
                <label class="settings-label" for="article-model">模型</label>

                <div v-if="articleState.availableModels.length && !useCustomArticleModel" class="model-row">
                  <select id="article-model" v-model="articleModel" class="settings-input model-select">
                    <option value="" disabled>请选择模型</option>
                    <option v-for="m in articleState.availableModels" :key="m.id" :value="m.id">{{ m.id }}</option>
                    <option value="__custom__">自定义输入…</option>
                  </select>
                  <button
                    class="btn-secondary btn-sm"
                    type="button"
                    :disabled="articleState.verifying || !articleModel.trim()"
                    @click="handleVerifyModel('article')"
                  >
                    {{ articleState.verifying ? '验证中' : '测试' }}
                  </button>
                </div>
                <div v-else class="model-row">
                  <input
                    id="article-model"
                    v-model="articleModel"
                    class="settings-input"
                    type="text"
                    placeholder="qwen-plus"
                    autocomplete="off"
                    spellcheck="false"
                    @focus="useCustomArticleModel = true"
                  >
                  <button
                    class="btn-secondary btn-sm"
                    type="button"
                    :disabled="articleState.verifying || !articleModel.trim()"
                    @click="handleVerifyModel('article')"
                  >
                    {{ articleState.verifying ? '验证中' : '测试' }}
                  </button>
                </div>
              </div>

              <p v-if="articleState.verifyResult === 'success'" class="verify-success settings-inline-status">模型可用</p>
              <p v-if="articleState.verifyResult === 'error'" class="settings-error settings-inline-status">{{ articleState.verifyError }}</p>
            </div>
          </section>

          <section v-show="settingsTab === 'imageGeneration'" class="settings-panel" role="tabpanel">
            <div class="settings-section-head">
              <div>
                <p class="settings-section-kicker">图片生成</p>
                <h3 class="settings-section-title">配置配图生成模型</h3>
              </div>
              <p class="settings-section-note">爆款文章配图会使用这一组独立的图片生成接口配置。</p>
            </div>

            <div class="settings-group">
              <div class="settings-group-head">
                <h4 class="settings-group-title">连接配置</h4>
                <p class="settings-group-copy">用于 AI 生图和后续图片能力扩展，不会复用文章正文的模型设置。</p>
              </div>

              <div class="settings-fields">
                <label class="settings-label" for="image-generation-base-url">服务地址</label>
                <input
                  id="image-generation-base-url"
                  v-model="imageGenerationBaseUrl"
                  class="settings-input"
                  type="url"
                  inputmode="url"
                  placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
                  autocomplete="off"
                  spellcheck="false"
                >

                <label class="settings-label" for="image-generation-api-key">API Key</label>
                <p v-if="props.settings.features.imageGeneration.apiKey" class="settings-secret-hint">
                  已保存，留空保持不变；输入空格后保存可清空。
                </p>
                <div class="token-row">
                  <input
                    id="image-generation-api-key"
                    v-model="imageGenerationApiKey"
                    class="settings-input"
                    :type="showImageGenerationApiKey ? 'text' : 'password'"
                    placeholder="留空则保持现有 Key"
                    autocomplete="off"
                    spellcheck="false"
                  >
                  <button class="btn-secondary btn-sm" type="button" @click="showImageGenerationApiKey = !showImageGenerationApiKey">
                    {{ showImageGenerationApiKey ? '隐藏' : '显示' }}
                  </button>
                </div>
              </div>
            </div>

            <div class="settings-group">
              <div class="settings-group-head settings-group-head-inline">
                <div>
                  <h4 class="settings-group-title">模型配置</h4>
                  <p class="settings-group-copy">可拉取模型列表，也可直接手动填写图片生成模型名。</p>
                </div>
                <button
                  class="btn-fetch-models"
                  type="button"
                  :disabled="imageGenerationState.loading || !canFetchImageGenerationModels"
                  @click="handleFetchModels('imageGeneration')"
                >
                  <svg v-if="imageGenerationState.loading" class="spin-icon" width="14" height="14" viewBox="0 0 16 16" fill="none">
                    <circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="2" stroke-dasharray="28" stroke-dashoffset="10" stroke-linecap="round"/>
                  </svg>
                  {{ imageGenerationState.loading ? '获取中…' : '刷新列表' }}
                </button>
              </div>

              <p v-if="imageGenerationState.error" class="settings-error settings-inline-status">{{ imageGenerationState.error }}</p>

              <div class="settings-fields">
                <label class="settings-label" for="image-generation-model">模型</label>

                <div v-if="imageGenerationState.availableModels.length && !useCustomImageGenerationModel" class="model-row">
                  <select id="image-generation-model" v-model="imageGenerationModel" class="settings-input model-select">
                    <option value="" disabled>请选择模型</option>
                    <option v-for="m in imageGenerationState.availableModels" :key="m.id" :value="m.id">{{ m.id }}</option>
                    <option value="__custom__">自定义输入…</option>
                  </select>
                  <button
                    class="btn-secondary btn-sm"
                    type="button"
                    :disabled="imageGenerationState.verifying || !imageGenerationModel.trim()"
                    @click="handleVerifyModel('imageGeneration')"
                  >
                    {{ imageGenerationState.verifying ? '验证中' : '测试' }}
                  </button>
                </div>
                <div v-else class="model-row">
                  <input
                    id="image-generation-model"
                    v-model="imageGenerationModel"
                    class="settings-input"
                    type="text"
                    placeholder="wanx2.1-t2i-turbo"
                    autocomplete="off"
                    spellcheck="false"
                    @focus="useCustomImageGenerationModel = true"
                  >
                  <button
                    class="btn-secondary btn-sm"
                    type="button"
                    :disabled="imageGenerationState.verifying || !imageGenerationModel.trim()"
                    @click="handleVerifyModel('imageGeneration')"
                  >
                    {{ imageGenerationState.verifying ? '验证中' : '测试' }}
                  </button>
                </div>
              </div>

              <p v-if="imageGenerationState.verifyResult === 'success'" class="verify-success settings-inline-status">模型可用</p>
              <p v-if="imageGenerationState.verifyResult === 'error'" class="settings-error settings-inline-status">{{ imageGenerationState.verifyError }}</p>
            </div>
          </section>

          <section v-show="settingsTab === 'homepage'" class="settings-panel" role="tabpanel">
            <div class="settings-section-head">
              <div>
                <p class="settings-section-kicker">首页热点</p>
                <h3 class="settings-section-title">选择热点数据来源</h3>
              </div>
              <p class="settings-section-note">默认推荐 60s，多平台聚合且无需额外配置。</p>
            </div>

            <div class="provider-switch" role="radiogroup" aria-label="热点数据源选择">
              <button
                class="provider-tab"
                :class="{ 'provider-tab-active': hotItemsProvider === '60s' }"
                type="button"
                @click="hotItemsProvider = '60s'"
              >
                60s（默认）
              </button>
              <button
                class="provider-tab"
                :class="{ 'provider-tab-active': hotItemsProvider === 'alapi' }"
                type="button"
                @click="hotItemsProvider = 'alapi'"
              >
                ALAPI
              </button>
            </div>

            <div class="settings-group">
              <div class="settings-group-head">
                <h4 class="settings-group-title">热点服务配置</h4>
                <p class="settings-group-copy">如果切到 ALAPI，再填写对应 Token；使用 60s 时可留空。</p>
              </div>

              <template v-if="hotItemsProvider === 'alapi'">
                <div class="settings-fields">
                  <label class="settings-label" for="alapi-token">ALAPI Token</label>
                  <p v-if="props.homepageSettings.hotItems.alapiToken" class="settings-secret-hint">
                    已保存，留空保持不变；输入空格后保存可清空。
                  </p>
                  <div class="token-row">
                    <input
                      id="alapi-token"
                      v-model="alapiToken"
                      class="settings-input"
                      :type="showAlapiToken ? 'text' : 'password'"
                      placeholder="输入 ALAPI Token"
                      autocomplete="off"
                      spellcheck="false"
                    >
                    <button class="btn-secondary btn-sm" type="button" @click="showAlapiToken = !showAlapiToken">
                      {{ showAlapiToken ? '隐藏' : '显示' }}
                    </button>
                  </div>
                </div>
              </template>

              <p v-else class="settings-note-block">当前使用 60s 聚合热点，无需额外密钥即可拉取抖音、微博、知乎等热点内容。</p>
            </div>

            <p v-if="props.homepageError" class="settings-error settings-inline-status">{{ props.homepageError }}</p>
          </section>

          <p v-if="props.error" class="settings-error settings-inline-status">{{ props.error }}</p>
        </div>

        <footer class="settings-footer">
          <p class="settings-note">配置由后端持久化保存；敏感信息只会以掩码形式回填。</p>
          <div class="settings-footer-actions">
            <button class="btn-secondary" type="button" :disabled="saving || homepageSaving" @click="handleClose">取消</button>
            <button class="btn-primary" type="button" :disabled="saving || homepageSaving" @click="handleSave">
              {{ saving || homepageSaving ? '保存中…' : '保存设置' }}
            </button>
          </div>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AnalysisFeature, AnalysisProvider, AnalysisSettings, FeatureModelStateMap, HomepageSettings, HotItemsProvider } from '../types/settings'

type SettingsTab = 'video' | 'image' | 'article' | 'imageGeneration' | 'homepage'

const props = defineProps<{
  visible: boolean
  settings: AnalysisSettings
  saving: boolean
  error: string
  featureModelStates: FeatureModelStateMap
  homepageSettings: HomepageSettings
  homepageSaving: boolean
  homepageError: string
}>()

const emit = defineEmits<{
  close: []
  save: [settings: AnalysisSettings, homepageSettings: HomepageSettings]
  'fetch-models': [feature: AnalysisFeature, provider: AnalysisProvider | undefined, settings: AnalysisSettings]
  'verify-model': [feature: AnalysisFeature, provider: AnalysisProvider | undefined, model: string, settings: AnalysisSettings]
}>()

const settingsTab = ref<SettingsTab>('video')
const videoProvider = ref<AnalysisProvider>('coze')
const videoBaseUrl = ref('')
const videoApiToken = ref('')
const videoApiKey = ref('')
const videoModel = ref('')
const imageBaseUrl = ref('')
const imageApiKey = ref('')
const imageModel = ref('')
const articleBaseUrl = ref('')
const articleApiKey = ref('')
const articleModel = ref('')
const imageGenerationBaseUrl = ref('')
const imageGenerationApiKey = ref('')
const imageGenerationModel = ref('')
const showVideoApiToken = ref(false)
const showVideoApiKey = ref(false)
const showImageApiKey = ref(false)
const showArticleApiKey = ref(false)
const showImageGenerationApiKey = ref(false)
const useCustomVideoModel = ref(false)
const useCustomImageModel = ref(false)
const useCustomArticleModel = ref(false)
const useCustomImageGenerationModel = ref(false)
const hotItemsProvider = ref<HotItemsProvider>('60s')
const alapiToken = ref('')
const showAlapiToken = ref(false)
const feishuAppId = ref('')
const feishuAppSecret = ref('')
const feishuFolderToken = ref('')
const showFeishuAppSecret = ref(false)
let isHydratingForm = false

const canFetchVideoModels = computed(() => {
  return videoProvider.value === 'qwen' && videoBaseUrl.value.trim().length > 0 && videoApiKey.value.trim().length > 0
})

const canFetchImageModels = computed(() => {
  return imageBaseUrl.value.trim().length > 0 && imageApiKey.value.trim().length > 0
})

const canFetchArticleModels = computed(() => {
  return articleBaseUrl.value.trim().length > 0 && articleApiKey.value.trim().length > 0
})

const canFetchImageGenerationModels = computed(() => {
  return imageGenerationBaseUrl.value.trim().length > 0 && imageGenerationApiKey.value.trim().length > 0
})

const videoState = computed(() => props.featureModelStates.video)
const imageState = computed(() => props.featureModelStates.image)
const articleState = computed(() => props.featureModelStates.article)
const imageGenerationState = computed(() => props.featureModelStates.imageGeneration)

function populateFormFromSettings(settings: AnalysisSettings): void {
  isHydratingForm = true
  videoProvider.value = settings.features.video.provider
  videoBaseUrl.value = settings.features.video.baseUrl ?? ''
  videoApiToken.value = settings.features.video.apiToken ?? ''
  videoApiKey.value = settings.features.video.apiKey ?? ''
  videoModel.value = settings.features.video.model ?? ''
  imageBaseUrl.value = settings.features.image.baseUrl ?? ''
  imageApiKey.value = settings.features.image.apiKey ?? ''
  imageModel.value = settings.features.image.model ?? ''
  articleBaseUrl.value = settings.features.article.baseUrl ?? ''
  articleApiKey.value = settings.features.article.apiKey ?? ''
  articleModel.value = settings.features.article.model ?? ''
  imageGenerationBaseUrl.value = settings.features.imageGeneration.baseUrl ?? ''
  imageGenerationApiKey.value = settings.features.imageGeneration.apiKey ?? ''
  imageGenerationModel.value = settings.features.imageGeneration.model ?? ''
  showVideoApiToken.value = false
  showVideoApiKey.value = false
  showImageApiKey.value = false
  showArticleApiKey.value = false
  showImageGenerationApiKey.value = false
  useCustomVideoModel.value = false
  useCustomImageModel.value = false
  useCustomArticleModel.value = false
  useCustomImageGenerationModel.value = false
  feishuAppId.value = settings.integrations?.feishu?.appId ?? ''
  feishuAppSecret.value = settings.integrations?.feishu?.appSecret ?? ''
  feishuFolderToken.value = settings.integrations?.feishu?.folderToken ?? ''
  showFeishuAppSecret.value = false
  isHydratingForm = false
}

function populateHomepageForm(): void {
  isHydratingForm = true
  hotItemsProvider.value = props.homepageSettings.hotItems.provider
  alapiToken.value = props.homepageSettings.hotItems.alapiToken ?? ''
  showAlapiToken.value = false
  isHydratingForm = false
}

watch(() => props.visible, (visible) => {
  if (!visible) {
    return
  }

  populateFormFromSettings(props.settings)
  populateHomepageForm()
})

watch(() => props.settings, (settings) => {
  if (!props.visible || isHydratingForm) {
    return
  }

  populateFormFromSettings(settings)
}, { deep: true })

watch(() => props.homepageSettings, () => {
  if (!props.visible || isHydratingForm) {
    return
  }

  populateHomepageForm()
}, { deep: true })

watch(videoModel, (value) => {
  if (value === '__custom__') {
    videoModel.value = ''
    useCustomVideoModel.value = true
  }
})

watch(imageModel, (value) => {
  if (value === '__custom__') {
    imageModel.value = ''
    useCustomImageModel.value = true
  }
})

watch(articleModel, (value) => {
  if (value === '__custom__') {
    articleModel.value = ''
    useCustomArticleModel.value = true
  }
})

watch(imageGenerationModel, (value) => {
  if (value === '__custom__') {
    imageGenerationModel.value = ''
    useCustomImageGenerationModel.value = true
  }
})

function normalizeOptionalField(value: string): string {
  return value.trim()
}

function normalizeOptionalSecret(value: string): string | undefined {
  if (value === '') {
    return undefined
  }

  return value.trim()
}

function buildCurrentSettings(): AnalysisSettings {
  return {
    integrations: {
      feishu: {
        appId: normalizeOptionalField(feishuAppId.value) || undefined,
        appSecret: normalizeOptionalSecret(feishuAppSecret.value),
        folderToken: normalizeOptionalField(feishuFolderToken.value) || undefined,
      },
    },
    features: {
      video: {
        provider: videoProvider.value,
        baseUrl: normalizeOptionalField(videoBaseUrl.value),
        apiToken: videoProvider.value === 'coze' ? normalizeOptionalSecret(videoApiToken.value) : undefined,
        apiKey: videoProvider.value === 'qwen' ? normalizeOptionalSecret(videoApiKey.value) : undefined,
        model: videoProvider.value === 'qwen' ? normalizeOptionalField(videoModel.value) : undefined,
      },
      image: {
        baseUrl: normalizeOptionalField(imageBaseUrl.value),
        apiKey: normalizeOptionalSecret(imageApiKey.value),
        model: normalizeOptionalField(imageModel.value),
      },
      article: {
        baseUrl: normalizeOptionalField(articleBaseUrl.value),
        apiKey: normalizeOptionalSecret(articleApiKey.value),
        model: normalizeOptionalField(articleModel.value),
      },
      imageGeneration: {
        baseUrl: normalizeOptionalField(imageGenerationBaseUrl.value),
        apiKey: normalizeOptionalSecret(imageGenerationApiKey.value),
        model: normalizeOptionalField(imageGenerationModel.value),
      },
    },
  }
}

function getFeatureProvider(feature: AnalysisFeature): AnalysisProvider | undefined {
  return feature === 'video' ? videoProvider.value : 'qwen'
}

function buildCurrentHomepageSettings(): HomepageSettings {
  return {
    hotItems: {
      provider: hotItemsProvider.value,
      alapiToken: normalizeOptionalSecret(alapiToken.value),
    },
  }
}

function handleSave(): void {
  emit('save', buildCurrentSettings(), buildCurrentHomepageSettings())
}

function handleFetchModels(feature: AnalysisFeature): void {
  emit('fetch-models', feature, getFeatureProvider(feature), buildCurrentSettings())
}

function handleVerifyModel(feature: AnalysisFeature): void {
  const model = feature === 'video'
    ? videoModel.value.trim()
    : feature === 'image'
      ? imageModel.value.trim()
      : feature === 'article'
        ? articleModel.value.trim()
        : imageGenerationModel.value.trim()

  if (!model) {
    return
  }

  emit('verify-model', feature, getFeatureProvider(feature), model, buildCurrentSettings())
}

function handleClose(): void {
  emit('close')
}
</script>

<style scoped>
.settings-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: grid;
  place-items: center;
  padding: 20px;
  background: var(--color-overlay);
  backdrop-filter: blur(10px);
}

.settings-modal {
  width: min(760px, 100%);
  max-height: 90vh;
  overflow-y: auto;
  display: grid;
  gap: 18px;
  border: 1px solid var(--color-border);
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-md);
}

.settings-header-copy {
  display: grid;
  gap: 8px;
  max-width: 56ch;
}

.settings-kicker,
.settings-section-kicker,
.settings-group-title,
.settings-note,
.settings-note-block,
.settings-subtitle,
.settings-section-note,
.settings-group-copy,
.settings-label,
.settings-secret-hint,
.settings-inline-status {
  margin: 0;
}

.settings-kicker,
.settings-section-kicker {
  color: var(--color-text-muted);
  font-size: 0.74rem;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.settings-title {
  margin: 0;
  font-size: 1.28rem;
  line-height: 1.15;
}

.settings-subtitle {
  color: var(--color-text-secondary);
  font-size: 0.92rem;
  line-height: 1.6;
}

.settings-close-btn {
  width: 36px;
  height: 36px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--surface-page);
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 1.2rem;
  line-height: 1;
}

.settings-close-btn:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.settings-tabs {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-md);
  background: var(--surface-page);
  border: 1px solid var(--color-border);
}

.settings-tab {
  min-height: 36px;
  padding: 0 14px;
  border: none;
  border-radius: calc(var(--radius-md) - 4px);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.settings-tab:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.settings-tab-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.settings-body {
  display: grid;
  gap: 16px;
}

.settings-panel {
  display: grid;
  gap: 16px;
}

.settings-section-head,
.settings-group-head {
  display: grid;
  gap: 6px;
}

.settings-section-head {
  gap: 8px;
}

.settings-section-title {
  margin: 0;
  font-size: 1.02rem;
  color: var(--color-text);
}

.settings-section-note,
.settings-group-copy,
.settings-note,
.settings-note-block,
.settings-secret-hint {
  color: var(--color-text-muted);
  font-size: 0.83rem;
  line-height: 1.55;
}

.provider-switch {
  display: inline-flex;
  flex-wrap: wrap;
  width: fit-content;
  gap: 4px;
  padding: 4px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.provider-tab {
  min-height: 36px;
  padding: 0 14px;
  border: none;
  border-radius: calc(var(--radius-md) - 4px);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}

.provider-tab:hover {
  background: var(--color-surface-hover);
  color: var(--color-text);
}

.provider-tab-active {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}

.settings-group {
  display: grid;
  gap: 14px;
  padding: 16px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--color-border);
  background: var(--surface-page);
}

.settings-group-head-inline {
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
}

.settings-group-title {
  color: var(--color-text);
  font-size: 0.9rem;
  font-weight: 600;
}

.settings-fields {
  display: grid;
  gap: 12px;
}

.settings-label {
  font-size: 0.82rem;
  color: var(--color-text-secondary);
  font-weight: 600;
}

.settings-input {
  width: 100%;
  min-height: 42px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--surface-muted);
  color: var(--color-text);
  outline: none;
  font-size: 0.88rem;
  transition: border-color var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), box-shadow var(--duration-fast) var(--ease-out);
}

.settings-input:focus {
  border-color: var(--color-border-accent);
  background: var(--surface-card);
  box-shadow: var(--focus-ring);
}

.settings-input::placeholder {
  color: var(--color-text-muted);
}

.model-select {
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 12 12'%3E%3Cpath d='M3 4.5L6 7.5L9 4.5' stroke='%238d96a5' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round' fill='none'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 14px center;
  padding-right: 36px;
}

.token-row,
.model-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: start;
}

.settings-inline-status {
  font-size: 0.84rem;
}

.settings-error {
  color: var(--color-danger);
}

.verify-success {
  color: var(--color-success);
}

.btn-fetch-models,
.btn-primary,
.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
}

.btn-sm {
  min-height: 42px;
  padding: 0 14px;
  white-space: nowrap;
}

.btn-fetch-models {
  min-height: 36px;
  padding: 0 12px;
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.btn-fetch-models:hover:not(:disabled),
.btn-secondary:hover:not(:disabled) {
  background: var(--color-surface-hover);
  border-color: var(--color-border-hover);
  color: var(--color-text);
}

.btn-fetch-models:disabled,
.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.settings-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  padding-top: 4px;
  border-top: 1px solid var(--color-border);
}

.settings-note {
  flex: 1;
  min-width: 0;
}

.settings-footer-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}

.btn-primary,
.btn-secondary {
  min-height: 40px;
  padding: 0 16px;
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

.btn-secondary {
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  color: var(--color-text-secondary);
}

.settings-note-block {
  padding: 12px 14px;
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border);
  background: var(--surface-card);
}

@media (max-width: 720px) {
  .settings-group-head-inline,
  .token-row,
  .model-row,
  .settings-footer {
    grid-template-columns: 1fr;
    flex-direction: column;
    align-items: stretch;
  }

  .settings-tabs,
  .provider-switch {
    width: 100%;
  }

  .settings-footer-actions {
    justify-content: stretch;
  }

  .settings-footer-actions > * {
    flex: 1;
  }
}
</style>
