# Merge To Target Branch

[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2+-blue.svg)](https://www.jetbrains.com/idea/)

**Merge To Target Branch** is an IntelliJ IDEA plugin designed to streamline your Git workflow. It automates the multi-step process of merging your current working branch into a target branch (like `main`, `master`, or `develop`), pushing the changes, and returning you right back to where you were—all with a single click.

## 🚀 Why Use This Plugin?

Manually merging a branch often involves:
1.  Checking out the target branch.
2.  Pulling the latest changes from remote.
3.  Merging your feature branch.
4.  Pushing the merged changes.
5.  Checking out your feature branch again to continue work.

This plugin reduces those 5+ steps into **one automated action**.

## ✨ Key Features

- **One-Click Workflow:** Automates checkout, pull, merge, push, and return-to-branch.
- **Smart Branch Selection:**
  - **Remember Last Used:** Automatically suggests the last branch you merged into.
  - **Default Branches:** Set a permanent default branch (e.g., `main`) by clicking the **star icon (★)** in the branch list.
- **Intelligent Conflict Handling:** If a merge conflict occurs, the plugin stops and leaves you on the target branch so you can resolve conflicts using IntelliJ's built-in tools.
- **Remote Integration:** Option to automatically push to the remote repository after a successful merge.
- **Native Experience:** Integrated directly into the IntelliJ VCS toolbar and Git menu.

## 🛠 How It Works

When you trigger the merge action, the plugin executes the following sequence:

1.  **Checkout:** Switches to the selected target branch.
2.  **Pull:** Updates the target branch from the remote (`git pull`).
3.  **Merge:** Merges your original branch using `--no-ff` to preserve commit history.
4.  **Push (Optional):** Pushes the updated target branch to the remote repository.
5.  **Restore:** Switches back to your original branch so you can keep working.

## ⌨️ Shortcuts & Access

- **Toolbar:** Click the Merge icon in the Main VCS Toolbar.
- **Menu:** `Git` > `Merge to Target Branch`.
- **Keyboard Shortcuts:**
  - **Windows/Linux:** `Ctrl + Alt + M`
  - **macOS:** `Shift + Cmd + Alt + M`

## 📦 Installation

1.  Open **IntelliJ IDEA**.
2.  Go to `Settings` (Windows/Linux) or `IntelliJ IDEA` > `Settings` (macOS).
3.  Select **Plugins** and click the **Marketplace** tab.
4.  Search for **"Merge To Target Branch"**.
5.  Click **Install** and restart the IDE if prompted.

---

*Simplify your Git workflow and focus on writing code, not managing branches.*
