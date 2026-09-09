package com.aicn.web;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * 「DeepSeek Harness Web」工具窗工厂。
 *
 * IntelliJ 会在窗口/项目之间复用 factory 实例（可能同时服务多个尚存活的内容），因此
 * 本工厂不持有任何状态 —— 每次 createToolWindowContent 为对应工具窗内容新建一个
 * WebConsoleToolWindowSession（浏览器、调度器、监听、选区记录等全部 per-window），
 * 窗口之间互不串扰（在 A 窗口选中代码/拖入文件不会跑到 B 窗口）。
 */
public class WebConsoleToolWindowFactory implements ToolWindowFactory {

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    // 会话构造即完成内容挂载/浏览器初始化/监听注册/服务保活，随内容 Disposable 销毁
    new WebConsoleToolWindowSession(project, toolWindow);
  }
}
