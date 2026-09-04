<template>
  <section class="admin-view gl-field">
    <header class="section-header">
      <h2 class="section-title">平台管理</h2>
      <p class="section-desc">处理用户、审核、等级权益、信任准入与平台配置</p>
    </header>

    <div class="admin-tabs" role="tablist" aria-label="管理模块">
      <!-- 任务书 #72 卡C D4：页签可见性从 reviewerOnly 二分升级为 per-tab 角色集（canSeeTab）。
           users=三查看角色（platform_admin/customer_service/risk，与后端卡A门禁同口径——
           content_reviewer 无列表权限，维持现状不可见）；其余 admin 页签 platform_admin 专属；
           公共素材/门店媒体/账号前缀=content_reviewer 既有可见集合原样保留。 -->
      <button v-if="canSeeTab('users')" type="button" role="tab" :aria-selected="activeSection === 'users'"
        :class="{ active: activeSection === 'users' }" @click="activeSection = 'users'">用户管理</button>
      <button v-if="canSeeTab('kyb')" type="button" role="tab" :aria-selected="activeSection === 'kyb'"
        :class="{ active: activeSection === 'kyb' }" @click="activeSection = 'kyb'">
        KYB 审核 <span v-if="kybTotal" class="count-badge">{{ kybTotal }}</span>
      </button>
      <button v-if="canSeeTab('org-renames')" type="button" role="tab" :aria-selected="activeSection === 'org-renames'"
        :class="{ active: activeSection === 'org-renames' }" @click="activeSection = 'org-renames'">主体更名</button>
      <button v-if="canSeeTab('recommenders')" type="button" role="tab" :aria-selected="activeSection === 'recommenders'"
        :class="{ active: activeSection === 'recommenders' }"
        @click="activeSection = 'recommenders'; void loadRecommenderRequests()">
        推荐官认证 <span v-if="recommenderTotal" class="count-badge">{{ recommenderTotal }}</span>
      </button>
      <button v-if="canSeeTab('tasks')" type="button" role="tab" :aria-selected="activeSection === 'tasks'"
        :class="{ active: activeSection === 'tasks' }"
        @click="activeSection = 'tasks'; void loadReviewTasks(); void loadReviewStats()">
        任务审核 <span v-if="reviewStats?.pending" class="count-badge">{{ reviewStats.pending }}</span>
      </button>
      <button v-if="canSeeTab('reputation')" type="button" role="tab" :aria-selected="activeSection === 'reputation'"
        :class="{ active: activeSection === 'reputation' }"
        @click="activeSection = 'reputation'">等级与权益</button>
      <button v-if="canSeeTab('judges')" type="button" role="tab" :aria-selected="activeSection === 'judges'"
        :class="{ active: activeSection === 'judges' }"
        @click="activeSection = 'judges'">审判官准入</button>
      <button v-if="canSeeTab('finance')" type="button" role="tab" :aria-selected="activeSection === 'finance'"
        :class="{ active: activeSection === 'finance' }"
        @click="activeSection = 'finance'; void loadJournals()">财务对账</button>
      <button v-if="canSeeTab('risk')" type="button" role="tab" :aria-selected="activeSection === 'risk'"
        :class="{ active: activeSection === 'risk' }" @click="activeSection = 'risk'">风险调查</button>
      <button v-if="canSeeTab('credits-packages')" type="button" role="tab" :aria-selected="activeSection === 'credits-packages'"
        :class="{ active: activeSection === 'credits-packages' }" @click="activeSection = 'credits-packages'">积分套餐</button>
      <button v-if="canSeeTab('analytics')" type="button" role="tab" :aria-selected="activeSection === 'analytics'"
        :class="{ active: activeSection === 'analytics' }" @click="activeSection = 'analytics'">经营分析</button>
      <button v-if="canSeeTab('commerce')" type="button" role="tab" :aria-selected="activeSection === 'commerce'"
        :class="{ active: activeSection === 'commerce' }"
        @click="activeSection = 'commerce'">订单核销</button>
      <button v-if="canSeeTab('ai-models')" type="button" role="tab" :aria-selected="activeSection === 'ai-models'"
        :class="{ active: activeSection === 'ai-models' }" @click="activeSection = 'ai-models'">AI 模型</button>
      <button v-if="canSeeTab('homepage-hot')" type="button" role="tab" :aria-selected="activeSection === 'homepage-hot'"
        :class="{ active: activeSection === 'homepage-hot' }" @click="activeSection = 'homepage-hot'">首页热点</button>
      <button v-if="canSeeTab('audit')" type="button" role="tab" :aria-selected="activeSection === 'audit'"
        :class="{ active: activeSection === 'audit' }" @click="activeSection = 'audit'">统一审计</button>
      <button v-if="canSeeTab('public-assets')" type="button" role="tab" :aria-selected="activeSection === 'public-assets'"
        :class="{ active: activeSection === 'public-assets' }"
        @click="activeSection = 'public-assets'">
        公共素材
      </button>
      <button v-if="canSeeTab('store-media')" type="button" role="tab" :aria-selected="activeSection === 'store-media'"
        :class="{ active: activeSection === 'store-media' }"
        @click="activeSection = 'store-media'">
        门店媒体
      </button>
      <!-- 任务书 #51：成员账号前缀改名（商家侧入口已下线，这里是全平台唯一入口）。
           刻意放在页签末尾——本文件多处测试按数字下标点页签，插在中间会整片错位 -->
      <button v-if="canSeeTab('org-prefix')" type="button" role="tab" :aria-selected="activeSection === 'org-prefix'"
        :class="{ active: activeSection === 'org-prefix' }"
        @click="activeSection = 'org-prefix'">
        账号前缀
      </button>
      <!-- 任务书 #57：创作风格 skill 库（查看/编辑/启停）。admin-only 功能，对 reviewer 隐藏。 -->
      <button
        v-if="canSeeTab('creation-skills')"
        type="button"
        role="tab"
        :aria-selected="activeSection === 'creation-skills'"
        :class="{ active: activeSection === 'creation-skills' }"
        @click="activeSection = 'creation-skills'"
      >
        创作风格
      </button>
      <!-- 任务书 #61：去AI味规则库（单选激活 + 内容编辑）。admin-only，对 reviewer 隐藏。 -->
      <button
        v-if="canSeeTab('humanize-skills')"
        type="button"
        role="tab"
        :aria-selected="activeSection === 'humanize-skills'"
        :class="{ active: activeSection === 'humanize-skills' }"
        @click="activeSection = 'humanize-skills'"
      >
        去AI味
      </button>
      <!-- 任务书 #64 卡7：BGM 曲库。新页签只能追加在 DOM 尾部（既有测试按下标点页签）。 -->
      <button
        v-if="canSeeTab('bgm-library')"
        type="button"
        role="tab"
        :aria-selected="activeSection === 'bgm-library'"
        :class="{ active: activeSection === 'bgm-library' }"
        @click="activeSection = 'bgm-library'"
      >
        BGM 曲库
      </button>
      <!-- 任务书 #65 卡7：视频任务监控（只读指标，admin-only）。同样只能追加在尾部。 -->
      <button
        v-if="canSeeTab('video-monitor')"
        type="button"
        role="tab"
        :aria-selected="activeSection === 'video-monitor'"
        :class="{ active: activeSection === 'video-monitor' }"
        @click="activeSection = 'video-monitor'"
      >
        视频任务
      </button>
      <!-- 2026-09-04：平台权限审核队列从用户端工作台底部治理区迁入（工作台撤「争议与平台治理」区）。
           后端门禁以 backend_role=platform_admin 为唯一授权权威（默认页签角色集），非 admin 403。 -->
      <button
        v-if="canSeeTab('permission-review')"
        type="button"
        role="tab"
        :aria-selected="activeSection === 'permission-review'"
        :class="{ active: activeSection === 'permission-review' }"
        @click="activeSection = 'permission-review'"
      >
        权限审核
      </button>
    </div>

    <div v-if="activeSection === 'org-renames'" class="admin-panel" role="tabpanel">
      <OrganizationRenameAdminPanel />
    </div>
    <div v-if="activeSection === 'org-prefix'" class="admin-panel" role="tabpanel">
      <OrganizationPrefixAdminPanel />
    </div>
    <div v-if="activeSection === 'users'" class="admin-panel" role="tabpanel">
      <form class="panel-toolbar search-toolbar" @submit.prevent="searchUsers">
        <!-- 任务书 #72 卡C：状态/身份筛选（全部=不传参，变更即 offset 归零重载）。 -->
        <div class="user-filters">
          <label>状态
            <select v-model="userStatusFilter" class="user-filter-select" data-testid="user-status-filter" @change="applyUserFilters">
              <option value="">全部</option>
              <option value="active">正常</option>
              <option value="suspended">已停用</option>
              <option value="pending_review">待复核</option>
            </select>
          </label>
          <label>身份
            <select v-model="userIdentityFilter" class="user-filter-select" data-testid="user-identity-filter" @change="applyUserFilters">
              <option value="">全部</option>
              <option value="recommender">推荐官</option>
              <option value="merchant">商家</option>
              <option value="member">成员</option>
            </select>
          </label>
        </div>
        <div class="user-search-group">
          <input v-model="userSearch" type="search" maxlength="100" placeholder="搜索邮箱、昵称或账号 ID">
          <button class="refresh-btn" type="submit" :disabled="loading">搜索</button>
          <!-- 任务书 #71：商家身份唯一来源=平台初始化（D2/D4）。主按钮视觉同全局
               .btn-confirm，但刻意用独立类名——弹窗确认按钮依赖 .btn-confirm 查找语义。 -->
          <button class="init-merchant-btn" type="button" data-testid="open-merchant-init" @click="openInitDialog">
            初始化商家账号
          </button>
        </div>
      </form>
      <p v-if="loadError" class="error-msg" role="alert">{{ loadError }}</p>
      <p v-if="userActionMessage" class="action-success-msg" role="status" data-testid="user-action-message">
        {{ userActionMessage }}</p>
      <div v-if="loading" class="loading-state">加载中...</div>
      <template v-else>
      <div class="table-card">
        <div class="table-scroll">
        <table class="user-table">
          <thead><tr><th>邮箱</th><th>昵称</th><th>角色</th><th>状态</th><th>身份</th><th>后台角色</th>
            <th>积分余额</th><th>累计获得</th><th>累计使用</th><th>注册时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="user in users" :key="user.id" :class="{ 'row-suspended': user.status === 'suspended' }">
              <td class="td-email">{{ user.email }}</td><td>{{ user.displayName || '-' }}</td>
              <td><span class="role-tag" :class="'role-' + user.role">{{ user.role }}</span></td>
              <td><span class="badge" :class="userStatusMeta(user.status).badge">{{ userStatusMeta(user.status).label }}</span></td>
              <td class="td-identity">
                <span v-if="user.identities && (user.identities.recommender || user.identities.merchant || user.identities.member)"
                  class="identity-chip-group">
                  <span v-if="user.identities.recommender" class="type-tag">推荐官</span>
                  <span v-if="user.identities.merchant" class="type-tag"
                    :title="user.identities.ownedOrgNames || '未建主体'">商家</span>
                  <span v-if="user.identities.member" class="type-tag"
                    :title="user.identities.ownedOrgNames || '未建主体'">成员</span>
                </span>
                <span v-else class="td-muted">—</span>
              </td>
              <td>{{ user.roles && user.roles.length ? user.roles.join('、') : '—' }}</td>
              <td class="td-balance">{{ user.balance }}</td><td>{{ user.totalEarned }}</td>
              <td>{{ user.totalSpent }}</td><td class="td-time">{{ formatDate(user.createdAt) }}</td>
              <td class="user-row-btns">
                <button class="detail-btn" type="button" @click="openUserDetail(user)">详情</button>
                <button v-if="adminControls" class="adjust-btn" type="button" @click="openAdjust(user)">调整积分</button>
                <button v-if="adminControls && user.status !== 'suspended'" class="suspend-btn" type="button"
                  @click="openUserSuspend(user)">停用</button>
                <button v-if="adminControls && user.status === 'suspended'" class="restore-btn" type="button"
                  @click="openUserRestore(user)">恢复</button>
                <button v-if="adminControls" class="reset-btn" type="button"
                  @click="openUserResetPassword(user)">重置密码</button>
              </td>
            </tr>
            <tr v-if="users.length === 0"><td colspan="11" class="td-empty">暂无用户</td></tr>
          </tbody>
        </table>
        </div>
      </div>
      <OpsPagination :total="usersTotal" :limit="usersLimit" :offset="usersOffset"
        @change="changeUsersPage" @change-limit="changeUsersLimit" />
      </template>
    </div>

    <div v-else-if="activeSection === 'kyb'" class="admin-panel" role="tabpanel">
      <div class="panel-toolbar">
        <div><h3>待审核申请</h3><p>按提交时间顺序处理商户、门店和收款账户资料</p></div>
        <button class="refresh-btn" type="button" :disabled="kybLoading" @click="loadKybRequests">刷新</button>
      </div>
      <p v-if="kybError" class="error-msg" role="alert">{{ kybError }}</p>
      <div v-if="kybLoading" class="loading-state">加载中...</div>
      <template v-else>
      <div class="table-card">
        <div class="table-scroll">
        <table class="user-table kyb-table">
          <thead><tr><th>类型</th><th>组织</th><th>目标</th><th>提交时间</th><th>审核时限</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in kybRequests" :key="item.id">
              <td><span class="type-tag">{{ verificationTypeLabels[item.verificationType] }}</span></td>
              <td class="id-cell" :title="item.organizationId">{{ item.organizationId }}</td>
              <td class="id-cell" :title="item.targetId || ''">{{ item.targetId || '-' }}</td>
              <td class="td-time">{{ formatDateTime(item.createdAt) }}</td>
              <td class="td-time" :class="{ overdue: isOverdue(item.reviewDeadline) }">
                {{ formatDateTime(item.reviewDeadline) }}
              </td>
              <td class="review-actions">
                <button class="approve-btn" type="button" @click="openReview(item, 'approve')">通过</button>
                <button class="reject-btn" type="button" @click="openReview(item, 'reject')">拒绝</button>
              </td>
            </tr>
            <tr v-if="kybRequests.length === 0"><td colspan="6" class="td-empty">暂无待审核申请</td></tr>
          </tbody>
        </table>
        </div>
      </div>
      <OpsPagination :total="kybTotal" :limit="kybLimit" :offset="kybOffset"
        @change="changeKybPage" @change-limit="changeKybLimit" />
      </template>
    </div>

    <div v-else-if="activeSection === 'recommenders'" class="admin-panel" role="tabpanel">
      <div class="panel-toolbar">
        <div><h3>推荐官平台认证</h3><p>自助开通不受影响，认证通过后获得平台认证标识</p></div>
        <button class="refresh-btn" type="button" :disabled="recommenderLoading" @click="loadRecommenderRequests">刷新</button>
      </div>
      <p v-if="recommenderError" class="error-msg" role="alert">{{ recommenderError }}</p>
      <div v-if="recommenderLoading" class="loading-state">加载中...</div>
      <template v-else>
      <div class="table-card">
        <div class="table-scroll">
        <table class="user-table kyb-table">
          <thead><tr><th>账号</th><th>材料</th><th>提交时间</th><th>审核时限</th><th>审核原因</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="item in recommenderRequests" :key="item.id">
              <td class="id-cell" :title="item.accountId">{{ item.accountId }}</td>
              <td class="materials-cell"><code>{{ item.materials || '—' }}</code></td>
              <td class="td-time">{{ formatDateTime(item.createdAt || null) }}</td>
              <td class="td-time">{{ formatDateTime(item.reviewDeadline || null) }}</td>
              <td>
                <input v-model="recommenderNotes[item.id]" class="field-input" type="text" maxlength="500" placeholder="拒绝原因（拒绝必填）" />
              </td>
              <td class="review-actions">
                <button class="approve-btn" type="button" @click="reviewRecommender(item, 'approve')">通过</button>
                <button class="reject-btn" type="button" @click="reviewRecommender(item, 'reject')">拒绝</button>
              </td>
            </tr>
            <tr v-if="recommenderRequests.length === 0"><td colspan="6" class="td-empty">暂无待审核认证</td></tr>
          </tbody>
        </table>
        </div>
      </div>
      <OpsPagination :total="recommenderTotal" :limit="recommenderLimit" :offset="recommenderOffset"
        @change="changeRecommenderPage" @change-limit="changeRecommenderLimit" />
      </template>
    </div>

    <div v-else-if="activeSection === 'tasks'" class="admin-panel" role="tabpanel">
      <div class="panel-toolbar">
        <div><h3>任务审核</h3><p>全审政策：所有任务提交后需审核通过才在大厅上架</p></div>
        <form class="search-toolbar" @submit.prevent="submitTaskSearch">
          <input v-model="taskSearch" type="search" maxlength="100" placeholder="搜索任务标题或描述">
          <button class="refresh-btn" type="submit" :disabled="taskReviewLoading">搜索</button>
        </form>
      </div>
      <div class="review-status-bar">
        <div class="status-pill-group" aria-label="任务审核状态筛选">
          <button v-for="option in REVIEW_STATUS_OPTIONS" :key="option.value" type="button"
            class="status-pill" :class="{ active: reviewStatus === option.value }"
            :aria-pressed="reviewStatus === option.value" @click="setReviewStatus(option.value)">
            {{ option.label }}
          </button>
        </div>
        <div v-if="reviewStats" class="review-stats" aria-label="审核统计">
          <span class="badge badge-warning">待审 <span class="gl-num">{{ reviewStats.pending }}</span></span>
          <span class="badge badge-danger">超时 <span class="gl-num">{{ reviewStats.overdue }}</span></span>
          <span class="badge badge-success">24h 通过 <span class="gl-num">{{ reviewStats.approvedLast24Hours }}</span></span>
          <span class="badge badge-danger">24h 驳回 <span class="gl-num">{{ reviewStats.rejectedLast24Hours }}</span></span>
        </div>
      </div>
      <p v-if="taskReviewError" class="error-msg" role="alert">{{ taskReviewError }}</p>
      <div v-if="taskReviewLoading" class="loading-state">加载中...</div>
      <template v-else>
        <div class="table-card">
          <div class="table-scroll">
          <table class="user-table kyb-table">
            <thead><tr><th>标题</th><th>类型</th><th>平台</th><th>赏金</th><th>组织</th><th>状态</th><th>驳回原因</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="t in reviewTasks" :key="t.id">
                <td>{{ t.title }}</td>
                <!-- 任务书 #75 卡 D4：套餐推广任务类型标识（佣金来自套餐版本快照，任务侧无赏金）。 -->
                <td>
                  <span v-if="t.commercePackageId" class="badge badge-success" title="末次点击单归因，改绑为人工纠错">套餐推广</span>
                  <span v-else class="type-tag">—</span>
                </td>
                <td><span class="type-tag">{{ t.platform || '—' }}</span></td>
                <td class="td-balance">{{ t.bountyCents ? '¥' + (t.bountyCents / 100).toFixed(2) : (t.commercePackageId ? '套餐分佣' : '—') }}</td>
                <td class="id-cell" :title="t.organizationId">{{ t.organizationId }}</td>
                <td><span class="badge" :class="reviewStatusOption.badge">{{ reviewStatusOption.label }}</span></td>
                <td>
                  <input v-if="reviewStatus === 'pending_review'" v-model="taskReviewNotes[t.id]" class="field-input" type="text" maxlength="500" placeholder="驳回原因（驳回必填）" />
                  <div v-else-if="reviewStatus === 'rejected'" class="review-note-history">
                    <span>{{ t.lastReviewNote || '—' }}</span>
                    <span class="td-time">{{ formatDateTime(t.lastReviewedAt ?? null) }}</span>
                  </div>
                  <span v-else>—</span>
                </td>
                <td class="review-actions">
                  <template v-if="reviewStatus === 'pending_review'">
                    <button class="approve-btn" type="button" @click="reviewTask(t, 'approve')">通过</button>
                    <button class="reject-btn" type="button" @click="reviewTask(t, 'reject')">驳回</button>
                  </template>
                  <!-- 已通过/已驳回视图不再给操作入口（决策 F：终态再审后端 409，前端不暴露入口） -->
                  <span v-else>—</span>
                </td>
              </tr>
              <tr v-if="reviewTasks.length === 0"><td colspan="8" class="td-empty">{{ reviewStatusOption.empty }}</td></tr>
            </tbody>
          </table>
          </div>
        </div>
        <OpsPagination :total="taskTotal" :limit="taskLimit" :offset="taskOffset"
          @change="changeTaskPage" @change-limit="changeTaskLimit" />
      </template>
    </div>

    <div v-else-if="activeSection === 'reputation'" class="admin-panel" role="tabpanel">
      <ReputationAdminPanel />
    </div>

    <div v-else-if="activeSection === 'judges'" class="admin-panel" role="tabpanel">
      <JudgeAdminPanel />
    </div>

    <div v-else-if="activeSection === 'finance'" class="admin-panel" role="tabpanel">
      <div class="panel-toolbar">
        <div><h3>账本流水</h3><p>双录账本（journal/posting），按组织筛选。真实 PSP 接入前仅 sandbox 流水。</p></div>
        <button class="refresh-btn" type="button" :disabled="journalLoading" @click="loadJournals">刷新</button>
      </div>
      <div class="ops-filters">
        <label>组织 ID
          <input v-model="journalOrgFilter" type="text" placeholder="留空 = 全量" @keyup.enter="applyJournalFilter" />
        </label>
        <button type="button" class="refresh-btn" :disabled="journalLoading" @click="applyJournalFilter">查询</button>
      </div>
      <p v-if="journalError" class="error-msg" role="alert">{{ journalError }}</p>
      <div v-if="journalLoading" class="loading-state">加载中...</div>
      <template v-else>
      <div class="table-card">
        <div class="table-scroll">
        <table class="user-table kyb-table">
          <thead><tr><th>类型</th><th>组织</th><th>关联</th><th>备注</th><th>幂等键</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="j in journals" :key="j.id">
              <td><span class="type-tag">{{ JOURNAL_TYPE_LABELS[j.type] || j.type }}</span></td>
              <td class="id-cell" :title="j.organizationId || ''">{{ j.organizationId || '—' }}</td>
              <td class="id-cell" :title="j.engagementRef || ''">{{ j.engagementRef || '—' }}</td>
              <td>{{ j.memo || '—' }}</td>
              <td class="id-cell" :title="j.operationId || ''">{{ j.operationId ? j.operationId.slice(0, 16) + '…' : '—' }}</td>
              <td class="td-time">{{ formatDateTime(j.createdAt) }}</td>
            </tr>
            <tr v-if="journals.length === 0"><td colspan="6" class="td-empty">暂无流水</td></tr>
          </tbody>
        </table>
        </div>
      </div>
      <OpsPagination :total="journalTotal" :limit="journalLimit" :offset="journalOffset"
        @change="changeJournalPage" @change-limit="changeJournalLimit" />
      </template>
    </div>

    <div v-else-if="activeSection === 'risk'" class="admin-panel" role="tabpanel">
      <RiskAdminPanel />
    </div>
    <div v-else-if="activeSection === 'credits-packages'" class="admin-panel" role="tabpanel">
      <CreditsPackagesPanel />
    </div>

    <div v-else-if="activeSection === 'analytics'" class="admin-panel" role="tabpanel">
      <BusinessAnalyticsPanel admin />
    </div>

    <div v-else-if="activeSection === 'commerce'" class="admin-panel" role="tabpanel">
      <CommerceAdminPanel />
    </div>

    <div v-else-if="activeSection === 'public-assets'" class="admin-panel" role="tabpanel">
      <PublicAssetsAdminPanel />
    </div>

    <div v-else-if="activeSection === 'store-media'" class="admin-panel" role="tabpanel">
      <StoreMediaModerationAdminPanel />
    </div>

    <div v-else-if="activeSection === 'ai-models'" class="admin-panel" role="tabpanel">
      <!-- 凭据在上、模型在下：模型配置引用凭据，先有凭据才谈得上指向它。
           changed 转发：凭据增删改/停用后模型面板立即重拉凭据下拉，无需整页刷新。 -->
      <AiPlatformCredentialsPanel @changed="modelsPanel?.reloadCredentials()" />
      <AiPlatformModelsPanel ref="modelsPanel" />
      <AiPriceTablePanel />
    </div>

    <div v-else-if="activeSection === 'homepage-hot'" class="admin-panel" role="tabpanel">
      <!-- 任务书 #47 S7b / D18①：热点数据源平台级配置（用户级每用户配置已下线） -->
      <HomepageHotConfigPanel />
    </div>

    <div v-else-if="activeSection === 'creation-skills'" class="admin-panel" role="tabpanel">
      <!-- 任务书 #57：创作风格 skill 库（标题套路/体裁/文风的目录与注入 prompt 运营） -->
      <CreationSkillsAdminPanel />
    </div>

    <div v-else-if="activeSection === 'humanize-skills'" class="admin-panel" role="tabpanel">
      <!-- 任务书 #61：去AI味规则库（平台级单选激活，创作型 12 场景统一注入） -->
      <HumanizeSkillsAdminPanel />
    </div>
    <div v-else-if="activeSection === 'bgm-library'" class="admin-panel" role="tabpanel">
      <BgmTracksAdminPanel />
    </div>
    <div v-else-if="activeSection === 'video-monitor'" class="admin-panel" role="tabpanel">
      <!-- 任务书 #65 卡7：视频任务监控（7d/30d 只读指标） -->
      <VideoTaskMonitorPanel />
    </div>
    <div v-else-if="activeSection === 'permission-review'" class="admin-panel" role="tabpanel">
      <!-- 2026-09-04：平台侧商家权限升级审核队列（原用户端工作台底部挂载，迁治理台归口） -->
      <PermissionReviewPanel />
    </div>

    <div v-else class="admin-panel" role="tabpanel">
      <UnifiedAuditPanel />
    </div>

    <Teleport to="body">
      <AdjustCreditsDialog
        :target="adjustTarget"
        :amount="adjustAmount"
        :note="adjustNote"
        :error="adjustError"
        :adjusting="adjusting"
        @close="adjustTarget = null"
        @update:amount="adjustAmount = $event"
        @update:note="adjustNote = $event"
        @confirm="handleAdjust"
      />
      <MerchantAccountInitDialog
        :open="initDialogOpen"
        :email="initEmail"
        :display-name="initDisplayName"
        :email-error="initEmailError"
        :error="initError"
        :submitting="initSubmitting"
        :result="initResult"
        @close="closeInitDialog"
        @update:email="initEmail = $event"
        @update:display-name="initDisplayName = $event"
        @submit="handleInitMerchantAccount"
      />
      <!-- 任务书 #72 卡 D：账号详情抽屉（只读身份档案 + admin 管控分区）与两个管控弹窗 -->
      <AdminUserDetailDrawer
        :user="detailUser"
        :admin="adminControls"
        @close="closeUserDetail"
        @refresh="void refreshUsersAfterAction()"
        @adjust="openAdjust"
      />
      <AdminUserSuspendDialog
        :open="Boolean(suspendDialogUser)"
        :user="suspendDialogUser"
        :mode="suspendDialogMode"
        @close="suspendDialogUser = null"
        @done="handleSuspendDone"
      />
      <AdminUserResetPasswordDialog
        :open="Boolean(resetDialogUser)"
        :user="resetDialogUser"
        @close="resetDialogUser = null"
        @done="handleResetDone"
      />
      <div v-if="reviewTarget" class="modal-overlay" @click.self="closeReview">
        <div class="modal-card review-modal" role="dialog" aria-modal="true" aria-labelledby="kyb-review-title">
          <header class="modal-header">
            <h3 id="kyb-review-title" class="modal-title">
              {{ reviewDecision === 'approve' ? '通过' : '拒绝' }}{{ verificationTypeLabels[reviewTarget.verificationType] }}
            </h3>
            <button class="modal-close" type="button" aria-label="关闭" @click="closeReview">关闭</button>
          </header>
          <div class="modal-body">
            <dl class="review-summary">
              <dt>组织</dt><dd>{{ reviewTarget.organizationId }}</dd>
              <dt>目标</dt><dd>{{ reviewTarget.targetId || '-' }}</dd>
            </dl>
            <div v-if="detailLoading" class="detail-loading">正在加载审核资料...</div>
            <section v-else-if="reviewDetail" class="review-detail" aria-label="审核资料">
              <dl v-if="reviewDetail.subject.type === 'merchant_profile'" class="detail-grid">
                <dt>法定名称</dt><dd>{{ reviewDetail.subject.legalName || '-' }}</dd>
                <dt>信用代码</dt><dd>{{ reviewDetail.subject.unifiedSocialCreditCode || '-' }}</dd>
                <dt>主体类型</dt><dd>{{ reviewDetail.subject.businessType || '-' }}</dd>
                <dt>行业类型</dt><dd>{{ industryLabels[reviewDetail.subject.industry || ''] || reviewDetail.subject.industry || '-' }}</dd>
                <dt>法人</dt><dd>{{ reviewDetail.subject.legalPersonName || '-' }}</dd>
                <dt>法人证件</dt><dd>{{ reviewDetail.subject.legalPersonIdNumberMasked || '-' }}</dd>
                <dt>成立日期</dt><dd>{{ reviewDetail.subject.establishmentDate || '-' }}</dd>
                <dt>经营地址</dt><dd>{{ formatStructured(reviewDetail.subject.businessAddress) }}</dd>
                <dt>联系电话</dt><dd>{{ reviewDetail.subject.contactPhone || '-' }}</dd>
                <dt>联系邮箱</dt><dd>{{ reviewDetail.subject.contactEmail || '-' }}</dd>
              </dl>
              <dl v-else-if="reviewDetail.subject.type === 'withdrawal_account'" class="detail-grid">
                <dt>账户类型</dt><dd>{{ accountTypeLabels[reviewDetail.subject.accountType] }}</dd>
                <dt>账户名称</dt><dd>{{ reviewDetail.subject.accountName }}</dd>
                <dt>收款账号</dt><dd>{{ reviewDetail.subject.accountNumberMasked }}</dd>
                <dt>银行</dt><dd>{{ reviewDetail.subject.bankName || '-' }}</dd>
                <dt>支行</dt><dd>{{ reviewDetail.subject.branchName || '-' }}</dd>
              </dl>
              <dl v-else class="detail-grid">
                <dt>地址</dt><dd>{{ formatStructured(reviewDetail.subject.address) }}</dd>
                <dt>电话</dt><dd>{{ reviewDetail.subject.phone || '-' }}</dd>
                <dt>营业时间</dt><dd>{{ formatStructured(reviewDetail.subject.businessHours) }}</dd>
                <dt>说明</dt><dd>{{ reviewDetail.subject.description || '-' }}</dd>
              </dl>
              <div v-if="reviewDetail.attachments.length" class="review-materials">
                <h4>审核材料</h4>
                <div v-for="attachment in reviewDetail.attachments" :key="attachment.id" class="material-row">
                  <div>
                    <strong>{{ attachmentTypeLabels[attachment.attachmentType] }}</strong>
                    <span>{{ attachment.mimeType || '未知类型' }} · {{ formatBytes(attachment.sizeBytes) }}</span>
                  </div>
                  <button type="button" class="material-view" @click="openAttachment(attachment.id)">查看</button>
                </div>
              </div>
            </section>
            <label class="field-label">审核备注
              <textarea v-model="reviewNote" class="field-input field-textarea" maxlength="500"
                :placeholder="reviewDecision === 'reject' ? '请填写拒绝原因' : '选填审核说明'" />
            </label>
            <p v-if="reviewError" class="error-msg" role="alert">{{ reviewError }}</p>
            <div class="modal-actions">
              <button class="btn-cancel" type="button" @click="closeReview">取消</button>
              <button class="btn-confirm" :class="{ danger: reviewDecision === 'reject' }" type="button"
                :disabled="reviewing || detailLoading || !reviewDetail" @click="handleReview">
                {{ reviewing ? '提交中...' : '确认' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AiPlatformCredentialsPanel from '../../components/AiPlatformCredentialsPanel.vue'
import AiPlatformModelsPanel from '../../components/AiPlatformModelsPanel.vue'
import AiPriceTablePanel from '../../components/AiPriceTablePanel.vue'
import BgmTracksAdminPanel from '../../components/BgmTracksAdminPanel.vue'
import HomepageHotConfigPanel from '../../components/HomepageHotConfigPanel.vue'
import CreationSkillsAdminPanel from './components/CreationSkillsAdminPanel.vue'
import HumanizeSkillsAdminPanel from './components/HumanizeSkillsAdminPanel.vue'
import VideoTaskMonitorPanel from './components/VideoTaskMonitorPanel.vue'
import PermissionReviewPanel from '../../components/PermissionReviewPanel.vue'
import CommerceAdminPanel from '../../components/CommerceAdminPanel.vue'
import JudgeAdminPanel from '../../components/JudgeAdminPanel.vue'
import ReputationAdminPanel from '../../components/ReputationAdminPanel.vue'
import RiskAdminPanel from '../../components/RiskAdminPanel.vue'
import CreditsPackagesPanel from '../../components/CreditsPackagesPanel.vue'
import BusinessAnalyticsPanel from '../../components/BusinessAnalyticsPanel.vue'
import UnifiedAuditPanel from '../../components/UnifiedAuditPanel.vue'
import AdjustCreditsDialog from './components/AdjustCreditsDialog.vue'
import MerchantAccountInitDialog from './components/MerchantAccountInitDialog.vue'
import AdminUserDetailDrawer from './components/AdminUserDetailDrawer.vue'
import AdminUserSuspendDialog from './components/AdminUserSuspendDialog.vue'
import AdminUserResetPasswordDialog from './components/AdminUserResetPasswordDialog.vue'
import OpsPagination from './components/OpsPagination.vue'
import PublicAssetsAdminPanel from './components/PublicAssetsAdminPanel.vue'
import OrganizationRenameAdminPanel from './components/OrganizationRenameAdminPanel.vue'
import OrganizationPrefixAdminPanel from './components/OrganizationPrefixAdminPanel.vue'
import StoreMediaModerationAdminPanel from './components/StoreMediaModerationAdminPanel.vue'
import { useGrassland } from '../../composables/useGrassland'
import { useAuth } from '../../composables/useAuth'
import { request, GrasslandHttpError } from '../../composables/grassland-http'
import type {
  KybVerificationDetail,
  KybVerificationRequest,
  KybVerificationType,
  PagedResult,
  RecommenderVerificationRequest,
  Task,
  MerchantAttachmentType,
  WithdrawalAccountType,
} from '../../types/grassland'

interface UserItem {
  id: string
  email: string
  displayName: string | null
  role: string
  status: string
  createdAt: string
  balance: number
  totalEarned: number
  totalSpent: number
  /** backend_role dbValue 列表（卡A 起后端已返回）。 */
  roles?: string[]
  /** 身份/组织归属聚合（任务书 #72 卡A）：ownedOrgNames=null 表示未建/非 owner；
   *  ownedOrgs 结构化清单（卡D 连坐，additive）供详情抽屉组织管控分区定位 orgId。 */
  identities?: {
    recommender: boolean
    merchant: boolean
    member: boolean
    ownedOrgNames: string | null
    ownedOrgs?: Array<{ id: string; name: string; status: string }>
  }
}

/**
 * 页签可见角色集（任务书 #72 卡C D4）。未列出的页签默认 platform_admin 专属。
 * users=三查看角色，与后端卡A门禁同口径（platform_admin+customer_service+risk——
 * content_reviewer 无 /api/admin/users 读取权限，维持既有不可见）；
 * 公共素材/门店媒体/账号前缀=content_reviewer 既有可见集合原样保留。
 */
const TAB_ROLES: Partial<Record<string, readonly string[]>> = {
  users: ['platform_admin', 'customer_service', 'risk'],
  'public-assets': ['platform_admin', 'content_reviewer'],
  'store-media': ['platform_admin', 'content_reviewer'],
  'org-prefix': ['platform_admin', 'content_reviewer'],
}
const DEFAULT_TAB_ROLES: readonly string[] = ['platform_admin']

const { currentUser, hasBackendRole } = useAuth()
/** 会话未装载（冷会话直登治理台）时维持全可见，装载后按角色收敛（与原 reviewerOnly 同拍）。 */
function canSeeTab(tab: string): boolean {
  if (!currentUser.value) return true
  const roles = TAB_ROLES[tab] ?? DEFAULT_TAB_ROLES
  return roles.some((role) => hasBackendRole(role))
}
/** 第一个可见页签（会话装载后活动页签被角色收敛时跳回；reviewer → public-assets 与原语义一致）。 */
function fallbackSection(): typeof activeSection.value {
  const candidates: Array<typeof activeSection.value> = ['users', 'public-assets']
  return candidates.find((tab) => canSeeTab(tab)) ?? 'public-assets'
}
/** 管控按钮（调整积分/停用/恢复/重置密码）platform_admin 专属——与页签可见性同源（卡C）。 */
const adminControls = computed(() => !currentUser.value || hasBackendRole('platform_admin'))

/** 状态列徽标：active→success / suspended→danger / 其余（pending_review 等）→warning。 */
const USER_STATUS_META: Record<string, { label: string; badge: string }> = {
  active: { label: '正常', badge: 'badge-success' },
  suspended: { label: '已停用', badge: 'badge-danger' },
  pending_review: { label: '待复核', badge: 'badge-warning' },
  deleted: { label: '已删除', badge: 'badge-warning' },
}
function userStatusMeta(status: string): { label: string; badge: string } {
  return USER_STATUS_META[status] ?? { label: status, badge: 'badge-warning' }
}
/** 五个内置列表每页条数真源（默认 10，OpsPagination 触发 10/20/50/100 切换并归零 offset）。 */
const usersLimit = ref(10)
/** 平台模型面板引用：接收凭据面板 changed 转发，重拉凭据下拉（见 ai-models 页签）。 */
const modelsPanel = ref<InstanceType<typeof AiPlatformModelsPanel> | null>(null)
const kybLimit = ref(10)
const recommenderLimit = ref(10)
const taskLimit = ref(10)
const journalLimit = ref(10)
const users = ref<UserItem[]>([])
const userSearch = ref('')
/** 状态/身份筛选（任务书 #72 卡C）：空串=全部=不传参。 */
const userStatusFilter = ref('')
const userIdentityFilter = ref('')
const usersOffset = ref(0)
const usersTotal = ref(0)
const activeSection = ref<
  'users' | 'kyb' | 'org-renames' | 'org-prefix' | 'recommenders' | 'tasks' | 'reputation' | 'judges' | 'finance' | 'risk' | 'credits-packages' | 'analytics' | 'commerce' | 'ai-models' | 'homepage-hot' | 'creation-skills' | 'humanize-skills' | 'bgm-library' | 'video-monitor' | 'permission-review' | 'public-assets' | 'store-media' | 'audit'
>('users')
const loading = ref(false)
const loadError = ref('')

const adjustTarget = ref<UserItem | null>(null)
const adjustAmount = ref(0)
const adjustNote = ref('')
const adjusting = ref(false)
const adjustError = ref('')

const grassland = useGrassland()
const kybRequests = ref<KybVerificationRequest[]>([])
const kybLoading = ref(false)
const kybError = ref('')
const kybOffset = ref(0)
const kybTotal = ref(0)
const recommenderRequests = ref<RecommenderVerificationRequest[]>([])
const recommenderLoading = ref(false)
const recommenderError = ref('')
const recommenderNotes = ref<Record<string, string>>({})
const recommenderOffset = ref(0)
const recommenderTotal = ref(0)
interface JournalEntry {
  id: string
  type: string
  operationId: string | null
  currency: string
  organizationId: string | null
  engagementRef: string | null
  memo: string | null
  createdAt: string | null
}
const journals = ref<JournalEntry[]>([])
const journalLoading = ref(false)
const journalError = ref('')
const journalOrgFilter = ref('')
const journalOffset = ref(0)
const journalTotal = ref(0)

/** 任务审核三态筛选项（后端 status 参数 + 行状态徽标 + 空态文案）。 */
const REVIEW_STATUS_OPTIONS = [
  { value: 'pending_review', label: '待审核', badge: 'badge-warning', empty: '暂无待审核任务' },
  { value: 'published', label: '已通过', badge: 'badge-success', empty: '暂无已通过任务' },
  { value: 'rejected', label: '已驳回', badge: 'badge-danger', empty: '暂无已驳回任务' },
] as const
type ReviewStatusFilter = typeof REVIEW_STATUS_OPTIONS[number]['value']

const reviewTasks = ref<Task[]>([])
const taskSearch = ref('')
const taskReviewLoading = ref(false)
const taskReviewError = ref('')
const taskReviewNotes = ref<Record<string, string>>({})
const reviewStatus = ref<ReviewStatusFilter>('pending_review')
const taskOffset = ref(0)
const taskTotal = ref(0)
const reviewStatusOption = computed(() =>
  REVIEW_STATUS_OPTIONS.find((option) => option.value === reviewStatus.value) ?? REVIEW_STATUS_OPTIONS[0])

/** 任务审核统计条（进页签才请求，不进 onMounted）。 */
interface ReviewStats {
  pending: number
  overdue: number
  approvedLast24Hours: number
  rejectedLast24Hours: number
}
const reviewStats = ref<ReviewStats | null>(null)
const reviewTarget = ref<KybVerificationRequest | null>(null)
const reviewDecision = ref<'approve' | 'reject'>('approve')
const reviewNote = ref('')
const reviewing = ref(false)
const reviewError = ref('')
const reviewDetail = ref<KybVerificationDetail | null>(null)
const detailLoading = ref(false)
let reviewLoadVersion = 0

const verificationTypeLabels: Record<KybVerificationType, string> = {
  merchant_profile: '商户资料',
  store_profile: '门店资料',
  withdrawal_account: '收款账户',
}

const accountTypeLabels: Record<WithdrawalAccountType, string> = {
  bank_card: '银行卡',
  alipay: '支付宝',
  wechat: '微信',
}

const industryLabels: Record<string, string> = {
  catering: '餐饮', retail: '零售', beauty: '美业', education: '教育培训',
  e_commerce: '电商', healthcare: '医疗健康', finance: '金融服务',
  real_estate: '房地产', travel: '旅游', children: '母婴儿童', other: '其他',
}

const JOURNAL_TYPE_LABELS: Record<string, string> = {
  DEPOSIT: '充值', RESERVE: '预留', RELEASE: '释放',
  CAPTURE: '结算', REVERSE: '冲正', WITHDRAW: '提现', OPENING: '期初',
  CONSUMER_PAYMENT: '消费支付', CONSUMER_REFUND: '消费退款', CONSUMER_SPLIT: '核销分账',
  FREEBIE_RESERVE: '霸王餐押金预付', FREEBIE_REFUND: '霸王餐押金返还',
  FREEBIE_COMPENSATE: '霸王餐押金补偿',
}

async function loadReviewTasks(): Promise<void> {
  taskReviewLoading.value = true
  taskReviewError.value = ''
  const result = await grassland.listReviewTasks({
    status: reviewStatus.value,
    q: taskSearch.value || undefined,
    limit: taskLimit.value,
    offset: taskOffset.value,
  })
  if (result) {
    reviewTasks.value = [...result.items]
    taskTotal.value = result.total
  } else {
    taskReviewError.value = grassland.error.value || '任务审核列表加载失败'
  }
  taskReviewLoading.value = false
}

async function loadReviewStats(): Promise<void> {
  try {
    reviewStats.value = await request<ReviewStats>('/api/admin/tasks/review/stats')
  } catch {
    reviewStats.value = null
  }
}

/** 切三态：offset 归零重载（当前状态重复点击不重发请求）。 */
function setReviewStatus(status: ReviewStatusFilter): void {
  if (reviewStatus.value === status) return
  reviewStatus.value = status
  taskOffset.value = 0
  void loadReviewTasks()
}

function submitTaskSearch(): void {
  taskOffset.value = 0
  void loadReviewTasks()
}

function changeTaskPage(offset: number): void {
  taskOffset.value = offset
  void loadReviewTasks()
}

/** 切每页条数：limit 生效 + offset 归零重载。 */
function changeTaskLimit(limit: number): void {
  taskLimit.value = limit
  taskOffset.value = 0
  void loadReviewTasks()
}

async function reviewTask(task: Task, decision: 'approve' | 'reject'): Promise<void> {
  const note = (taskReviewNotes.value[task.id] || '').trim()
  if (decision === 'reject' && !note) {
    taskReviewError.value = '驳回任务必须填写原因'
    return
  }
  taskReviewError.value = ''
  const result = decision === 'approve'
    ? await grassland.approveTaskReview(task.id, task.version)
    : await grassland.rejectTaskReview(task.id, task.version, note)
  if (result) {
    // 带当前筛选条件重载本页，替代本地删行；删行致越界由 OpsPagination 收敛兑底。
    // 待审数变了：同步刷新统计条与页签徽标。
    await Promise.all([loadReviewTasks(), loadReviewStats()])
    delete taskReviewNotes.value[task.id]
  } else {
    taskReviewError.value = grassland.error.value || '审核失败'
  }
}

async function loadJournals(): Promise<void> {
  journalLoading.value = true
  journalError.value = ''
  const result = await grassland.listFinanceJournals({
    organizationId: journalOrgFilter.value || undefined,
    limit: journalLimit.value,
    offset: journalOffset.value,
  })
  if (result) {
    journals.value = result.items as unknown as JournalEntry[]
    journalTotal.value = result.total
  } else {
    journalError.value = grassland.error.value || '账本流水加载失败'
  }
  journalLoading.value = false
}

/** 应用组织筛选：offset 归零后重载。 */
function applyJournalFilter(): void {
  journalOffset.value = 0
  void loadJournals()
}

function changeJournalPage(offset: number): void {
  journalOffset.value = offset
  void loadJournals()
}

function changeJournalLimit(limit: number): void {
  journalLimit.value = limit
  journalOffset.value = 0
  void loadJournals()
}

const attachmentTypeLabels: Record<MerchantAttachmentType, string> = {
  business_license: '营业执照',
  legal_person_id_front: '法人证件正面',
  legal_person_id_back: '法人证件反面',
  industry_license: '行业许可证',
  financial_qualification: '财务资质',
  store_photo: '门店照片',
  other: '其他材料',
}

onMounted(() => {
  if (!canSeeTab('users')) {
    activeSection.value = fallbackSection()
    return
  }
  // kyb 对客服/风控不可见（页签收敛），首拉只拉可见面板避免 403 噪音
  void Promise.all([loadUsers(), canSeeTab('kyb') ? loadKybRequests() : Promise.resolve()])
})

/**
 * 冷会话直登治理台：AdminView 在登录前就已挂载，onMounted 的首拉会 401 并把错误留在默认页签
 * （此前靠「先在用户端登录、cookie 已存在」掩盖）。身份从无到有时补拉一次默认列表。
 */
watch(() => currentUser.value?.id, (id, prev) => {
  if (!id || id === prev || !canSeeTab('users')) return
  void Promise.all([loadUsers(), canSeeTab('kyb') ? loadKybRequests() : Promise.resolve()])
})

/** 会话装载/角色变化把活动页签收敛掉时，跳回第一个可见页签（原 reviewerOnly 重定向语义泛化）。 */
watch(() => canSeeTab(activeSection.value), (visible) => {
  if (!visible) activeSection.value = fallbackSection()
})

async function loadUsers(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    const query = userSearch.value.trim()
    const params = new URLSearchParams({
      limit: String(usersLimit.value),
      offset: String(usersOffset.value),
    })
    if (query) params.set('q', query)
    if (userStatusFilter.value) params.set('status', userStatusFilter.value)
    if (userIdentityFilter.value) params.set('identityType', userIdentityFilter.value)
    const data = await request<PagedResult<UserItem>>(
      `/api/admin/users?${params.toString()}`,
      {},
      { fallbackError: '加载失败' },
    )
    users.value = data.items
    usersTotal.value = data.total
  } catch (e: unknown) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

/** 搜索提交：offset 归零后重载。 */
function searchUsers(): void {
  usersOffset.value = 0
  void loadUsers()
}

/** 状态/身份筛选变更：offset 归零重载（「全部」不传参，保持旧调用形态）。 */
function applyUserFilters(): void {
  usersOffset.value = 0
  void loadUsers()
}

function changeUsersPage(offset: number): void {
  usersOffset.value = offset
  void loadUsers()
}

function changeUsersLimit(limit: number): void {
  usersLimit.value = limit
  usersOffset.value = 0
  void loadUsers()
}

async function loadKybRequests(): Promise<void> {
  kybLoading.value = true
  kybError.value = ''
  const result = await grassland.listKybVerifications({ limit: kybLimit.value, offset: kybOffset.value })
  if (result) {
    kybRequests.value = [...result.items]
    kybTotal.value = result.total
  } else {
    kybError.value = grassland.error.value || 'KYB 审核队列加载失败'
  }
  kybLoading.value = false
}

function changeKybPage(offset: number): void {
  kybOffset.value = offset
  void loadKybRequests()
}

function changeKybLimit(limit: number): void {
  kybLimit.value = limit
  kybOffset.value = 0
  void loadKybRequests()
}

async function loadRecommenderRequests(): Promise<void> {
  recommenderLoading.value = true
  recommenderError.value = ''
  const result = await grassland.listRecommenderVerifications({ limit: recommenderLimit.value, offset: recommenderOffset.value })
  if (result) {
    recommenderRequests.value = [...result.items]
    recommenderTotal.value = result.total
  } else {
    recommenderError.value = grassland.error.value || '推荐官认证队列加载失败'
  }
  recommenderLoading.value = false
}

function changeRecommenderPage(offset: number): void {
  recommenderOffset.value = offset
  void loadRecommenderRequests()
}

function changeRecommenderLimit(limit: number): void {
  recommenderLimit.value = limit
  recommenderOffset.value = 0
  void loadRecommenderRequests()
}

async function reviewRecommender(request: RecommenderVerificationRequest, decision: 'approve' | 'reject'): Promise<void> {
  const note = (recommenderNotes.value[request.id] || '').trim()
  if (decision === 'reject' && !note) {
    recommenderError.value = '拒绝推荐官认证必须填写原因'
    return
  }
  recommenderError.value = ''
  const result = await grassland.reviewRecommenderVerification(request.id, decision, note || undefined)
  if (result) {
    // 带当前筛选重载本页（替代本地删行）；越界由分页组件收敛。
    await loadRecommenderRequests()
    delete recommenderNotes.value[request.id]
  } else {
    recommenderError.value = grassland.error.value || '审核失败'
  }
}

async function openReview(item: KybVerificationRequest, decision: 'approve' | 'reject'): Promise<void> {
  const loadVersion = ++reviewLoadVersion
  reviewTarget.value = item
  reviewDecision.value = decision
  reviewNote.value = ''
  reviewError.value = ''
  reviewDetail.value = null
  detailLoading.value = true
  const result = await grassland.getKybVerificationDetail(item.id)
  if (loadVersion !== reviewLoadVersion || reviewTarget.value?.id !== item.id) return
  if (result) {
    reviewDetail.value = result
  } else {
    reviewError.value = grassland.error.value || '审核详情加载失败'
  }
  detailLoading.value = false
}

function closeReview(): void {
  if (reviewing.value) return
  reviewLoadVersion += 1
  reviewTarget.value = null
  reviewDetail.value = null
  detailLoading.value = false
  reviewError.value = ''
}

async function handleReview(): Promise<void> {
  const target = reviewTarget.value
  if (!target || !reviewDetail.value || detailLoading.value) return
  if (reviewDecision.value === 'reject' && !reviewNote.value.trim()) {
    reviewError.value = '请填写拒绝原因'
    return
  }
  reviewing.value = true
  reviewError.value = ''
  const result = await grassland.reviewKybVerification(
    target.id, reviewDecision.value, reviewNote.value.trim() || undefined)
  if (result) {
    // 审核成功后带当前筛选重载本页（替代本地删行）；越界由分页组件收敛兑底。
    reviewTarget.value = null
    await loadKybRequests()
  } else {
    reviewError.value = grassland.error.value || '审核提交失败'
  }
  reviewing.value = false
}

async function openAttachment(attachmentId: string): Promise<void> {
  const target = reviewTarget.value
  if (!target) return
  reviewError.value = ''
  const result = await grassland.getKybAttachmentDownload(target.id, attachmentId)
  if (!result) {
    reviewError.value = grassland.error.value || '审核材料暂不可用'
    return
  }
  try {
    const url = new URL(result.downloadUrl, window.location.origin)
    if (!['http:', 'https:'].includes(url.protocol)) throw new Error('unsupported protocol')
    window.open(url.toString(), '_blank', 'noopener,noreferrer')
  } catch {
    reviewError.value = '审核材料地址无效'
  }
}

function openAdjust(user: UserItem): void {
  adjustTarget.value = user
  adjustAmount.value = 0
  adjustNote.value = ''
  adjustError.value = ''
}

// —— 任务书 #72 卡 D：详情抽屉 + 停用/恢复 + 重置密码弹窗 ——
const detailUser = ref<UserItem | null>(null)
const suspendDialogUser = ref<UserItem | null>(null)
const suspendDialogMode = ref<'suspend' | 'restore'>('suspend')
const resetDialogUser = ref<UserItem | null>(null)
const userActionMessage = ref('')

function openUserDetail(user: UserItem): void {
  detailUser.value = user
}

function openUserSuspend(user: UserItem): void {
  suspendDialogMode.value = 'suspend'
  suspendDialogUser.value = user
}

function openUserRestore(user: UserItem): void {
  suspendDialogMode.value = 'restore'
  suspendDialogUser.value = user
}

function openUserResetPassword(user: UserItem): void {
  resetDialogUser.value = user
}

function closeUserDetail(): void {
  detailUser.value = null
}

/** 管控成功统一收口：提示 + 刷新当前页（抽屉若开着则同步行数据，组织状态/角色随之更新）。 */
async function refreshUsersAfterAction(message?: string): Promise<void> {
  if (message) userActionMessage.value = message
  await loadUsers()
  if (detailUser.value) {
    detailUser.value = users.value.find((row) => row.id === detailUser.value?.id) ?? detailUser.value
  }
}

function handleSuspendDone(): void {
  suspendDialogUser.value = null
  void refreshUsersAfterAction(suspendDialogMode.value === 'suspend' ? '账号已停用' : '账号已恢复')
}

function handleResetDone(): void {
  resetDialogUser.value = null
  void refreshUsersAfterAction('密码已重置，请线下交付一次性初始密码')
}

async function handleAdjust(): Promise<void> {
  if (!adjustTarget.value) return
  if (adjustAmount.value === 0) {
    adjustError.value = '数量不能为 0'
    return
  }
  if (!adjustNote.value.trim()) {
    adjustError.value = '请输入备注'
    return
  }

  adjusting.value = true
  adjustError.value = ''

  try {
    await request('/api/admin/adjust-credits', {
      method: 'POST',
      body: JSON.stringify({
        userId: adjustTarget.value.id,
        amount: adjustAmount.value,
        note: adjustNote.value.trim(),
      }),
    }, { fallbackError: '调整失败' })
    adjustTarget.value = null
    await loadUsers()
  } catch (e: unknown) {
    adjustError.value = e instanceof Error ? e.message : '调整失败'
  } finally {
    adjusting.value = false
  }
}

/** 任务书 #71：初始化商家账号（商家身份唯一来源=平台初始化，D2/D4/D5）。 */
interface MerchantAccountInitResult {
  userId: string
  email: string
  displayName: string
  initialPassword: string
}
const initDialogOpen = ref(false)
const initEmail = ref('')
const initDisplayName = ref('')
const initEmailError = ref('')
const initError = ref('')
const initSubmitting = ref(false)
const initResult = ref<MerchantAccountInitResult | null>(null)

function openInitDialog(): void {
  initDialogOpen.value = true
  initEmail.value = ''
  initDisplayName.value = ''
  initEmailError.value = ''
  initError.value = ''
  initResult.value = null
}

async function handleInitMerchantAccount(): Promise<void> {
  initEmailError.value = ''
  initError.value = ''
  const email = initEmail.value.trim()
  const displayName = initDisplayName.value.trim()
  if (!email || !email.includes('@')) {
    initEmailError.value = '请填写有效邮箱'
    return
  }
  if (!displayName) {
    initError.value = '请填写姓名'
    return
  }

  initSubmitting.value = true
  try {
    initResult.value = await request<MerchantAccountInitResult>('/api/admin/merchant-accounts', {
      method: 'POST',
      body: JSON.stringify({ email, displayName }),
    }, { fallbackError: '初始化失败' })
  } catch (e: unknown) {
    if (e instanceof GrasslandHttpError && e.status === 409) {
      // 邮箱已注册：字段级错误（仅支持全新邮箱，D5）
      initEmailError.value = e.message || '该邮箱已注册；商家账号仅支持全新邮箱初始化'
    } else {
      initError.value = e instanceof Error ? e.message : '初始化失败'
    }
  } finally {
    initSubmitting.value = false
  }
}

/** 关闭初始化弹窗；已建号（拿到过一次性密码）时刷新用户列表。 */
function closeInitDialog(): void {
  if (initSubmitting.value) return
  const hadResult = initResult.value !== null
  initDialogOpen.value = false
  initResult.value = null
  if (hadResult) void loadUsers()
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

function isOverdue(iso: string | null): boolean {
  return Boolean(iso && new Date(iso).getTime() < Date.now())
}

function formatStructured(raw: string | null): string {
  if (!raw) return '-'
  try {
    const value = JSON.parse(raw) as unknown
    if (Array.isArray(value)) {
      return value.map((item) => {
        if (!item || typeof item !== 'object') return String(item)
        const row = item as Record<string, unknown>
        return [row.dayOfWeek ? `周${row.dayOfWeek}` : null, row.openTime, row.closeTime]
          .filter(Boolean).join(' ')
      }).join('；') || '-'
    }
    if (value && typeof value === 'object') {
      const row = value as Record<string, unknown>
      return ['province', 'city', 'district', 'address']
        .map((key) => row[key]).filter((item) => typeof item === 'string' && item).join(' ') || raw
    }
    return String(value)
  } catch {
    return raw
  }
}

function formatBytes(value: number | null): string {
  if (value == null || value < 0) return '-'
  if (value < 1024) return `${value} B`
  return `${(value / 1024).toFixed(value < 10240 ? 1 : 0)} KB`
}
</script>

<style scoped>
.admin-view {
  display: grid;
  gap: var(--space-lg);
  max-width: 1180px;
  margin: 0 auto;
}

.admin-tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--color-border);
  overflow-x: auto;
}

.admin-tabs button {
  flex: 0 0 auto;
  min-height: 40px;
  padding: 0 14px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.admin-tabs button.active {
  border-bottom-color: var(--color-accent);
  color: var(--color-text);
  font-weight: 600;
}

.count-badge {
  display: inline-flex;
  min-width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  margin-left: 4px;
  padding: 0 5px;
  border-radius: var(--radius-pill);
  background: var(--color-danger);
  color: var(--color-on-accent);
  font-size: 0.72rem;
}

.admin-panel {
  display: grid;
  gap: var(--space-md);
}

.panel-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--space-md);
}

.panel-toolbar h3,
.panel-toolbar p {
  margin: 0;
}

.search-toolbar { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-xs); flex-wrap: wrap; }
/* 任务书 #72 卡C：状态/身份筛选（左）+ 搜索/初始化（右），窄屏自然换行 */
.user-filters { display: flex; align-items: center; gap: var(--space-sm); margin-right: auto; flex-wrap: wrap; }
.user-filters label { display: flex; align-items: center; gap: var(--space-xs); font-size: 0.84rem; color: var(--color-text-secondary); }
.user-filter-select { min-height: 34px; padding: 0 var(--space-xs); border: 1px solid var(--color-border); background: transparent; color: var(--color-text); border-radius: var(--radius-sm); font-size: var(--text-sm); cursor: pointer; }
.user-filter-select:focus-visible { outline: none; border-color: var(--color-accent); }
.user-search-group { display: flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; }
/* 财务对账的组织筛选行（类名与运营处置台同名，但 scoped 不跨组件，须本地定义） */
.ops-filters { display: flex; align-items: center; gap: var(--space-sm); flex-wrap: wrap; }
.ops-filters label { display: flex; align-items: center; gap: var(--space-xs); font-size: 0.84rem; color: var(--color-text-secondary); }
.ops-filters input { min-height: 32px; padding: 6px var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); font: inherit; }
.search-toolbar input { min-width: min(320px, 64vw); min-height: 34px; padding: 6px var(--space-sm); border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-text); }

/* 任务审核三态筛选：DESIGN.md nav-pill-group + category-tab 范式——
   pill 容器（surface-muted 底 + pill 圆角 + 6px 内边距）内嵌胶囊页签（激活=画布底 + 阴影）。 */
.review-status-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-md); flex-wrap: wrap; }
.status-pill-group { display: inline-flex; gap: 2px; padding: 6px; background: var(--surface-muted); border: 1px solid var(--color-border); border-radius: var(--radius-pill); }
.status-pill {
  padding: 8px 14px;
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
}
.status-pill:hover { color: var(--color-text-secondary); }
.status-pill.active { background: var(--color-surface); color: var(--color-text); font-weight: 600; box-shadow: var(--shadow-card); }
.review-stats { display: inline-flex; align-items: center; gap: var(--space-xs); flex-wrap: wrap; }
.review-note-history { display: grid; gap: 2px; font-size: 0.8rem; }

.panel-toolbar h3 {
  font-size: 1rem;
}

.panel-toolbar p {
  margin-top: 4px;
  color: var(--color-text-muted);
  font-size: 0.82rem;
}

.refresh-btn,
.approve-btn,
.reject-btn {
  min-height: 32px;
  padding: 0 var(--space-sm);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  cursor: pointer;
}

.refresh-btn {
  border: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text-secondary);
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.kyb-table {
  table-layout: fixed;
  min-width: 920px;
}

.id-cell {
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 0.76rem;
}

.type-tag {
  display: inline-block;
  padding: 3px 7px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--surface-muted);
  white-space: nowrap;
}

.overdue {
  color: var(--color-danger) !important;
  font-weight: 600;
}

.review-actions {
  display: flex;
  gap: 6px;
}

.approve-btn {
  border: 1px solid color-mix(in srgb, var(--color-success) 35%, transparent);
  background: color-mix(in srgb, var(--color-success) 8%, transparent);
  color: var(--color-success);
}

.reject-btn {
  border: 1px solid color-mix(in srgb, var(--color-danger) 30%, transparent);
  background: color-mix(in srgb, var(--color-danger) 7%, transparent);
  color: var(--color-danger);
}

.section-header {
  display: grid;
  gap: var(--space-xs);
}

.section-title {
  font-size: var(--text-xl);
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--color-text);
  margin: 0;
}

.section-desc {
  font-size: var(--text-sm);
  color: var(--color-text-muted);
  margin: 0;
}

.loading-state {
  padding: var(--space-xl);
  text-align: center;
  color: var(--color-text-muted);
  font-size: 0.9rem;
}

.error-msg {
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-danger) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-danger) 20%, transparent);
  color: var(--color-danger);
  font-size: 0.86rem;
  margin: 0;
}

.table-card {
  padding: var(--space-md);
  background: var(--surface-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

/* 表格定高滚动层：约 10 行的高度封顶，行多则内部滚动（表头吸顶）；
   宽表横向滚动也从 card 挪到本层，避免粘性表头漏出左右留白。 */
.table-scroll {
  max-height: min(520px, 64vh);
  overflow: auto;
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.86rem;
}

.user-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--surface-card);
  text-align: left;
  padding: var(--space-sm) var(--space-md);
  font-weight: 600;
  color: var(--color-text-muted);
  border-bottom: 1px solid var(--color-border);
  white-space: nowrap;
}

.user-table td {
  padding: var(--space-sm) var(--space-md);
  color: var(--color-text);
  border-bottom: 1px solid var(--color-border);
}

.user-table tbody tr:last-child td {
  border-bottom: none;
}

.td-email {
  font-weight: 500;
}

.td-balance {
  font-weight: 700;
  color: var(--color-accent);
}

.td-time {
  white-space: nowrap;
  color: var(--color-text-muted);
}

.td-empty {
  text-align: center;
  padding: var(--space-xl) !important;
  color: var(--color-text-muted);
}

.role-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: 0.78rem;
  font-weight: 600;
  text-transform: uppercase;
}

.role-admin {
  background: color-mix(in srgb, var(--color-warning) 15%, transparent);
  color: var(--color-warning);
}

.role-user {
  background: var(--surface-muted);
  color: var(--color-text-muted);
}

.adjust-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-accent);
  font-size: 0.78rem;
  cursor: pointer;
  transition: all 0.15s ease-out;
}

.adjust-btn:hover {
  background: var(--surface-hover);
  border-color: var(--color-border-accent);
}

/* 任务书 #72 卡C：行操作四钮（详情/调整积分/停用|恢复/重置密码）。基础形与 .adjust-btn 同格，
   独立类名——既有测试以 .adjust-btn 定位调整积分，不可共享。 */
.user-row-btns { display: flex; gap: 6px; flex-wrap: wrap; }

.detail-btn,
.suspend-btn,
.restore-btn,
.reset-btn {
  padding: 4px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  font-size: 0.78rem;
  cursor: pointer;
  transition: all 0.15s ease-out;
}

.detail-btn {
  color: var(--color-accent);
}

.suspend-btn {
  border-color: color-mix(in srgb, var(--color-danger) 30%, transparent);
  color: var(--color-danger);
}

.restore-btn {
  color: var(--color-success);
  border-color: color-mix(in srgb, var(--color-success) 35%, transparent);
}

.reset-btn {
  color: var(--color-text-secondary);
}

/* 已停用行整行弱化（同文件 .refresh-btn:disabled 的 opacity 先例） */
.row-suspended {
  opacity: 0.55;
}

/* 管控成功提示（任务书 #72 卡D）：与 .error-msg 同构、success 语义色 */
.action-success-msg {
  margin: 0;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-sm);
  background: color-mix(in srgb, var(--color-success) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--color-success) 20%, transparent);
  color: var(--color-success);
  font-size: 0.8rem;
}

.td-identity .identity-chip-group {
  display: inline-flex;
  gap: 4px;
  flex-wrap: wrap;
}

.td-muted {
  color: var(--color-text-muted);
}

/* 初始化商家账号（任务书 #71）：工具区主按钮——视觉同全局 .btn-confirm（primary
   渐变），独立类名避免抢占弹窗确认按钮的 .btn-confirm 查找语义。 */
.init-merchant-btn {
  margin-left: var(--space-sm);
  padding: 8px 20px;
  border: none;
  border-radius: var(--radius-md);
  background: var(--gradient-accent);
  color: var(--color-on-accent);
  font-size: 0.86rem;
  font-weight: 600;
  cursor: pointer;
}

.init-merchant-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 弹窗骨架（overlay/card/header/btn 等）已收口 src/style.css 全局层——
   Teleport 到 body 后 scoped 够不着子组件，历史教训见 AdjustCreditsDialog */

.review-modal {
  width: min(720px, 94vw);
  max-height: min(820px, 92vh);
  overflow-y: auto;
}

/* 表格行内联备注输入（非弹窗上下文，勿删；弹窗内输入走全局 .modal-card .field-input） */
.field-input {
  width: 100%;
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--surface-muted);
  color: var(--color-text);
  font-size: 0.88rem;
  box-sizing: border-box;
}

.field-input:focus {
  outline: none;
  border-color: var(--color-accent);
}

.field-textarea {
  min-height: 96px;
  height: auto;
  padding: 10px 12px;
  resize: vertical;
}

.review-summary {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  font-size: 0.8rem;
}

.review-summary dt {
  color: var(--color-text-muted);
}

.review-summary dd {
  overflow-wrap: anywhere;
  margin: 0;
  color: var(--color-text-secondary);
}

.detail-loading {
  min-height: 120px;
  display: grid;
  place-items: center;
  color: var(--color-text-muted);
  font-size: 0.84rem;
}

.review-detail {
  display: grid;
  gap: 16px;
  padding-block: 14px;
  border-block: 1px solid var(--color-border);
}

.detail-grid {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr) 92px minmax(0, 1fr);
  gap: 10px 14px;
  margin: 0;
  font-size: 0.82rem;
}

.detail-grid dt {
  color: var(--color-text-muted);
}

.detail-grid dd {
  min-width: 0;
  margin: 0;
  color: var(--color-text);
  overflow-wrap: anywhere;
}

.review-materials {
  display: grid;
  gap: 8px;
}

.review-materials h4 {
  margin: 0;
  font-size: 0.84rem;
}

.material-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  padding-block: 8px;
  border-top: 1px solid var(--color-border);
}

.material-row div {
  display: grid;
  gap: 3px;
}

.material-row strong,
.material-row span {
  font-size: 0.8rem;
}

.material-row span {
  color: var(--color-text-muted);
}

.material-view {
  min-width: 56px;
  height: 32px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-accent);
  cursor: pointer;
}

.approve-btn:disabled,
.reject-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 640px) {
  .panel-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .refresh-btn {
    align-self: flex-start;
  }

  .detail-grid {
    grid-template-columns: 76px minmax(0, 1fr);
  }

}
</style>
