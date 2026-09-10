/*
 * ui-trim.js —— 插件工具窗界面精简（v0.3.17）
 *
 * 三条规则（均为 UI 层样式干预，不触碰数据/React 状态）：
 * 1) rail 精简：整体布局不变——侧边栏列、展开态内容（工作区树/区段头）全部保留；
 *    「添加工作区」收起与展开两态都隐藏；「搜索会话」仅收起（rail 56px）形态隐藏；
 *    「新建会话」与品牌/展开开关、底部「设置」一律保留；rail/wide 切换时按当前形态
 *    应用或恢复（display 复位），避免展开后按钮被永久隐藏。
 * 2) 工作区 chip 固定化：对话窗 hero 的「选择工作区」chip 不再可点击——置 disabled、
 *    屏蔽指针、隐藏下拉箭头，视觉上固定为静态「📁 当前文件夹」标签。
 *
 * 实现要点：
 *  - 栏本体 = 含「收起/打开侧边栏」开关按钮的最近容器（从待隐藏按钮向上找）；
 *    其 class 含 collapsed 标记（CSS Module <hash>_collapsed，子串匹配）即收起态。
 *  - 只操作按钮级元素，绝不触碰 grid 列轨道（display:none 列会破坏 Grid 布局）。
 * 脚本幂等：同一页面只安装一次 observer；MutationObserver + 120ms 防抖持续响应
 * 收起/展开切换与 SPA 重渲染（React 重建节点后自动重新应用）。
 */
(function () {
  'use strict';
  if (window.__dshUiTrimInstalled) { return; }
  window.__dshUiTrimInstalled = true;
  if (!document.body) { return; }

  function setDisplay(el, v) {
    if (el && el.style.display !== v) { el.style.display = v; }
  }
  function vis(el) {
    try { return !!el && el.parentNode && el.getClientRects().length > 0; } catch (e) { return false; }
  }
  function qa(sel, ctx) {
    try { return Array.prototype.slice.call((ctx || document).querySelectorAll(sel)); } catch (e) { return []; }
  }
  function up(el, ok) {
    for (var n = el; n && n !== document.body; n = n.parentNode) {
      if (ok(n)) { return n; }
    }
    return null;
  }

  // 添加工作区：收起与展开两态都隐藏；搜索会话：仅收起态隐藏（展开态保留）。
  var ALWAYS_HIDE = 'button[aria-label="添加工作区"]';
  var RAIL_HIDE = 'button[aria-label="搜索会话"]';
  var ANY_HIDE = ALWAYS_HIDE + ',' + RAIL_HIDE;
  // 对话窗 hero 的工作区 chip（button，可点开切换菜单）。
  var WS_CHIP = 'button[aria-label="选择工作区"]';

  // 工作区 chip 固定化：disabled + 屏蔽指针（含 hover/focus）+ 隐藏下拉箭头。
  // 组件无 :disabled 灰化样式，禁用后视觉不变，恰好呈现为静态文件夹标签。
  function pinWorkspaceChip() {
    qa(WS_CHIP).forEach(function (b) {
      if (!b.disabled) { b.disabled = true; }
      if (b.style.pointerEvents !== 'none') { b.style.pointerEvents = 'none'; }
      if (b.style.cursor !== 'default') { b.style.cursor = 'default'; }
      qa('svg[class*="chevron"],span[class*="chevron"]', b).forEach(function (c) {
        setDisplay(c, 'none');
      });
    });
  }

  function trim() {
    try {
      var cols = qa('div[class*="sidebarCol"]');
      for (var i = 0; i < cols.length; i++) {
        var col = cols[i];
        if (!vis(col)) { continue; }
        var news = col.querySelector(ANY_HIDE);
        if (!news) { continue; }
        // 栏本体：含侧边栏开关的最近容器（从待隐藏按钮向上找）。
        var root = up(news, function (n) {
          return n.tagName === 'DIV' && n !== col
            && n.querySelector('button[aria-label="收起侧边栏"],button[aria-label="打开侧边栏"]') !== null;
        }) || col;
        // 收起态标志：沿根容器向上找首个 class 含 collapsed 的祖先（限在列内）。
        var collapsed = false;
        for (var n2 = root; n2 && n2 !== document.body; n2 = n2.parentNode) {
          if (String(n2.className || '').indexOf('collapsed') !== -1) { collapsed = true; break; }
          if (n2 === col) { break; }
        }
        // 添加工作区两态都隐藏；搜索会话仅收起态隐藏，展开态复位（rail/wide 可能复用节点）。
        qa(ALWAYS_HIDE, col).forEach(function (b) { setDisplay(b, 'none'); });
        qa(RAIL_HIDE, col).forEach(function (b) { setDisplay(b, collapsed ? 'none' : ''); });
      }
      // 对话窗工作区 chip 固定为静态标签（不参与侧边栏循环）。
      pinWorkspaceChip();
    } catch (e) {
      if (window.console) { console.warn('[AiWeb] ui-trim:', e); }
    }
  }

  trim();
  var timer = 0;
  var mo = new MutationObserver(function () {
    if (timer) { return; }
    timer = setTimeout(function () { timer = 0; trim(); }, 120);
  });
  mo.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style'] });
})();
