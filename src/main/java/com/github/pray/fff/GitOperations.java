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
     * Merge branch
     * @return true if conflict occurs, false otherwise
     */
    public static boolean mergeBranch(@NotNull Project project,
                                 @NotNull GitRepository repository,
                                 String sourceBranch,
                                 String targetBranch,
                                 boolean shouldPush) throws GitCommandException {
        VirtualFile root = repository.getRoot();
        Git git = Git.getInstance();

        logger.info("Start merging branch: {} -> {} (Root: {})", sourceBranch, targetBranch, root.getPath());

        // Get remote repository name (default to origin, use first remote if not exists)
        String remoteName = getRemoteName(repository);

        try {
            // 1. Checkout target branch
            runGitCommand(project, root, GitCommand.CHECKOUT, "Checkout branch", targetBranch);

            // 2. Pull code
            runGitCommand(project, root, GitCommand.PULL, "Pull code", remoteName, targetBranch);

            // 3. Merge
            boolean hasConflict = false;
            GitCommandResult mergeResult = null;
            try {
                GitLineHandler mergeHandler = new GitLineHandler(project, root, GitCommand.MERGE);
                mergeHandler.addParameters("--no-ff", sourceBranch);
                mergeResult = git.runCommand(mergeHandler);
                
                if (!mergeResult.success()) {
                    // Check if failure is caused by conflict
                    String errorOutput = mergeResult.getErrorOutputAsJoinedString();
                    if (isMergeConflict(errorOutput)) {
                        logger.warn("Conflict occurred during merge: {}", errorOutput);
                        hasConflict = true;
                    } else {
                        // Not a conflict, throw exception
                        throw new GitCommandException("Failed to merge branch: " + errorOutput);
                    }
                }
            } catch (GitCommandException e) {
                // If conflict is already detected, do not rethrow
                if (!hasConflict) {
                    throw e;
                }
            }
            
            // Even if merge command returns success, check for unresolved conflict files
            if (!hasConflict) {
                hasConflict = checkForUnmergedFiles(project, root, git);
            }

            // If there are conflicts, refresh repository status and return
            if (hasConflict) {
                logger.info("Conflict detected, stop merge process, stay on target branch: {}", targetBranch);
                // Execute git status to refresh IDE repository status
                refreshRepositoryStatus(project, root, git);
                return true;
            }

            // 4. Push
            if (shouldPush) {
                runGitCommand(project, root, GitCommand.PUSH, "Push code", remoteName, targetBranch);
            }

            // 5. Checkout back to source branch (do not throw exception on failure, just log)
            try {
                GitLineHandler checkoutBack = new GitLineHandler(project, root, GitCommand.CHECKOUT);
                checkoutBack.addParameters(sourceBranch);
                GitCommandResult result = git.runCommand(checkoutBack);
                if (!result.success()) {
                    logger.warn("Failed to switch back to source branch: {}", result.getErrorOutputAsJoinedString());
                }
            } catch (Exception e) {
                logger.warn("Exception occurred when switching back to source branch", e);
            }

            logger.info("Merge completed: {} -> {}", sourceBranch, targetBranch);
            return false;

        } catch (GitCommandException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Unexpected error", e);
            throw new GitCommandException("Internal error: " + e.getMessage());
        }
    }

    /**
     * Check if error message indicates merge conflict
     */
    private static boolean isMergeConflict(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String lowerMsg = errorMessage.toLowerCase();
        return lowerMsg.contains("conflict") || 
               lowerMsg.contains("merge conflict") ||
               lowerMsg.contains("unmerged") ||
               lowerMsg.contains("automatic merge failed");
    }

    /**
     * Check for unresolved conflict files
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
                // Check for unmerged files (lines starting with "UU", "AA", "DD" etc. indicate conflict)
                if (output != null && !output.trim().isEmpty()) {
                    String[] lines = output.split("\n");
                    for (String line : lines) {
                        if (line.length() >= 2) {
                            char status1 = line.charAt(0);
                            char status2 = line.charAt(1);
                            // UU, AA, DD, AU, UA, DU, UD etc. indicate conflict
                            if ((status1 == 'U' || status1 == 'A' || status1 == 'D') &&
                                (status2 == 'U' || status2 == 'A' || status2 == 'D')) {
                                logger.info("Unresolved conflict file detected: {}", line);
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error checking conflict file status", e);
        }
        return false;
    }

    /**
     * Refresh repository status to let IDE recognize latest conflict status
     */
    private static void refreshRepositoryStatus(@NotNull Project project,
                                                @NotNull VirtualFile root,
                                                @NotNull Git git) {
        try {
            logger.info("Refresh repository status to update IDE conflict detection");
            // Execute git status to refresh status
            GitLineHandler statusHandler = new GitLineHandler(project, root, GitCommand.STATUS);
            GitCommandResult result = git.runCommand(statusHandler);
            if (result.success()) {
                logger.info("Repository status refreshed successfully");
            } else {
                logger.warn("Repository status refresh failed: {}", result.getErrorOutputAsJoinedString());
            }
        } catch (Exception e) {
            logger.warn("Error refreshing repository status", e);
        }
    }

    /**
     * Get remote repository name (prefer origin, otherwise use first remote)
     */
    private static String getRemoteName(@NotNull GitRepository repository) {
        try {
            Collection<GitRemote> remotes = repository.getRemotes();
            if (remotes.isEmpty()) {
                return "origin"; // Default value
            }
            // Prefer origin
            for (GitRemote remote : remotes) {
                if ("origin".equals(remote.getName())) {
                    return "origin";
                }
            }
            // Otherwise use the first remote
            return remotes.iterator().next().getName();
        } catch (Exception e) {
            logger.warn("Unable to get remote repository list, using default value: origin", e);
            return "origin";
        }
    }

    /**
     * Extract common execution logic to reduce code duplication
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
            logger.error("{} failed: {}", actionName, errorMsg);
            throw new GitCommandException(actionName + " failed: " + errorMsg);
        } else {
            logger.info("{} succeeded", actionName);
        }
    }
}
