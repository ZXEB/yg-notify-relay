/* YG 消息转发中继 · 网页控制台
 * 纯原生 ES2020，无构建步骤、无外部依赖。
 * 分层：常量 / 工具 / api / 状态 / 渲染 / SSE / 交互绑定。
 * 关键取舍见各处中文注释。
 */
(() => {
  'use strict';

  // ---------- 常量 ----------
  const PAGE_SIZE = 30;
  const SEARCH_DEBOUNCE = 300;
  const RECONNECT_BASE = 3000; // 首次重连等待 3s
  const RECONNECT_MAX = 30000; // 指数退避上限 30s
  const RECONNECT_FACTOR = 1.8;
  const RELATIVE_REFRESH = 60000; // 相对时间每分钟刷新
  const FLASH_MS = 1200;
  const SVG_NS = 'http://www.w3.org/2000/svg';
  // 8 色固定色板：同一个 package 永远拿到同一个颜色
  const APP_COLORS = [
    '#2f7bff', '#5e5ce6', '#bf5af2', '#ff375f',
    '#ff9f0a', '#35c759', '#0fb9b1', '#ff7ab6',
  ];
  const VIEW_TITLES = { messages: '消息', devices: '设备', settings: '设置' };
  const VIEW_ORDER = ['messages', 'devices', 'settings'];

  // 页面可能被反代到子路径（例如 /relay/），API 与页面同级，前缀由当前路径推导
  const API_BASE = (() => {
    const { pathname } = window.location;
    if (pathname.endsWith('/')) return pathname.slice(0, -1);
    const cut = pathname.lastIndexOf('/');
    return cut <= 0 ? '' : pathname.slice(0, cut);
  })();
  const api = (path) => API_BASE + path;

  // ---------- 工具 ----------
  function byId(id) {
    return document.getElementById(id);
  }

  /** 统一用 createElement + textContent 建节点，天然免疫 HTML 注入 */
  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function debounce(fn, delay) {
    let timer = null;
    return (...args) => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = null;
        fn(...args);
      }, delay);
    };
  }

  function pad2(value) {
    return String(value).padStart(2, '0');
  }

  /** 32 位字符串 hash，映射到色板下标 */
  function hashIndex(str, mod) {
    let hash = 0;
    for (let i = 0; i < str.length; i += 1) {
      hash = (hash * 31 + str.charCodeAt(i)) | 0;
    }
    return Math.abs(hash) % mod;
  }

  /** 取首个字符做色块文字，兼容代理对（emoji） */
  function firstChar(str) {
    const text = String(str || '').trim();
    if (!text) return '?';
    const chars = Array.from(text);
    return chars[0];
  }

  /** 相对时间：刚刚 / N分钟前 / N小时前 / 日期（浏览器本地时区） */
  function formatRelative(ts, now = Date.now()) {
    const value = Number(ts);
    if (!Number.isFinite(value) || value <= 0) return '未知时间';
    const diff = Math.max(0, now - value);
    if (diff < 60 * 1000) return '刚刚';
    if (diff < 60 * 60 * 1000) return `${Math.floor(diff / 60000)}分钟前`;
    if (diff < 24 * 60 * 60 * 1000) return `${Math.floor(diff / 3600000)}小时前`;
    return formatShortDate(value);
  }

  function formatShortDate(ts) {
    const date = new Date(Number(ts));
    const now = new Date();
    const day = `${date.getMonth() + 1}月${date.getDate()}日`;
    return date.getFullYear() === now.getFullYear() ? day : `${date.getFullYear()}年${day}`;
  }

  function formatDateTime(ts) {
    const date = new Date(Number(ts));
    if (Number.isNaN(date.getTime())) return '';
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ` +
      `${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
  }

  function formatUptime(seconds) {
    const total = Math.max(0, Math.floor(Number(seconds) || 0));
    const days = Math.floor(total / 86400);
    const hours = Math.floor((total % 86400) / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    if (days > 0) return `${days}天${hours}小时`;
    if (hours > 0) return `${hours}小时${minutes}分`;
    if (minutes > 0) return `${minutes}分${total % 60}秒`;
    return `${total}秒`;
  }

  function deviceLabel(ev) {
    if (ev.device_alias) return String(ev.device_alias);
    const id = String(ev.device_id || '');
    return id ? `设备 ${id.slice(0, 8)}` : '未知设备';
  }

  /** 复制：优先 Clipboard API，http 局域网（非安全上下文）回退 execCommand */
  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (err) {
      // 权限被拒时继续走回退方案
    }
    const area = el('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.top = '-1000px';
    area.style.opacity = '0';
    document.body.append(area);
    let ok = false;
    try {
      area.select();
      ok = document.execCommand('copy');
    } catch (err) {
      ok = false;
    }
    area.remove();
    return ok;
  }

  function iconBolt(className) {
    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('class', className);
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS(SVG_NS, 'path');
    path.setAttribute('d', 'M13 2 4.6 13.4h5.2L10 22l8.4-11.4h-5.3L13 2Z');
    path.setAttribute('fill', 'currentColor');
    svg.append(path);
    return svg;
  }

  // ---------- api 层 ----------
  class ApiError extends Error {
    constructor(message, status, payload) {
      super(message);
      this.name = 'ApiError';
      this.status = status;
      this.payload = payload;
    }
  }

  async function apiFetch(path, options = {}) {
    const { method = 'GET', body, authExempt = false } = options;
    const init = {
      method,
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    let response;
    try {
      response = await fetch(path, init);
    } catch (err) {
      throw new ApiError('网络异常，请检查与服务器的连接', 0, null);
    }
    const raw = await response.text();
    let payload = null;
    if (raw) {
      try {
        payload = JSON.parse(raw);
      } catch (err) {
        payload = null;
      }
    }
    if (!response.ok) {
      // 401 统一处理：会话失效立即回登录视图（session/login/healthz 除外）
      if (response.status === 401 && !authExempt) handleUnauthorized();
      const fallback = `请求失败（${response.status}）`;
      const message = payload && typeof payload.error === 'string' ? payload.error : fallback;
      throw new ApiError(message, response.status, payload);
    }
    return payload;
  }

  function apiSession() {
    return apiFetch(api('/api/session'), { authExempt: true });
  }

  function apiLogin(password) {
    return apiFetch(api('/api/login'), {
      method: 'POST',
      body: { password },
      authExempt: true,
    });
  }

  function apiLogout() {
    return apiFetch(api('/api/logout'), { method: 'POST' });
  }

  function apiEvents({ limit = PAGE_SIZE, cursor = null, q = '', app = '', device = '' } = {}) {
    const params = new URLSearchParams({ limit: String(limit) });
    if (cursor) params.set('cursor', cursor);
    if (q) params.set('q', q);
    if (app) params.set('app', app);
    if (device) params.set('device', device);
    return apiFetch(api(`/api/events?${params.toString()}`));
  }

  function apiStats() {
    return apiFetch(api('/api/stats'));
  }

  function apiDevices() {
    return apiFetch(api('/api/devices'));
  }

  function apiClear(scope) {
    return apiFetch(api('/api/clear'), { method: 'POST', body: { scope } });
  }

  function apiRotateToken() {
    return apiFetch(api('/api/rotate-token'), { method: 'POST' });
  }

  function apiHealth() {
    return apiFetch(api('/healthz'), { authExempt: true });
  }

  // ---------- DOM 引用 ----------
  const dom = {
    loginView: byId('loginView'),
    loginCard: byId('loginCard'),
    loginForm: byId('loginForm'),
    loginPassword: byId('loginPassword'),
    loginBtn: byId('loginBtn'),
    loginBtnLabel: byId('loginBtnLabel'),
    loginError: byId('loginError'),

    appView: byId('appView'),
    pageTitle: byId('pageTitle'),
    statusWrap: byId('statusWrap'),
    statusText: byId('statusText'),

    paneMessages: byId('paneMessages'),
    paneDevices: byId('paneDevices'),
    paneSettings: byId('paneSettings'),

    searchInput: byId('searchInput'),
    appChips: byId('appChips'),
    deviceChips: byId('deviceChips'),
    eventList: byId('eventList'),
    emptyState: byId('emptyState'),
    listStatus: byId('listStatus'),

    deviceList: byId('deviceList'),

    tabBar: byId('tabBar'),

    rawScrim: byId('rawScrim'),
    rawDrawer: byId('rawDrawer'),
    rawMeta: byId('rawMeta'),
    rawJson: byId('rawJson'),
    rawClose: byId('rawClose'),
    rawCopy: byId('rawCopy'),

    exportJsonl: byId('exportJsonl'),
    exportJson: byId('exportJson'),
    clearOpen: byId('clearOpen'),
    clearScrim: byId('clearScrim'),
    clearDialog: byId('clearDialog'),
    clearInput: byId('clearInput'),
    clearCancel: byId('clearCancel'),
    clearConfirm: byId('clearConfirm'),
    tokenValue: byId('tokenValue'),
    tokenToggle: byId('tokenToggle'),
    tokenCopy: byId('tokenCopy'),
    tokenWarn: byId('tokenWarn'),
    healthBtn: byId('healthBtn'),
    healthOut: byId('healthOut'),
    logoutBtn: byId('logoutBtn'),

    toast: byId('toast'),
  };

  // ---------- 状态 ----------
  const state = {
    authenticated: false,
    view: 'messages',
    query: '',
    app: '',
    device: '',
    events: [],
    ids: new Set(),
    nextCursor: null,
    loadingList: false,
    listSeq: 0, // 请求序号：快速切筛选时丢弃过期响应
    stats: null,
    devices: [],
    devicesLoaded: false,
    devicesLoading: false,
    rawText: '',
    token: null,
    tokenVisible: false,
    clearBusy: false,
    loginBusy: false,
    lockUntil: 0,
    lockTimer: null,
    toastTimer: null,
    relativeTimer: null,
    stream: null,
    reconnectTimer: null,
    reconnectDelay: RECONNECT_BASE,
    scrollTick: false,
  };

  // ---------- 登录 / 视图切换 ----------
  function setLoginError(message) {
    dom.loginError.textContent = message || '';
    dom.loginError.classList.toggle('is-visible', Boolean(message));
  }

  function setLoginBusy(busy) {
    state.loginBusy = busy;
    dom.loginBtn.classList.toggle('is-busy', busy);
    dom.loginBtn.disabled = busy || state.lockUntil > Date.now();
    dom.loginBtnLabel.textContent = busy ? '验证中' : '登录';
  }

  function shakeLoginCard(message) {
    setLoginError(message);
    dom.loginCard.classList.remove('shake');
    // 读一次布局属性强制回流，让同一个动画能被重复触发
    void dom.loginCard.offsetWidth;
    dom.loginCard.classList.add('shake');
  }

  function startLockCountdown(seconds) {
    if (state.lockTimer) clearInterval(state.lockTimer);
    state.lockUntil = Date.now() + seconds * 1000;
    shakeLoginCard(`尝试过多，请 ${seconds} 秒后再试`);
    const tick = () => {
      const left = Math.max(0, Math.ceil((state.lockUntil - Date.now()) / 1000));
      if (left <= 0) {
        clearInterval(state.lockTimer);
        state.lockTimer = null;
        state.lockUntil = 0;
        setLoginError('');
        setLoginBusy(false);
        return;
      }
      setLoginError(`尝试过多，请 ${left} 秒后再试`);
      dom.loginBtn.classList.remove('is-busy');
      dom.loginBtn.disabled = true;
      dom.loginBtnLabel.textContent = `请稍候 ${left}s`;
    };
    tick();
    state.lockTimer = setInterval(tick, 300);
  }

  async function submitLogin(event) {
    event.preventDefault();
    if (state.loginBusy || state.lockUntil > Date.now()) return;
    const password = dom.loginPassword.value;
    if (!password) {
      shakeLoginCard('请输入网页密码');
      return;
    }
    setLoginError('');
    setLoginBusy(true);
    try {
      await apiLogin(password);
      dom.loginPassword.value = '';
      setLoginBusy(false);
      await enterApp();
    } catch (err) {
      if (err.status === 429) {
        const payload = err.payload || {};
        const seconds = Math.max(1, Math.ceil(Number(payload.retry_after) || 30));
        // 倒计时期间按钮的禁用与文案交给 tick 管，这里主动清掉 loading 态
        state.loginBusy = false;
        dom.loginBtn.classList.remove('is-busy');
        startLockCountdown(seconds);
        return;
      }
      setLoginBusy(false);
      shakeLoginCard(err.status === 401 ? '密码错误，请重试' : err.message);
    }
  }

  function enterLogin() {
    stopStream();
    state.authenticated = false;
    dom.appView.hidden = true;
    dom.tabBar.hidden = true;
    dom.loginView.hidden = false;
    window.setTimeout(() => dom.loginPassword.focus(), 80);
  }

  function handleUnauthorized() {
    if (!state.authenticated && !dom.loginView.hidden) return;
    enterLogin();
    setLoginError('会话已失效，请重新登录');
  }

  async function enterApp() {
    state.authenticated = true;
    dom.loginView.hidden = true;
    dom.appView.hidden = false;
    dom.tabBar.hidden = false;
    setLoginError('');
    setView(state.view);
    startStream();
    await Promise.all([loadStats(), reloadMessages()]);
  }

  function setView(view) {
    state.view = view;
    dom.pageTitle.textContent = VIEW_TITLES[view] || VIEW_TITLES.messages;
    dom.paneMessages.hidden = view !== 'messages';
    dom.paneDevices.hidden = view !== 'devices';
    dom.paneSettings.hidden = view !== 'settings';
    dom.statusWrap.hidden = view !== 'messages'; // 实时状态点只在消息页展示
    const buttons = dom.tabBar.querySelectorAll('.tab-btn');
    buttons.forEach((btn) => {
      const active = btn.dataset.view === view;
      btn.classList.toggle('is-active', active);
      btn.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    const index = Math.max(0, VIEW_ORDER.indexOf(view));
    dom.tabBar.style.setProperty('--nav-index', String(index));
    if (view === 'devices' && !state.devicesLoaded) loadDevices();
    if (view === 'messages') onScroll();
  }

  function setStatus(text, live) {
    dom.statusText.textContent = text;
    dom.statusWrap.classList.toggle('is-live', Boolean(live));
  }

  function toast(message, kind = 'ok') {
    dom.toast.textContent = message;
    dom.toast.classList.toggle('is-danger', kind === 'danger');
    dom.toast.classList.add('is-show');
    if (state.toastTimer) clearTimeout(state.toastTimer);
    state.toastTimer = window.setTimeout(() => dom.toast.classList.remove('is-show'), 2600);
  }

  // ---------- 消息列表 ----------
  function setListStatus(text, isError = false) {
    dom.listStatus.textContent = text || '';
    dom.listStatus.classList.toggle('is-error', Boolean(isError));
  }

  function renderEmpty() {
    dom.emptyState.hidden = state.events.length > 0;
  }

  function applyFirstPage(data) {
    const events = Array.isArray(data.events) ? data.events : [];
    state.events = events;
    state.ids = new Set(events.map((ev) => String(ev.id)));
    state.nextCursor = data.next_cursor || null;
    renderEventList();
    setListStatus(state.nextCursor ? '' : (events.length ? '没有更多了' : ''));
  }

  async function reloadMessages() {
    const seq = (state.listSeq += 1);
    state.loadingList = true;
    setListStatus('正在加载…');
    try {
      const data = await apiEvents({
        limit: PAGE_SIZE,
        q: state.query,
        app: state.app,
        device: state.device,
      });
      if (seq !== state.listSeq) return;
      applyFirstPage(data);
    } catch (err) {
      if (seq !== state.listSeq || err.status === 401) return;
      setListStatus(err.message, true);
    } finally {
      if (seq === state.listSeq) {
        state.loadingList = false;
        onScroll(); // 首页不足一屏时继续追加，避免出现"滚不动"的假死
      }
    }
  }

  async function loadMore() {
    if (state.loadingList || !state.nextCursor || state.view !== 'messages') return;
    const seq = state.listSeq;
    let added = 0;
    state.loadingList = true;
    setListStatus('正在加载更多…');
    try {
      const data = await apiEvents({
        cursor: state.nextCursor,
        q: state.query,
        app: state.app,
        device: state.device,
      });
      if (seq !== state.listSeq) return;
      const more = Array.isArray(data.events) ? data.events : [];
      more.forEach((ev) => {
        const id = String(ev.id);
        if (state.ids.has(id)) return;
        state.ids.add(id);
        state.events.push(ev);
        dom.eventList.append(renderEventCard(ev));
        added += 1;
      });
      state.nextCursor = data.next_cursor || null;
      renderEmpty();
      setListStatus(state.nextCursor ? '' : '没有更多了');
    } catch (err) {
      if (seq === state.listSeq && err.status !== 401) setListStatus(err.message, true);
    } finally {
      if (seq === state.listSeq) state.loadingList = false;
      // 一页没撑满视口时继续追加；本页没新增数据就不再自动续页，防止空页死循环
      if (added > 0 && state.nextCursor && seq === state.listSeq) {
        requestAnimationFrame(onScroll);
      }
    }
  }

  function renderEventList() {
    dom.eventList.replaceChildren();
    state.events.forEach((ev) => dom.eventList.append(renderEventCard(ev)));
    renderEmpty();
  }

  function renderEventCard(ev) {
    const receivedAt = Number(ev.received_at) || 0;
    const card = el('article', 'msg-card');
    card.dataset.id = String(ev.id || '');

    const head = el('div', 'msg-head');
    const avatar = el('span', 'app-avatar', firstChar(ev.app_label || ev.app_package));
    const tint = APP_COLORS[hashIndex(String(ev.app_package || ''), APP_COLORS.length)];
    avatar.style.setProperty('--tint', tint);
    avatar.setAttribute('aria-hidden', 'true');
    head.append(avatar);

    const meta = el('div', 'msg-meta');
    meta.append(el('span', 'msg-app', ev.app_label || ev.app_package || '未知应用'));
    const sub = el('span', 'msg-sub');
    sub.append(document.createTextNode(`${deviceLabel(ev)} · `));
    const relative = el('span', 'rel-time');
    relative.dataset.ts = String(receivedAt);
    relative.textContent = formatRelative(receivedAt);
    sub.append(relative);
    meta.append(sub);
    head.append(meta);
    card.append(head);

    const title = ev.title || (ev.type === 'sms' ? '短信' : '通知');
    card.append(el('h3', 'msg-title', title));

    const body = ev.text || ev.big_text || '';
    if (body) card.append(el('p', 'msg-text', body));

    const actions = el('div', 'msg-actions');
    const rawBtn = el('button', 'ghost-btn', '原始 JSON');
    rawBtn.type = 'button';
    rawBtn.addEventListener('click', (event) => {
      event.stopPropagation(); // 阻止冒泡，否则会顺带切换卡片展开态
      openRawDrawer(ev);
    });
    actions.append(rawBtn);
    card.append(actions);

    // 点击卡片展开/收起正文（正文 3 行截断，展开后露出原始 JSON 按钮）
    card.addEventListener('click', () => card.classList.toggle('expanded'));
    return card;
  }

  function prependEvent(ev) {
    const id = String(ev.id);
    if (state.ids.has(id)) return false;
    state.ids.add(id);
    state.events.unshift(ev);
    const card = renderEventCard(ev);
    card.classList.add('is-new');
    card.addEventListener('animationend', () => card.classList.remove('is-new'), { once: true });
    window.setTimeout(() => card.classList.remove('is-new'), FLASH_MS + 160);
    dom.eventList.prepend(card);
    renderEmpty();
    return true;
  }

  function matchesFilter(ev) {
    if (state.app && String(ev.app_package) !== state.app) return false;
    if (state.device && String(ev.device_id) !== state.device) return false;
    if (state.query) {
      const needle = state.query.toLowerCase();
      const haystack = [ev.title, ev.text, ev.big_text, ev.sub_text, ev.app_label]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      if (!haystack.includes(needle)) return false;
    }
    return true;
  }

  function updateRelativeTimes() {
    const now = Date.now();
    document.querySelectorAll('.rel-time').forEach((node) => {
      node.textContent = formatRelative(Number(node.dataset.ts), now);
    });
  }

  // ---------- 筛选 chips ----------
  function makeChip(label, count, active, onClick) {
    const chip = el('button', active ? 'chip is-active' : 'chip');
    chip.type = 'button';
    chip.setAttribute('aria-pressed', active ? 'true' : 'false');
    chip.append(el('span', 'chip-label', label));
    if (Number.isFinite(count) && count > 0) {
      chip.append(el('span', 'chip-count', String(count)));
    }
    chip.addEventListener('click', onClick);
    return chip;
  }

  function renderChipRow(container, allLabel, activeValue, items, onPick) {
    container.replaceChildren();
    container.append(makeChip(allLabel, null, activeValue === '', () => onPick('')));
    items.forEach((item) => {
      const active = activeValue === item.value;
      container.append(makeChip(item.label, item.count, active, () => onPick(
        active ? '' : item.value,
      )));
    });
  }

  function renderChips() {
    const apps = state.stats && Array.isArray(state.stats.apps) ? state.stats.apps : [];
    const devices = state.stats && Array.isArray(state.stats.devices) ? state.stats.devices : [];

    renderChipRow(
      dom.appChips,
      '全部应用',
      state.app,
      apps.map((item) => ({
        value: String(item.package || ''),
        label: item.label || String(item.package || '未知应用'),
        count: Number(item.count),
      })).filter((item) => item.value),
      (value) => {
        state.app = value;
        renderChips();
        reloadMessages();
      },
    );

    renderChipRow(
      dom.deviceChips,
      '全部设备',
      state.device,
      devices.map((item) => ({
        value: String(item.id || ''),
        label: item.alias || `设备 ${String(item.id || '').slice(0, 8)}`,
        count: Number(item.count),
      })).filter((item) => item.value),
      (value) => {
        state.device = value;
        renderChips();
        reloadMessages();
      },
    );
  }

  async function loadStats() {
    try {
      state.stats = await apiStats();
      renderChips();
    } catch (err) {
      if (err.status !== 401) setListStatus(`统计加载失败：${err.message}`, true);
    }
  }

  // ---------- 原始 JSON 抽屉 ----------
  function openRawDrawer(ev) {
    const source = ev && ev.raw_json !== undefined && ev.raw_json !== null ? ev.raw_json : ev;
    let text = '';
    try {
      text = JSON.stringify(source, null, 2);
    } catch (err) {
      text = String(source);
    }
    state.rawText = text || '';
    // textContent 写入 <pre>：raw_json 里就算带 <script> 也只会被当成文本
    dom.rawJson.textContent = state.rawText;
    dom.rawMeta.textContent = `${ev.app_label || ev.app_package || ''} · ` +
      `${formatDateTime(ev.received_at)}`;
    dom.rawScrim.classList.add('is-open');
    dom.rawDrawer.classList.add('is-open');
    dom.rawDrawer.setAttribute('aria-hidden', 'false');
    document.body.classList.add('no-scroll');
  }

  function closeRawDrawer() {
    dom.rawScrim.classList.remove('is-open');
    dom.rawDrawer.classList.remove('is-open');
    dom.rawDrawer.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('no-scroll');
  }

  // ---------- 设备 ----------
  async function loadDevices() {
    if (state.devicesLoading) return;
    state.devicesLoading = true;
    dom.deviceList.replaceChildren(el('p', 'hint', '正在加载设备…'));
    try {
      const data = await apiDevices();
      state.devices = Array.isArray(data.devices) ? data.devices : [];
      state.devicesLoaded = true;
      renderDevices();
    } catch (err) {
      if (err.status !== 401) {
        dom.deviceList.replaceChildren(el('p', 'hint is-error', `设备加载失败：${err.message}`));
      }
    } finally {
      state.devicesLoading = false;
    }
  }

  function renderDevices() {
    dom.deviceList.replaceChildren();
    if (!state.devices.length) {
      dom.deviceList.append(
        el('p', 'hint', '还没有设备上报过消息。在手机 App 里填写服务器地址和接入令牌即可自动注册。'),
      );
      return;
    }
    state.devices.forEach((dev) => dom.deviceList.append(renderDeviceCard(dev)));
  }

  function renderDeviceCard(dev) {
    const card = el('article', 'glass device-card');

    const top = el('div', 'device-top');
    const main = el('div', 'device-main');
    main.append(el('h2', 'device-alias', dev.alias || '未命名设备'));
    const model = [dev.manufacturer, dev.model].filter(Boolean).join(' ');
    const line = [
      model,
      dev.android ? `Android ${dev.android}` : '',
      dev.app_version ? `App ${dev.app_version}` : '',
    ].filter(Boolean).join(' · ');
    main.append(el('p', 'device-model', line || '型号未知'));
    top.append(main);
    card.append(top);

    const stats = el('div', 'device-stats');

    const batteryCell = el('div', 'stat');
    batteryCell.append(el('span', 'stat-label', '电量'));
    const batteryValue = el('span', 'stat-value');
    if (dev.battery === null || dev.battery === undefined) {
      batteryValue.textContent = '未知';
    } else {
      batteryValue.textContent = `${dev.battery}%`;
      if (dev.charging) {
        batteryValue.classList.add('is-charging');
        batteryValue.append(iconBolt('ico-bolt'));
      }
    }
    batteryCell.append(batteryValue);

    const networkCell = el('div', 'stat');
    networkCell.append(el('span', 'stat-label', '网络'));
    networkCell.append(el('span', 'stat-value', dev.network || '未知'));

    const seenCell = el('div', 'stat');
    seenCell.append(el('span', 'stat-label', '最后在线'));
    const seenValue = el('span', 'stat-value rel-time');
    seenValue.dataset.ts = String(Number(dev.last_seen) || 0);
    seenValue.textContent = formatRelative(Number(dev.last_seen) || 0);
    seenCell.append(seenValue);

    const countCell = el('div', 'stat');
    countCell.append(el('span', 'stat-label', '累计消息'));
    countCell.append(el('span', 'stat-value', `${Number(dev.event_count) || 0} 条`));

    stats.append(batteryCell, networkCell, seenCell, countCell);
    card.append(stats);
    return card;
  }

  // ---------- 设置 ----------
  function exportEvents(format) {
    // 下载头由服务端 Content-Disposition 给，前端只要发起导航
    window.location.href = api(`/api/export?format=${format === 'json' ? 'json' : 'jsonl'}`);
  }

  function openClearDialog() {
    dom.clearInput.value = '';
    dom.clearConfirm.disabled = true;
    dom.clearScrim.classList.add('is-open');
    dom.clearDialog.classList.add('is-open');
    dom.clearDialog.setAttribute('aria-hidden', 'false');
    document.body.classList.add('no-scroll');
    window.setTimeout(() => dom.clearInput.focus(), 80);
  }

  function closeClearDialog() {
    dom.clearScrim.classList.remove('is-open');
    dom.clearDialog.classList.remove('is-open');
    dom.clearDialog.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('no-scroll');
  }

  async function submitClear() {
    if (state.clearBusy || dom.clearInput.value.trim() !== 'ALL') return;
    state.clearBusy = true;
    dom.clearConfirm.disabled = true;
    try {
      const data = await apiClear('all');
      const deleted = Number(data && data.deleted) || 0;
      closeClearDialog();
      toast(`已清空 ${deleted} 条消息`);
      await Promise.all([
        reloadMessages(),
        loadStats(),
        state.devicesLoaded ? loadDevices() : Promise.resolve(),
      ]);
    } catch (err) {
      if (err.status !== 401) toast(err.message, 'danger');
    } finally {
      state.clearBusy = false;
      dom.clearConfirm.disabled = dom.clearInput.value.trim() !== 'ALL';
    }
  }

  function renderToken() {
    const visible = Boolean(state.tokenVisible && state.token);
    dom.tokenValue.classList.toggle('is-masked', !visible);
    dom.tokenValue.textContent = visible ? state.token : (state.token ? '令牌已生成 · 已隐藏' : '未生成');
    dom.tokenToggle.textContent = state.tokenVisible ? '隐藏' : '显示';
    dom.tokenCopy.disabled = !visible;
    dom.tokenWarn.hidden = !visible;
  }

  async function toggleToken() {
    if (state.tokenVisible) {
      state.tokenVisible = false;
      renderToken();
      return;
    }
    if (state.token) {
      // 已经轮换过：只切换可见性，避免每次点“显示”都作废手机里的令牌
      state.tokenVisible = true;
      renderToken();
      return;
    }
    dom.tokenToggle.disabled = true;
    dom.tokenToggle.textContent = '生成中…';
    try {
      const data = await apiRotateToken();
      state.token = typeof data.ingest_token === 'string' ? data.ingest_token : '';
      state.tokenVisible = Boolean(state.token);
      if (state.token) toast('已生成新令牌，旧令牌立即失效');
    } catch (err) {
      if (err.status !== 401) toast(err.message, 'danger');
    } finally {
      dom.tokenToggle.disabled = false;
      renderToken();
    }
  }

  async function checkHealth() {
    dom.healthBtn.disabled = true;
    dom.healthOut.textContent = '正在检测…';
    try {
      const data = await apiHealth();
      const uptime = formatUptime(Number(data && data.uptime) || 0);
      const events = Number(data && data.events) || 0;
      dom.healthOut.textContent = data && data.ok
        ? `正常 · 已运行 ${uptime} · 已存事件 ${events} 条`
        : `服务未就绪 · 已运行 ${uptime}`;
    } catch (err) {
      dom.healthOut.textContent = `健康检查失败：${err.message}`;
    } finally {
      dom.healthBtn.disabled = false;
    }
  }

  async function logout() {
    try {
      await apiLogout();
    } catch (err) {
      // 即便请求失败也回到登录视图，本地状态不能留着
    }
    enterLogin();
    setLoginError('');
    toast('已退出登录');
  }

  // ---------- SSE ----------
  function stopStream() {
    if (state.stream) {
      state.stream.close();
      state.stream = null;
    }
    if (state.reconnectTimer) {
      clearTimeout(state.reconnectTimer);
      state.reconnectTimer = null;
    }
  }

  function startStream() {
    if (!state.authenticated || document.hidden || state.stream) return;
    if (state.reconnectTimer) {
      clearTimeout(state.reconnectTimer);
      state.reconnectTimer = null;
    }
    const source = new EventSource(api('/api/stream'));
    state.stream = source;
    source.onopen = () => {
      state.reconnectDelay = RECONNECT_BASE; // 连上就重置退避
      setStatus('已连接', true);
    };
    source.onmessage = (event) => handleStreamPayload(event.data);
    source.onerror = () => {
      // 关掉浏览器自带的粗暴重连，改用自己的指数退避
      if (state.stream === source) state.stream = null;
      source.close();
      setStatus('重连中', false);
      scheduleReconnect();
    };
  }

  function scheduleReconnect() {
    if (state.reconnectTimer || !state.authenticated || document.hidden) return;
    const delay = state.reconnectDelay;
    state.reconnectTimer = window.setTimeout(() => {
      state.reconnectTimer = null;
      state.reconnectDelay = Math.min(Math.round(delay * RECONNECT_FACTOR), RECONNECT_MAX);
      startStream();
    }, delay);
  }

  function handleStreamPayload(raw) {
    if (!raw) return;
    let payload;
    try {
      payload = JSON.parse(raw);
    } catch (err) {
      return; // 心跳与异常数据直接忽略
    }
    if (!payload || typeof payload !== 'object' || payload.id === undefined) return;
    if (!matchesFilter(payload)) return;
    prependEvent(payload);
  }

  function onVisibilityChange() {
    if (!state.authenticated) return;
    if (document.hidden) {
      // 页面不可见时断开，省移动端电量和连接数
      stopStream();
      setStatus('已暂停', false);
      return;
    }
    state.reconnectDelay = RECONNECT_BASE;
    setStatus('连接中', false);
    startStream();
  }

  // ---------- 滚动加载 ----------
  function onScroll() {
    if (state.scrollTick) return;
    state.scrollTick = true;
    requestAnimationFrame(() => {
      state.scrollTick = false;
      if (state.view !== 'messages') return;
      const doc = document.documentElement;
      const nearBottom = doc.scrollHeight <= window.innerHeight + 200
        || window.scrollY + window.innerHeight >= doc.scrollHeight - 480;
      if (nearBottom) loadMore();
    });
  }

  // ---------- 事件绑定 ----------
  function bindEvents() {
    dom.loginForm.addEventListener('submit', submitLogin);
    dom.searchInput.addEventListener('input', debounce(() => {
      const value = dom.searchInput.value.trim();
      if (value === state.query) return;
      state.query = value;
      reloadMessages();
    }, SEARCH_DEBOUNCE));

    dom.tabBar.addEventListener('click', (event) => {
      const btn = event.target.closest('.tab-btn');
      if (btn && btn.dataset.view) setView(btn.dataset.view);
    });
    window.addEventListener('scroll', onScroll, { passive: true });
    document.addEventListener('visibilitychange', onVisibilityChange);

    dom.rawScrim.addEventListener('click', closeRawDrawer);
    dom.rawClose.addEventListener('click', closeRawDrawer);
    dom.rawCopy.addEventListener('click', async () => {
      const ok = await copyText(state.rawText);
      toast(ok ? '已复制原始 JSON' : '复制失败，请手动选择文本', ok ? 'ok' : 'danger');
    });

    dom.exportJsonl.addEventListener('click', () => exportEvents('jsonl'));
    dom.exportJson.addEventListener('click', () => exportEvents('json'));

    dom.clearOpen.addEventListener('click', openClearDialog);
    dom.clearCancel.addEventListener('click', closeClearDialog);
    dom.clearScrim.addEventListener('click', closeClearDialog);
    dom.clearInput.addEventListener('input', () => {
      dom.clearConfirm.disabled = state.clearBusy || dom.clearInput.value.trim() !== 'ALL';
    });
    dom.clearConfirm.addEventListener('click', submitClear);

    dom.tokenToggle.addEventListener('click', toggleToken);
    dom.tokenCopy.addEventListener('click', async () => {
      if (!state.tokenVisible || !state.token) return;
      const ok = await copyText(state.token);
      toast(ok ? '令牌已复制' : '复制失败，请手动选择文本', ok ? 'ok' : 'danger');
    });
    dom.healthBtn.addEventListener('click', checkHealth);
    dom.logoutBtn.addEventListener('click', logout);

    document.addEventListener('keydown', (event) => {
      if (event.key !== 'Escape') return;
      if (dom.rawDrawer.classList.contains('is-open')) closeRawDrawer();
      if (dom.clearDialog.classList.contains('is-open')) closeClearDialog();
    });
  }

  // ---------- 启动 ----------
  async function boot() {
    bindEvents();
    renderToken();
    state.relativeTimer = setInterval(updateRelativeTimes, RELATIVE_REFRESH);
    let session = null;
    try {
      session = await apiSession();
    } catch (err) {
      session = null;
    }
    if (session && session.authenticated) {
      await enterApp();
      return;
    }
    enterLogin();
    if (!session) setLoginError('无法连接服务器，请确认服务已启动');
  }

  boot();
})();
