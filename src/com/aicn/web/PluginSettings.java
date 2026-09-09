package com.aicn.web;

import com.intellij.ide.util.PropertiesComponent;

/**
 * 应用级设置（PropertiesComponent 持久化，免 plugin.xml 注册新服务）：
 * harness 工作目录 / 启动命令 / 服务地址。默认值与历史硬编码一致，
 * 可在工具窗「⚙ 设置」里修改（SettingsDialog）。
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

  /** 恢复默认：删除全部键（读取端回退默认值）。 */
  public static void reset() {
    pc().unsetValue(KEY_DIR);
    pc().unsetValue(KEY_CMD);
    pc().unsetValue(KEY_BASE);
  }
}
