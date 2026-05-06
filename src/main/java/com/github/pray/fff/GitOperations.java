package com.github.pray.fff;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.commands.Git;
import git4idea.commands.GitCommand;
import git4idea.commands.GitCommandResult;
import git4idea.commands.GitLineHandler;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRemote;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class GitOperations {
    private static final Logger logger = LoggerFactory.getLogger(GitOperations.class);

    public static class GitCommandException extends Exception {
        public GitCommandException(String message) {
            super(message);
        }
    }

    /**
     * 合并分支
     * @return 如果发生冲突返回true，否则返回false
     */
    public static boolean mergeBranch(@NotNull Project project,
                                 @NotNull GitRepository repository,
                                 String sourceBranch,
                                 String targetBranch,
                                 boolean shouldPush) throws GitCommandException {
        VirtualFile root = repository.getRoot();
        Git git = Git.getInstance();

        logger.info("开始合并分支: {} -> {} (Root: {})", sourceBranch, targetBranch, root.getPath());

        // 获取远程仓库名称（默认使用 origin，如果不存在则使用第一个远程）
        String remoteName = getRemoteName(repository);

        try {
            // 1. 切换到目标分支
            runGitCommand(project, root, GitCommand.CHECKOUT, "切换分支", targetBranch);

            // 2. 拉取代码
            runGitCommand(project, root, GitCommand.PULL, "拉取代码", remoteName, targetBranch);

            // 3. 合并
            boolean hasConflict = false;
            GitCommandResult mergeResult = null;
            try {
                GitLineHandler mergeHandler = new GitLineHandler(project, root, GitCommand.MERGE);
                mergeHandler.addParameters("--no-ff", sourceBranch);
                mergeResult = git.runCommand(mergeHandler);
                
                if (!mergeResult.success()) {
                    // 检查是否是冲突导致的失败
                    String errorOutput = mergeResult.getErrorOutputAsJoinedString();
                    if (isMergeConflict(errorOutput)) {
                        logger.warn("合并时发生冲突: {}", errorOutput);
                        hasConflict = true;
                    } else {
                        // 不是冲突，抛出异常
                        throw new GitCommandException("合并分支失败: " + errorOutput);
                    }
                }
            } catch (GitCommandException e) {
                // 如果已经检测到冲突，不重新抛出
                if (!hasConflict) {
                    throw e;
                }
            }
            
            // 即使merge命令返回成功，也要检查是否有未解决的冲突文件
            if (!hasConflict) {
                hasConflict = checkForUnmergedFiles(project, root, git);
            }

            // 如果有冲突，刷新仓库状态并返回
            if (hasConflict) {
                logger.info("检测到冲突，停止合并流程，停留在目标分支: {}", targetBranch);
                // 执行 git status 来刷新 IDE 的仓库状态
                refreshRepositoryStatus(project, root, git);
                return true;
            }

            // 4. 推送
            if (shouldPush) {
                runGitCommand(project, root, GitCommand.PUSH, "推送代码", remoteName, targetBranch);
            }

            // 5. 切回源分支（失败不抛异常，只记录）
            try {
                GitLineHandler checkoutBack = new GitLineHandler(project, root, GitCommand.CHECKOUT);
                checkoutBack.addParameters(sourceBranch);
                GitCommandResult result = git.runCommand(checkoutBack);
                if (!result.success()) {
                    logger.warn("切回源分支失败: {}", result.getErrorOutputAsJoinedString());
                }
            } catch (Exception e) {
                logger.warn("切回源分支时发生异常", e);
            }

            logger.info("合并完成: {} -> {}", sourceBranch, targetBranch);
            return false;

        } catch (GitCommandException e) {
            throw e;
        } catch (Exception e) {
            logger.error("未预期的错误", e);
            throw new GitCommandException("内部错误: " + e.getMessage());
        }
    }

    /**
     * 检查错误消息是否表示合并冲突
     */
    private static boolean isMergeConflict(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String lowerMsg = errorMessage.toLowerCase();
        return lowerMsg.contains("conflict") || 
               lowerMsg.contains("冲突") ||
               lowerMsg.contains("merge conflict") ||
               lowerMsg.contains("unmerged") ||
               lowerMsg.contains("automatic merge failed");
    }

    /**
     * 检查是否有未解决的冲突文件
     */
    private static boolean checkForUnmergedFiles(@NotNull Project project, 
                                                  @NotNull VirtualFile root, 
                                                  @NotNull Git git) {
        try {
            GitLineHandler statusHandler = new GitLineHandler(project, root, GitCommand.STATUS);
            statusHandler.addParameters("--porcelain");
            GitCommandResult result = git.runCommand(statusHandler);
            
            if (result.success()) {
                String output = result.getOutputAsJoinedString();
                // 检查是否有未合并的文件（以"UU"、"AA"、"DD"等开头的行表示冲突）
                if (output != null && !output.trim().isEmpty()) {
                    String[] lines = output.split("\n");
                    for (String line : lines) {
                        if (line.length() >= 2) {
                            char status1 = line.charAt(0);
                            char status2 = line.charAt(1);
                            // UU, AA, DD, AU, UA, DU, UD 等表示冲突
                            if ((status1 == 'U' || status1 == 'A' || status1 == 'D') &&
                                (status2 == 'U' || status2 == 'A' || status2 == 'D')) {
                                logger.info("检测到未解决的冲突文件: {}", line);
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("检查冲突文件状态时出错", e);
        }
        return false;
    }

    /**
     * 刷新仓库状态，让 IDE 识别最新的冲突状态
     */
    private static void refreshRepositoryStatus(@NotNull Project project,
                                                @NotNull VirtualFile root,
                                                @NotNull Git git) {
        try {
            logger.info("刷新仓库状态以更新 IDE 冲突检测");
            // 执行 git status 来刷新状态
            GitLineHandler statusHandler = new GitLineHandler(project, root, GitCommand.STATUS);
            GitCommandResult result = git.runCommand(statusHandler);
            if (result.success()) {
                logger.info("仓库状态刷新成功");
            } else {
                logger.warn("仓库状态刷新失败: {}", result.getErrorOutputAsJoinedString());
            }
        } catch (Exception e) {
            logger.warn("刷新仓库状态时出错", e);
        }
    }

    /**
     * 获取远程仓库名称（优先 origin，否则使用第一个远程）
     */
    private static String getRemoteName(@NotNull GitRepository repository) {
        try {
            Collection<GitRemote> remotes = repository.getRemotes();
            if (remotes.isEmpty()) {
                return "origin"; // 默认值
            }
            // 优先使用 origin
            for (GitRemote remote : remotes) {
                if ("origin".equals(remote.getName())) {
                    return "origin";
                }
            }
            // 否则使用第一个远程
            return remotes.iterator().next().getName();
        } catch (Exception e) {
            logger.warn("无法获取远程仓库列表，使用默认值 origin", e);
            return "origin";
        }
    }

    /**
     * 提取通用执行逻辑，减少代码重复
     */
    private static void runGitCommand(Project project, VirtualFile root, GitCommand command, 
                                      String actionName, String... parameters) throws GitCommandException {
        GitLineHandler handler = new GitLineHandler(project, root, command);
        if (parameters != null && parameters.length > 0) {
            handler.addParameters(parameters);
        }

        GitCommandResult result = Git.getInstance().runCommand(handler);

        if (!result.success()) {
            String errorMsg = result.getErrorOutputAsJoinedString();
            logger.error("{} 失败: {}", actionName, errorMsg);
            throw new GitCommandException(actionName + " 失败: " + errorMsg);
        } else {
            logger.info("{} 成功", actionName);
        }
    }
}
