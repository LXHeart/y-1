/* Design-only interactions. All records are examples, held in memory; no API requests. */
const $ = (selector) => document.querySelector(selector);
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, (char) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[char]));
const money = (value) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
const paths = {
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
  moon: '<path d="M20.9 13A9 9 0 0 1 11 3.1 9 9 0 1 0 20.9 13Z"/>',
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  arrow: '<path d="M7 17 17 7M7 7h10v10"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
};
const icon = (name) => '<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' + paths[name] + '</svg>';
const badge = (label, tone = 'neutral') => '<span class="badge badge-' + tone + '">' + escapeHtml(label) + '</span>';
const steps = ['草稿', '审核', '招募', '履约', '结算'];
const tasks = [
  { id: 'task-1', title: '秋日双人套餐 · 到店体验', store: '滨江店', format: '图文', stage: 3, state: '待验收', tone: 'warning', filter: 'review', amount: 300, due: '09 月 12 日 18:00', note: '2 份交付等待验收', action: '查看交付', partner: '小麦的食光', summary: '记录一次真实的双人用餐体验，突出当季食材与套餐搭配。' },
  { id: 'task-2', title: '手作咖啡新品 · 图文探店', store: '滨江店', format: '图文', stage: 2, state: '招募中', tone: 'info', filter: 'applicants', amount: 200, due: '09 月 15 日 18:00', note: '3 位推荐官等待筛选', action: '查看报名', partner: '3 位推荐官', summary: '面向本地生活推荐官，分享手作咖啡新品的真实体验。' },
  { id: 'task-3', title: '周末亲子烘焙 · 视频探店', store: '城西店', format: '视频', stage: 1, state: '审核中', tone: 'info', filter: 'pending', amount: 450, due: '09 月 18 日 18:00', note: '平台正在审核任务内容', action: '查看任务', partner: '尚未开放报名', summary: '记录亲子烘焙过程，交付一条完整的体验视频。' },
];
const reviews = [
  { id: 'GL-2409', title: '初秋轻食 · 到店图文推广', org: '青禾食集', format: '图文体验', amount: 300, submitted: '09-09 14:32', status: 'pending', requirement: '到店体验后提交真实图文；说明套餐内容与使用时段。' },
  { id: 'GL-2408', title: '手作花艺 · 周末体验', org: '花间工作室', format: '视频体验', amount: 500, submitted: '09-09 13:48', status: 'pending', requirement: '拍摄花艺制作过程；交付一条完整体验视频。' },
  { id: 'GL-2407', title: '城市骑行 · 新品试用', org: '山外生活', format: '图文体验', amount: 260, submitted: '09-09 11:06', status: 'pending', requirement: '试用骑行配件，明确适用场景与真实体验。' },
  { id: 'GL-2406', title: '秋季面包 · 新品分享', org: '麦田面包房', format: '图文体验', amount: 180, submitted: '09-09 10:15', status: 'rejected', requirement: '需补充门店地址与套餐使用条件后重新提交。' },
];
const draftTitle = '把秋天装进一顿双人晚餐';
const draftBody = '下班后和朋友去了青禾食集滨江店。秋日双人套餐把当季蔬菜、热食和甜点搭配在一起，适合慢慢吃完一顿晚饭。\n\n我最喜欢的是食材清爽的口感。两个人边聊边吃，份量也合适。靠窗的位置安静，能看到傍晚的街景。\n\n如果你也在找一个和朋友聚餐的地方，可以先看看门店的套餐说明和可用时段，再安排到店。';
const state = {
  view: 'workbench', workTab: 'tasks', taskFilter: 'all', store: 'all', taskQuery: '', task: 'task-1',
  opsFilter: 'pending', opsQuery: '', sort: 'desc', selected: new Set(),
  aiStatus: 'draft', aiSource: 'task-1', aiTitle: draftTitle, aiText: draftBody, aiRequirements: '写一篇约 150 字的到店体验图文。保留真实感受，突出当季食材与双人套餐；避免夸大效果。', aiEditing: false,
};
let toastTimer;
let aiTimer;
let dialogTrigger;
let dialogAction;
const demo = $('#demo');
const dialog = $('#detail-dialog');
const pendingTotal = () => reviews.filter((item) => item.status === 'pending').length;

function notify(message) {
  clearTimeout(toastTimer);
  $('#toast').textContent = message;
  $('#toast').hidden = false;
  toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 3500);
}
function themeButton() {
  const light = document.documentElement.dataset.theme === 'light';
  $('#theme-toggle').innerHTML = icon(light ? 'moon' : 'sun') + '<span>' + (light ? '暗色主题' : '亮色主题') + '</span>';
  $('#theme-toggle').setAttribute('aria-label', light ? '切换为暗色主题' : '切换为亮色主题');
}
function header(label, account = '林同学', role = '商家') {
  return '<div class="app-header"><div class="brand">' + icon('grid') + '<strong>草场</strong><span class="brand-sub">' + label + '</span></div><div class="account"><span class="account-copy secondary">' + role + ' · ' + account + '</span><span class="avatar" aria-hidden="true">林</span></div></div>';
}
function stageLine(index) {
  return '<ol class="workflow" aria-label="任务阶段">' + steps.map((label, i) => '<li class="' + (i < index ? 'done' : i === index ? 'now' : '') + '"' + (i === index ? ' aria-current="step"' : '') + '>' + label + '</li>').join('') + '</ol>';
}
function matchingTasks() {
  return tasks.filter((task) => (state.taskFilter === 'all' || task.filter === state.taskFilter) && (state.store === 'all' || task.store === state.store) && (task.title + task.store).includes(state.taskQuery));
}
function summary(task) {
  return '<aside class="panel" aria-label="当前任务摘要"><div class="panel-body"><div class="creation-source"><span class="muted">当前合作</span>' + badge(task.state, task.tone) + '</div><h2 class="summary-title">' + escapeHtml(task.title) + '</h2>' + stageLine(task.stage) +
    '<p class="secondary">' + escapeHtml(task.summary) + '</p><dl class="summary-dl"><div><dt>任务赏金</dt><dd class="gl-num">' + money(task.amount) + ' / 人</dd></div><div><dt>推荐官</dt><dd>' + escapeHtml(task.partner) + '</dd></div><div><dt>截止时间</dt><dd class="gl-num">' + task.due + '</dd></div></dl>' +
    '<div class="quiet-note"><p>' + escapeHtml(task.note) + '</p><p>完成验收后进入结算，以实际到账记录为准。</p></div><hr class="divider"><button type="button" class="gl-btn-primary full-button" data-task-detail="' + task.id + '">' + task.action + '</button>' + (task.format === '图文' ? '<button type="button" class="gl-link" data-action="task-creation">在 AI 中准备内容 ' + icon('arrow') + '</button>' : '') + '</div></aside>';
}
function taskRows() {
  const visible = matchingTasks();
  if (!visible.length) return '<div class="empty"><h3>没有符合条件的任务</h3><p class="secondary">换个关键词，或清除当前筛选。</p><button type="button" data-action="clear-tasks">清除筛选</button></div>';
  return '<ul class="task-list">' + visible.map((task) =>
    '<li class="task-row' + (task.id === state.task ? ' is-selected' : '') + '"><div class="task-top"><div><button type="button" class="task-title" data-task="' + task.id + '" aria-pressed="' + (task.id === state.task) + '">' + escapeHtml(task.title) + '</button><div class="task-meta"><span>' + task.store + '</span><span>·</span><span>' + task.format + '交付</span><span>·</span><span class="gl-num">' + task.due + ' 截止</span></div></div>' + badge(task.state, task.tone) + '</div><div class="task-bottom"><div><p class="workflow-caption"><span class="workflow-dot" aria-hidden="true"></span>' + steps[task.stage] + '阶段</p><p class="muted">' + task.note + '</p></div><div class="money-label"><strong class="gl-num">' + money(task.amount) + '<span class="muted"> / 人</span></strong><span class="muted">任务赏金</span></div></div></li>'
  ).join('') + '</ul>';
}
function workbench() {
  const visible = matchingTasks();
  const current = visible.find((task) => task.id === state.task) || visible[0];
  if (current) state.task = current.id;
  const filters = [['all', '全部任务'], ['applicants', '待筛选'], ['review', '待验收']];
  const count = (key) => tasks.filter((task) => (key === 'all' || task.filter === key) && (state.store === 'all' || task.store === state.store)).length;
  let body;
  if (state.workTab === 'org') {
    body = '<section class="panel"><div class="panel-heading"><h2>青禾食集</h2>' + badge('已认证', 'success') + '</div><div class="panel-body"><p class="secondary">统一管理合作门店与商家资料。</p><dl class="summary-dl"><div><dt>当前门店</dt><dd>滨江店、城西店</dd></div><div><dt>成员身份</dt><dd>商家管理员</dd></div><div><dt>品牌资料</dt><dd>资料已完善</dd></div></dl></div></section>';
  } else if (state.workTab === 'finance') {
    body = '<section class="panel"><div class="panel-heading"><h2>资金账户</h2>' + badge('人民币账户') + '</div><div class="panel-body"><p class="muted">可用余额</p><h2 class="gl-num">' + money(3600) + '</h2><dl class="summary-dl"><div><dt>任务预留</dt><dd class="gl-num">' + money(1200) + '</dd></div><div><dt>待结算</dt><dd class="gl-num">' + money(600) + '</dd></div><div><dt>本月已结算</dt><dd class="gl-num">' + money(2400) + '</dd></div></dl><p class="quiet-note">预留与待结算金额分别列示；实际资金变化以账单记录为准。</p></div></section>';
  } else {
    body = '<div class="toolbar"><div class="filters" role="group" aria-label="任务筛选">' + filters.map(([key, label]) => '<button type="button" class="filter" data-task-filter="' + key + '" aria-pressed="' + (state.taskFilter === key) + '">' + label + '<span class="gl-num">' + count(key) + '</span></button>').join('') + '</div><label class="search">' + icon('search') + '<input id="task-search" type="search" aria-label="搜索任务" placeholder="搜索任务或门店" value="' + escapeHtml(state.taskQuery) + '"></label></div><div class="work-grid"><section class="panel" aria-label="任务列表" id="task-results">' + taskRows() + '</section><div id="task-summary"' + (current ? '' : ' hidden') + '>' + (current ? summary(current) : '') + '</div></div><p class="work-note">' + icon('check') + '<span>任务阶段沿合作流程推进，交付和结算分别确认。</span></p>';
  }
  const heading = state.workTab === 'org' ? '商家主体与门店' : state.workTab === 'finance' ? '资金与经营' : '任务与报名';
  return header('商家工作台') + '<div class="content"><div class="context-strip"><div class="context-org"><strong>青禾食集</strong><span class="badge badge-neutral">商家视角</span><select id="store-filter" aria-label="按门店筛选任务"><option value="all">全部门店</option><option value="滨江店"' + (state.store === '滨江店' ? ' selected' : '') + '>滨江店</option><option value="城西店"' + (state.store === '城西店' ? ' selected' : '') + '>城西店</option></select></div><span class="muted"><span class="context-dot" aria-hidden="true"></span> 合作进展，一处跟进</span></div><div class="page-heading"><div><h1>' + heading + '</h1><p>筛选推荐官，跟进交付，查看结算。</p></div><button type="button" data-action="new-task" data-focus-default>发布新任务</button></div><nav class="page-tabs" aria-label="商家工作台页签">' + [['tasks', '任务与报名'], ['org', '商家主体与门店'], ['finance', '资金与经营']].map(([key, label]) => '<button type="button" class="page-tab" data-work-tab="' + key + '" aria-pressed="' + (state.workTab === key) + '">' + label + '</button>').join('') + '</nav>' + body + '</div>';
}
function ai() {
  const source = tasks.find((task) => task.id === state.aiSource) || tasks[0];
  const running = state.aiStatus === 'running';
  const label = running ? '生成中' : state.aiStatus === 'ready' ? '已生成' : '草稿';
  const paragraphs = state.aiText.split('\n\n').map((text) => '<p>' + escapeHtml(text) + '</p>').join('');
  const output = state.aiEditing ? '<label class="field" for="ai-editor"><span class="muted">编辑正文</span><textarea id="ai-editor">' + escapeHtml(state.aiText) + '</textarea></label>' : '<h2>' + escapeHtml(state.aiTitle) + '</h2>' + paragraphs;
  return header('AI 创作中心', '林同学', '创作者') + '<div class="content"><div class="context-strip"><div class="context-org"><span class="muted">任务创作</span>' + icon('chevron') + '<span>' + escapeHtml(source.title) + '</span></div>' + badge('来源条件已锁定', 'neutral') + '</div><div class="page-heading"><div><h1>图文创作</h1><p>素材、要求和结果保持在同一个工作区。</p></div><span class="badge badge-neutral gl-num">可用 120 积分 · 示例额度</span></div><div class="ai-grid"><section class="panel" aria-labelledby="ai-input-title"><div class="panel-heading"><h2 id="ai-input-title">创作要求</h2>' + icon('file') + '</div><div class="panel-body"><div class="field"><label for="ai-format">内容形式</label><input id="ai-format" value="图文体验" readonly aria-describedby="format-hint"><span class="muted" id="format-hint">沿用任务指定的内容形式。</span></div><div class="field"><label for="ai-requirements">这次想表达什么</label><textarea id="ai-requirements" rows="5">' + escapeHtml(state.aiRequirements) + '</textarea><span class="muted">以真实体验为依据，生成后检查内容再发布。</span></div><div class="quiet-note"><p><strong>已带入任务要求</strong></p><p>青禾食集 · ' + escapeHtml(source.store) + '<br>' + escapeHtml(source.summary) + '</p></div><hr class="divider"><button type="button" class="gl-btn-primary full-button" data-action="generate"' + (running ? ' disabled' : '') + '>' + (running ? '正在生成图文…' : '生成图文 · 5 积分') + '</button><p class="muted" style="margin-top:var(--space-xs)">演示不会扣费；正式生成前需确认计费规则。</p>' + (running ? '<button type="button" class="gl-link" data-action="cancel-ai">取消本次生成</button>' : '') + '</div></section><section class="panel" aria-label="内容结果" aria-busy="' + running + '"><div class="panel-heading"><h2>内容预览</h2><span role="status">' + badge(label, running ? 'info' : state.aiStatus === 'ready' ? 'success' : 'neutral') + '</span></div><div class="document"><div class="document-label">' + icon('file') + '<span>到店体验图文 · 可继续编辑</span></div>' + output + '</div><div class="document-footer"><span class="muted gl-num">' + state.aiText.length + ' 字 · 请核对门店与套餐信息</span><button type="button" data-action="edit-ai" data-focus-default>' + (state.aiEditing ? '保存正文' : '继续编辑') + '</button></div></section></div></div>';
}
function sidebar() {
  return '<p class="sidebar-label">业务导航</p><div class="sidebar-group">' + icon('file') + '审核队列</div><span class="sidebar-item secondary">KYB 审核</span><button type="button" class="sidebar-item active full-button" data-action="current-queue" aria-current="page">任务审核 <span class="count">' + pendingTotal() + '</span></button><span class="sidebar-item secondary">权限审核</span><div class="sidebar-group">用户与主体</div><div class="sidebar-group">交易与财务</div><div class="sidebar-group">内容与 AI</div><div class="sidebar-group">风控与审计</div>';
}
function matchingReviews() {
  return reviews.filter((item) => (state.opsFilter === 'all' || item.status === state.opsFilter) && (item.title + item.org + item.id).includes(state.opsQuery)).sort((a, b) => state.sort === 'desc' ? b.submitted.localeCompare(a.submitted) : a.submitted.localeCompare(b.submitted));
}
function reviewBadge(status) {
  return status === 'pending' ? badge('待审核', 'warning') : status === 'approved' ? badge('已通过', 'success') : badge('已退回', 'danger');
}
function reviewResults() {
  const visible = matchingReviews();
  const rows = visible.map((item) => '<tr class="' + (state.selected.has(item.id) ? 'is-selected' : '') + '"><td><label class="checkbox-target"><input type="checkbox" data-select-review="' + item.id + '" aria-label="选择 ' + item.id + '"' + (state.selected.has(item.id) ? ' checked' : '') + '></label></td><td><button type="button" class="gl-link table-title" data-review="' + item.id + '">' + escapeHtml(item.title) + '</button><small>' + item.id + ' · ' + escapeHtml(item.org) + '</small></td><td>' + item.format + '</td><td class="numeric gl-num">' + money(item.amount) + '</td><td class="gl-num">' + item.submitted + '</td><td>' + reviewBadge(item.status) + '</td><td><button type="button" data-review="' + item.id + '">' + (item.status === 'pending' ? '查看材料' : '查看记录') + '</button></td></tr>').join('');
  return (state.selected.size ? '<div class="bulk-bar"><span>已选择 <strong class="gl-num">' + state.selected.size + '</strong> 项 · 当前筛选结果</span><button type="button" data-action="bulk-review">查看所选任务</button></div>' : '') +
    (visible.length ? '<p class="muted table-scroll-hint">左右滑动查看赏金、状态与操作</p><div class="table-scroll" role="region" aria-label="任务审核表格，可横向滚动" tabindex="0"><table class="gl-table"><caption class="sr-only">任务审核队列</caption><thead><tr><th scope="col"><label class="checkbox-target"><input type="checkbox" id="select-all" aria-label="选择当前筛选全部记录"' + (visible.every((item) => state.selected.has(item.id)) ? ' checked' : '') + '></label></th><th scope="col">任务 / 商家主体</th><th scope="col">内容类型</th><th scope="col" class="numeric">单人赏金（元）</th><th scope="col" aria-sort="' + (state.sort === 'desc' ? 'descending' : 'ascending') + '"><button type="button" class="sort-button" data-action="sort-reviews">提交时间 ' + (state.sort === 'desc' ? '↓' : '↑') + '</button></th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody>' + rows + '</tbody></table></div>' : '<div class="panel empty"><h3>没有符合条件的审核记录</h3><p class="secondary">尝试其他关键词，或查看全部记录。</p><button type="button" data-action="clear-reviews">清除筛选</button></div>') +
    '<div class="pagination"><span class="muted gl-num">当前结果 ' + visible.length + ' 条 · 共 ' + reviews.length + ' 条示例记录</span><span class="muted">全部结果已显示</span></div>';
}
function opsView() {
  return header('治理台 / 管理后台', '林同学', '平台管理员') + '<div class="ops-grid"><aside class="ops-sidebar" aria-label="业务导航">' + sidebar() + '</aside><div class="ops-content"><div class="breadcrumb"><button type="button" class="icon-button ops-menu-button" data-action="ops-menu" aria-label="打开业务导航">' + icon('menu') + '</button><span>审核队列</span>' + icon('chevron') + '<strong class="secondary">任务审核</strong></div><div class="ops-body"><div class="page-heading"><div><h1>任务审核</h1><p>核对推广内容、交付要求与赏金规则。</p></div><button type="button" data-action="refresh-reviews" data-focus-default>刷新队列</button></div><div class="page-tabs" role="group" aria-label="审核状态筛选">' + [['pending', '待审核'], ['all', '全部记录'], ['rejected', '已退回']].map(([key, label]) => '<button type="button" class="page-tab" data-review-filter="' + key + '" aria-pressed="' + (state.opsFilter === key) + '">' + label + ' <span class="gl-num">' + reviews.filter((item) => key === 'all' || item.status === key).length + '</span></button>').join('') + '</div><div class="toolbar"><label class="search">' + icon('search') + '<input type="search" id="review-search" aria-label="搜索审核记录" placeholder="搜索任务、编号或商家" value="' + escapeHtml(state.opsQuery) + '"></label><span class="muted">范围：全部商家 · 示例队列</span></div><div id="review-results">' + reviewResults() + '</div><div class="ops-help">' + icon('file') + '<div><h3>先看完整材料，再做决定</h3><p class="muted">审核意见与任务关联保存；退回时说明需要补充或修改的内容。</p></div></div></div></div></div>';
}
function render() {
  document.documentElement.dataset.app = state.view === 'ops' ? 'ops' : 'client';
  for (const button of document.querySelectorAll('[data-view]')) button.setAttribute('aria-pressed', String(button.dataset.view === state.view));
  demo.innerHTML = state.view === 'workbench' ? workbench() : state.view === 'ai' ? ai() : opsView();
  themeButton();
}
function switchView(view) {
  state.view = view;
  render();
}
function openDialog(title, content, action) {
  dialogTrigger = document.activeElement;
  dialogAction = action;
  delete dialog.dataset.kind;
  $('#dialog-title').textContent = title;
  $('#dialog-content').innerHTML = content;
  dialog.showModal();
}
function closeDialog() { dialog.close(); }
function showTaskDetail(id) {
    const task = tasks.find((item) => item.id === id);
    openDialog(task.action, '<h3>' + escapeHtml(task.title) + '</h3>' + stageLine(task.stage) + '<div class="evidence"><strong>' + escapeHtml(task.partner) + '</strong><p>' + escapeHtml(task.summary) + '</p></div><p class="secondary">' + escapeHtml(task.note) + '</p><div class="dialog-actions"><button type="button" data-close-dialog>返回任务</button></div>');
}

dialog.addEventListener('close', () => {
  const target = dialogTrigger?.isConnected ? dialogTrigger : demo.querySelector('[data-focus-default]');
  target?.focus();
  dialogAction = undefined;
});
document.addEventListener('click', (event) => {
  const button = event.target.closest('button');
  if (!button) return;
  if (button.dataset.view) { switchView(button.dataset.view); return; }
  if (button.id === 'theme-toggle') {
    document.documentElement.dataset.theme = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
    themeButton(); return;
  }
  if (button.hasAttribute('data-close-dialog')) { closeDialog(); return; }
  if (button.dataset.workTab) { state.workTab = button.dataset.workTab; render(); return; }
  if (button.dataset.taskFilter) { state.taskFilter = button.dataset.taskFilter; render(); return; }
  if (button.dataset.task) { state.task = button.dataset.task; render(); demo.querySelector('[data-task="' + state.task + '"]')?.focus(); if (matchMedia('(max-width:1023px)').matches) showTaskDetail(state.task); return; }
  if (button.dataset.taskDetail) { showTaskDetail(button.dataset.taskDetail); return; }
  if (button.dataset.review) { showReview(button.dataset.review); return; }
  const action = button.dataset.action;
  if (action === 'clear-tasks') { state.taskFilter = 'all'; state.store = 'all'; state.taskQuery = ''; render(); }
  if (action === 'task-creation') {
    const source = tasks.find((task) => task.id === state.task);
    clearTimeout(aiTimer);
    state.aiSource = source.id;
    state.aiStatus = 'draft'; state.aiEditing = false;
    state.aiTitle = source.id === 'task-1' ? draftTitle : source.title;
    state.aiText = source.id === 'task-1' ? draftBody : source.summary + '\n\n内容应来自真实体验，完成后请核对门店与交付要求。';
    state.aiRequirements = '围绕“' + source.title + '”整理真实体验图文，保留任务要求，不夸大效果。';
    switchView('ai'); notify('已带入任务来源与创作要求。');
  }
  if (action === 'new-task') {
    openDialog('新建推广任务', '<form id="new-task-form"><div class="field"><label for="new-task-title">任务名称</label><input id="new-task-title" name="title" required maxlength="100" placeholder="例如：新品到店体验"></div><div class="field"><label for="new-task-amount">单人赏金（元）</label><input id="new-task-amount" name="amount" type="number" min="1" max="100000" value="300" required></div><p class="muted">先保存草稿，再补充要求并提交审核。本页仅演示操作。</p><div class="dialog-actions"><button type="button" data-close-dialog>取消</button><button class="gl-btn-primary" type="submit">保存草稿</button></div></form>');
  }
  if (action === 'generate') {
    state.aiRequirements = $('#ai-requirements').value;
    if (!state.aiRequirements.trim()) { $('#ai-requirements').setCustomValidity('请先填写创作要求。'); $('#ai-requirements').reportValidity(); return; }
    if (state.aiEditing) state.aiText = $('#ai-editor').value;
    state.aiStatus = 'running'; render();
    aiTimer = setTimeout(() => { state.aiStatus = 'ready'; if (state.view === 'ai') { render(); notify('示例内容已生成，可以继续编辑。'); } }, 1200);
  }
  if (action === 'cancel-ai') { clearTimeout(aiTimer); state.aiStatus = 'draft'; render(); notify('本次生成已取消，保留已有内容。'); }
  if (action === 'edit-ai') {
    if (state.aiEditing) state.aiText = $('#ai-editor').value;
    state.aiRequirements = $('#ai-requirements').value;
    state.aiEditing = !state.aiEditing;
    render();
    (state.aiEditing ? $('#ai-editor') : demo.querySelector('[data-action="edit-ai"]'))?.focus();
  }
  if (button.dataset.reviewFilter) { state.opsFilter = button.dataset.reviewFilter; state.selected.clear(); render(); }
  if (action === 'sort-reviews') { state.sort = state.sort === 'desc' ? 'asc' : 'desc'; $('#review-results').innerHTML = reviewResults(); demo.querySelector('[data-action="sort-reviews"]')?.focus(); }
  if (action === 'clear-reviews') { state.opsQuery = ''; state.opsFilter = 'all'; state.selected.clear(); render(); }
  if (action === 'refresh-reviews') { notify('示例队列已更新，共 ' + pendingTotal() + ' 条待审核。'); }
  if (action === 'bulk-review') {
    const selected = reviews.filter((item) => state.selected.has(item.id));
    openDialog('所选任务 · ' + selected.length + ' 项', '<p class="secondary">选择范围：当前筛选结果。</p>' + selected.map((item) => '<div class="evidence"><strong>' + escapeHtml(item.title) + '</strong><p>' + item.id + ' · ' + money(item.amount) + ' / 人</p></div>').join('') + '<p class="muted">审核结论需要结合每条任务的材料填写。</p><div class="dialog-actions"><button type="button" data-close-dialog>返回队列</button></div>');
  }
  if (action === 'ops-menu') { openDialog('业务导航', '<nav aria-label="移动业务导航">' + sidebar() + '</nav>'); dialog.dataset.kind = 'navigation'; }
  if (action === 'current-queue' && dialog.open) closeDialog();
});
function showReview(id) {
  const item = reviews.find((row) => row.id === id);
  const form = item.status === 'pending' ? '<form id="review-form"><div class="field"><label for="review-decision">审核结论</label><select id="review-decision" name="decision"><option value="approved">通过审核</option><option value="rejected">退回修改</option></select></div><div class="field"><label for="review-reason">审核意见</label><textarea id="review-reason" name="reason" rows="3" required placeholder="说明审核依据或需要修改的内容"></textarea></div><div class="dialog-actions"><button type="button" data-close-dialog>取消</button><button type="submit" class="gl-btn-primary">保存审核意见</button></div></form>' : '<p class="quiet-note">' + escapeHtml(item.reason || item.requirement) + '</p><div class="dialog-actions"><button type="button" data-close-dialog>返回队列</button></div>';
  openDialog('任务审核 · ' + item.id, '<div class="creation-source"><span class="muted">' + escapeHtml(item.org) + '</span>' + reviewBadge(item.status) + '</div><h3>' + escapeHtml(item.title) + '</h3><div class="evidence"><strong>任务要求</strong><p>' + escapeHtml(item.requirement) + '</p><p class="gl-num">单人赏金 ' + money(item.amount) + '</p></div>' + form, id);
}
document.addEventListener('input', (event) => {
  const input = event.target;
  if (input.id === 'task-search') {
    state.taskQuery = input.value;
    const visible = matchingTasks();
    const current = visible.find((task) => task.id === state.task) || visible[0];
    if (current) state.task = current.id;
    $('#task-results').innerHTML = taskRows();
    $('#task-summary').hidden = !current;
    $('#task-summary').innerHTML = current ? summary(current) : '';
  }
  if (input.id === 'review-search') { state.opsQuery = input.value; state.selected.clear(); $('#review-results').innerHTML = reviewResults(); }
  if (input.id === 'ai-requirements') { input.setCustomValidity(''); state.aiRequirements = input.value; }
  if (input.id === 'new-task-title' || input.id === 'review-reason') input.setCustomValidity('');
  if (input.id === 'ai-editor') state.aiText = input.value;
});
document.addEventListener('change', (event) => {
  const input = event.target;
  if (input.id === 'store-filter') { state.store = input.value; render(); }
  if (input.dataset.selectReview) {
    if (input.checked) state.selected.add(input.dataset.selectReview); else state.selected.delete(input.dataset.selectReview);
    $('#review-results').innerHTML = reviewResults();
    demo.querySelector('[data-select-review="' + input.dataset.selectReview + '"]')?.focus();
  }
  if (input.id === 'select-all') {
    for (const item of matchingReviews()) { if (input.checked) state.selected.add(item.id); else state.selected.delete(item.id); }
    $('#review-results').innerHTML = reviewResults(); $('#select-all')?.focus();
  }
});
document.addEventListener('submit', (event) => {
  if (event.target.id === 'new-task-form') {
    event.preventDefault();
    const data = new FormData(event.target);
    if (!String(data.get('title')).trim()) { $('#new-task-title').setCustomValidity('请填写任务名称。'); $('#new-task-title').reportValidity(); return; }
    const id = 'draft-' + Date.now();
    tasks.unshift({ id, title: String(data.get('title')).trim(), store: state.store === 'all' ? '滨江店' : state.store, format: '图文', stage: 0, state: '草稿', tone: 'neutral', filter: 'draft', amount: Number(data.get('amount')), due: '尚未设置', note: '补充交付要求后提交审核', action: '查看草稿', partner: '尚未开放报名', summary: '新建任务草稿，请继续完善合作内容与要求。' });
    state.task = id; state.workTab = 'tasks'; state.taskFilter = 'all'; state.taskQuery = '';
    closeDialog(); render(); notify('示例草稿已保存。');
  }
  if (event.target.id === 'review-form') {
    event.preventDefault();
    const data = new FormData(event.target);
    if (!String(data.get('reason')).trim()) { $('#review-reason').setCustomValidity('请填写审核意见。'); $('#review-reason').reportValidity(); return; }
    const item = reviews.find((row) => row.id === dialogAction);
    item.status = String(data.get('decision'));
    item.reason = String(data.get('reason'));
    state.selected.delete(item.id);
    closeDialog(); render(); notify('示例审核意见已保存，队列已更新。');
  }
});
themeButton();
render();
