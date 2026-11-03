package de.nowakhub.commitlinesplitter

import com.intellij.dvcs.repo.Repository
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsLogDataKeys
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.vcsUtil.VcsFileUtil
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.charset.Charset

/**
 * Git Log → commit details → right-click a single file → "Split Lines to Separate Commit...".
 * Line-level variant of the built-in "Extract Selected Changes to Separate Commit...".
 */
class SplitLinesAction : DumbAwareAction() {

  private class Context(val project: Project, val repository: GitRepository, val commitHash: String, val change: Change) {
    val root: VirtualFile get() = repository.root
    val filePath get() = (change.afterRevision ?: change.beforeRevision)!!.file
    val relativePath: String get() = VcsFileUtil.getRelativeFilePath(filePath.path, root)
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  private fun context(e: AnActionEvent): Context? {
    val project = e.project ?: return null
    val selection = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION) ?: return null
    val commit = selection.commits.singleOrNull() ?: return null
    val change = e.getData(VcsDataKeys.SELECTED_CHANGES_IN_DETAILS)?.singleOrNull() ?: return null
    val manager = GitRepositoryManager.getInstance(project)
    val repository = manager.getRepositoryForRootQuick(commit.root) ?: return null
    if (manager.isExternal(repository)) return null
    return Context(project, repository, commit.hash.asString(), change)
  }

  override fun update(e: AnActionEvent) {
    val ctx = context(e)
    if (ctx == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    e.presentation.isVisible = true
    val reason = when {
      ctx.change.type == Change.Type.MOVED -> "Renamed or moved files are not supported"
      FileTypeRegistry.getInstance().getFileTypeByFileName(ctx.filePath.name).isBinary -> "Binary files cannot be split line by line"
      ctx.repository.state != Repository.State.NORMAL && ctx.repository.state != Repository.State.DETACHED ->
        "Not available during ${ctx.repository.state.name.lowercase()}"
      else -> null
    }
    e.presentation.isEnabled = reason == null
    e.presentation.description = reason ?: templatePresentation.description
  }

  override fun actionPerformed(e: AnActionEvent) {
    val ctx = context(e) ?: return
    val project = ctx.project
    val op = SplitCommitOperation(GitPlumbing(Git4IdeaRunner(project, ctx.root)))

    class Prepared(val input: FileSplitInput, val message: String, val remotes: List<String>, val charset: Charset)

    val prepared = try {
      ProgressManager.getInstance().runProcessWithProgressSynchronously(ThrowableComputable<Prepared, Exception> {
        val plumbing = GitPlumbing(Git4IdeaRunner(project, ctx.root))
        val head = plumbing.resolveCommit("HEAD")
        op.linearDescendants(ctx.commitHash, head) // fail early if commit is not in a linear HEAD history
        Prepared(
          op.loadInput(ctx.commitHash, ctx.relativePath),
          plumbing.readCommit(ctx.commitHash).message,
          plumbing.remoteBranchesContaining(ctx.commitHash),
          ReadAction.nonBlocking<Charset> { ctx.filePath.getCharset(project) }.executeSynchronously(),
        )
      }, "Loading Commit…", true, project)
    }
    catch (ex: Exception) {
      showError(project, ex)
      return
    }

    if (prepared.input.model.hunks.isEmpty()) {
      Messages.showInfoMessage(project, "There are no line changes in ${ctx.relativePath} in this commit.", TITLE)
      return
    }

    if (prepared.remotes.isNotEmpty()) {
      val answer = Messages.showYesNoDialog(
        project,
        "This commit is already contained in ${prepared.remotes.take(3).joinToString()}" +
        (if (prepared.remotes.size > 3) " and ${prepared.remotes.size - 3} more" else "") +
        ".\nSplitting it rewrites published history and will require a force push.\n\nContinue?",
        TITLE, "Split Anyway", Messages.getCancelButton(), Messages.getWarningIcon(),
      )
      if (answer != Messages.YES) return
    }

    val dialog = SplitLinesDialog(project, prepared.input, prepared.charset, ctx.commitHash.take(8), prepared.message)
    if (!dialog.showAndGet()) return

    val extracted = dialog.extractedLines
    val newMessage = dialog.newCommitMessage
    val originalMessage = dialog.originalCommitMessage

    startSplit(project, ctx.root, op, ctx.commitHash, ctx.relativePath, prepared.input, extracted, originalMessage, newMessage)
  }

  /**
   * Runs the split in a background task, then refreshes the repository (still in the background) and
   * shows the result notification on the EDT. [onDone] is for tests.
   */
  internal fun startSplit(
    project: Project,
    root: VirtualFile,
    op: SplitCommitOperation,
    commitHash: String,
    relativePath: String,
    input: FileSplitInput,
    extracted: Set<Int>,
    originalMessage: String,
    newMessage: String,
    onDone: ((SplitResult?, Throwable?) -> Unit)? = null,
  ) {
    object : Task.Backgroundable(project, "Splitting commit ${commitHash.take(8)}…", false) {
      private var result: SplitResult? = null

      override fun run(indicator: ProgressIndicator) {
        result = op.execute(commitHash, input, extracted, originalMessage, newMessage)
        refreshInBackground(project, root)
      }

      override fun onSuccess() {
        val r = result ?: return
        val details = "$relativePath: ${extracted.size} line(s) moved to new commit ${r.extractedCommit.take(8)}" +
                      if (r.rewrittenDescendants > 0) " (${r.rewrittenDescendants} later commit(s) rebased)" else ""
        notifications().createNotification("Commit split", details, NotificationType.INFORMATION)
          .addAction(NotificationAction.createSimpleExpiring("Undo") { undo(project, root, op, r) })
          .notify(project)
        onDone?.invoke(r, null)
      }

      override fun onThrowable(error: Throwable) {
        showError(project, error)
        onDone?.invoke(null, error)
      }
    }.queue()
  }

  private fun undo(project: Project, root: VirtualFile, op: SplitCommitOperation, result: SplitResult) {
    object : Task.Backgroundable(project, "Undoing split…", false) {
      override fun run(indicator: ProgressIndicator) {
        op.undo(result)
        refreshInBackground(project, root)
      }
      override fun onSuccess() {
        notifications().createNotification("Split undone", "Branch restored to ${result.oldHead.take(8)}", NotificationType.INFORMATION).notify(project)
      }
      override fun onThrowable(error: Throwable) {
        notifications().createNotification("Undo failed",
          "The branch has changed since the split (${error.message}). Use the reflog to restore ${result.oldHead.take(8)} manually.",
          NotificationType.ERROR).notify(project)
      }
    }.queue()
  }

  /**
   * Re-reads the repository state and refreshes the Git Log. Must run on a background thread
   * (GitRepositoryManager.updateRepository asserts that). A refresh failure must not turn a
   * successful split into an error, so it is only logged.
   */
  private fun refreshInBackground(project: Project, root: VirtualFile) {
    ThreadingAssertions.assertBackgroundThread()
    try {
      GitRepositoryManager.getInstance(project).updateRepository(root)
      VcsProjectLog.getInstance(project).dataManager?.refresh(listOf(root))
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: Exception) {
      LOG.warn("Failed to refresh repository state after split", e)
    }
  }

  private fun showError(project: Project, error: Throwable) {
    val message = when (error) {
      is SplitException, is VcsException -> error.message
      else -> error.message ?: error.toString()
    }
    notifications().createNotification("Cannot split commit", message ?: "Unknown error", NotificationType.ERROR).notify(project)
  }

  private fun notifications() = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)

  companion object {
    private val LOG = logger<SplitLinesAction>()
    const val TITLE = "Split Lines to Separate Commit"
    const val NOTIFICATION_GROUP = "Commit Line Splitter"
  }
}
