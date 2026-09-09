package com.aicn.web;

import com.intellij.openapi.application.ApplicationManager;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用级共享的 Harness 生命周期管理：dsh web 是本机一个服务，供所有项目窗口共用。
 *  - 只启动一次：服务不在线且某窗口请求时启动，启动任务全局去重（不会重复拉起）；
 *  - 切项目/关工具窗【不会】结束它——新窗口只是重新“检测/复用”（用户期望：切换文件夹不该重启 harness）；
 *  - 结束时机：用户点顶栏停止、设置改了服务地址、或 JVM 退出（shutdown hook 兜底）。
 * 外部已启动的服务（probe 在线且不是本管理器拉起的）视为“外部”，永不误杀。
 */
public final class HarnessManager {

  public static final int OFF = 0;
  public static final int STARTING = 1;
  public static final int ON = 2;

  private static final HarnessManager INSTANCE = new HarnessManager();

  public static HarnessManager get() {
    return INSTANCE;
  }

  /** 状态变化/启动结果的观察者（工具窗注册，dispose 时注销）。 */
  private final List<Runnable> watchers = new CopyOnWriteArrayList<>();
  /** 等待本次启动结果的一次性回调（ensureRunning 的调用方）。 */
  private final List<Runnable> pending = new ArrayList<>();
  private final Object lock = new Object();
  private final List<Process> owned = new ArrayList<>();
  private final AtomicBoolean lifecycleBusy = new AtomicBoolean(false);

  private volatile int state = OFF;
  private volatile String token = "";
  private volatile boolean startedByUs = false;
  private volatile String cachedBase = null;
  private volatile DshClient client = null;
  private volatile long startEpochMs = 0;          // 本次 STARTING 的起点（看门狗超时用）
  private volatile int downStreak = 0;            // 连续探测失败次数（防抖动）
  private final java.util.concurrent.ScheduledExecutorService mgrSched =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AiWeb-Manager");
        t.setDaemon(true);
        return t;
      });

  private HarnessManager() {
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        stopOwnedProcesses();
      } catch (Throwable ignore) {
        // JVM 退出阶段尽力而为
      }
    }, "AiWeb-ShutdownHook"));
    // 看门狗：状态永不自锁 —— 启动超时强制落定 OFF；卡在 STARTING 但服务实际在线则纠正为 ON；
    // 已开启但连续探测失败（~15s）→ 如实转 OFF 并通知各窗口。
    mgrSched.scheduleWithFixedDelay(this::watchdog, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
  }

  private void watchdog() {
    int st = state;
    if (st == STARTING) {
      if (System.currentTimeMillis() - startEpochMs > 60000) {
        System.err.println("[AiWeb] watchdog: start timed out (>60s), forcing OFF");
        stopOwnedProcesses();
        clearOwned();
        List<Runnable> toRun;
        synchronized (lock) {
          state = OFF;
          startedByUs = false;
          toRun = new ArrayList<>(pending);
          pending.clear();
        }
        for (Runnable r : toRun) {
          SwingUtilities.invokeLater(r);
        }
        notifyWatchers();
        return;
      }
      if (probe()) {
        synchronized (lock) {
          if (state == STARTING) {
            state = ON; // 服务其实已在线：状态纠正；进程若是本管理器拉起的仍算 startedByUs
            startedByUs = hasOwned();
          }
        }
        downStreak = 0;
        notifyWatchers();
      }
      return;
    }
    if (st == ON) {
      if (probe()) {
        downStreak = 0;
      } else if (++downStreak >= 3) { // 连续 ~15s 无响应才认定下线（防重载/繁忙误判）
        System.err.println("[AiWeb] watchdog: harness went down, state -> OFF");
        synchronized (lock) {
          state = OFF;
          startedByUs = false;
        }
        notifyWatchers();
      }
    }
  }

  // ---------------- 观察者 ----------------

  public void addWatcher(Runnable r) {
    watchers.add(r);
  }

  public void removeWatcher(Runnable r) {
    watchers.remove(r);
  }

  private void notifyWatchers() {
    SwingUtilities.invokeLater(() -> {
      for (Runnable r : watchers) {
        try {
          r.run();
        } catch (Throwable ignore) {
          // 单个观察者异常不影响其它
        }
      }
    });
  }

  // ---------------- 状态查询 ----------------

  public int state() {
    return state;
  }

  public String token() {
    return token;
  }

  /** 当前在线服务是否由本管理器（本插件）启动。 */
  public boolean startedByUs() {
    return startedByUs;
  }

  /** 存活探测（绑定当前设置的服务地址；地址变化时重建客户端）。 */
  public boolean probe() {
    String base = PluginSettings.baseUrl();
    DshClient c = client;
    if (c == null || !base.equals(cachedBase)) {
      synchronized (lock) {
        if (client == null || !base.equals(cachedBase)) {
          cachedBase = base;
          client = new DshClient(base);
          c = client;
        } else {
          c = client;
        }
      }
    }
    return c.probe();
  }

  // ---------------- 确保在线 ----------------

  /**
   * 确保 harness 在线（全局去重）：
   *  - 已在线（含外部服务）：立刻回调 onResult；
   *  - 正在启动：排队，启动结束时回调；
   *  - 不在线：拉起（dir/命令取当前设置），探测成功后回调；失败也回调（状态 OFF）。
   * 回调在 EDT 执行；窗口据此渲染按钮并加载页面。
   */
  public void ensureRunning(final Runnable onResult) {
    boolean launchTask = false;
    synchronized (lock) {
      if (state == ON || probe()) {
        state = ON;
        startedByUs = hasOwned();
      } else if (state == STARTING) {
        pending.add(onResult);
        return;
      } else {
        state = STARTING;
        startEpochMs = System.currentTimeMillis();
        pending.add(onResult);
        launchTask = true;
      }
    }
    if (!launchTask) {
      SwingUtilities.invokeLater(onResult);
      notifyWatchers();
      return;
    }
    if (lifecycleBusy.compareAndSet(false, true)) {
      try {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
          try {
            runStart();
          } finally {
            lifecycleBusy.set(false);
          }
        });
      } catch (Throwable t) {
        // 应用池不可用（极端情况）：不能把状态留在 STARTING —— 看门狗也会兜底，
        // 这里立即落定 OFF 并通知
        System.err.println("[AiWeb] cannot schedule start task: " + t);
        lifecycleBusy.set(false);
        List<Runnable> toRun;
        synchronized (lock) {
          state = OFF;
          startedByUs = false;
          toRun = new ArrayList<>(pending);
          pending.clear();
        }
        for (Runnable r : toRun) {
          SwingUtilities.invokeLater(r);
        }
        notifyWatchers();
      }
    } else {
      // 理论上不会到这里（state==STARTING 分支已处理并发请求）
      synchronized (lock) {
        if (state == STARTING) {
          pending.add(onResult);
        }
      }
    }
  }

  /** 窗口周期自校正用：卡在 STARTING 但服务实际在线 → 纠正为 ON（只改状态，不启动）。 */
  public void markOnIfUp() {
    synchronized (lock) {
      if (state == STARTING && probe()) {
        state = ON;
        startedByUs = hasOwned();
        downStreak = 0;
      }
    }
  }

  /**
   * 实测到“外部实例在线”而状态还停在 OFF → 纠正为 ON(外部)。
   * 典型场景：本插件停掉服务后用户从终端另起 dsh，再点按钮/窗口周期探测发现它在——
   * 若不纠正，管理器仍是 OFF，窗口的周期自校正会把按钮从“已开启(外部)”拉回“已关闭”。
   * 本管理器有存活的自主进程时不动（那属于已开启(自启)，走按钮关闭流程）。
   */
  public void markOnlineExternal() {
    boolean changed = false;
    synchronized (lock) {
      if (!hasOwned() && state != ON) {
        state = ON;
        startedByUs = false;
        downStreak = 0;
        changed = true;
      }
    }
    if (changed) {
      notifyWatchers();
    }
  }

  private void runStart() {
    boolean ok = false;
    try {
      if (probe()) {
        ok = true;
      } else {
        String dir = PluginSettings.harnessDir();
        String cmd = PluginSettings.startCommand();
        System.err.println("[AiWeb] starting harness (dir=" + dir + "): " + cmd);
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", cmd.trim());
        pb.directory(new java.io.File(dir));
        Process p = pb.start();
        synchronized (lock) {
          owned.add(p);
        }
        drainOutput(p);
        for (int i = 0; i < 60; i++) { // 最多 30s
          Thread.sleep(500);
          if (probe()) {
            ok = true;
            break;
          }
        }
      }
    } catch (Exception e) {
      System.err.println("[AiWeb] start harness error: " + e);
    }
    List<Runnable> toRun;
    synchronized (lock) {
      if (state == STARTING) {
        state = ok ? ON : OFF;
      }
      startedByUs = ok && hasOwned();
      toRun = new ArrayList<>(pending);
      pending.clear();
    }
    System.err.println("[AiWeb] harness " + (ok ? "started" : "start failed"));
    for (Runnable r : toRun) {
      SwingUtilities.invokeLater(r);
    }
    notifyWatchers();
  }

  // ---------------- 停止 ----------------

  /**
   * 结束本管理器/插件启动的 harness 进程树（外部启动的不受影响）。
   * 只下 taskkill、不阻塞等待、【不清空 owned 列表】（等待确认下线后再 clearOwned）；
   * 返回仍然存活的 owned 进程数。
   */
  public int stopOwnedProcesses() {
    List<Process> toKill;
    synchronized (lock) {
      toKill = new ArrayList<>(owned);
    }
    int stillAlive = 0;
    for (Process p : toKill) {
      if (!p.isAlive()) {
        continue;
      }
      try {
        Process tk = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(p.pid())).start();
        System.err.println("[AiWeb] harness process tree killed, pid=" + p.pid());
        if (p.isAlive()) {
          stillAlive++;
        }
      } catch (Exception e) {
        System.err.println("[AiWeb] harness stop error: " + e);
      }
    }
    return stillAlive;
  }

  private void clearOwned() {
    synchronized (lock) {
      owned.clear();
    }
  }

  /**
   * 顶栏手动停止：杀掉本插件启动的实例后【轮询等待端口真正释放】。taskkill 异步且可能有
   * 残存子进程（pnpm 派生的 node 偶发脱离树），等待期间只要端口仍应答就【反复补杀】仍然
   * 存活的 owned 进程，杜绝"自启实例没死透却按外部 ON"的误判。
   * 窗口上限 ~12s：owned 全部退出且端口仍应答，才认定为外部实例在服务 → ON(外部)。
   */
  public void stopOwnedAndRefresh() {
    stopOwnedProcesses();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      final long deadline = System.currentTimeMillis() + 12000;
      boolean up = true;
      long t0 = System.currentTimeMillis();
      while (System.currentTimeMillis() < deadline) {
        up = probe();
        if (!up) {
          break;
        }
        // 端口还应答：进程垂死，或 pnpm/node 残存子进程没被第一刀杀干净 → 反复补杀
        if (hasOwned()) {
          stopOwnedProcesses();
        }
        try {
          Thread.sleep(300);
        } catch (InterruptedException ie) {
          break;
        }
      }
      final boolean finalUp = up;
      final long waited = System.currentTimeMillis() - t0;
      final boolean ownedAllDead = !hasOwned();
      clearOwned();
      System.err.println("[AiWeb] stop: waited " + waited + "ms, service "
          + (finalUp ? "still up (external instance?, owned-all-dead=" + ownedAllDead + ")"
                     : "down, state -> 已关闭"));
      SwingUtilities.invokeLater(() -> {
        synchronized (lock) {
          state = (finalUp && ownedAllDead) ? ON : OFF;
          startedByUs = false;
        }
        notifyWatchers();
      });
    });
  }

  /** 设置改了服务地址等：作废 token/客户端并结束自启实例（状态回调由调用方发起新 ensureRunning）。 */
  public void invalidate() {
    token = "";
    cachedBase = null;
    synchronized (lock) {
      client = null;
    }
    stopOwnedProcesses();
    clearOwned();
  }

  // ---------------- 内部 ----------------

  private boolean hasOwned() {
    synchronized (lock) {
      for (Process p : owned) {
        if (p.isAlive()) {
          return true;
        }
      }
    }
    return false;
  }

  /** 后台线程排空子进程输出（防管道阻塞）并解析 "dsh web: <url>" 里的进程 token。 */
  private void drainOutput(Process p) {
    Thread out = new Thread(() -> streamDrain(p.getInputStream()), "AiWeb-HarnessOut");
    Thread err = new Thread(() -> streamDrain(p.getErrorStream()), "AiWeb-HarnessErr");
    out.setDaemon(true);
    err.setDaemon(true);
    out.start();
    err.start();
  }

  private void streamDrain(java.io.InputStream in) {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        System.err.println("[AiWeb harness] " + line);
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("dsh web:\\s+(\\S+)").matcher(line);
        if (m.find()) {
          try {
            java.net.URI u = new java.net.URI(m.group(1));
            String q = u.getRawQuery();
            if (q != null) {
              for (String kv : q.split("&")) {
                if (kv.startsWith("token=")) {
                  String tok = kv.substring("token=".length());
                  if (tok.length() > 0 && !tok.equals(token)) {
                    token = tok;
                    System.err.println("[AiWeb] captured harness launch token");
                    notifyWatchers(); // 窗口可借此补一次带 token 的导航
                  }
                  break;
                }
              }
            }
          } catch (Exception ignore) {
            // 非 URL 行忽略
          }
        }
      }
    } catch (Exception ignore) {
      // 流随进程结束关闭
    }
  }
}
