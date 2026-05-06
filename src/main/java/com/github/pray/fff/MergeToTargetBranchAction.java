package com.github.pray.fff;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import git4idea.GitLocalBranch;
import git4idea.GitUtil;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class MergeToTargetBranchAction extends AnAction {
    private static final Logger logger = LoggerFactory.getLogger(MergeToTargetBranchAction.class);
    public static final String NOTIFICATION_GROUP_ID = "MergeToTargetBranch.Notifications";

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // 1. 直接使用 Git API（升级到 2.x 插件后不再需要反射）
        GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
        List<GitRepository> repositories = manager.getRepositories();
        if (repositories.isEmpty()) {
            Messages.showErrorDialog(project, "未找到 Git 仓库", "错误");
            return;
        }
        GitRepository repository = repositories.get(0);

        String currentBranch = repository.getCurrentBranchName();
        if (currentBranch == null) {
            Messages.showErrorDialog(project, "无法获取当前分支信息", "错误");
            return;
        }

        // 获取本地分支列表
        List<String> branchNames = repository.getBranches().getLocalBranches().stream()
                .map(GitLocalBranch::getName)
                .collect(Collectors.toList());

        // 2. 优化：将数据直接传入 Dialog，不在 Dialog 内部再次查询
        BranchSelectionDialog dialog = new BranchSelectionDialog(project, branchNames);
        
        if (dialog.showAndGet()) {
            String selectedBranch = dialog.getSelectedBranch();
            boolean shouldPush = dialog.shouldPush();
            if (selectedBranch != null) {
                // 逻辑保持不变：如果没有手动设置默认，则记住当前选择
                if (!BranchPreferenceHelper.hasManualDefaultBranch(project)) {
                    BranchPreferenceHelper.setAutoRememberBranch(project, selectedBranch);
                }
                executeMergeTask(project, repository, selectedBranch, currentBranch, shouldPush);
            }
        }
    }

    private void executeMergeTask(Project project, GitRepository repository, String targetBranch, String originalBranch, boolean shouldPush) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Merging Branches") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(false);
                    indicator.setText("Starting merge process...");
                    boolean hasConflict = GitOperations.mergeBranch(project, repository, originalBranch, targetBranch, shouldPush);
                    
                    if (hasConflict) {
                        // 检测到冲突，刷新状态并提示用户
                        ApplicationManager.getApplication().invokeLater(() -> {
                            openConflictResolver(project, repository, originalBranch, targetBranch);
                        });
                    } else {
                        ApplicationManager.getApplication().invokeLater(() -> 
                            showNotification(project, "合并成功", 
                                   String.format("成功将 %s 合并到 %s!", originalBranch, targetBranch), 
                                   NotificationType.INFORMATION));
                    }
                } catch (GitOperations.GitCommandException ex) {
                    logger.warn("Merge operation failed: " + ex.getMessage());
                    ApplicationManager.getApplication().invokeLater(() -> 
                        showNotification(project, "合并失败", 
                               "当前分支: " + targetBranch + ". 原因: " + ex.getMessage(), 
                               NotificationType.WARNING));
                } catch (Exception ex) {
                    logger.error("Unexpected error during merge", ex);
                    ApplicationManager.getApplication().invokeLater(() -> 
                        showNotification(project, "合并失败", 
                               "未预期的错误: " + ex.getMessage(), 
                               NotificationType.ERROR));
                }
            }
        });
    }

    /**
     * 打开冲突解决面板
     */
    private void openConflictResolver(@NotNull Project project, @NotNull GitRepository repository, 
                                     String originalBranch, String targetBranch) {
        try {
            // 先刷新文件系统，确保 IDE 识别到冲突文件
            VirtualFile root = repository.getRoot();
            if (root != null) {
                VfsUtil.markDirtyAndRefresh(false, true, true, root);
                logger.info("已刷新文件系统以更新冲突状态");
            }
            
            // 等待一小段时间让 IDE 处理刷新
            Thread.sleep(500);
            
            // 发送合并后的通知，包含完整信息
            String message = String.format(
                "合并 %s 到 %s 时发生冲突，已停留在目标分支。请通过手动Resolve Conflicts后再提交。",
                originalBranch, targetBranch
            );
            showNotification(project, "合并冲突", message, NotificationType.WARNING);
            logger.info("检测到冲突，已提示用户手动打开冲突解决面板");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("等待刷新时被中断", e);
        } catch (Exception e) {
            logger.error("打开冲突解决面板失败", e);
            // 提示用户手动打开冲突面板
            showNotification(project, "提示", 
                   "检测到冲突，请通过 VCS -> Git -> Resolve Conflicts 手动打开冲突解决面板", 
                   NotificationType.INFORMATION);
        }
    }

    private static void showNotification(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, type)
                .notify(project);
    }

    // 3. 优化：提取偏好设置逻辑到静态内部类，减少主类混乱
    private static class BranchPreferenceHelper {
        private static final String MANUAL_DEFAULT_BRANCH_KEY = "MergeToTargetBranch.ManualDefaultBranch";
        private static final String AUTO_REMEMBER_BRANCH_KEY = "MergeToTargetBranch.AutoRememberBranch";
        private static final List<String> SMART_DEFAULT_BRANCHES = Arrays.asList("main", "master", "develop");

        static String getManualDefaultBranch(Project project) {
            return PropertiesComponent.getInstance(project).getValue(MANUAL_DEFAULT_BRANCH_KEY);
        }

        static void setManualDefaultBranch(Project project, String branchName) {
            PropertiesComponent.getInstance(project).setValue(MANUAL_DEFAULT_BRANCH_KEY, branchName);
        }

        static void clearManualDefaultBranch(Project project) {
            PropertiesComponent.getInstance(project).unsetValue(MANUAL_DEFAULT_BRANCH_KEY);
        }

        static boolean hasManualDefaultBranch(Project project) {
            return getManualDefaultBranch(project) != null;
        }

        static void setAutoRememberBranch(Project project, String branchName) {
            PropertiesComponent.getInstance(project).setValue(AUTO_REMEMBER_BRANCH_KEY, branchName);
        }

        static String getEffectiveDefaultBranch(Project project, List<String> availableBranches) {
            String manual = getManualDefaultBranch(project);
            if (manual != null && availableBranches.contains(manual)) return manual;

            String auto = PropertiesComponent.getInstance(project).getValue(AUTO_REMEMBER_BRANCH_KEY);
            if (auto != null && availableBranches.contains(auto)) return auto;

            return SMART_DEFAULT_BRANCHES.stream()
                    .filter(availableBranches::contains)
                    .findFirst()
                    .orElse(null);
        }
    }

    // 4. 优化：CellRenderer 性能优化（组件复用）
    private static class BranchListCellRenderer implements ListCellRenderer<String> {
        private final Project project;
        private final DefaultListCellRenderer defaultRenderer = new DefaultListCellRenderer();
        private int hoveredIndex = -1;

        // 预初始化组件，避免在 getListCellRendererComponent 中重复创建
        private final JPanel panel = new JPanel(new BorderLayout(5, 0));
        private final JLabel nameLabel = new JLabel();
        private final JLabel starLabel = new JLabel();

        public BranchListCellRenderer(Project project) {
            this.project = project;
            panel.setOpaque(true);
            panel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
            panel.add(nameLabel, BorderLayout.WEST);
            
            starLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            starLabel.setHorizontalAlignment(SwingConstants.CENTER);
            starLabel.setPreferredSize(new Dimension(20, 20));
            panel.add(starLabel, BorderLayout.EAST);
        }

        public void setHoveredIndex(int index) {
            this.hoveredIndex = index;
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            if (value == null) return defaultRenderer.getListCellRendererComponent(list, null, index, isSelected, cellHasFocus);

            boolean isInDropdown = index >= 0;
            String currentManualDefault = BranchPreferenceHelper.getManualDefaultBranch(project);
            boolean isDefault = value.equals(currentManualDefault);
            boolean isHovered = (index == hoveredIndex);

            // 设置文本
            nameLabel.setText(value);

            // 设置颜色
            if (isSelected) {
                panel.setBackground(list.getSelectionBackground());
                nameLabel.setForeground(list.getSelectionForeground());
            } else {
                panel.setBackground(list.getBackground());
                nameLabel.setForeground(list.getForeground());
            }

            // 设置星星状态
            if (isInDropdown) {
                starLabel.setVisible(true);
                if (isDefault) {
                    starLabel.setText("★");
                    starLabel.setForeground(new Color(255, 200, 0));
                    starLabel.setToolTipText("默认分支，点击取消");
                } else if (isHovered || cellHasFocus) {
                    starLabel.setText("☆");
                    starLabel.setForeground(Color.GRAY);
                    starLabel.setToolTipText("点击设为默认分支");
                } else {
                    starLabel.setVisible(false);
                }
            } else {
                starLabel.setVisible(false);
            }

            return panel;
        }
    }

    private static class BranchSelectionDialog extends DialogWrapper {
        private final ComboBox<String> branchComboBox;
        private final JCheckBox pushCheckBox;
        private final Project project;
        private final List<String> branches;
        private final BranchListCellRenderer cellRenderer;

        // 优化：构造函数接收准备好的数据
        public BranchSelectionDialog(Project project, List<String> branches) {
            super(project);
            this.project = project;
            this.branches = branches;
            
            branchComboBox = new ComboBox<>(branches.toArray(new String[0]));
            pushCheckBox = new JCheckBox("Merge 后自动 Push 到远程仓库", true);
            cellRenderer = new BranchListCellRenderer(project);
            branchComboBox.setRenderer(cellRenderer);

            String effectiveDefault = BranchPreferenceHelper.getEffectiveDefaultBranch(project, branches);
            if (effectiveDefault != null) {
                branchComboBox.setSelectedItem(effectiveDefault);
            }

            setupStarInteraction();
            setTitle("选择目标分支");
            init();
        }

        private void setupStarInteraction() {
            branchComboBox.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    SwingUtilities.invokeLater(() -> attachMouseListenerToPopupList());
                }
                @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { cellRenderer.setHoveredIndex(-1); }
                @Override public void popupMenuCanceled(PopupMenuEvent e) { cellRenderer.setHoveredIndex(-1); }
            });
        }

        // 5. 优化：简化的 JList 查找逻辑，递归查找是最稳健的
        private void attachMouseListenerToPopupList() {
            JList<?> list = findJListInComponent(branchComboBox);
            if (list == null) {
                // 尝试查找所有 Window (为了兼容某些 LookAndFeel 的 Popup 实现)
                for (Window window : Window.getWindows()) {
                    if (window instanceof Container && window.isShowing()) {
                         list = findJListInComponent((Container) window);
                         if (list != null && isBelongToComboBox(list, branchComboBox)) break;
                         else list = null;
                    }
                }
            }

            if (list != null) {
                // 清理旧监听器防止重复添加
                for (java.awt.event.MouseListener ml : list.getMouseListeners()) {
                    if (ml instanceof StarMouseAdapter) return;
                }
                StarMouseAdapter adapter = new StarMouseAdapter(list);
                list.addMouseListener(adapter);
                list.addMouseMotionListener(adapter);
            }
        }

        private JList<?> findJListInComponent(Container container) {
            for (Component comp : container.getComponents()) {
                if (comp instanceof JList) return (JList<?>) comp;
                if (comp instanceof Container) {
                    JList<?> result = findJListInComponent((Container) comp);
                    if (result != null) return result;
                }
            }
            return null;
        }

        // 确保找到的 List 确实是属于当前 ComboBox 的
        private boolean isBelongToComboBox(JList<?> list, JComboBox<?> comboBox) {
            if (comboBox.getItemCount() == 0) return false;
            ListModel<?> model = list.getModel();
            return model.getSize() > 0 && model.getElementAt(0).equals(comboBox.getItemAt(0));
        }

        private class StarMouseAdapter extends MouseAdapter {
            private final JList<?> list;
            
            public StarMouseAdapter(JList<?> list) { this.list = list; }

            @Override
            public void mouseMoved(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index != -1 && index != cellRenderer.hoveredIndex) {
                    cellRenderer.setHoveredIndex(index);
                    list.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                cellRenderer.setHoveredIndex(-1);
                list.repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index != -1 && isClickOnStar(e, index)) {
                    e.consume();
                    String branch = branches.get(index);
                    toggleDefaultBranch(branch);
                    list.repaint();
                }
            }

            private boolean isClickOnStar(MouseEvent e, int index) {
                Rectangle bounds = list.getCellBounds(index, index);
                if (bounds == null) return false;
                // 假设星星在最右侧 30px 区域
                return e.getX() > (bounds.x + bounds.width - 30);
            }
        }

        private void toggleDefaultBranch(String branchName) {
            if (branchName.equals(BranchPreferenceHelper.getManualDefaultBranch(project))) {
                BranchPreferenceHelper.clearManualDefaultBranch(project);
                showNotification(project, "取消成功", "已取消默认分支: " + branchName, NotificationType.INFORMATION);
            } else {
                BranchPreferenceHelper.setManualDefaultBranch(project, branchName);
                showNotification(project, "设置成功", "默认分支已设为: " + branchName, NotificationType.INFORMATION);
            }
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 10));
            
            JPanel topPanel = new JPanel(new BorderLayout(0, 5));
            topPanel.add(new JLabel("选择目标分支:"), BorderLayout.NORTH);
            topPanel.add(branchComboBox, BorderLayout.CENTER);
            
            panel.add(topPanel, BorderLayout.NORTH);
            panel.add(pushCheckBox, BorderLayout.CENTER);
            
            // 设置对话框宽度
            panel.setPreferredSize(new Dimension(400, panel.getPreferredSize().height));
            return panel;
        }

        public String getSelectedBranch() { return (String) branchComboBox.getSelectedItem(); }
        
        public boolean shouldPush() { return pushCheckBox.isSelected(); }
    }
}
