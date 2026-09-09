# DeepSeek Harness Web — JetBrains Marketplace 发布清单

## 本地已备好的产物（f:\phpstorm_plugin_web\）

| 文件 | 用途 |
|---|---|
| `build\ai-harness-web-signed.zip` | **已签名发布包**（v0.3.16，含 LICENSE.txt + vendor email），直接上传它 |
| `tools\ai-harness-signing.key` | 签名私钥（**不要外泄**，后续每个版本签名都要用） |
| `tools\ai-harness-signing.crt` | 自签证书（签发 10 年），配合私钥签名/验证 |
| `tools\marketplace-zip-signer-cli.jar` | JetBrains 官方签名工具 v0.1.43（已签名并 verify 通过） |

> **签名方案说明**：现行官方流程（plugin-signing 文档）无需再向 Marketplace 提交 CSR 申请证书——作者本地用自签 X509 证书签名 zip 即可，上传后 Marketplace 自动校验并用自己的 CA 二次签名。`ai-harness-signing.csr` 已废弃可删。

## 待办 ①（只有你能做）：上传插件

官方文档路径（没有 "Developer Account" 菜单，那是旧版说法）：

1. 浏览器打开 https://plugins.jetbrains.com/ ，用 **1083481902@qq.com** 登录
2. 点右上角**头像** → 进入 **Profile**（个人主页）
3. 页面上找 **Add new plugin** 按钮（若没有，看头像菜单里的 **My plugins / 我的插件** 入口，进入后同样找 Add new plugin）
4. 上传 `f:\phpstorm_plugin_web\build\ai-harness-web-signed.zip`，按下方内容填表后提交

> 若页面上传时提示签名/证书相关问题，把提示原文告诉我——服务器会提取 zip 内公钥并与你的账号绑定，正常情况首次上传即可直接通过。

## 后续版本更新（可选，待办 ②）

首次手动上传成功后，可用 token 走 API 自动更新：Profile → **My Tokens** 生成 permanent token，然后：

```powershell
curl -i --header "Authorization: Bearer perm:xxx" -F xmlId=com.aicn.web -F file=@ai-harness-web-signed.zip https://plugins.jetbrains.com/api/updates/upload
```

## 上传表单（内容已备好可直接复制）

按下面填写：

### Tagline（一句话简介）

EN: Embed your local DeepSeek Harness web UI in a PhpStorm tool window with Qoder-style in-editor code/file references.

CN: 将本机 DeepSeek Harness 网页端嵌入 PhpStorm 工具窗，支持 Qoder 式编辑器内代码/文件引用。

### Description（HTML，EN）

```html
<h3>DeepSeek Harness Web</h3>
<p>Embeds your local <b>DeepSeek Harness</b> web UI (127.0.0.1:3080) in a PhpStorm tool window. The chat interface is pixel-identical to the web app - sidebar, messages, models, settings, approvals - enriched with IDE integration.</p>
<h3>Features</h3>
<ul>
  <li><b>In-editor references (Qoder-style):</b> drag files/code into the composer, or select code in the editor to get a hover "add to chat" bar - confirmed selections appear as native color chips (file icon + path:line) inside the chat input, fully editable as text between chips</li>
  <li><b>Hover to remove:</b> move the mouse over any inserted chip to reveal a × button; click it to drop the reference and clear the source-editor selection</li>
  <li><b>Reliable fallback:</b> if the page structure ever changes, references automatically degrade to text markers ([[file:line]]) that expand in place on send</li>
  <li><b>Per-window sessions:</b> each tool window keeps its own chat/session; selections, drags and workspaces never leak across windows</li>
  <li><b>Workspace:</b> current project folder is auto-registered and pinned; list shows only that folder</li>
  <li><b>Harness service control:</b> auto-starts the local DeepSeek Harness server when not running; status shown in the toolbar with a one-click off switch</li>
</ul>
```

### Description（HTML，CN，可选用）

```html
<h3>DeepSeek Harness Web</h3>
<p>在 PhpStorm 工具窗中嵌入本机 <b>DeepSeek Harness</b> 网页端（127.0.0.1:3080），对话界面与交互完全沿用网页端（侧边栏/消息/模型/设置/审批），并补充 IDE 集成。</p>
<h3>功能</h3>
<ul>
  <li><b>编辑器内引用（Qoder 风格）：</b>拖入文件/代码到输入框，或选中代码后由 hover 浮条确认加入 —— 引用显示为原生彩色 chip（文件图标 + 路径:行号），chip 间可直接输入文字</li>
  <li><b>悬浮 × 移除：</b>鼠标移到任意 chip 上出现 ×，点击即移除引用并同步清空编辑器选区</li>
  <li><b>降级兼容：</b>页面结构变化时自动退回 [[文件:行号]] 文本标记通道，任何版本下选中/拖入/发送均可靠</li>
  <li><b>多窗口隔离：</b>每个工具窗独立会话，操作互不串扰</li>
  <li><b>工作区：</b>自动注册当前项目文件夹并置顶</li>
  <li><b>服务控制：</b>未运行时自动启动 Harness，顶栏显示状态并可一键关闭</li>
</ul>
```

### Changelog（v0.3.16）

EN:
- v0.3.16: remove the toolbar "add selection" button (superseded by the in-editor hover bar)
- v0.3.15: fix × flicker on chips when hovering between chip and the remove button
- v0.3.14: hover a chip to reveal × - click removes the reference and clears the editor selection
- v0.3.13: auto-add on selection replaced by a hover confirm bar above the selection ("add to chat")
- v0.3.12: references are native web color chips with file icon (Qoder style) instead of text markers

CN:
- v0.3.16：移除顶栏「添加选中代码」按钮（已被编辑器内 hover 浮条取代）
- v0.3.15：修复 chip 与 × 之间移动时的闪烁与点击无效
- v0.3.14：悬浮 chip 出现 ×，点击一键移除引用并清空选区
- v0.3.13：选中自动添加改为选区上方 hover 确认浮条（点击才添加）
- v0.3.12：引用改为网页原生彩色 chip（Qoder 风格，文件图标 + 文件名:行号）

### License（License Type 下拉）

选 **Apache License 2.0**（zip 内已带 LICENSE.txt 副本，满足条款要求）

### 其它

- Vendor / Contact email 已内置：1083481902@qq.com
- Source Code URL：可不填；若日后开源再补
- 勾选 "I confirm that the plugin is compatible with…" 各项后点 **Publish**
