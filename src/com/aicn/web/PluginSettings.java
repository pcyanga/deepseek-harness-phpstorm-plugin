package com.aicn.web;

import com.intellij.ide.util.PropertiesComponent;

/**
 * 应用级设置（PropertiesComponent 持久化，免 plugin.xml 注册新服务）：
 * harness 工作目录 / 启动命令 / 服务地址。可在工具窗「⚙ 设置」里修改（SettingsDialog）。
 *
 * 值语义：三项都从未保存（键缺失）或全被清空 → {@link #unconfigured()} = true，
 * 视为「未配置」：工具窗显示安装引导，HarnessManager 不探测默认地址、不自动启动
 * （默认值是作者机器示例，对未配置用户无意义）。getter（harnessDir()/startCommand()/
 * baseUrl()）为兼容旧行为仍回退默认值，仅供已配置路径使用；对话框回显请用 rawXxx()。
 */
public final class PluginSettings {

  private static final String KEY_DIR = "aicn.web.harnessDir";
  private static final String KEY_CMD = "aicn.web.startCommand";
  private static final String KEY_BASE = "aicn.web.baseUrl";

  public static final String DEFAULT_DIR = "F:\\deepseek-harness";
  public static final String DEFAULT_CMD = "pnpm dsh web --no-open";
  public static final String DEFAULT_BASE = "http://127.0.0.1:3080";

  private PluginSettings() {
  }

  private static PropertiesComponent pc() {
    return PropertiesComponent.getInstance();
  }

  /** Harness 工作目录：启动命令在其中执行。 */
  public static String harnessDir() {
    String v = pc().getValue(KEY_DIR);
    return v == null || v.trim().isEmpty() ? DEFAULT_DIR : v.trim();
  }

  /** 启动命令：服务未运行且需要自启时执行（如 pnpm dsh web --no-open）。 */
  public static String startCommand() {
    String v = pc().getValue(KEY_CMD);
    return v == null || v.trim().isEmpty() ? DEFAULT_CMD : v.trim();
  }

  /** 服务地址：去尾斜杠；空值回退默认。 */
  public static String baseUrl() {
    String v = pc().getValue(KEY_BASE);
    if (v == null || v.trim().isEmpty()) {
      return DEFAULT_BASE;
    }
    String b = v.trim();
    while (b.endsWith("/")) {
      b = b.substring(0, b.length() - 1);
    }
    return b;
  }

  public static void apply(String dir, String cmd, String base) {
    pc().setValue(KEY_DIR, dir == null ? "" : dir.trim());
    pc().setValue(KEY_CMD, cmd == null ? "" : cmd.trim());
    pc().setValue(KEY_BASE, base == null ? "" : base.trim());
  }

  /** 持久化原文（未保存过=空串，与「显式清空保存」在对话框里一致显示为空）。 */
  public static String rawDir() {
    return raw(KEY_DIR);
  }

  public static String rawCmd() {
    return raw(KEY_CMD);
  }

  public static String rawBase() {
    return raw(KEY_BASE);
  }

  private static String raw(String key) {
    String v = pc().getValue(key);
    return v == null ? "" : v.trim();
  }

  /**
   * 未配置 = 三个配置项均未保存过或全为空。此时工具窗显示安装引导页，
   * HarnessManager 不做任何探测/自启（见 HarnessManager.probe/runStart）。
   */
  public static boolean unconfigured() {
    return !hasNonBlank(KEY_DIR) && !hasNonBlank(KEY_CMD) && !hasNonBlank(KEY_BASE);
  }

  private static boolean hasNonBlank(String key) {
    String v = pc().getValue(key);
    return v != null && !v.trim().isEmpty();
  }
}
