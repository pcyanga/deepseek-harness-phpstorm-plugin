package com.aicn.web;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Harness 设置对话框：工作目录 / 启动命令 / 服务地址。
 * 「确定」把值写入 {@link PluginSettings}（PropertiesComponent 持久化）。
 * 调用方通过 {@link #applied()} 判断是否需要按新值（尤其服务地址变化时）重载页面。
 */
public class SettingsDialog extends JDialog {

  private final JTextField dirField = new JBTextField();
  private final JTextField cmdField = new JBTextField();
  private final JTextField baseField = new JBTextField();
  private boolean applied = false;

  public SettingsDialog(Component parent) {
    super(parent == null ? null : SwingUtilities.getWindowAncestor(parent),
        "Harness 设置", ModalityType.APPLICATION_MODAL);
    dirField.setText(PluginSettings.harnessDir());
    cmdField.setText(PluginSettings.startCommand());
    baseField.setText(PluginSettings.baseUrl());
    Dimension size = new Dimension(300, 26);
    dirField.setPreferredSize(size);
    cmdField.setPreferredSize(size);
    baseField.setPreferredSize(size);

    JButton browseBtn = new JButton("浏览…");
    browseBtn.addActionListener(e -> {
      VirtualFile vf = FileChooser.chooseFile(
          FileChooserDescriptorFactory.createSingleFolderDescriptor(), null, null);
      if (vf != null) {
        dirField.setText(vf.getPath());
      }
    });
    JPanel dirRow = new JPanel(new BorderLayout(6, 0));
    dirRow.add(dirField, BorderLayout.CENTER);
    dirRow.add(browseBtn, BorderLayout.EAST);

    JPanel form = new JPanel(new GridBagLayout());
    GridBagConstraints base = new GridBagConstraints();
    base.weightx = 1.0;
    base.fill = GridBagConstraints.HORIZONTAL;
    base.insets = new Insets(8, 12, 0, 12);
    addRow(form, base, 0, "Harness 工作目录",
        "代码所在目录，启动命令在其中执行（如 F:\\deepseek-harness）", dirRow);
    addRow(form, base, 2, "启动命令",
        "服务未运行且需自启时执行（如 pnpm dsh web --no-open）", cmdField);
    addRow(form, base, 4, "服务地址",
        "内嵌页面加载与存活探测的地址（如 http://127.0.0.1:3080）", baseField);

    JButton resetBtn = new JButton("恢复默认");
    resetBtn.addActionListener(e -> {
      PluginSettings.reset();
      dirField.setText(PluginSettings.harnessDir());
      cmdField.setText(PluginSettings.startCommand());
      baseField.setText(PluginSettings.baseUrl());
    });
    JButton okBtn = new JButton("确定");
    okBtn.addActionListener(e -> {
      PluginSettings.apply(dirField.getText(), cmdField.getText(), baseField.getText());
      applied = true;
      dispose();
    });
    JButton cancelBtn = new JButton("取消");
    cancelBtn.addActionListener(e -> dispose());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
    buttons.add(resetBtn);
    buttons.add(okBtn);
    buttons.add(cancelBtn);

    JPanel root = new JPanel(new BorderLayout());
    root.add(form, BorderLayout.CENTER);
    root.add(buttons, BorderLayout.SOUTH);
    root.setBorder(BorderFactory.createEmptyBorder(4, 0, 10, 0));
    setContentPane(root);
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    pack();
    setLocationRelativeTo(parent == null ? null : SwingUtilities.getWindowAncestor(parent));
  }

  /** 一行标签 + 一行输入；labelY 为标签所在 gridy，输入在 labelY+1。 */
  private static void addRow(JPanel form, GridBagConstraints base, int labelY,
                             String label, String tip, Component input) {
    GridBagConstraints lc = (GridBagConstraints) base.clone();
    lc.gridx = 0;
    lc.gridy = labelY;
    lc.anchor = GridBagConstraints.WEST;
    JBLabel l = new JBLabel(label);
    l.setToolTipText(tip);
    form.add(l, lc);

    GridBagConstraints ic = (GridBagConstraints) base.clone();
    ic.gridx = 0;
    ic.gridy = labelY + 1;
    form.add(input, ic);
  }

  /** 用户是否点了「确定」（真 → 设置已持久化，调用方按需重载）。 */
  public boolean applied() {
    return applied;
  }
}
