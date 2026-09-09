/* DeepSeek Harness Web - inline composer references v3 (chip native).
 *
 * v3 replaces the v2 plain-text [[token]] approach with the page's OWN
 * file-reference chip when available. The harness composer is a Lexical
 * contenteditable (div[data-composer-input] inside [data-composer-card])
 * whose node registry contains ReferenceChipNode ('reference-chip') - the
 * same atomic decorator chip the page's own '@' file menu inserts. That chip
 * is exactly the Qoder-style colored inline reference the user asked for:
 * an icon + short label between which text can be typed freely.
 *
 * Injection mechanics (verified headless against lexical 0.49):
 *
 *   - the Lexical editor instance is reachable through the composer root
 *     element: root.__lexicalEditor (Lexical sets it in setRootElement).
 *   - the chip class comes from the editor's node registry:
 *     ed._nodes.get('reference-chip').klass
 *   - minting a node INSIDE ed.update(fn) assigns it a fresh NodeKey and
 *     registers it in the pending nodeMap (LexicalNode's constructor calls
 *     $setNodeKey, which needs the active editor the update callback
 *     establishes) - so no $-helpers are required at all; plain instance
 *     methods (insertBefore/insertAfter/splitText/append/remove) work.
 *   - the update callback's pending selection is re-derived from the real
 *     DOM selection ($internalCreateSelection inside $beginUpdate), so
 *     wherever the user's caret is in the composer is where the chip lands
 *     (text-anchor middle splits the text node; element anchors insert at
 *     the child offset; no selection falls back to the end of the last
 *     block).
 *   - chip identity is tracked per NodeKey; an editor update listener
 *     sweeps the committed nodeMap after every commit and reports chips
 *     that vanished (user Backspace, undo, or a real send clearing the
 *     draft) to the IDE bridge via __dshChipRemoved -> the Java side clears
 *     the source editor selection, exactly like the old __dshSent flow.
 *   - sending needs NO interception at all: the draft submits through the
 *     page's own machinery, which serializes every chip through the
 *     registered 'reference' source codec (identity: ref passes through
 *     unchanged - our ref IS the full "path:lines" value), so the AI
 *     receives the complete path reference natively.
 *   - hover-remove: the official chip face is display-only (deletion is
 *     keyboard-only). Chips this plugin inserts show a × knob while the
 *     mouse hovers them - a body-level fixed element, so the Lexical/React
 *     DOM is never touched; clicking it removes the chip through the same
 *     commit-sweep path, so the IDE clears the source editor selection.
 *
 * If the page ever lacks the chip class (structure change), everything
 * falls back to the v2 plain-text token pipeline (paste channel, send-time
 * expansion, Enter/click takeover) plus the legacy top strip; textarea
 * composers keep the v1 native-setter path.
 *
 * The plugin calls window.__dshAddRefChip(value, label) exactly like before.
 */
(function () {
  if (window.__dshChipOverlay) { return; }
  window.__dshChipOverlay = true;

  var MAX = 60;
  var REVERT_MS = 300;

  // inline text-token state (v2 fallback pipeline)
  var maps = [];    // { token, value } - inline tokens currently in the draft
  var pending = []; // { value, label } - waiting for the composer to exist/enable
  var flusher = null;
  var pendingRevert = null;

  // legacy top-chip fallback state
  var topChips = [];
  var strip = null;
  var stripStyle = null;
  var rafStrip = 0;

  // chip-native state (v3 primary channel)
  var CHIP_TYPE = 'reference-chip';
  var chipTrack = new Map();  // value -> Set<nodeKey> of chips we inserted
  var chipWatchEd = null;     // editor the sweep listener is registered on

  var diagShown = false;
  var diagEl = null;
  var warnTimer = null;

  var CARD_SEL = '[data-composer-card]';
  var CE_SEL = '[data-composer-input]'; // Lexical contenteditable (current dsh)
  var TA_SEL = 'textarea:not([disabled])'; // legacy composer
  var SEND_SEL = 'button[aria-label="\u53d1\u9001\u6d88\u606f"],button[aria-label="Send message"]';

  // ---------------------------------------------------------------- helpers

  function nameOf(p) {
    var i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
    return i >= 0 ? p.slice(i + 1) : p;
  }

  function tokenOf(label, value) {
    return '[[' + (label || nameOf(value)) + ']]';
  }

  function isVisible(el) {
    if (!el) { return false; }
    var r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  /** The composer text surface, or null while absent/locked.
   *  { kind:'ce', root } = Lexical contenteditable; { kind:'ta', root } = textarea. */
  function composer() {
    var card = document.querySelector(CARD_SEL);
    if (!card) { return null; }
    var ce = card.querySelector(CE_SEL);
    if (ce) {
      if (!ce.isContentEditable) { return null; } // locked/inert state
      return { kind: 'ce', root: ce };
    }
    var ta = card.querySelector(TA_SEL);
    if (ta) { return { kind: 'ta', root: ta }; }
    return null;
  }

  function draftOf(c) {
    return c.kind === 'ce' ? (c.root.innerText || '') : (c.root.value || '');
  }

  /** Collapse whitespace so Lexical paragraph normalization and our strings
   *  compare reliably. */
  function norm(s) {
    return String(s || '').replace(/\s+/g, ' ').trim();
  }

  function nativeSet(el, v) {
    var s = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
    s.call(el, v);
    var ev = new Event('input', { bubbles: true });
    ev.__dshOurs = true;
    el.dispatchEvent(ev);
  }

  /** Fire a synthetic paste carrying `text` on the composer element. The
   *  page's PASTE_COMMAND handler (composer keymap) takes it through its
   *  sanitized insert; Lexical commits the update synchronously. */
  function pasteText(target, text) {
    try {
      var dt = new DataTransfer();
      dt.setData('text/plain', text);
      var ev = new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: dt });
      target.dispatchEvent(ev);
      return true;
    } catch (e) { return false; }
  }

  function focusRoot(c) {
    try { if (document.activeElement !== c.root) { c.root.focus(); } } catch (e) { /* ignore */ }
  }

  /** DOM-select the whole editor, then sync it into Lexical's editor-state
   *  selection via the shared document-level selectionchange listener. */
  function selectAllDom(root) {
    try {
      var range = document.createRange();
      range.selectNodeContents(root);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
      root.dispatchEvent(new Event('selectionchange', { bubbles: true }));
    } catch (e) { /* ignore */ }
  }

  /** Whether `text` still contains any of the given tokens. */
  function hasToken(text, list) {
    for (var i = 0; i < list.length; i++) {
      if (text.indexOf(list[i]) !== -1) { return true; }
    }
    return false;
  }

  // ---------------------------------------------------------------- diagnostics

  function showDiag(msg) {
    if (diagEl) { return; }
    diagEl = document.createElement('div');
    diagEl.style.cssText =
      'position:fixed;top:8px;left:50%;transform:translateX(-50%);z-index:2147483647;' +
      'max-width:80vw;padding:6px 14px;border-radius:6px;font:12px/1.5 system-ui,sans-serif;' +
      'color:#fff;background:rgba(180,60,60,.95);box-shadow:0 2px 8px rgba(0,0,0,.35);' +
      'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;';
    diagEl.textContent = msg;
    (document.body || document.documentElement).appendChild(diagEl);
    window.setTimeout(function () {
      if (diagEl && diagEl.parentNode) { diagEl.parentNode.removeChild(diagEl); }
      diagEl = null;
    }, 8000);
  }

  function warnMissingComposer() {
    if (diagShown) { return; }
    diagShown = true;
    showDiag('\u2550\u2550 ai-harness-web \uff1a\u672a\u627e\u5230\u8f93\u5165\u6846'
      + ' ([data-composer-card] [data-composer-input])\uff0c\u5f15\u7528\u529f\u80fd\u672a\u751f\u6548\u3002');
  }

  // ---------------------------------------------------------------- top-chip strip (fallback UI)

  function ensureStrip() {
    if (strip) { return; }
    if (!stripStyle) {
      stripStyle = document.createElement('style');
      stripStyle.textContent =
        '#dshFileChips{display:none;flex-wrap:wrap;gap:4px;padding:0 12px;margin:0;' +
        'box-sizing:border-box;font-family:inherit;max-height:30vh;overflow-y:auto;' +
        'pointer-events:none;}' +
        '.dshFileChip{display:inline-flex;align-items:center;gap:2px;min-width:0;max-width:100%;' +
        'padding:2px 4px 2px 8px;border-radius:6px;background:rgba(97,135,216,.22);pointer-events:auto;}' +
        '.dshFileChipName{overflow:hidden;color:var(--dsw-alias-label-primary,#1b1b1f);' +
        'font-size:12px;line-height:18px;white-space:nowrap;text-overflow:ellipsis;}' +
        '.dshFileChipX{display:grid;place-items:center;flex:none;width:16px;height:16px;padding:0;' +
        'border:none;border-radius:50%;background:transparent;' +
        'color:var(--dsw-alias-label-caption,#8a8a93);font-size:13px;line-height:1;cursor:pointer;}' +
        '.dshFileChipX:hover{background:rgba(97,135,216,.35);' +
        'color:var(--dsw-alias-label-primary,#1b1b1f);}';
      (document.head || document.documentElement).appendChild(stripStyle);
    }
    strip = document.createElement('div');
    strip.id = 'dshFileChips';
    document.body.appendChild(strip);
  }

  function placeStrip() {
    rafStrip = 0;
    if (!topChips.length) { return; }
    var card = document.querySelector(CARD_SEL);
    if (!card) {
      if (strip) { strip.style.display = 'none'; }
      scheduleStrip();
      return;
    }
    if (!strip.isConnected || strip.parentNode !== card) {
      try { card.insertBefore(strip, card.firstChild); } catch (e) { scheduleStrip(); return; }
    }
    strip.style.display = 'flex';
    scheduleStrip();
  }
  function scheduleStrip() {
    if (!rafStrip) { rafStrip = window.requestAnimationFrame(placeStrip); }
  }

  function renderTop() {
    if (!strip) { return; }
    strip.innerHTML = '';
    if (!topChips.length) { strip.style.display = 'none'; return; }
    topChips.forEach(function (en) {
      var chip = document.createElement('span');
      chip.className = 'dshFileChip';
      chip.title = en.value;
      var label = document.createElement('span');
      label.className = 'dshFileChipName';
      label.textContent = en.label || nameOf(en.value);
      var x = document.createElement('button');
      x.type = 'button';
      x.className = 'dshFileChipX';
      x.setAttribute('aria-label', '\u79fb\u9664 ' + (en.label || nameOf(en.value)));
      x.textContent = '\u00D7';
      x.addEventListener('mousedown', function (e) { e.preventDefault(); e.stopPropagation(); });
      x.addEventListener('click', function (e) { e.stopPropagation(); removeTop(en.value); });
      chip.appendChild(label);
      chip.appendChild(x);
      strip.appendChild(chip);
    });
    scheduleStrip();
  }

  function addTop(value, label) {
    ensureStrip();
    for (var i = 0; i < topChips.length; i++) {
      if (topChips[i].value === value) { return false; } // dedupe
    }
    if (topChips.length >= MAX) { return false; }
    topChips.push({ value: value, label: typeof label === 'string' ? label.trim() : '' });
    renderTop();
    return true;
  }

  function removeTop(value) {
    var i = -1;
    for (var k = 0; k < topChips.length; k++) {
      if (topChips[k].value === value) { i = k; break; }
    }
    if (i === -1) { return; }
    topChips.splice(i, 1);
    renderTop();
    notifyRemoved(value);
  }

  function notifyRemoved(value) {
    if (window.__dshChipRemoved) {
      try { window.__dshChipRemoved(JSON.stringify({ t: 'rm', v: value })); } catch (e) { /* best effort */ }
    }
  }

  // ---------------------------------------------------------------- v3 chip-native channel

  /** Detect the official file-chip capability on the current composer.
   *  Returns { ed, Chip, root } or null (textarea / no registry / locked). */
  function chipCap() {
    var c = composer();
    if (!c || c.kind !== 'ce') { return null; }
    try {
      var ed = c.root.__lexicalEditor;
      if (!ed) { return null; }
      var reg = ed._nodes && ed._nodes.get(CHIP_TYPE);
      if (!reg || typeof reg.klass !== 'function') { return null; }
      return { ed: ed, Chip: reg.klass, root: c.root };
    } catch (e) { return null; }
  }

  /** Number of live chips we currently track. */
  function chipCount() {
    var n = 0;
    chipTrack.forEach(function (keys) { n += keys.size; });
    return n;
  }

  function trackChip(value, key) {
    var keys = chipTrack.get(value);
    if (!keys) { keys = new Set(); chipTrack.set(value, keys); }
    keys.add(key);
  }

  /** Report chips that left the committed document (deleted by the user,
   *  undone, or consumed by a real send clearing the draft). Runs on every
   *  editor commit; only field reads inside (no active-context getters). */
  function sweepChips(cap) {
    if (!cap || chipTrack.size === 0) { return; }
    var map;
    try { map = cap.ed.getEditorState()._nodeMap; } catch (e) { return; }
    var drops = [];
    chipTrack.forEach(function (keys, value) {
      var alive = false;
      keys.forEach(function (k) {
        var n = map.get(k);
        if (n && n.__type === CHIP_TYPE) { alive = true; }
      });
      if (!alive) { drops.push(value); }
    });
    for (var i = 0; i < drops.length; i++) {
      chipTrack.delete(drops[i]);
      notifyRemoved(drops[i]);
    }
    // 本插件 chip 被发送/删除清掉时若 × 仍悬浮其上：同步收起
    if (chipHoverX && chipHoverSpan && !chipHoverSpan.isConnected) { hideChipX(); }
  }

  /** One update listener per editor instance: sweep after every commit. */
  function ensureChipWatch(cap) {
    if (chipWatchEd === cap.ed) { return; }
    chipWatchEd = cap.ed;
    try {
      cap.ed.registerUpdateListener(function () { sweepChips(cap); });
    } catch (e) { /* listener registration is best-effort */ }
    // A fresh editor means the previous document (and its chips) is gone:
    // sweep immediately so the IDE clears selections of chips that died
    // with the old composer.
    sweepChips(cap);
  }

  /** Park the DOM caret right after the chip we just inserted (used when the
   *  focus is NOT inside the composer, so consecutive drops chain up and
   *  typing after a later focus continues right after the chip). The span is
   *  located by its Lexical key attribute, so chips the user added through
   *  the page's own '@' menu never confuse the anchor. */
  function placeCaretAfterChip(root, ed, key) {
    try {
      var sel = window.getSelection();
      if (sel && sel.anchorNode && root.contains(sel.anchorNode)) { return; } // user caret wins
      var spans = root.querySelectorAll('span[data-composer-chip]');
      var target = null;
      for (var i = spans.length - 1; i >= 0; i--) {
        if (spans[i].getAttribute('data-composer-chip') === 'reference'
          && ed && spans[i]['__lexicalKey_' + ed._key] === key) { target = spans[i]; break; }
      }
      if (!target) { return; }
      var range = document.createRange();
      range.setStartAfter(target);
      range.collapse(true);
      sel.removeAllRanges();
      sel.addRange(range);
      root.dispatchEvent(new Event('selectionchange', { bubbles: true }));
    } catch (e) { /* ignore */ }
  }

  /** Insert one official ReferenceChipNode at the composer caret (document
   *  end when the composer was never focused / has no selection). All work
   *  happens inside editor.update so minting gets a key; no $-helpers. */
  function placeChipRef(value, label) {
    var cap = chipCap();
    if (!cap) { return 'no'; }
    var lab = (typeof label === 'string' && label.trim().length > 0) ? label.trim() : nameOf(value);
    var insert = {
      source: 'reference', ref: value, label: lab, appearance: 'file', clipboardText: value,
    };
    var key = null;
    try {
      cap.ed.update(function () {
        var pending = cap.ed._pendingEditorState || cap.ed.getEditorState();
        var map = pending._nodeMap;
        var sel = pending._selection;
        var chip = new cap.Chip(insert); // minted inside update: key assigned + registered
        var done = false;
        if (sel && sel.anchor) {
          var a = sel.anchor;
          var node = map.get(a.key);
          if (node) {
            try {
              if (a.type === 'text') {
                var len = node.getTextContentSize();
                if (a.offset > 0 && a.offset < len) {
                  var parts = node.splitText(a.offset);
                  (parts[1] || node).insertBefore(chip);
                } else if (a.offset <= 0) {
                  node.insertBefore(chip);
                } else {
                  node.insertAfter(chip);
                }
              } else {
                var child = node.getChildAtIndex(a.offset);
                if (child) { child.insertBefore(chip); } else { node.append(chip); }
              }
              done = true;
            } catch (e2) { done = false; }
          }
        }
        if (!done) {
          // No (usable) selection: append to the last block; mint a paragraph
          // when the document is still empty.
          var root = map.get('root');
          if (!root) { throw new Error('no-root'); }
          var kids = root.getChildren();
          var last = kids.length ? kids[kids.length - 1] : null;
          if (last) {
            last.append(chip);
          } else {
            var reg = cap.ed._nodes && cap.ed._nodes.get('paragraph');
            if (!reg || typeof reg.klass !== 'function') { throw new Error('no-paragraph'); }
            var p = new reg.klass();
            root.append(p);
            p.append(chip);
          }
        }
        key = chip.getKey();
      }, { discrete: true });
    } catch (e) {
      key = null; // the update machinery already restored the previous state
    }
    if (!key) { return 'no'; }
    trackChip(value, key);
    ensureChipWatch(cap);
    placeCaretAfterChip(cap.root, cap.ed, key);
    return 'ok';
  }

  /** Remove every tracked chip carrying `value` from the document (the
   *  commit listener then reports the removal to the IDE). */
  function removeChipValue(value) {
    var cap = chipCap();
    var keys = chipTrack.get(value);
    if (!cap || !keys || keys.size === 0) { return false; }
    try {
      cap.ed.update(function () {
        var map = cap.ed.getEditorState()._nodeMap;
        keys.forEach(function (k) {
          var n = map.get(k);
          if (n) {
            try { n.remove(); } catch (e) { /* per-node best effort */ }
          }
        });
      }, { discrete: true });
      return true;
    } catch (e) { return false; }
  }

  // ------------------------------------------------ hover ×：悬浮 chip 一键移除（v3）
  // 官方 ReferenceChip 纯展示（删除只靠键盘 Backspace）。这里给「本插件注入」的 chip
  // 提供 QoderCN 式悬浮删除：fixed 小圆钮叠在 chip 右端，点击即从 Lexical 文档移除
  // 该 chip（走 removeChipValue → commit sweep 上报 IDE 清选区）。按钮挂 document.body
  // —— 不触碰 Lexical/React 管理的 chip DOM，不会被页面重渲染清掉；rAF 逐帧跟随位置。

  var chipHoverX = null;     // 悬浮 × 元素（body 层 fixed）
  var chipHoverSpan = null;  // 当前悬浮的 chip span
  var chipHoverRaf = 0;      // 位置跟随帧句柄

  function hideChipX() {
    if (chipHoverRaf) { window.cancelAnimationFrame(chipHoverRaf); chipHoverRaf = 0; }
    if (chipHoverX) {
      try {
        if (chipHoverX.parentNode) { chipHoverX.parentNode.removeChild(chipHoverX); }
      } catch (e) { /* ignore */ }
      chipHoverX = null;
    }
    chipHoverSpan = null;
  }

  function chipXTick() {
    chipHoverRaf = 0;
    var x = chipHoverX;
    var span = chipHoverSpan;
    if (!x || !span || !span.isConnected) { hideChipX(); return; }
    var r = span.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) { hideChipX(); return; } // 滚出视口/隐藏
    x.style.left = Math.round(r.right - 18) + 'px';
    x.style.top = Math.round(r.top + (r.height - 16) / 2) + 'px';
    x.style.display = 'block';
    chipHoverRaf = window.requestAnimationFrame(chipXTick);
  }

  function removeChipViaHoverX() {
    var span = chipHoverSpan;
    var cap = chipCap();
    hideChipX();
    if (!cap || !span) { return; }
    var prefix = '__lexicalKey_' + cap.ed._key;
    var key = span[prefix];
    var value = null;
    chipTrack.forEach(function (keys, v) {
      if (!value && key && keys.has(key)) { value = v; }
    });
    if (!value) { return; } // 非本插件注入的 chip：不接管（页面无删除协议）
    if (!removeChipValue(value)) {
      chipTrack.delete(value);
      notifyRemoved(value); // 编辑器已不在：直接上报让 IDE 清选区
    }
  }

  document.addEventListener('mouseover', function (e) {
    var t = e.target;
    if (!t || !t.closest || !t.closest(CARD_SEL)) { return; }
    var chip = t.closest('span[data-composer-chip="reference"]');
    if (!chip) { return; }
    if (chipHoverSpan === chip && chipHoverX) { return; } // 已在显示
    var cap = chipCap();
    if (!cap) { return; }
    var key = chip['__lexicalKey_' + cap.ed._key];
    if (!key) { return; }
    var owned = false;
    chipTrack.forEach(function (keys) { if (keys.has(key)) { owned = true; } });
    if (!owned) { return; } // 页面自身 '@' 菜单 chip：维持页面行为
    hideChipX();
    var x = document.createElement('span');
    x.setAttribute('aria-label', '\u79fb\u9664\u5f15\u7528');
    x.title = '\u79fb\u9664\u5f15\u7528';
    x.textContent = '\u00D7';
    x.style.cssText = 'position:fixed;display:none;z-index:2147483647;width:16px;height:16px;'
      + 'line-height:14px;text-align:center;border-radius:50%;font-size:13px;'
      + 'font-family:sans-serif;cursor:pointer;user-select:none;pointer-events:auto;'
      + 'background:rgba(110,110,110,0.92);color:#fff;'
      + 'box-shadow:0 0 0 1px rgba(255,255,255,0.65),0 1px 3px rgba(0,0,0,0.35);';
    x.addEventListener('mousedown', function (ev) {
      ev.preventDefault(); // 不移动光标/不清选区
      ev.stopPropagation();
    });
    x.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      removeChipViaHoverX();
    });
    (document.body || document.documentElement).appendChild(x);
    chipHoverX = x;
    chipHoverSpan = chip;
    if (!chipHoverRaf) { chipHoverRaf = window.requestAnimationFrame(chipXTick); }
  }, true);

  document.addEventListener('mouseout', function (e) {
    if (!chipHoverX || !chipHoverSpan) { return; }
    var chip = chipHoverSpan;
    var to = e.relatedTarget;
    // 新位置仍落在 chip 或我们自己的 × 上（含各自子元素）→ 保持显示。× 挂在 body，
    // 鼠标从 chip 移到 × 的 mouseout 若不豁免，会陷入 show→hide→show 闪烁循环，
    // 且 × 永远无法稳定接收点击。
    if (to && (to === chip || to === chipHoverX
        || chipHoverX.contains(to) || chip.contains(to))) {
      return;
    }
    hideChipX();
  }, true);

  // ---------------------------------------------------------------- draft writes (v2 fallback)

  /** Rewrite the whole draft (send expansion / token removal).
   *  Lexical needs the select-all + synthetic-paste dance; the textarea
   *  keeps the native setter. Returns true when tokens are really gone. */
  function replaceDraft(c, text, goneTokens) {
    if (c.kind === 'ta') {
      nativeSet(c.root, text);
      return !hasToken(c.root.value || '', goneTokens);
    }
    try {
      focusRoot(c);
      selectAllDom(c.root);
      pasteText(c.root, text);
      // Lexical commits synchronously; verify no stale token survived.
      return !hasToken(c.root.innerText || '', goneTokens);
    } catch (e) { return false; }
  }

  /** Insert a token block at the editor-state caret (document end when the
   *  composer was never focused). Never steals focus, so the user can keep
   *  multi-selecting in the IDE editor. Returns true when visible. */
  function insertToken(c, token, block) {
    if (c.kind === 'ta') {
      var tv = c.root.value || '';
      var pos = document.activeElement === c.root && typeof c.root.selectionStart === 'number'
        ? c.root.selectionStart : tv.length;
      nativeSet(c.root, tv.slice(0, pos) + block + tv.slice(pos));
      return (c.root.value || '').indexOf(token) !== -1;
    }
    try {
      pasteText(c.root, block);
      return (c.root.innerText || '').indexOf(token) !== -1;
    } catch (e) { return false; }
  }

  /** Place one reference; returns 'inline' (token in draft), 'chip' (top
   *  strip fallback) or 'pending' (composer absent - caller queues). */
  function placeInline(value, label) {
    var token = tokenOf(label, value);
    var c = composer();
    if (!c) { return 'pending'; }
    var text = draftOf(c);
    if (text.indexOf(token) !== -1) { return 'inline'; } // dedupe
    var block = (text.length > 0 && text.charAt(text.length - 1) !== '\n') ? '\n' : '';
    block += token;
    if (insertToken(c, token, block)) {
      maps.push({ token: token, value: value });
      return 'inline';
    }
    // The page did not adopt the write (unfamiliar variant): show the always
    // visible top strip instead - its values still splice on send.
    addTop(value, label);
    return 'chip';
  }

  /** Place one reference through the primary channel (official chip) with
   *  the text pipeline as fallback. */
  function placeOne(value, label) {
    if (placeChipRef(value, label) === 'ok') { return 'inline'; }
    return placeInline(value, label);
  }

  function flushPending() {
    var c = composer();
    if (!c) {
      if (pending.length) {
        ensureFlusher();
        if (warnTimer === null) {
          warnTimer = window.setTimeout(function () { warnTimer = null; warnMissingComposer(); }, 2500);
        }
      }
      return;
    }
    if (warnTimer !== null) { window.clearTimeout(warnTimer); warnTimer = null; }
    if (!pending.length) { stopFlusher(); return; }
    var batch = pending.slice();
    pending = [];
    for (var i = 0; i < batch.length; i++) {
      if (placeOne(batch[i].value, batch[i].label) === 'pending') {
        pending = pending.concat(batch.slice(i));
        ensureFlusher();
        return;
      }
    }
    if (pending.length) { ensureFlusher(); } else { stopFlusher(); }
  }

  function ensureFlusher() {
    if (flusher) { return; }
    flusher = window.setInterval(flushPending, 300);
  }
  function stopFlusher() {
    if (flusher) { window.clearInterval(flusher); flusher = null; }
  }

  // ---------------------------------------------------------------- window API

  function activeTotal() {
    return pending.length + maps.length + topChips.length + chipCount();
  }

  function addRef(value, label) {
    if (typeof value !== 'string') { return; }
    value = value.trim();
    if (!value) { return; }
    if (activeTotal() >= MAX) { return; }
    if (placeOne(value, label) === 'pending') {
      pending.push({ value: value, label: typeof label === 'string' ? label.trim() : '' });
      ensureFlusher();
    }
  }

  function removeRef(value) {
    var removed = false;
    var gone = [];
    for (var i = maps.length - 1; i >= 0; i--) {
      if (maps[i].value !== value) { continue; }
      gone.push(maps[i].token);
      maps.splice(i, 1);
      removed = true;
    }
    if (!removed && chipTrack.has(value)) {
      if (!removeChipValue(value)) {
        // The chip's editor is gone (page rebuilt): drop the tracking and
        // report directly so the IDE selection still clears.
        chipTrack.delete(value);
        notifyRemoved(value);
      }
      return; // success path reports through the commit sweep
    }
    if (!removed) {
      removeTop(value);
      return;
    }
    var c = composer();
    if (c && gone.length) {
      var v = draftOf(c);
      var nv = v;
      for (var j = 0; j < gone.length; j++) { nv = nv.replace(gone[j], ''); }
      if (nv !== v) { replaceDraft(c, nv, gone); }
    }
    notifyRemoved(value);
  }

  window.__dshAddRefChip = addRef;
  window.__dshAddFileChip = function (p) { addRef(p, ''); }; // label falls back to file name
  window.__dshRemoveFileChip = removeRef;
  window.__dshListFileChips = function () {
    var vals = [];
    for (var i = 0; i < topChips.length; i++) { vals.push(topChips[i].value); }
    for (var j = 0; j < maps.length; j++) { vals.push(maps[j].value); }
    chipTrack.forEach(function (keys, value) { vals.push(value); });
    return vals;
  };
  window.__dshClearFileChips = function () {
    var gone = [];
    for (var i = 0; i < maps.length; i++) { gone.push(maps[i].token); }
    maps = [];
    var c = composer();
    if (c && gone.length) {
      var v = draftOf(c);
      var nv = v;
      for (var j = 0; j < gone.length; j++) { nv = nv.replace(gone[j], ''); }
      if (nv !== v) { replaceDraft(c, nv, gone); }
    }
    var chipValues = [];
    chipTrack.forEach(function (keys, value) { chipValues.push(value); });
    for (var k = 0; k < chipValues.length; k++) {
      if (!removeChipValue(chipValues[k])) {
        chipTrack.delete(chipValues[k]);
        notifyRemoved(chipValues[k]);
      }
    }
    topChips = [];
    renderTop();
  };

  // ---------------------------------------------------------------- send expansion (v2 fallback)

  /** Expand every surviving inline token to its full value. */
  function expandTokens(text, ms) {
    var out = text, consumed = [];
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i];
      if (out.indexOf(m.token) === -1) { continue; }
      out = out.replace(m.token, m.value);
      consumed.push(m.value);
    }
    return { text: out, consumed: consumed };
  }

  /** Send-gesture expansion. Returns 'ok' (expanded, send may proceed),
   *  'none' (nothing to expand - pass through) or 'fail' (expansion could
   *  not be applied - the caller must NOT let the raw tokens send). */
  function splicePaths() {
    var c = composer();
    if (!c) { return 'none'; }
    var original = draftOf(c);
    var ms = maps.slice();
    var ex = expandTokens(original, ms);
    var consumed = ex.consumed.slice();
    var finalText = ex.text;
    if (topChips.length) {
      var block = [];
      for (var k = 0; k < topChips.length; k++) {
        block.push(topChips[k].value);
        consumed.push(topChips[k].value);
      }
      finalText = block.join('\n') + (ex.text ? '\n' + ex.text : '');
    }
    if (!consumed.length) {
      maps = [];
      return 'none';
    }
    var ok = replaceDraft(c, finalText, ms.map(function (m) { return m.token; }));
    if (!ok) {
      // Expansion failed: keep tokens in place, never send raw tokens.
      showDiag('\u2550\u2550 ai-harness-web \uff1a\u5f15\u7528\u5c55\u5f00\u5931\u8d25\uff0c'
        + '\u672c\u6b21\u4e0d\u53d1\u9001\uff0c\u8bf7\u91cd\u8bd5\u3002');
      return 'fail';
    }
    maps = [];
    var chipTaken = topChips.slice();
    topChips = [];
    renderTop();
    pendingRevert = {
      c: c, original: original, ms: ms, chips: chipTaken,
      consumed: consumed, finalText: finalText,
    };
    window.setTimeout(revertCheck, REVERT_MS);
    return 'ok';
  }

  function revertCheck() {
    var st = pendingRevert;
    pendingRevert = null;
    if (!st) { return; }
    var cur = '';
    try { cur = st.c.isConnected ? draftOf(st.c) : ''; } catch (e) { cur = ''; }
    if (norm(cur) === norm(st.finalText)) {
      // No send consumed the draft (locked phase / failed submit / failed
      // send auto-restored the expanded draft) -> restore the tokens.
      try { replaceDraft(st.c, st.original, []); } catch (e) { /* ignore */ }
      for (var i = 0; i < st.ms.length; i++) {
        var dup = false;
        for (var j = 0; j < maps.length; j++) {
          if (maps[j].token === st.ms[i].token && maps[j].value === st.ms[i].value) { dup = true; break; }
        }
        if (!dup) { maps.push(st.ms[i]); }
      }
      for (var k = 0; k < st.chips.length; k++) {
        addTop(st.chips[k].value, st.chips[k].label);
      }
    } else if (window.__dshSent) {
      // The send really consumed the draft: report which full-path values
      // this message carried so the IDE clears the source editor selections.
      try {
        window.__dshSent(JSON.stringify(st.consumed));
      } catch (e) { /* bridge is best-effort */ }
    }
  }

  // ---------------------------------------------------------------- send gestures (v2 fallback)

  var enterBusy = false;
  function triggerSendButton() {
    enterBusy = false;
    var btn = document.querySelector(SEND_SEL);
    if (btn && !btn.disabled) {
      try { btn.click(); } catch (e) { /* revert fallback restores tokens */ }
    }
  }

  function draftNeedsHandling(v) {
    if (topChips.length) { return true; }
    for (var i = 0; i < maps.length; i++) {
      if ((v || '').indexOf(maps[i].token) !== -1) { return true; }
    }
    return false;
  }

  /** Popup menus (slash/@ completions) own Enter; never hijack them. */
  function completionOpen() {
    var nodes = document.querySelectorAll('[role="listbox"], [role="menu"], [role="option"]');
    for (var i = 0; i < nodes.length; i++) {
      if (isVisible(nodes[i])) { return true; }
    }
    return false;
  }

  // Enter with our tokens in the draft: take the send over - swap the
  // expanded text in first, then click the send button (same path as mouse).
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' || e.isComposing || e.keyCode === 229) { return; }
    if (e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) { return; }
    var t = e.target;
    if (!t || !t.closest) { return; }
    if (!t.closest(CARD_SEL + ' ' + CE_SEL) && !t.closest(CARD_SEL + ' textarea')) { return; }
    if (completionOpen()) { return; } // let the popup consume this Enter
    var root = t.closest(CE_SEL);
    if (!draftNeedsHandling(root ? (root.innerText || '') : t.value)) { return; }
    e.preventDefault();
    e.stopPropagation();
    if (enterBusy) { return; }
    enterBusy = true;
    var r = splicePaths();
    if (r !== 'ok') {
      enterBusy = false; // nothing was sent and nothing is pending a revert
    } else {
      window.setTimeout(triggerSendButton, 0);
    }
  }, true);

  // Mouse send: expand before the button's own click handler (capture phase
  // runs ahead of React's delegated click). On expansion failure stop the
  // event so the raw tokens never leave the box.
  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) { return; }
    var btn = e.target.closest(SEND_SEL);
    if (btn && !btn.disabled && splicePaths() === 'fail') {
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);

  // User edits: dropping a token by hand (or typing over it) drops the
  // reference -> tell the IDE so it can clear the source selection.
  // (v3 chips are watched through the editor commit sweep instead.)
  document.addEventListener('input', function (e) {
    if (!e || e.__dshOurs) { return; }
    if (!maps.length) { return; } // nothing we track can be affected
    if (!e.target || e.target.tagName !== 'TEXTAREA' && !(e.target.closest && e.target.closest(CE_SEL))) {
      return;
    }
    var c = composer();
    if (!c) { return; }
    var text = draftOf(c);
    for (var i = maps.length - 1; i >= 0; i--) {
      if (text.indexOf(maps[i].token) === -1) {
        var gone = maps[i].value;
        maps.splice(i, 1);
        notifyRemoved(gone);
      }
    }
  }, true);
})();
