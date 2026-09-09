package com.aicn.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.ide.dnd.FileCopyPasteUtil;
import com.intellij.ide.dnd.FileFlavorProvider;
import com.intellij.ide.dnd.TransferableWrapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.SelectionEvent;
import com.intellij.openapi.editor.event.SelectionListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.psi.PsiElement;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 「DeepSeek Harness Web」单个工具窗内容的服务会话（每个工具窗内容一个实例）。
 *
 * 会话化的原因：IntelliJ 会在多个窗口/项目间复用 ToolWindowFactory 实例并反复调用
 * createToolWindowContent（同一实例可能同时服务多个尚存活的内容）。若 browser、
 * 调度器、选区监听、选区附加记录等可变状态挂在 factory 上，后建窗口会覆盖先建窗口的
 * 状态 —— 表现为「在 A 窗口选中代码/拖入文件却出现在 B 窗口」「其中一个窗口的选中
 * 不再响应」。故每个工具窗内容都建立独立会话，全部可变状态归会话所有，并随内容的
 * Disposable 一起销毁，窗口之间天然隔离。
 *
 * 功能：JCEF 嵌入 harness 网页端并自动保活导航；Harness 开关/刷新/浏览器打开/设置；
 * 文件与代码拖动；编辑器选中代码稳定 400ms 后在选区上方弹出
 * 「添加到对话」hover 确认条（同 Qoder 交互，仅本窗口项目），点击才把引用写入
 * 输入框 —— 引用显示为网页原生彩色 chip（文件名:行号），用户可在多个 chip 之间输入
 * 描述，发送时由页面序列化为完整「路径:行号」；消息发出/删除引用后自动清空对应
 * 编辑器选区（JBCefJSQuery 桥）；当前项目文件夹工作区注册与侧边栏过滤。
 */
final class WebConsoleToolWindowSession {

  /** 构造即完成全部初始化：UI 组装、浏览器/桥创建、监听与 Harness 保活（EDT）。 */
  WebConsoleToolWindowSession(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    this.project = project;
    currentWindow = toolWindow;
    init();
  }

  // 服务地址/启动命令/工作目录均可在工具窗「⚙ 设置」修改（PluginSettings，默认值见该类）。
  private static final int MAX_INJECT_CHARS = 50000;

  /**
   * 注入 JS 的“当前项目文件夹自动注册为工作区”部分。
   * dsh 0.1.2 起点号式 /api/workspace.list 等 unary 404；浏览器数据实际走会话内
   * 斜杠式 /api/workspace/create（typert 网关，页内 fetch 实测 200）。
   * 直接 RPC：POST /api/workspace/create，信封
   * {"type":"client-request","rpcId":"…","method":"workspace/create",
   *  "payload":{"args":{"request":{"path":"<目录>"}}}} → 200
   * {result:{ok:true,value:{workspace:{…},created:true}}}；宿主落盘、前端 follow 流
   * 实时新增分组——全程零对话框。故本段不做任何 UI 驱动：
   * 仅当 __dshWsKeep 对应分组尚未出现时，用页面自身会话调一次创建 RPC（幂等）；
   * 创建完成后 AutoOpenChip/MenuPick 负责把 chip 切到当前文件夹。
   * 依赖 __dshWsKeep（文件夹名）、__dshWsPath（完整路径）与 seen/labelOf（同 IIFE 内定义）。
   */
  private static final String WS_REGISTER_JS = """
      window.__dshRegState={};
      window.__dshEnsureWorkspace=function(){
      var keep=window.__dshWsKeep||'';
      if(!keep||!window.__dshWsPath)return;
      var st=window.__dshRegState[keep];
      if(st&&(st.done||st.pending))return;
      var groups=document.querySelectorAll('[class*="groupSection"]');
      var found=false;
      for(var i=0;i<groups.length;i++){
        var row=groups[i].querySelector('[class*="projectRow"]');
        if(seen(keep,labelOf(row))){found=true;break;}
      }
      if(found){window.__dshRegState[keep]={done:true};window.__dshWsFilter&&window.__dshWsFilter();return;}
      // 分组不可见（侧边栏默认收起时无 groupSection 节点）或尚未出现：无从确认，
      // 直接幂等 create（已注册则 created:false，无害）——注册不依赖侧边栏可见。
      window.__dshRegState[keep]={pending:true,at:Date.now()};
      var fail=function(){
        window.__dshRegState[keep]={failed:true,at:Date.now()};
        setTimeout(function(){delete window.__dshRegState[keep];},12000);
      };
      try{
        fetch('/api/workspace/create',{method:'POST',headers:{'content-type':'application/json'},
          body:JSON.stringify({type:'client-request',rpcId:'plg-'+Date.now()+'-'+keep,
            method:'workspace/create',payload:{args:{request:{path:window.__dshWsPath}}}})})
          .then(function(res){return res.text();})
          .then(function(text){
            var ok=false;
            try{var j=JSON.parse(text);ok=!!(j&&j.result&&j.result.ok===true);}catch(e){}
            if(ok){
              window.__dshRegState[keep]={done:true};
              window.__dshWsFilter&&window.__dshWsFilter();
            }else{fail();}
          })
          .catch(fail);
      }catch(e){fail();}
      };
      """;

  private final Project project;   // 本会话所属窗口（项目）：选区/工作区归属判定
  private final ToolWindow currentWindow;

  private volatile int harnessState; // 0=关闭 1=启动中 2=开启
  private JButton harnessButton;
  private JBCefBrowser browser;
  private volatile boolean disposed;                   // 内容已销毁（导航/回调自检用）

  // JS→Java：网页端真正发出消息后回调（发送把「选中代码」引用 chip 拼进消息并发出时，
  // 自动清空编辑器里对应的选区）。查询实例随浏览器重建/工具窗销毁而释放；页面桥函数
  // __dshSent 随每次 chip 注入重新挂到页面（见 sentBridgeJs）。
  private volatile JBCefJSQuery sentQuery;
  // 最近一次由「编辑器选区」生成的引用 chip 的来源（发送消费该引用后据此清空选区）
  private volatile Editor attachedEditor;
  private volatile String attachedValue;               // chip value（路径:行号）
  private volatile int attachedStart;
  private volatile int attachedEnd;
  // 「添加到对话」hover 确认条（qoder 交互）：选区稳定后弹出，点击按钮才注入引用 chip
  private volatile Editor hoverEditor;                 // 确认条所属编辑器
  private volatile String hoverPath;                   // 对应文件路径（value 前缀）
  private volatile int hoverStart;                     // 对应选区（offset）
  private volatile int hoverEnd;
  private Editor hoverBoundEditor;                     // 已挂生命周期监听的编辑器（同一时刻最多一个）
  private com.intellij.openapi.editor.event.VisibleAreaListener hoverScrollL; // 滚动跟随监听
  private FocusAdapter hoverFocusL;                    // 失焦收起监听
  private JBPopup hoverPopup;                          // 当前确认条（EDT only）
  private volatile int navAttempts;                    // 页面导航代数（旧代的重载回调作废）
  private volatile String navTokenUsed;                // 已用哪个 token 导航过（同 token 只补一次导航）
  private final Runnable harnessWatcher = this::syncFromHarness; // HarnessManager 观察者
  private ScheduledExecutorService pageScheduler = newPageScheduler();

  private static ScheduledExecutorService newPageScheduler() {
    return Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "AiWeb-PageRetry");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * 让内嵌浏览器跳转到当前服务地址（带 token 则拼上），并安排 3/8/18 秒自愈检查：
   * 若 JCEF 尚未就绪导致导航未发生（URL 仍是 about:blank），自动重发一次。
   */
  private void navigate() {
    if (browser == null || disposed) {
      return;
    }
    final int attempt = ++navAttempts;
    final String url = browserUrl();
    String tok = HarnessManager.get().token();
    if (url.contains("token=")) {
      navTokenUsed = tok; // 记录本次导航已带此 token，防止反复补导航刷页面
    }
    System.err.println("[AiWeb] navigate: " + url);
    browser.loadURL(url);
    pageScheduler.schedule(() -> maybeRetryNav(attempt), 3, java.util.concurrent.TimeUnit.SECONDS);
    pageScheduler.schedule(() -> maybeRetryNav(attempt), 8, java.util.concurrent.TimeUnit.SECONDS);
    pageScheduler.schedule(() -> maybeRetryNav(attempt), 18, java.util.concurrent.TimeUnit.SECONDS);
  }

  /** 自愈：页面仍是空白（导航未发生）才重载；只处理最新一代导航。 */
  private void maybeRetryNav(final int attempt) {
    SwingUtilities.invokeLater(() -> {
      if (browser == null || disposed || attempt != navAttempts) {
        return; // 已有更新的导航，旧代回调作废
      }
      try {
        String cur = browser.getCefBrowser().getURL();
        if (cur == null || cur.isEmpty() || cur.equals("about:blank")) {
          System.err.println("[AiWeb] page not loaded yet, retrying navigation");
          browser.loadURL(browserUrl());
        }
      } catch (Exception e) {
        System.err.println("[AiWeb] nav check error: " + e);
      }
    });
  }

  /** 需要时才导航：浏览器空白；或页面还没带 token 而 token 已就绪（同一 token 只补一次）。 */
  private void navigateIfNeeded() {
    if (browser == null || disposed) {
      return;
    }
    try {
      String cur = browser.getCefBrowser().getURL();
      boolean blank = cur == null || cur.isEmpty() || cur.equals("about:blank");
      if (blank) {
        navigate();
        return;
      }
      String tok = HarnessManager.get().token();
      if (tok != null && !tok.isEmpty() && !tok.equals(navTokenUsed) && !cur.contains("token=")) {
        navigate(); // token 晚到：补一次带 token 的导航
      }
    } catch (Exception ignore) {
      navigate(); // 浏览器查询异常：直接导航，由自愈兜底
    }
  }

  /** HarnessManager 状态变化（含启动结果、token 捕获、手动停止）统一入口（EDT 回调）。 */
  private void syncFromHarness() {
    if (disposed || harnessButton == null) {
      return;
    }
    harnessState = HarnessManager.get().state();
    updateHarnessButton();
    if (harnessState == HarnessManager.ON) {
      navigateIfNeeded();
    }
  }

  /** 周期自校正（每 3s）：状态没跟上时纠正并如实渲染按钮；在线且页面空白则补导航。 */
  private void reconcileLabel() {
    if (disposed || harnessButton == null) {
      return;
    }
    HarnessManager m = HarnessManager.get();
    int st = m.state();
    if (st != HarnessManager.ON && m.probe()) {
      // 服务实测在线而状态没跟上：启动中 → ON；已关闭 → ON(外部)（如外部另起的 dsh）
      if (st == HarnessManager.STARTING) {
        m.markOnIfUp();
      } else if (st == HarnessManager.OFF) {
        m.markOnlineExternal();
      }
    }
    st = m.state();
    if (st != harnessState) {
      harnessState = st;
      updateHarnessButton();
    }
    if (st == HarnessManager.ON) {
      navigateIfNeeded(); // 有 navTokenUsed 守卫，不会每 3s 刷页面
    }
  }

  /** 初始化工具窗内容：UI、JCEF 浏览器、JS→Java 桥、监听与 Harness 保活（EDT）。 */
  private void init() {
    // 每会话全新实例：调度器/导航代数等字段初始值即正确，无需复用重建逻辑
    currentWindow.setTitle("DeepSeek Harness Web");
    currentWindow.setStripeTitle("Harness Web");

    JPanel root = new JPanel(new BorderLayout());
    if (!JBCefApp.isSupported()) {
      JBLabel tip = new JBLabel("<html><b>JCEF（内置浏览器）未启用。</b><br/>"
          + "在 Help → Edit Custom VM Options 添加：<br/>"
          + "<code>-Dide.browser.jcef.enabled=true</code><br/>然后重启 PhpStorm。</html>");
      tip.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
      root.add(tip, BorderLayout.CENTER);
    } else {
      browser = new JBCefBrowser();

      // JS→Java 桥：网页把「本次发送消费的引用 chip 值列表」上报上来（chip-overlay.js
      // 的 revertCheck 判定消息确实发出后调用 window.__dshSent）。桥与浏览器绑定，
      // 随本会话销毁释放；页面刷新后由 chip 注入把桥函数重新挂到页面。
      try {
        sentQuery = JBCefJSQuery.create((com.intellij.ui.jcef.JBCefBrowserBase) browser);
        sentQuery.addHandler(message -> {
          if (message == null || message.isEmpty()) {
            return null;
          }
          try {
            // 消息形态：引用值数组（消息发送消费，__dshSent）或 {t:'rm',v:…}
            // （手动删除 chip，__dshChipRemoved）。先解一层可能的整体字符串包裹。
            JsonElement el = parseTopLevel(message);
            if (el.isJsonArray()) {
              final List<String> values = new ArrayList<>();
              for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                  values.add(e.getAsString());
                }
              }
              if (!values.isEmpty()) {
                SwingUtilities.invokeLater(() -> clearSelectionIfSent(values));
              }
            } else if (el.isJsonObject() && el.getAsJsonObject().has("t")
                && "rm".equals(el.getAsJsonObject().get("t").getAsString())
                && el.getAsJsonObject().has("v")) {
              final String v = el.getAsJsonObject().get("v").getAsString();
              SwingUtilities.invokeLater(() -> clearSelectionIfRemoved(v));
            }
          } catch (Exception ignore) {
            // 格式异常：忽略
          }
          return null;
        });
      } catch (Exception ex) {
        // 桥不可用只影响「选区自动清空」，其余功能不受影响
        sentQuery = null;
      }

      // 顶栏：服务开关 + 刷新 + 浏览器打开 + 设置（「添加选中代码」已由 hover 确认条取代，0.3.16 移除）
      JPanel bar = new JPanel(new BorderLayout());
      bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
      harnessButton = new JButton("⏻ Harness 检测中…");
      harnessButton.setEnabled(false);
      harnessButton.addActionListener(e -> onHarnessButton());
      JButton refreshBtn = new JButton("刷新");
      refreshBtn.setToolTipText("重新加载网页");
      refreshBtn.addActionListener(e -> {
        if (browser != null) {
          browser.getCefBrowser().reload();
        }
        scheduleFilterInjection();
      });
      JButton externalBtn = new JButton("浏览器打开");
      externalBtn.setToolTipText("在系统默认浏览器中打开");
      externalBtn.addActionListener(e -> {
        try {
          Desktop.getDesktop().browse(new URI(PluginSettings.baseUrl()));
        } catch (Exception ex) {
          System.err.println("[AiWeb] open external: " + ex);
        }
      });
      JButton settingsBtn = new JButton("⚙ 设置");
      settingsBtn.setToolTipText("设置：Harness 工作目录 / 启动命令 / 服务地址");
      settingsBtn.addActionListener(e -> openSettings());
      JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
      left.add(harnessButton);
      JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
      right.add(settingsBtn);
      right.add(refreshBtn);
      right.add(externalBtn);
      bar.add(left, BorderLayout.WEST);
      bar.add(right, BorderLayout.EAST);
      root.add(bar, BorderLayout.NORTH);
      root.add(browser.getComponent(), BorderLayout.CENTER);

      // 拖放：文件/代码 → 注入输入框（挂 JCEF 组件上，拦截 OS 级拖入）
      browser.getComponent().setDropTarget(new DropTarget(browser.getComponent(),
          new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent e) {
              e.acceptDrag(e.getDropAction());
            }

            @Override
            public void dragOver(DropTargetDragEvent e) {
              e.acceptDrag(e.getDropAction());
            }

            @Override
            public void drop(DropTargetDropEvent e) {
              e.acceptDrop(e.getDropAction());
              handleDrop(e.getTransferable());
              e.dropComplete(true);
            }
          }));

      // 后台：向应用级 HarnessManager 请求“确保在线”（全局去重：外部已启动立刻识别并复用，
      // 未运行才由管理器拉起一次）。切换项目/关工具窗【不会】结束服务——只重新检测复用。
      // 工作区注册由页面加载后注入的 __dshEnsureWorkspace 完成（页内 /api/workspace/create）。
      harnessState = 1;
      updateHarnessButton();
      final HarnessManager mgr = HarnessManager.get();
      mgr.removeWatcher(harnessWatcher); // 防重入重复注册（切项目复用实例时）
      mgr.addWatcher(harnessWatcher);
      final Runnable onReady = () -> {
        if (disposed) {
          return;
        }
        harnessState = mgr.state();
        updateHarnessButton();
        if (harnessState == HarnessManager.ON) {
          navigateIfNeeded(); // 页面加载；JCEF 未就绪/空白会在 3/8/18s 自愈重载
        }
      };
      mgr.ensureRunning(onReady);
      scheduleFilterInjection();
      attachSelectionListener(currentWindow);

      // 工具窗内容销毁只注销观察者与页面自愈调度器；harness 进程归 HarnessManager 管理，
      // 仅在 IDE 退出（shutdown hook）或用户手动停止时结束 —— 否则切文件夹会误杀服务。
      // 会话与内容一一对应：销毁即清本会话全部状态，不影响其它窗口的会话。
      Disposer.register(currentWindow.getDisposable(), () -> {
        disposed = true;
        mgr.removeWatcher(harnessWatcher);
        pageScheduler.shutdownNow();
        SwingUtilities.invokeLater(this::hideHoverBar); // 收起可能残留的确认条
        if (sentQuery != null) {
          try {
            sentQuery.dispose();
          } catch (Exception ignore) {
            // fallthrough
          }
          sentQuery = null;
        }
      });
    }

    Content content = ContentFactory.getInstance().createContent(root, "", false);
    currentWindow.getContentManager().addContent(content);

    // 周期自校正：即使某次回调丢失/状态没跟上，按钮也能在 ~3s 内如实反映
    // （服务在线→已开启并自动补导航；不在线→已关闭），杜绝"一直启动中"卡住。
    // 放在内容挂载之后：即使前面出意外，工具窗也不至于空白。
    if (browser != null) {
      pageScheduler.scheduleWithFixedDelay(
          () -> SwingUtilities.invokeLater(this::reconcileLabel),
          3, 3, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  // ------------------------------------------------------------- 拖放 → 输入框

  /**
   * AWT 拖放处理。依次尝试：
   *  1) IDE 内部虚拟文件（Project 视图 / 编辑器 Tab 等，TransferableWrapper）
   *  2) OS 文件列表（资源管理器拖入，javaFileListFlavor）
   *  3) 深度提取：IDE 内部拖动的 JVM 本地对象 —— 提交 tab 的变更文件、变更列表节点、
   *     VCS Change/FilePath、树节点、任意集合/数组（IntelliJ 内部 DnD 是真实 OLE 拖动，
   *     但这些数据只带 JVM 本地 flavor，不走 javaFileListFlavor，前两步识别不到）
   *  4) 纯文本（编辑器拖出的选中代码；若整段都是有效文件路径则转成路径注入）
   */
  private void handleDrop(Transferable t) {
    try {
      // 1) IDE 内部虚拟文件拖放
      List<VirtualFile> vfs = FileCopyPasteUtil.getVirtualFileListFromAttachedObject(t);
      if (vfs != null && !vfs.isEmpty()) {
        List<String> ps = new ArrayList<>();
        for (VirtualFile v : vfs) {
          ps.add(v.getPath());
        }
        injectFiles(ps);
        return;
      }
    } catch (Exception ex) {
      System.err.println("[AiWeb] drop vfs error: " + ex);
    }
    try {
      // 2) OS 文件列表
      List<File> files = FileCopyPasteUtil.getFileList(t);
      if (files != null && !files.isEmpty()) {
        List<String> ps = new ArrayList<>();
        for (File f : files) {
          ps.add(f.getAbsolutePath());
        }
        injectFiles(ps);
        return;
      }
    } catch (Exception ex) {
      System.err.println("[AiWeb] drop os-files error: " + ex);
    }
    try {
      // 3) 深度提取（提交 tab 等 IDE 内部拖动）
      List<String> deep = extractPathsFromTransferable(t);
      if (!deep.isEmpty()) {
        injectFiles(deep);
        return;
      }
    } catch (Exception ex) {
      System.err.println("[AiWeb] drop deep error: " + ex);
    }
    try {
      // 4) 纯文本（编辑器拖出的选中代码）
      if (t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        Object data = t.getTransferData(DataFlavor.stringFlavor);
        if (data != null) {
          String text = String.valueOf(data);
          List<String> paths = textToExistingPaths(text);
          if (!paths.isEmpty()) {
            injectFiles(paths);
          } else {
            injectToComposer(text);
          }
        }
      }
    } catch (Exception ex) {
      System.err.println("[AiWeb] drop handle error: " + ex);
    }
  }

  // ------------------------------------------------- 拖放深度提取（IDE 内部对象）

  private static final int MAX_DROP_FILES = 50;
  private static final int MAX_DROP_DEPTH = 8;

  /**
   * 遍历拖动数据里的全部 DataFlavor（结构化优先），把能识别的文件路径抽出来。
   * IntelliJ 内部拖动（提交 tab、变更列表等）通过 JVM 本地 flavor 传输富对象，
   * 需要逐层解开：包装 Bean → 节点/集合 → Change/FilePath → 真实路径。
   */
  private List<String> extractPathsFromTransferable(Transferable t) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    collectFromTransferable(t, out, 0);
    return new ArrayList<>(out);
  }

  private void collectFromTransferable(Transferable t, Set<String> out, int depth) {
    DataFlavor[] flavors = t.getTransferDataFlavors();
    if (flavors == null || flavors.length == 0) {
      return;
    }
    for (DataFlavor f : orderFlavors(flavors)) {
      if (out.size() >= MAX_DROP_FILES) {
        return;
      }
      try {
        if (!t.isDataFlavorSupported(f)) {
          continue;
        }
        String mime = f.getMimeType() == null ? "" : f.getMimeType().toLowerCase();
        if (mime.startsWith("text/html")) {
          continue; // HTML 片段不是路径
        }
        boolean serialized = mime.contains("x-java-serialized-object");
        boolean listClass = f.getRepresentationClass() != null
            && List.class.isAssignableFrom(f.getRepresentationClass());
        if (serialized && !f.equals(DataFlavor.javaFileListFlavor) && !listClass) {
          continue; // 任意 Java 序列化对象不可靠，跳过
        }
        collectPaths(t.getTransferData(f), out, depth + 1);
      } catch (Exception ignore) {
        // 单个 flavor 失败不影响其它
      }
    }
  }

  /** 结构化 flavor 优先：JVM 本地对象 → 文件列表 → URI 列表 → 其余 → 纯文本。 */
  private static List<DataFlavor> orderFlavors(DataFlavor[] flavors) {
    List<DataFlavor> all = new ArrayList<>();
    for (DataFlavor f : flavors) {
      if (f != null) {
        all.add(f);
      }
    }
    all.sort((a, b) -> Integer.compare(flavorPriority(a), flavorPriority(b)));
    return all;
  }

  private static int flavorPriority(DataFlavor f) {
    String mime = f.getMimeType() == null ? "" : f.getMimeType().toLowerCase();
    if (mime.contains("x-java-jvm-local-objectref")) {
      return 0; // IDE 内部拖动的富对象（变更文件/节点/集合）
    }
    if (f.equals(DataFlavor.javaFileListFlavor)) {
      return 1;
    }
    if (mime.startsWith("text/uri-list")) {
      return 2;
    }
    if (f.equals(DataFlavor.stringFlavor)) {
      return 4; // 最后才当路径处理
    }
    return 3;
  }

  private void collectPaths(Object o, Set<String> out, int depth) {
    if (o == null || depth > MAX_DROP_DEPTH || out.size() >= MAX_DROP_FILES) {
      return;
    }
    try {
      if (o instanceof VirtualFile) {
        addPath(out, ((VirtualFile) o).getPath(), false);
        return;
      }
      if (o instanceof File) {
        addPath(out, ((File) o).getAbsolutePath(), false);
        return;
      }
      if (o instanceof Path) {
        addPath(out, o.toString(), false);
        return;
      }
      if (o instanceof FilePath) {
        addPath(out, ((FilePath) o).getPath(), false);
        return;
      }
      if (o instanceof TransferableWrapper) {
        TransferableWrapper w = (TransferableWrapper) o;
        List<File> files = w instanceof FileFlavorProvider ? ((FileFlavorProvider) w).asFileList() : null;
        if (files != null && !files.isEmpty()) {
          for (File f : files) {
            collectPaths(f, out, depth + 1);
          }
          return;
        }
        PsiElement[] psi = w.getPsiElements();
        if (psi != null && psi.length > 0) {
          for (PsiElement pe : psi) {
            collectPaths(pe, out, depth + 1);
          }
          return;
        }
        TreePath[] tp = w.getTreePaths();
        if (tp != null && tp.length > 0) {
          for (TreePath p : tp) {
            collectPaths(p, out, depth + 1);
          }
        }
        return;
      }
      if (o instanceof FileFlavorProvider) {
        List<File> files = ((FileFlavorProvider) o).asFileList();
        if (files != null) {
          for (File f : files) {
            collectPaths(f, out, depth + 1);
          }
        }
        return;
      }
      if (o instanceof Transferable) {
        collectFromTransferable((Transferable) o, out, depth + 1);
        return;
      }
      if (o instanceof DefaultMutableTreeNode) {
        collectPaths(((DefaultMutableTreeNode) o).getUserObject(), out, depth + 1);
        return;
      }
      if (o instanceof TreePath) {
        collectPaths(((TreePath) o).getLastPathComponent(), out, depth + 1);
        return;
      }
      if (o instanceof PsiElement) {
        collectPsiElement((PsiElement) o, out, depth);
        return;
      }
      if (o instanceof Collection) {
        int i = 0;
        for (Object e : (Collection<?>) o) {
          collectPaths(e, out, depth + 1);
          if (++i >= 200) {
            break;
          }
        }
        return;
      }
      if (o instanceof Object[]) {
        for (Object e : (Object[]) o) {
          collectPaths(e, out, depth + 1);
        }
        return;
      }
      if (o instanceof String) {
        addPath(out, (String) o, true); // 字符串只认真实存在的路径，避免把代码文本当路径
        return;
      }
      collectUnknown(o, out, depth);
    } catch (Exception ex) {
      System.err.println("[AiWeb] drop collect error: " + ex);
    }
  }

  /** Psi 元素：优先 getVirtualFile()（文件/目录），否则取所在文件。 */
  private void collectPsiElement(PsiElement pe, Set<String> out, int depth) {
    Object vf = invokeNoArg(pe, "getVirtualFile");
    if (vf != null) {
      collectPaths(vf, out, depth + 1);
      return;
    }
    Object file = invokeNoArg(pe, "getContainingFile");
    if (file != null && file != pe) {
      Object v = invokeNoArg(file, "getVirtualFile");
      if (v != null) {
        collectPaths(v, out, depth + 1);
      }
    }
  }

  /** 未知对象（VCS Change、DnD 包装 Bean、ChangeList 等）：按常见 getter 反射探测。 */
  private void collectUnknown(Object o, Set<String> out, int depth) {
    Object att = invokeNoArg(o, "getAttachedObject"); // DnDEvent / DnDDragStartBean 等包装
    if (att != null && att != o) {
      collectPaths(att, out, depth + 1);
      return;
    }
    Object rev = invokeNoArg(o, "getAfterRevision"); // VCS Change → ContentRevision
    if (rev == null) {
      rev = invokeNoArg(o, "getBeforeRevision");
    }
    if (rev != null) {
      Object file = invokeNoArg(rev, "getFile");
      if (file != null) {
        collectPaths(file, out, depth + 1);
        return;
      }
    }
    for (String m : new String[]{"asFileList", "getFiles", "getFileList", "getChanges"}) {
      Object r = invokeNoArg(o, m);
      if (r instanceof Collection) {
        collectPaths(r, out, depth + 1);
        return;
      }
    }
    Object vf = invokeNoArg(o, "getVirtualFile");
    if (vf != null) {
      collectPaths(vf, out, depth + 1);
      return;
    }
    Object fp = invokeNoArg(o, "getFilePath");
    if (fp != null) {
      collectPaths(fp, out, depth + 1);
    }
  }

  private static Object invokeNoArg(Object o, String method) {
    try {
      return o.getClass().getMethod(method).invoke(o);
    } catch (Exception e) {
      return null;
    }
  }

  /** 整段文本恰好都是「存在的文件路径/相对文件名」时转成路径；否则返回空（按普通文本注入）。 */
  private List<String> textToExistingPaths(String text) {
    LinkedHashSet<String> out = new LinkedHashSet<>();
    if (text == null || text.isEmpty()) {
      return new ArrayList<>(out);
    }
    int nonEmpty = 0;
    for (String line : text.split("\\r?\\n")) {
      String s = line.trim();
      if (s.isEmpty()) {
        continue;
      }
      nonEmpty++;
      if (out.size() >= MAX_DROP_FILES || !addPath(out, s, true)) {
        return new ArrayList<>(); // 有一行不是路径 → 按普通文本处理
      }
    }
    return nonEmpty == 0 ? new ArrayList<>() : new ArrayList<>(out);
  }

  /**
   * 收路径：绝对路径直接收（requireExisting 时须真实存在）；相对名只有落在当前
   * 项目根下且真实存在时才收；file: URI 转成本地路径。
   */
  private boolean addPath(Set<String> out, String p, boolean requireExisting) {
    if (p == null) {
      return false;
    }
    p = p.trim();
    if (p.isEmpty()) {
      return false;
    }
    if (p.startsWith("file:")) {
      try {
        p = new File(new URI(p)).getAbsolutePath();
      } catch (Exception e) {
        return false;
      }
    }
    boolean absolute = (p.length() > 2 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':')
        || p.startsWith("\\\\") || p.startsWith("/");
    if (absolute) {
      if (requireExisting && !new File(p).exists()) {
        return false;
      }
    } else {
      String resolved = resolveAgainstProjectRoot(p);
      if (resolved == null) {
        return false;
      }
      p = resolved;
    }
    return out.add(p);
  }

  /** 相对名（如提交列表里的 "src/Foo.java"）：仅当在当前项目根下确实存在时才收。 */
  private String resolveAgainstProjectRoot(String name) {
    if (name.contains("\n") || name.contains("\r") || name.contains("\t")) {
      return null;
    }
    String base = project != null ? project.getBasePath() : null;
    if (base == null || base.isEmpty()) {
      return null;
    }
    String cleaned = name.replace('\\', '/');
    while (cleaned.startsWith("/")) {
      cleaned = cleaned.substring(1);
    }
    if (cleaned.isEmpty()) {
      return null;
    }
    File f = new File(base, cleaned);
    return f.exists() ? f.getAbsolutePath() : null;
  }

  /**
   * 文件注入：优先网页端文件 chip（Trae 风格：只显示文件名 + 打叉删除，悬浮显示
   * 全路径；发送时由注入脚本自动把全路径拼进消息，草稿本身不出现路径）。
   * 网页脚本不可用时（异常旧页面）回退为文本注入。
   */
  private void injectFile(String path) {
    injectFiles(Collections.singletonList(path));
  }

  /** 批量注入文件 chip（一次 executeJavaScript：确保覆盖脚本就绪 + 逐个入列）。 */
  private void injectFiles(List<String> paths) {
    if (paths == null) {
      return;
    }
    List<String[]> pairs = new ArrayList<>();
    for (String p : paths) {
      if (p == null || p.trim().isEmpty()) {
        continue;
      }
      pairs.add(new String[]{p.trim(), null}); // label=null → 网页端用文件名
    }
    injectRefChips(pairs);
  }

  /** 注入一个引用 chip（value=发送时携带的完整引用，label=chip 上显示的短文本）。 */
  private void injectRefChip(String value, String label) {
    injectRefChips(Collections.singletonList(new String[]{value, label}));
  }

  /** 批量注入引用 chip（文件路径或 路径:行号 引用；一次 executeJavaScript）。 */
  private void injectRefChips(List<String[]> pairs) {
    if (browser == null || pairs == null || pairs.isEmpty()) {
      return;
    }
    StringBuilder calls = new StringBuilder();
    for (String[] pair : pairs) {
      if (pair == null || pair.length < 2 || pair[0] == null || pair[0].trim().isEmpty()) {
        continue;
      }
      if (calls.length() > 0) {
        calls.append(';');
      }
      calls.append("window.__dshAddRefChip(").append(jsonStr(pair[0].trim()))
          .append(',').append(jsonStr(pair[1] == null ? "" : pair[1])).append(')');
    }
    if (calls.length() == 0) {
      return;
    }
    // 每次注入顺带把 __dshSent 桥重挂到页面（页面刷新/重载后 window 会丢，首次 chip
    // 注入即恢复；chip-overlay.js 发送判定后调用它上报消费的引用值）。
    String js = "(function(){try{" + sentBridgeJs() + chipOverlayJs() + calls + ";}catch(e){"
        + "if(window.console)console.warn('[AiWeb] chip error:'+e.message);}})();";
    browser.getCefBrowser().executeJavaScript(js, "about:blank", 0);
  }

  /** 页面桥代码：把发送消费的引用值列表（JSON 字符串变量 v）上报给 Java 侧。 */
  private String sentBridgeJs() {
    JBCefJSQuery q = sentQuery;
    if (q == null) {
      return "";
    }
    // q.inject("v") 生成一段把局部变量 v（字符串）发给 Java 的语句（JBCefJSQuery 标准用法）；
    // 两条通道共用同一查询实例：__dshSent=消息发送消费的引用数组、__dshChipRemoved=手动删除的引用。
    return "window.__dshSent=function(v){" + q.inject("v")
        + "};window.__dshChipRemoved=function(v){" + q.inject("v") + "};";
  }

  private volatile String cachedChipOverlayJs;

  /** 网页端文件 chip 覆盖脚本（jar 内资源 /web/chip-overlay.js，加载一次后缓存）。 */
  private String chipOverlayJs() {
    String js = cachedChipOverlayJs;
    if (js == null) {
      try (java.io.InputStream in = WebConsoleToolWindowSession.class
          .getResourceAsStream("/web/chip-overlay.js")) {
        js = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
      } catch (Exception e) {
        System.err.println("[AiWeb] chip overlay load error: " + e);
        js = "";
      }
      cachedChipOverlayJs = js;
    }
    return js;
  }

  // 选中代码 → 输入框：由下方 hover 确认条通道负责（顶栏按钮已移除，0.3.16）

  // ----------------------------------------------------- 选中代码 → hover 确认条（qoder 风格）

  /** 编辑器选区稳定该毫秒数后才弹出「添加到对话」确认条（拖选防抖；拖选后立刻取消的不弹）。 */
  private static final int AUTO_SELECT_DELAY_MS = 400;

  private ScheduledExecutorService selectScheduler =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AiWeb-SelectAutoAttach");
        t.setDaemon(true);
        return t;
      });
  private final Object selectLock = new Object();
  private ScheduledFuture<?> selectPending;
  private volatile Editor selectPendingEditor;
  private volatile int selectPendingStart;
  private volatile int selectPendingEnd;

  /**
   * 注册全局编辑器选区监听：出现非空选区且稳定 400ms 后，在选区上方弹出「添加到
   * 对话」确认条（与 Qoder/QoderCN 的 hover 交互一致）；点击按钮才把「路径:行号」
   * 作为引用 chip 带入网页对话框（与「添加选中代码」按钮相同内容；网页端按 value
   * 去重，重复选择同一段不会叠加）。选区任何变动都会先收起旧确认条。监听随工具窗
   * Disposable 注销。
   */
  private void attachSelectionListener(ToolWindow toolWindow) {
    SelectionListener listener = new SelectionListener() {
      @Override
      public void selectionChanged(@NotNull SelectionEvent e) {
        onSelectionChanged(e);
      }
    };
    // 监听随本会话（内容）的 Disposable 注销：会话销毁时同时停掉防抖调度器
    EditorFactory.getInstance().getEventMulticaster()
        .addSelectionListener(listener, toolWindow.getDisposable());
    Disposer.register(toolWindow.getDisposable(), () -> selectScheduler.shutdownNow());
  }

  /**
   * 窗口归属检查：工具窗只服务所在窗口（项目）的编辑器。EditorFactory 的选区监听是
   * 应用级（每个窗口的工具窗都会收到全部窗口的选区事件），不做隔离的话会出现「在 A
   * 窗口选中代码，chip 却出现在 B 窗口」。无法归属项目的编辑器（预览/临时编辑器）
   * 按本窗口处理。
   */
  private boolean editorOfThisWindow(Editor ed) {
    return ed != null && (ed.getProject() == null || ed.getProject() == project);
  }

  private void onSelectionChanged(@NotNull SelectionEvent e) {
    if (browser == null) {
      return;
    }
    Editor ed = e.getEditor();
    if (ed == null || ed.isDisposed() || ed.getVirtualFile() == null) {
      return;
    }
    if (!editorOfThisWindow(ed)) {
      return; // 其它窗口（项目）的编辑器：不弹本工具窗的确认条
    }
    hideHoverBar(); // 选区变动：旧确认条先收起（同一段的重复事件由幂等去重兜底）
    if (e.getNewRange() == null || e.getNewRange().getLength() == 0) {
      return;
    }
    final String path = ed.getVirtualFile().getPath().replace('/', '\\');
    final int start = e.getNewRange().getStartOffset();
    final int end = e.getNewRange().getEndOffset();
    final int startLine = ed.getDocument().getLineNumber(start) + 1;
    final int endLine = ed.getDocument().getLineNumber(end) + 1;
    synchronized (selectLock) {
      selectPendingEditor = ed;
      selectPendingStart = start;
      selectPendingEnd = end;
      if (selectPending != null) {
        selectPending.cancel(false);
      }
      selectPending = selectScheduler.schedule(
          () -> hoverConfirmDue(ed, path, startLine, endLine),
          AUTO_SELECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }
  }

  /** 防抖到期（调度线程）：选区仍成立（未取消、未被后续选择替换）才转回 EDT 弹确认条。 */
  private void hoverConfirmDue(Editor ed, String path, int startLine, int endLine) {
    if (!selectionStillMatches(ed)) {
      return;
    }
    SwingUtilities.invokeLater(() -> showHoverConfirm(ed, path, startLine, endLine));
  }

  /** 防抖期间的选区内核：编辑器仍存活、有文本且选区与记录一致。 */
  private boolean selectionStillMatches(Editor ed) {
    try {
      return Boolean.TRUE.equals(ApplicationManager.getApplication().runReadAction(
          (com.intellij.openapi.util.Computable<Boolean>) () -> {
            if (ed.isDisposed() || selectPendingEditor != ed) {
              return Boolean.FALSE;
            }
            if (ed.getSelectionModel().getSelectedText() == null) {
              return Boolean.FALSE;
            }
            return ed.getSelectionModel().getSelectionStart() == selectPendingStart
                && ed.getSelectionModel().getSelectionEnd() == selectPendingEnd;
          }));
    } catch (Exception ex) {
      return false;
    }
  }

  /**
   * EDT：在选区起点上方弹出「添加到对话」确认条（浮层样式：工具提示底色 + 主题按钮，
   * 不抢键盘焦点，点击条外/选区变化/滚出可视自动收起）。同一段已展示则不重复弹。
   */
  private void showHoverConfirm(Editor ed, String path, int startLine, int endLine) {
    if (disposed || browser == null || ed == null || ed.isDisposed()
        || ed.getVirtualFile() == null || !selectionStillMatches(ed)) {
      return;
    }
    final int start = selectPendingStart;
    final int end = selectPendingEnd;
    if (hoverPopup != null && hoverEditor == ed && hoverStart == start && hoverEnd == end) {
      return; // 同一段已展示（selectionchange 抖动重复触发防抖）
    }
    hideHoverBar();
    String fileName = new File(path).getName();
    String rangeText = startLine == endLine
        ? "L" + startLine
        : "L" + startLine + "-" + endLine;
    JBLabel lbl = new JBLabel(fileName + " " + rangeText + " → 添加到对话？");
    lbl.setForeground(UIUtil.getToolTipForeground());
    JButton addBtn = new JButton("添加");
    addBtn.addActionListener(ev -> addFromHoverConfirm());
    JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
    bar.setBackground(UIUtil.getToolTipBackground());
    bar.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(JBColor.border()),
        BorderFactory.createEmptyBorder(4, 10, 4, 10)));
    bar.add(lbl);
    bar.add(addBtn);
    JBPopup popup = JBPopupFactory.getInstance().createComponentPopupBuilder(bar, addBtn)
        .setRequestFocus(false)
        .setFocusable(false)
        .setCancelOnClickOutside(true)
        .setCancelOnOtherWindowOpen(true)
        .createPopup();
    hoverEditor = ed;
    hoverPath = path;
    hoverStart = start;
    hoverEnd = end;
    hoverPopup = popup;
    bindHoverLifespan(ed);
    try {
      popup.showInScreenCoordinates(ed.getComponent(),
          hoverBarScreenPoint(ed, bar.getPreferredSize()));
    } catch (Exception ex) {
      hideHoverBar(); // 位置计算失败（编辑器瞬时不可见等）：收起，等下次选中再弹
    }
  }

  /** 点击「添加到对话」：注入引用 chip 并记住选区（发送消费该引用后自动清空选区）。 */
  private void addFromHoverConfirm() {
    final Editor ed = hoverEditor;
    final String path = hoverPath;
    final int start = hoverStart;
    final int end = hoverEnd;
    hideHoverBar(); // 确认条已被消费：先收起（重选会重新触发防抖弹出）
    if (disposed || browser == null || ed == null || path == null || ed.isDisposed()
        || ed.getVirtualFile() == null) {
      return;
    }
    Boolean still;
    try {
      still = ApplicationManager.getApplication().runReadAction(
          (com.intellij.openapi.util.Computable<Boolean>) () -> {
            com.intellij.openapi.editor.SelectionModel sm = ed.getSelectionModel();
            return sm.getSelectionStart() == start && sm.getSelectionEnd() == end
                && sm.getSelectedText() != null;
          });
    } catch (Exception ex) {
      return;
    }
    if (!Boolean.TRUE.equals(still)) {
      return; // 点击瞬间选区已被改掉：放弃本次添加（用户重选即可）
    }
    int startLine = ed.getDocument().getLineNumber(start) + 1;
    int endLine = ed.getDocument().getLineNumber(end) + 1;
    String range = startLine == endLine
        ? ":" + startLine
        : ":" + startLine + "-" + endLine;
    String value = path + range;
    injectRefChip(value, new File(path).getName() + range);
    rememberAttached(ed, value, start, end);
  }

  /** 收起确认条并清空 hover 状态（EDT）。 */
  private void hideHoverBar() {
    hoverEditor = null;
    hoverPath = null;
    hoverStart = -1;
    hoverEnd = -1;
    JBPopup p = hoverPopup;
    hoverPopup = null;
    if (p != null) {
      try {
        p.cancel();
      } catch (Exception ignore) {
        // popup 已自行关闭（click outside 等）：无需处理
      }
    }
  }

  /** 同一时刻只给一个编辑器挂生命周期监听：新编辑器接管时先摘除旧监听，避免堆积。 */
  private void bindHoverLifespan(Editor ed) {
    if (hoverBoundEditor == ed) {
      return;
    }
    if (hoverBoundEditor != null) {
      try {
        hoverBoundEditor.getScrollingModel().removeVisibleAreaListener(hoverScrollL);
      } catch (Exception ignore) {
        // fallthrough
      }
      try {
        hoverBoundEditor.getContentComponent().removeFocusListener(hoverFocusL);
      } catch (Exception ignore) {
        // fallthrough
      }
    }
    hoverBoundEditor = ed;
    hoverScrollL = ev -> repositionHover(ed);
    hoverFocusL = new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) {
        // 延迟一帧判断：点击「添加」按钮造成的瞬时失焦会先被按钮 action 清掉
        // hoverEditor，不会误收起；真实离开编辑器时才执行收起。
        SwingUtilities.invokeLater(() -> {
          if (hoverEditor == ed) {
            hideHoverBar();
          }
        });
      }
    };
    try {
      ed.getScrollingModel().addVisibleAreaListener(hoverScrollL);
    } catch (Exception ignore) {
      // 滚动跟随锦上添花：挂不上则固定原位（滚出可视由其它路径收起）
    }
    ed.getContentComponent().addFocusListener(hoverFocusL);
  }

  /** 视口滚动后：把确认条移动到选区起点当前屏幕位置；起点滚出可视区则收起。 */
  private void repositionHover(Editor ed) {
    JBPopup p = hoverPopup;
    if (p == null || hoverEditor != ed || hoverStart < 0 || ed.isDisposed()) {
      return;
    }
    Rectangle va = ed.getScrollingModel().getVisibleArea();
    Point anchor = ed.offsetToXY(hoverStart);
    if (anchor.y < va.y || anchor.y + ed.getLineHeight() > va.y + va.height) {
      hideHoverBar();
      return;
    }
    try {
      p.setLocation(hoverBarScreenPoint(ed, p.getSize()));
    } catch (Exception ignore) {
      // 位置更新失败（编辑器瞬时不可见）：忽略，下次滚动再试
    }
  }

  /** 确认条屏幕坐标：选区起点上方（太靠视口顶则放下方），水平方向不滚出可视区。 */
  private Point hoverBarScreenPoint(Editor ed, Dimension size) {
    Rectangle va = ed.getScrollingModel().getVisibleArea();
    Point p = ed.offsetToXY(hoverStart);
    int x = Math.max(va.x, p.x);
    if (x + size.width > va.x + va.width) {
      x = Math.max(va.x, va.x + va.width - size.width);
    }
    int y = p.y - size.height - 8;
    if (y < va.y) {
      y = p.y + ed.getLineHeight() + 6;
    }
    Point screen = new Point(x, y);
    SwingUtilities.convertPointToScreen(screen, ed.getContentComponent());
    return screen;
  }

  /** 记录最近一次由「编辑器选区 hover 确认」生成的引用 chip（发送消费该引用后清空对应选区）。 */
  private void rememberAttached(Editor ed, String value, int start, int end) {
    attachedEditor = ed;
    attachedValue = value;
    attachedStart = start;
    attachedEnd = end;
  }

  /** 解析网页上报的消息：若被整体字符串包裹（桥二次序列化）则解一层。 */
  private static JsonElement parseTopLevel(String message) {
    JsonElement el = JsonParser.parseString(message);
    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
      el = JsonParser.parseString(el.getAsString());
    }
    return el;
  }

  /**
   * 消息真正发出/引用 chip 被手动删除后调用（EDT）：匹配「选中代码」自动附加记录，
   * 清空对应编辑器选区 —— 该段代码已随消息发出（或用户已放弃引用），无需停留在选中态。
   */
  private void clearSelectionIfSent(List<String> values) {
    for (String v : values) {
      if (clearAttachedSelection(v)) {
        return;
      }
    }
  }

  /** 单个引用被手动删除（chip × 按钮）：匹配则清空对应编辑器选区。 */
  private void clearSelectionIfRemoved(String value) {
    clearAttachedSelection(value);
  }

  /** 引用值命中最近一次「选中代码」附加记录即消耗记录并清空其编辑器选区。 */
  private boolean clearAttachedSelection(String value) {
    final Editor ed = attachedEditor;
    final String val = attachedValue;
    if (ed == null || val == null || value == null || !val.equals(value)) {
      return false;
    }
    final int s = attachedStart;
    final int e = attachedEnd;
    attachedEditor = null; // 记录只用一次：引用已被发送消费/删除
    attachedValue = null;
    if (ed.isDisposed()) {
      return true;
    }
    Boolean same = ApplicationManager.getApplication().runReadAction(
        (com.intellij.openapi.util.Computable<Boolean>) () -> {
          com.intellij.openapi.editor.SelectionModel sm = ed.getSelectionModel();
          return sm.getSelectionStart() == s
              && sm.getSelectionEnd() == e
              && sm.getSelectedText() != null;
        });
    if (Boolean.TRUE.equals(same)) {
      ed.getSelectionModel().removeSelection();
    }
    return true;
  }

  // ------------------------------------------------------------- JS 注入

  /** 把文本追加到网页输入框（React 受控 textarea：原生 setter + input 事件）。 */
  private void injectToComposer(String text) {
    if (browser == null || text == null) {
      return;
    }
    String js = "(function(){try{"
        + "var ta=document.querySelector('textarea:not([disabled])');"
        + "if(!ta)return 'no-ta';"
        + "var setter=Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype,'value').set;"
        + "var cur=ta.value||'';"
        + "var sep=(cur.length>0&&!cur.endsWith('\\n'))?'\\n':'';"
        + "setter.call(ta,cur+sep+" + jsonStr(text) + ");"
        + "ta.dispatchEvent(new Event('input',{bubbles:true}));"
        + "ta.focus();"
        + "return 'ok';}catch(e){return 'err:'+e.message;}})();";
    browser.getCefBrowser().executeJavaScript(js, "about:blank", 0);
  }

  /**
   * 注入 JS（一次性初始化 + 每次更新 keep），对 0.1 / 0.1.2 等多版 dsh web 兼容：
   *  - 侧边历史：隐藏「非当前根文件夹」的工作区组。类名在产物里是 <hash>_groupSection /
   *    <hash>_projectRow，substring 匹配仍命中；但组行文本现在含图标/操作按钮等多余文本，
   *    故改读组行内的 <hash>_title 标题 span（或不含标题时回退整行），并按大小写不敏感、
   *    以及「路径最后一段」做容忍匹配，避免整行 textContent !== keep 导致全部被隐藏。
   *  - workspace 选择 chip：新版是 button[aria-haspopup="menu"] + class 含 workspace，
   *    标签读 <hash>_workspaceLabel span；未选当前根时自动打开菜单并点选对应菜单项。
   *    chip 位于侧边栏之外，收起侧边栏不影响注册/选中流程。
   *  - 侧边栏默认收起：等 chip 落到当前文件夹（__dshDoneKeep）后点一次
   *    button[aria-label="收起侧边栏"]；之后用户手动展开不再被强制收起（一次性）。
   *  - 注册判定不依赖侧边栏 DOM：分组不可见时幂等 create（已注册则 created:false）。
   *  - keep 为空（未识别到项目目录）时不隐藏任何组（防御：不把侧栏清空）。
   */
  private void applyWorkspaceFilter() {
    if (browser == null || project == null) {
      return;
    }
    String dir = currentEditorDir();
    String keep = dir == null || dir.isEmpty() ? "" : new File(dir).getName();
    String wsPath = dir == null ? "" : dir;
    String js = "(function(){"
        + "if(!document.body)return;"
        + "if(!window.__dshWsInit){"
        + "window.__dshWsInit=true;"
        + "window.__dshSideT0=Date.now();"
        + "var labelOf=function(el){"
        + "if(!el)return '';"
        + "var t=el.querySelector('[class*=\"title\"],[class*=\"workspaceLabel\"]');"
        + "return ((t||el).textContent||'').trim();};"
        + "var seen=function(keep,text){"
        + "var k=String(keep||'').trim().toLowerCase();"
        + "var t=String(text||'').replace(/\\s+/g,' ').trim().toLowerCase();"
        + "if(!k)return true;"
        + "if(t===k)return true;"
        + "var m=Math.max(t.lastIndexOf('/'),t.lastIndexOf('\\\\'));"
        + "var seg=m>=0?t.slice(m+1):t;"
        + "return seg===k;};"
        + "window.__dshWsFilter=function(){"
        + "var keep=window.__dshWsKeep||'';"
        + "var groups=document.querySelectorAll('[class*=\"groupSection\"]');"
        + "if(!groups.length)return;"
        + "var any=false;"
        + "for(var i=0;i<groups.length;i++){"
        + "var g=groups[i];"
        + "var row=g.querySelector('[class*=\"projectRow\"]');"
        + "var show=seen(keep,labelOf(row));"
        + "if(show)any=true;"
        + "g.style.display=show?'':'none';}"
        + "if(!any){var rp=window.__dshRegState&&window.__dshRegState[keep];var hideAll=rp&&rp.pending;for(var j=0;j<groups.length;j++){groups[j].style.display=hideAll?'none':'';}}};"
        + "window.__dshCollapseSidebar=function(){"
        + "var keep=window.__dshWsKeep||'';if(!keep)return;"
        + "if(window.__dshSideCollapsed)return;"
        + "if(window.__dshDoneKeep!==keep&&Date.now()-(window.__dshSideT0||0)<15000)return;"
        + "var btn=null;"
        + "document.querySelectorAll('button').forEach(function(b){"
        + "if(!btn&&(b.getAttribute('aria-label')||'')==='收起侧边栏')btn=b;});"
        + "window.__dshSideCollapsed=true;"
        + "if(!btn)return;"
        + "try{btn.click();}catch(e){}};"
        + "window.__dshAutoOpenChip=function(){"
        + "var keep=window.__dshWsKeep||'';if(!keep)return;"
        + "if(window.__dshDoneKeep===keep)return;"
        + "var now=Date.now();"
        + "if(window.__dshChipT&&now-window.__dshChipT<10000)return;"
        + "var chip=null;"
        + "document.querySelectorAll('[aria-haspopup=\"menu\"]').forEach(function(b){"
        + "if(String(b.className).indexOf('workspace')!==-1)chip=b;});"
        + "if(!chip)return;"
        + "if(seen(keep,labelOf(chip))){window.__dshDoneKeep=keep;return;}"
        + "var rp1=window.__dshRegState&&window.__dshRegState[keep];if(rp1&&rp1.pending)return;"
        + "if(chip.getAttribute('aria-expanded')==='true')return;"
        + "window.__dshChipT=now;try{chip.click();}catch(e){}};"
        + "window.__dshMenuPick=function(){"
        + "var keep=window.__dshWsKeep||'';if(!keep)return;"
        + "if(window.__dshDoneKeep===keep)return;"
        + "var items=document.querySelectorAll('[role=\"menuitem\"]');"
        + "for(var i=0;i<items.length;i++){"
        + "if(seen(keep,items[i].textContent)){window.__dshDoneKeep=keep;try{items[i].click();}catch(e){}return;}}};"
        + WS_REGISTER_JS
        + "var mo=new MutationObserver(function(){"
        + "window.__dshCollapseSidebar&&window.__dshCollapseSidebar();"
        + "window.__dshWsFilter&&window.__dshWsFilter();"
        + "window.__dshAutoOpenChip&&window.__dshAutoOpenChip();"
        + "window.__dshMenuPick&&window.__dshMenuPick();"
        + "window.__dshEnsureWorkspace&&window.__dshEnsureWorkspace();});"
        + "mo.observe(document.body,{childList:true,subtree:true});}"
        + "window.__dshWsKeep=" + jsonStr(keep) + ";"
        + "window.__dshWsPath=" + jsonStr(wsPath) + ";"
        + "window.__dshCollapseSidebar&&window.__dshCollapseSidebar();"
        + "window.__dshWsFilter&&window.__dshWsFilter();"
        + "window.__dshAutoOpenChip&&window.__dshAutoOpenChip();"
        + "window.__dshMenuPick&&window.__dshMenuPick();"
        + "window.__dshEnsureWorkspace&&window.__dshEnsureWorkspace();})();";
    browser.getCefBrowser().executeJavaScript(js, "about:blank", 0);
  }

  /**
   * 当前根目录文件夹（用户要求：工作区 = 当前项目根目录，不遍历子文件夹）。
   * 固定为本会话（窗口）的项目根：不能用全局焦点窗口判断 —— 多窗口下用户切到另一
   * 窗口时焦点指向对方项目，会让本窗口网页里的工作区被切走（两个窗口的侧边栏/对话
   * 上下文互相跟着焦点跳来跳去）。
   */
  private String currentEditorDir() {
    return project != null ? project.getBasePath() : null;
  }

  private final java.util.concurrent.atomic.AtomicBoolean polling =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /** 网页加载是异步的：后台轮询——注册当前项目根工作区（变化时）+ 注入过滤/菜单选中。 */
  private void scheduleFilterInjection() {
    if (!polling.compareAndSet(false, true)) {
      return; // 已有轮询在跑（刷新/多次打开不会叠加）
    }
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        for (int i = 0; i < 600 && browser != null; i++) { // 每 2s 一次，最长 20 分钟
        try {
          Thread.sleep(2000);
        } catch (InterruptedException ie) {
          return;
        }
        // 过滤/工作区选中/自动注册全在注入 JS 内完成（见 WS_REGISTER_JS 与 applyWorkspaceFilter）：
        // 注入与 dsh 的 HTTP RPC 路由完全解耦，页面模块挂载前后都安全、每次轮询都执行。
        applyWorkspaceFilter();
      }
      } finally {
        polling.set(false);
      }
    });
  }

  private static String jsonStr(String s) {
    return new JsonPrimitive(s == null ? "" : s).toString();
  }

  // ------------------------------------------------------------- Harness 服务管理
  // 生命周期（启动/停止/进程/token）统一由应用级 HarnessManager 管理：服务只启动一次，
  // 切换项目或关闭工具窗不结束它（仅重新检测复用）；IDE 退出或用户手动停止才结束。

  /** 内嵌浏览器加载地址：捕获到进程 token 时带上 /?token=... 以完成鉴权。 */
  private String browserUrl() {
    String t = HarnessManager.get().token();
    String base = PluginSettings.baseUrl();
    return (t != null && !t.isEmpty()) ? base + "/?token=" + t : base;
  }

  /** 打开「⚙ 设置」对话框；服务地址变化则作废 token/客户端并结束自启实例、在新地址重探测。 */
  private void openSettings() {
    final String oldBase = PluginSettings.baseUrl();
    SettingsDialog dlg = new SettingsDialog(harnessButton);
    dlg.setVisible(true);
    if (!dlg.applied()) {
      return;
    }
    final HarnessManager mgr = HarnessManager.get();
    if (oldBase.equals(PluginSettings.baseUrl())) {
      return; // 仅目录/命令变化：下次需要自启时由管理器按新值执行
    }
    // 服务地址变了：旧 token 属于旧服务；作废并结束自启实例，然后探测新地址
    mgr.invalidate();
    harnessState = 1;
    updateHarnessButton();
    mgr.ensureRunning(() -> {
      if (disposed) {
        return;
      }
      harnessState = mgr.state();
      updateHarnessButton();
      if (harnessState == HarnessManager.ON) {
        navigateIfNeeded();
        scheduleFilterInjection();
      }
    });
  }

  private void updateHarnessButton() {
    if (harnessButton == null) {
      return;
    }
    switch (harnessState) {
      case 1:
        harnessButton.setText("⏻ 启动中…");
        harnessButton.setEnabled(false);
        break;
      case 2:
        harnessButton.setText(HarnessManager.get().startedByUs() ? "⏻ Harness 已开启" : "⏻ Harness 已开启(外部)");
        harnessButton.setEnabled(true);
        break;
      default:
        harnessButton.setText("⏻ Harness 已关闭");
        harnessButton.setEnabled(true);
    }
  }

  /** 顶栏 Harness 开关：已开启 → 询问关闭（仅本插件启动的）；已关闭 → 启动。 */
  private void onHarnessButton() {
    harnessButton.setEnabled(false);
    final HarnessManager mgr = HarnessManager.get();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      final boolean running = mgr.probe();
      SwingUtilities.invokeLater(() -> {
        if (disposed) {
          return;
        }
        if (running) {
          harnessButton.setEnabled(true);
          if (mgr.startedByUs()) {
            // 本插件启动的实例：询问是否关闭
            int rc = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(harnessButton),
                "Harness 服务当前已开启。\n是否关闭？（仅关闭由本插件启动的服务，外部启动的服务不受影响）",
                "Harness 服务", JOptionPane.YES_NO_OPTION);
            if (rc != JOptionPane.YES_OPTION) {
              harnessState = HarnessManager.ON;
              updateHarnessButton();
              return;
            }
            harnessState = 1;
            updateHarnessButton();
            mgr.stopOwnedAndRefresh(); // 观察者（syncFromHarness）落定状态并按需导航
          } else {
            // 外部实例在线：没有可关闭的自启实例，不弹窗；同步管理器状态为 ON(外部) 并打开页面
            mgr.markOnlineExternal();
            harnessState = HarnessManager.ON;
            updateHarnessButton();
            navigateIfNeeded();
          }
        } else {
          harnessState = 1;
          updateHarnessButton();
          mgr.ensureRunning(() -> {
            if (disposed) {
              return;
            }
            harnessState = mgr.state();
            updateHarnessButton();
            if (harnessState == HarnessManager.ON) {
              navigateIfNeeded();
            }
          });
        }
      });
    });
  }

  // ------------------------------------------------------------- 工作区注册
  // 注册已完全由注入 JS 承担（WS_REGISTER_JS → 页内 POST /api/workspace/create），
  // Java 侧不再持有任何 workspace RPC（dsh 0.1.2 点号式 HTTP unary 已移除）。
}
