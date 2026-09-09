/* Headless probe: verify the injected chip-insertion mechanics against
 * lexical 0.49 exactly as chip-overlay.js v3 will drive it (no $-helpers
 * inside the update callbacks — pure instance/field access):
 *
 *   A. `new NodeKlass(...)` INSIDE editor.update() mints a key + registers
 *      the node in the pending nodeMap (LexicalNode constructor ->
 *      $setNodeKey with the active editor).
 *   B. keyless-created-elsewhere nodes are NOT usable -> must mint inside.
 *   C. caret-aware insertion: text-anchor middle -> splitText; text-anchor
 *      edge -> insertBefore/insertAfter; element anchor -> child-relative.
 *   D. document-end fallback when no selection.
 *   E. removal via node.remove() inside update; commit fires the update
 *      listener so the sweep can report vanished chips.
 */
import { createEditor, DecoratorNode } from 'file:///F:/deepseek-harness/node_modules/.pnpm/lexical@0.49.0_typescript@6.0.3/node_modules/lexical/dist/Lexical.dev.mjs';
import { $getRoot, $createParagraphNode, $createTextNode } from 'file:///F:/deepseek-harness/node_modules/.pnpm/lexical@0.49.0_typescript@6.0.3/node_modules/lexical/dist/Lexical.dev.mjs';

class ChipNode extends DecoratorNode {
  static getType() { return 'reference-chip'; }
  static clone(node) { return new ChipNode(node.__ref, node.__key); }
  static importJSON() { throw new Error('never imported'); }
  exportJSON() { return { type: 'reference-chip', version: 1, ref: this.__ref }; }
  constructor(ref, key) { super(key); this.__ref = ref; }
  createDOM() { return { setAttribute() {}, style: {} }; }
  updateDOM() { return false; }
  decorate() { return null; }
  isInline() { return true; }
  getTextContent() { return this.__ref; }
}

function freshEditor() {
  const ed = createEditor({ nodes: [ChipNode], onError: (e) => { throw e; } });
  ed.update(() => {}, { discrete: true }); // bring up an empty root state
  return ed;
}

let failures = 0;
function check(name, cond, extra) {
  console.log((cond ? 'PASS' : 'FAIL') + '  ' + name + (extra === undefined ? '' : '  -> ' + extra));
  if (!cond) failures++;
}

// ---- A/B: minting inside update works; outside does not --------------------
{
  const ed = freshEditor();
  let outside = null;
  try { outside = new ChipNode('X'); } catch (e) { outside = e; }
  check('B minting outside update throws', outside instanceof Error, String(outside).slice(0, 90));
  let key = null;
  ed.update(function () {
    const chip = new ChipNode('V:\\a\\b.php:1-5'); // mint INSIDE -> key
    key = chip.getKey();
    const root = ed.getEditorState()._nodeMap.get('root');
    let para = root.getChildren()[0];
    if (!para) { para = new (ed._nodes.get('paragraph').klass)(); root.append(para); }
    para.append(chip);
  }, { discrete: true });
  check('A1 key minted inside update', typeof key === 'string' && key.length > 0, key);
  let a2 = null;
  ed.read(function () {
    const st = ed.getEditorState();
    const node = st._nodeMap.get(key);
    const para = st._nodeMap.get('root').getChildren()[0];
    a2 = { ref: node && node.__ref, seq: para.getChildren().map((c) => c.getTextContent()) };
  });
  check('A2 committed to nodeMap', a2.ref === 'V:\\a\\b.php:1-5');
  check('A3 text projection order', a2.seq.length === 1 && a2.seq[0] === 'V:\\a\\b.php:1-5', JSON.stringify(a2.seq));
}

// ---- C1: text anchor in the middle -> splitText + insertBefore -------------
{
  const ed = freshEditor();
  ed.update(() => {
    const para = $createParagraphNode();
    para.append($createTextNode('hello world'));
    $getRoot().append(para);
  }, { discrete: true });
  ed.update(() => {
    const para = $getRoot().getChildren()[0];
    para.getFirstChild().select(6, 6); // caret after "hello "
  }, { discrete: true });
  let insKey = null;
  ed.update(function () {
    const pending = ed._pendingEditorState;
    const map = pending._nodeMap;
    const a = pending._selection && pending._selection.anchor;
    check('C1a anchor is text point', a && a.type === 'text', JSON.stringify(a));
    const chip = new ChipNode('C:\\x.php:9');
    const node = map.get(a.key);
    if (a.type === 'text') {
      const len = node.getTextContentSize();
      if (a.offset > 0 && a.offset < len) {
        const parts = node.splitText(a.offset);
        (parts[1] || node).insertBefore(chip);
      } else if (a.offset <= 0) {
        node.insertBefore(chip);
      } else {
        node.insertAfter(chip);
      }
    }
    insKey = chip.getKey();
  }, { discrete: true });
  check('C1b chip key', typeof insKey === 'string', insKey);
  let c1c = null;
  ed.read(function () {
    const para2 = ed.getEditorState()._nodeMap.get('root').getChildren()[0];
    c1c = para2.getChildren().map((c) => c.getTextContent());
  });
  check('C1c order hello/chip/world', c1c.length === 3 && c1c[0] === 'hello ' && c1c[2] === 'world',
    JSON.stringify(c1c));
}

// ---- C2: element anchor offset 0 (paragraph start) --------------------------
{
  const ed = freshEditor();
  ed.update(() => {
    const para = $createParagraphNode();
    para.append($createTextNode('ab'));
    $getRoot().append(para);
    para.select(0, 0);
  }, { discrete: true });
  ed.update(function () {
    const pending = ed._pendingEditorState;
    const a = pending._selection.anchor;
    const node = pending._nodeMap.get(a.key);
    const chip = new ChipNode('D:\\d.php:1');
    const child = node.getChildAtIndex(a.offset);
    if (child) child.insertBefore(chip); else node.append(chip);
  }, { discrete: true });
  let c2 = null;
  ed.read(function () {
    const para3 = ed.getEditorState()._nodeMap.get('root').getChildren()[0];
    c2 = para3.getChildren().map((c) => c.getTextContent());
  });
  check('C2 chip before first child', c2[0] === 'D:\\d.php:1' && c2.length === 2, JSON.stringify(c2));
}

// ---- D: no selection -> append at the last block's end ----------------------
{
  const ed = freshEditor();
  ed.update(() => {
    const para = $createParagraphNode();
    para.append($createTextNode('tail'));
    $getRoot().append(para);
  }, { discrete: true });
  ed.update(function () {
    const pending = ed._pendingEditorState;
    check('D1 no selection in headless second update', pending._selection === null);
    const chip = new ChipNode('E:\\e.php:2');
    const root = pending._nodeMap.get('root');
    root.getChildren()[root.getChildren().length - 1].append(chip);
  }, { discrete: true });
  let d2 = null;
  ed.read(function () {
    const para4 = ed.getEditorState()._nodeMap.get('root').getChildren()[0];
    d2 = para4.getChildren().map((c) => c.getTextContent());
  });
  check('D2 appended after tail', d2[1] === 'E:\\e.php:2', JSON.stringify(d2));
}

// ---- E: removal + update-listener sweep visibility --------------------------
{
  const ed = freshEditor();
  let key = null;
  ed.update(function () {
    const chip = new ChipNode('F:\\f.php:3');
    key = chip.getKey();
    const root = ed.getEditorState()._nodeMap.get('root');
    let para = root.getChildren()[0];
    if (!para) { para = new (ed._nodes.get('paragraph').klass)(); root.append(para); }
    para.append(chip);
  }, { discrete: true });
  let seenInListener = 'unset';
  const off = ed.registerUpdateListener(() => {
    const st = ed.getEditorState();
    seenInListener = st._nodeMap.has(key) ? 'alive' : 'gone';
  });
  ed.update(function () {
    const n = ed.getEditorState()._nodeMap.get(key);
    n.remove();
  }, { discrete: true });
  check('E1 listener sees chip gone after removal', seenInListener === 'gone', seenInListener);
  off();
}

console.log(failures === 0 ? '\nALL PASS' : '\nFAILURES: ' + failures);
process.exit(failures === 0 ? 0 : 1);
