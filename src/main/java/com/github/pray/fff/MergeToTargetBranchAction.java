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

        // 1. Directly use Git API (no longer needs reflection after upgrading to 2.x plugin)
        GitRepositoryManager manager = GitUtil.getRepositoryManager(project);
        List<GitRepository> repositories = manager.getRepositories();
        if (repositories.isEmpty()) {
            Messages.showErrorDialog(project, "Git repository not found", "Error");
            return;
        }
        GitRepository repository = repositories.get(0);

        String currentBranch = repository.getCurrentBranchName();
        if (currentBranch == null) {
            Messages.showErrorDialog(project, "Unable to get current branch information", "Error");
            return;
        }

        // Get local branch list
        List<String> branchNames = repository.getBranches().getLocalBranches().stream()
                .map(GitLocalBranch::getName)
                .collect(Collectors.toList());

        // 2. Optimization: Pass data directly into Dialog, don't query again inside Dialog
        BranchSelectionDialog dialog = new BranchSelectionDialog(project, branchNames);
        
        if (dialog.showAndGet()) {
            String selectedBranch = dialog.getSelectedBranch();
            boolean shouldPush = dialog.shouldPush();
            if (selectedBranch != null) {
                // Logic remains unchanged: if no manual default is set, remember current selection
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
                        // Conflict detected, refresh status and prompt user
                        ApplicationManager.getApplication().invokeLater(() -> {
                            openConflictResolver(project, repository, originalBranch, targetBranch);
                        });
                    } else {
                        ApplicationManager.getApplication().invokeLater(() -> 
                            showNotification(project, "Merge successful", 
                                   String.format("Successfully merged %s into %s!", originalBranch, targetBranch), 
                                   NotificationType.INFORMATION));
                    }
                } catch (GitOperations.GitCommandException ex) {
                    logger.warn("Merge operation failed: " + ex.getMessage());
                    ApplicationManager.getApplication().invokeLater(() -> 
                        showNotification(project, "Merge failed", 
                               "Current branch: " + targetBranch + ". Reason: " + ex.getMessage(), 
                               NotificationType.WARNING));
                } catch (Exception ex) {
                    logger.error("Unexpected error during merge", ex);
                    ApplicationManager.getApplication().invokeLater(() -> 
                        showNotification(project, "Merge failed", 
                               "Unexpected error: " + ex.getMessage(), 
                               NotificationType.ERROR));
                }
            }
        });
    }

    /**
     * Open conflict resolution panel
     */
    private void openConflictResolver(@NotNull Project project, @NotNull GitRepository repository, 
                                     String originalBranch, String targetBranch) {
        try {
            // First refresh file system to ensure IDE recognizes conflict files
            VirtualFile root = repository.getRoot();
            if (root != null) {
                VfsUtil.markDirtyAndRefresh(false, true, true, root);
                logger.info("File system refreshed to update conflict status");
            }
            
            // Wait for a short time for IDE to process refresh
            Thread.sleep(500);
            
            // Send notification after merge with full info
            String message = String.format(
                "Conflict occurred while merging %s into %s, stayed on target branch. Please manually Resolve Conflicts before committing.",
                originalBranch, targetBranch
            );
            showNotification(project, "Merge Conflict", message, NotificationType.WARNING);
            logger.info("Conflict detected, user prompted to manually open conflict resolution panel");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for refresh", e);
        } catch (Exception e) {
            logger.error("Failed to open conflict resolution panel", e);
            // Prompt user to manually open conflict panel
            showNotification(project, "Prompt", 
                   "Conflict detected, please manually open conflict resolution panel via VCS -> Git -> Resolve Conflicts", 
                   NotificationType.INFORMATION);
        }
    }

    private static void showNotification(Project project, String title, String content, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(title, content, type)
                .notify(project);
    }

    // 3. Optimization: Extract preference logic to static inner class to reduce main class clutter
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

    // 4. Optimization: CellRenderer performance optimization (component reuse)
    private static class BranchListCellRenderer implements ListCellRenderer<String> {
        private final Project project;
        private final DefaultListCellRenderer defaultRenderer = new DefaultListCellRenderer();
        private int hoveredIndex = -1;

        // Pre-initialize components to avoid repeated creation in getListCellRendererComponent
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

            // Set text
            nameLabel.setText(value);

            // Set color
            if (isSelected) {
                panel.setBackground(list.getSelectionBackground());
                nameLabel.setForeground(list.getSelectionForeground());
            } else {
                panel.setBackground(list.getBackground());
                nameLabel.setForeground(list.getForeground());
            }

            // Set star status
            if (isInDropdown) {
                starLabel.setVisible(true);
                if (isDefault) {
                    starLabel.setText("★");
                    starLabel.setForeground(new Color(255, 200, 0));
                    starLabel.setToolTipText("Default branch, click to cancel");
                } else if (isHovered || cellHasFocus) {
                    starLabel.setText("☆");
                    starLabel.setForeground(Color.GRAY);
                    starLabel.setToolTipText("Click to set as default branch");
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

        // Optimization: Constructor receives prepared data
        public BranchSelectionDialog(Project project, List<String> branches) {
            super(project);
            this.project = project;
            this.branches = branches;
            
            branchComboBox = new ComboBox<>(branches.toArray(new String[0]));
            pushCheckBox = new JCheckBox("Automatically Push to remote after Merge", true);
            cellRenderer = new BranchListCellRenderer(project);
            branchComboBox.setRenderer(cellRenderer);

            String effectiveDefault = BranchPreferenceHelper.getEffectiveDefaultBranch(project, branches);
            if (effectiveDefault != null) {
                branchComboBox.setSelectedItem(effectiveDefault);
            }

            setupStarInteraction();
            setTitle("Select Target Branch");
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

        // 5. Optimization: Simplified JList lookup logic, recursive lookup is the most robust
        private void attachMouseListenerToPopupList() {
            JList<?> list = findJListInComponent(branchComboBox);
            if (list == null) {
                // Try to find all Windows (for compatibility with some LookAndFeel Popup implementations)
                for (Window window : Window.getWindows()) {
                    if (window instanceof Container && window.isShowing()) {
                         list = findJListInComponent((Container) window);
                         if (list != null && isBelongToComboBox(list, branchComboBox)) break;
                         else list = null;
                    }
                }
            }

            if (list != null) {
                // Clean up old listeners to prevent duplicate additions
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

        // Ensure found List indeed belongs to current ComboBox
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
                // Assume star is in the rightmost 30px area
                return e.getX() > (bounds.x + bounds.width - 30);
            }
        }

        private void toggleDefaultBranch(String branchName) {
            if (branchName.equals(BranchPreferenceHelper.getManualDefaultBranch(project))) {
                BranchPreferenceHelper.clearManualDefaultBranch(project);
                showNotification(project, "Cancellation successful", "Default branch cancelled: " + branchName, NotificationType.INFORMATION);
            } else {
                BranchPreferenceHelper.setManualDefaultBranch(project, branchName);
                showNotification(project, "Setting successful", "Default branch set to: " + branchName, NotificationType.INFORMATION);
            }
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 10));
            
            JPanel topPanel = new JPanel(new BorderLayout(0, 5));
            topPanel.add(new JLabel("Select Target Branch:"), BorderLayout.NORTH);
            topPanel.add(branchComboBox, BorderLayout.CENTER);
            
            panel.add(topPanel, BorderLayout.NORTH);
            panel.add(pushCheckBox, BorderLayout.CENTER);
            
            // Set dialog width
            panel.setPreferredSize(new Dimension(400, panel.getPreferredSize().height));
            return panel;
        }

        public String getSelectedBranch() { return (String) branchComboBox.getSelectedItem(); }
        
        public boolean shouldPush() { return pushCheckBox.isSelected(); }
    }
}
