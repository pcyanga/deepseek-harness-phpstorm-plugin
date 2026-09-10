# DeepSeek Harness Web — JetBrains Marketplace 发布清单

## 本地已备好的产物（f:\phpstorm_plugin_web\）

| 文件 | 用途 |
|---|---|
| `build\ai-harness-web-signed.jar` | **已签名发布包（v0.3.19）**，上传新版本时直接传它（sign + verify 已通过） |
| `tools\ai-harness-signing.key` | 签名私钥（**不要外泄**，后续每个版本签名都要用） |
| `tools\ai-harness-signing.crt` | 自签证书（签发 10 年），配合私钥签名/验证 |
| `tools\marketplace-zip-signer-cli.jar` | JetBrains 官方签名工具 v0.1.43（已签名并 verify 通过） |

> **签名方案说明**：现行官方流程（plugin-signing 文档）无需再向 Marketplace 提交 CSR 申请证书——作者本地用自签 X509 证书签名 jar/zip 即可，上传后 Marketplace 自动校验并用自己的 CA 二次签名。`ai-harness-signing.csr` 已废弃可删。

## 待办 ①（只有你能做）：上传新版本（v0.3.19）

0.3.16 已过审；新版本在插件管理页面上传，不要走 Add new plugin：

1. 浏览器打开 https://plugins.jetbrains.com/ ，用 **1083481902@qq.com** 登录
2. 点头像 → **Profile** → 找到 **DeepSeek Harness Web** → 进入插件管理（或用头像菜单 My plugins）
3. 页面上找 **Upload new version / 上传新版本** 按钮（按 0.3.16 上传成功同样的入口/表单）
4. 上传 `f:\phpstorm_plugin_web\build\ai-harness-web-signed.jar`（单 jar 形态，0.3.16 已验证可行；Changelog 可填下方 0.3.19 条目——0.3.19 已包含 0.3.18 的 Logo）

> 上传后进入人工审核（约 2 个工作日），审核期间页面可能短暂显示 Not compatible——pending review 的已知中间态，无需处理。

## API 自动更新（可选，待办 ②）

用 token 走 API 自动更新：Profile → **My Tokens** 生成 permanent token，然后：

```powershell
curl -i --header "Authorization: Bearer perm:xxx" -F xmlId=com.aicn.web -F file=@ai-harness-web-signed.jar https://plugins.jetbrains.com/api/updates/upload
```

## 上传表单（内容已备好可直接复制）

按下面填写：

### Tagline（一句话简介）

EN: Chat with your local DeepSeek Harness inside PhpStorm - drag any file or folder from the IDE into the chat, or select code and confirm on the hover bar, and both become rich references in your message.

CN: 在 PhpStorm 中直连本地 DeepSeek Harness 对话：拖动文件/文件夹进输入框，或选中代码点 hover 浮条确认，引用即刻以彩色 chip 进入消息。

### Description（HTML，EN）

```html
<h3>DeepSeek Harness Web</h3>
<p>Embeds your local <b>DeepSeek Harness</b> web UI (127.0.0.1:3080) in a PhpStorm tool window. The chat interface is pixel-identical to the web app - sidebar, messages, models, settings, approvals - enriched with IDE integration.</p>
<h3>Features</h3>
<ul>
  <li><b>Drag to chat, select to chat:</b> drag any file or folder from the IDE straight into the composer, or select code in the editor and click the hover "add to chat" bar - both instantly appear as native color chips (file icon + path:line) inside the input, editable as text between chips, and are sent with full disk paths</li>
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
  <li><b>拖动文件、选中代码，一键进入对话：</b>从 IDE 拖动任意文件/文件夹到输入框，或选中代码后点选 hover「添加到对话」浮条——立即生成带文件图标的彩色引用 chip（文件名:行号），发送自动携带磁盘完整路径，AI 所见即真实路径</li>
  <li><b>悬浮 × 移除：</b>鼠标移到任意 chip 上出现 ×，点击即移除引用并同步清空编辑器选区</li>
  <li><b>降级兼容：</b>页面结构变化时自动退回 [[文件:行号]] 文本标记通道，任何版本下选中/拖入/发送均可靠</li>
  <li><b>多窗口隔离：</b>每个工具窗独立会话，操作互不串扰</li>
  <li><b>工作区：</b>自动注册当前项目文件夹并置顶</li>
  <li><b>服务控制：</b>未运行时自动启动 Harness，顶栏显示状态并可一键关闭</li>
</ul>
```

### Changelog（v0.3.19）

EN:
- v0.3.19: settings can now be saved empty - all-empty means "not configured", and the tool window then shows an onboarding guide (npx one-line install, quick-start button, tutorial link) instead of probing/auto-starting with machine-specific defaults; the settings dialog echoes the stored values as-is (cleared fields no longer get re-filled with defaults)
- v0.3.18: add the plugin logo (pluginIcon.svg / _dark) - now shown on the Marketplace page and in the IDE plugin manager
- v0.3.17: the chat window workspace entry is pinned to the current folder (no switch menu); the add-workspace entry is hidden in both collapsed and expanded states; when collapsed, the rail additionally hides the search icon (new-session & settings stay)
- v0.3.16: remove the toolbar "add selection" button (superseded by the in-editor hover bar)
- v0.3.15: fix × flicker on chips when hovering between chip and the remove button
- v0.3.14: hover a chip to reveal × - click removes the reference and clears the editor selection
- v0.3.13: auto-add on selection replaced by a hover confirm bar above the selection ("add to chat")
- v0.3.12: references are native web color chips with file icon (Qoder style) instead of text markers

CN:
- v0.3.19：修复设置清空保存不生效（再打开又回填默认值）——对话框现在如实回显已保存内容；三项全空保存 = 未配置，此时工具窗显示下载安装引导页（npx 一行安装 / 源码教程 / 快速开始按钮），不再用默认值盲目探测或自动启动
- v0.3.18：新增插件 Logo（pluginIcon.svg，含深色主题版）——Marketplace 页面与 IDE 插件管理器现显示插件图标
- v0.3.17：对话窗工作区固定为当前文件夹、不再弹出切换菜单；添加工作区入口收起与展开均隐藏；收起侧边栏时 rail 额外隐藏搜索图标（新建会话与设置保留，专注对话）；描述突出拖动文件与选中代码
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
