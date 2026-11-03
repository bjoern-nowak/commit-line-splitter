package de.nowakhub.commitlinesplitter

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Runs inside a headless IDE with the plugin loaded. */
class PluginIntegrationTest : BasePlatformTestCase() {

  fun `test action is registered right after the official extract action`() {
    val am = ActionManager.getInstance()
    val ours = am.getAction("CommitLineSplitter.SplitFileLines")
    assertNotNull(ours)
    assertInstanceOf(ours, SplitLinesAction::class.java)
    val group = am.getAction("Vcs.RepositoryChangesBrowserMenu") as DefaultActionGroup
    val ids = group.getChildActionsOrStubs().map { am.getId(it) }
    val position = ids.indexOf("CommitLineSplitter.SplitFileLines")
    assertTrue("action in menu: $ids", position >= 0)
    val official = ids.indexOf("Git.InMemory.Extract.Selected.Changes")
    if (official >= 0) assertEquals("directly below the official action: $ids", official + 1, position)
    else assertEquals("at the end of the menu (IDE before 2025.3 has no official action): $ids", ids.lastIndex, position)
  }

  /**
   * The Plugin Verifier can only say the plugin is "probably" dynamic. This asks the IDE's own check, the one that
   * decides whether installing, updating or uninstalling the plugin needs a restart.
   */
  fun `test plugin can be loaded and unloaded without IDE restart`() {
    val descriptor = PluginManagerCore.getPlugin(PluginId.getId("de.nowakhub.commitlinesplitter")) as IdeaPluginDescriptorImpl
    assertNull(DynamicPlugins.checkCanUnloadWithoutRestart(descriptor))
  }

  fun `test dialog builds editor and toggles lines and hunks`() {
    val base = "a\nb\nc\nd\ne\n".toByteArray()
    val target = "a\nB\nc\nd\ne\nf\n".toByteArray()
    val input = FileSplitInput("dir/file.txt", base, target, "100644")
    val dialog = SplitLinesDialog(project, input, Charsets.UTF_8, "abcdef12", "Original\n")
    try {
      val model = input.model
      assertEquals(2, model.hunks.size)
      // document has 1 header row per hunk + all diff lines
      assertEquals(model.lines.size + 2, dialog.editor.document.lineCount)
      assertTrue(dialog.editor.document.text.contains("+ B"))

      val hdr0 = dialog.headerRowOf(0)
      assertEquals(AllIcons.Diff.GutterCheckBox, dialog.iconAtRow(hdr0))

      // tick only the "+ B" line of hunk 0 -> header becomes indeterminate
      val addB = model.lines.indexOfFirst { it.kind == LineKind.ADDED && it.rawText == "B\n" }
      dialog.toggleRow(dialog.rowOfLine(addB))
      assertEquals(setOf(addB), dialog.extractedLines)
      assertEquals(AllIcons.Diff.GutterCheckBoxIndeterminate, dialog.iconAtRow(hdr0))
      assertEquals(AllIcons.Diff.GutterCheckBoxSelected, dialog.iconAtRow(dialog.rowOfLine(addB)))

      // clicking the header selects the whole hunk, again clears it
      dialog.toggleRow(hdr0)
      assertEquals(model.hunks[0].toSet(), dialog.extractedLines)
      dialog.toggleRow(hdr0)
      assertTrue(dialog.extractedLines.isEmpty())

      // Space on the second hunk header via caret
      val hdr1 = dialog.headerRowOf(1)
      dialog.editor.caretModel.moveToOffset(dialog.editor.document.getLineStartOffset(hdr1))
      dialog.toggleAtCaret()
      assertEquals(model.hunks[1].toSet(), dialog.extractedLines)

      // validation: needs a new commit message
      assertNotNull(dialog.validateForTest())
    }
    finally {
      Disposer.dispose(dialog.disposable)
    }
  }

  /** Exercises the production runner (git4idea GitBinaryHandler: stdin, env vars, binary output). */
  fun `test split through git4idea runner`() {
    val dir = Files.createTempDirectory("split-ij").toFile()
    try {
      fun sh(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { out }
      }
      fun out(vararg cmd: String) = ProcessBuilder(*cmd).directory(dir).start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
      sh("git", "init", "-q", "-b", "main")
      sh("git", "config", "user.name", "T"); sh("git", "config", "user.email", "t@example.com")
      File(dir, "f.txt").writeText("1\n2\n")
      sh("git", "add", "-A"); sh("git", "commit", "-q", "-m", "A")
      File(dir, "f.txt").writeText("1\nzwei ä\n2\ndrei\n")
      sh("git", "add", "-A"); sh("git", "commit", "-q", "-m", "B ü", "--author", "Jörg <j@example.com>")
      val b = out("git", "rev-parse", "HEAD")

      val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
      val op = SplitCommitOperation(GitPlumbing(Git4IdeaRunner(project, root)))
      val future = ApplicationManager.getApplication().executeOnPooledThread<SplitResult> {
        val input = op.loadInput(b, "f.txt")
        val drei = input.model.lines.indexOfFirst { it.rawText == "drei\n" }
        op.execute(b, input, setOf(drei), "B ü", "Add drei")
      }
      val result = future.get(60, TimeUnit.SECONDS)
      assertEquals(b, result.oldHead)
      assertEquals("Add drei\nB ü\nA", out("git", "log", "--format=%s"))
      assertEquals("1\nzwei ä\n2", out("git", "show", "HEAD~1:f.txt"))
      assertEquals("Jörg <j@example.com>", out("git", "log", "-1", "--format=%an <%ae>", "HEAD~1"))
      assertEquals("", out("git", "status", "--porcelain"))
    }
    finally {
      dir.deleteRecursively()
    }
  }

  /**
   * Regression test: drives the post-dialog flow exactly as the action does (started from the EDT).
   * Previously the repository refresh ran in onSuccess on the EDT, which IntelliJ rejects with
   * "Access from Event Dispatch Thread (EDT) is not allowed".
   */
  fun `test split task runs git and refresh off the EDT and notifies`() {
    val dir = Files.createTempDirectory("split-flow").toFile()
    try {
      fun sh(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { out }
      }
      fun out(vararg cmd: String) = ProcessBuilder(*cmd).directory(dir).start().inputStream.readBytes().toString(Charsets.UTF_8).trim()
      sh("git", "init", "-q", "-b", "main")
      sh("git", "config", "user.name", "T"); sh("git", "config", "user.email", "t@example.com")
      File(dir, "f.txt").writeText("1\n")
      sh("git", "add", "-A"); sh("git", "commit", "-q", "-m", "A")
      File(dir, "f.txt").writeText("1\n2\n3\n")
      sh("git", "add", "-A"); sh("git", "commit", "-q", "-m", "B")
      val b = out("git", "rev-parse", "HEAD")

      val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
      val op = SplitCommitOperation(GitPlumbing(Git4IdeaRunner(project, root)))
      val input = ApplicationManager.getApplication().executeOnPooledThread<FileSplitInput> { op.loadInput(b, "f.txt") }
        .get(60, TimeUnit.SECONDS)
      val three = input.model.lines.indexOfFirst { it.rawText == "3\n" }

      assertTrue(ApplicationManager.getApplication().isDispatchThread)
      var done = false
      var failure: Throwable? = null
      SplitLinesAction().startSplit(project, root, op, b, "f.txt", input, setOf(three), "B", "Add 3") { _, error ->
        failure = error
        done = true
      }
      val deadline = System.currentTimeMillis() + 60_000
      while (!done && System.currentTimeMillis() < deadline) {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(20)
      }
      assertTrue("split task did not finish", done)
      assertNull(failure?.stackTraceToString(), failure)
      assertEquals("Add 3\nB\nA", out("git", "log", "--format=%s"))
      assertEquals("1\n2", out("git", "show", "HEAD~1:f.txt"))
    }
    finally {
      dir.deleteRecursively()
    }
  }
}
