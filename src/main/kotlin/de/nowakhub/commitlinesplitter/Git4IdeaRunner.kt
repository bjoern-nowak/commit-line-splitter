package de.nowakhub.commitlinesplitter

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.GitBinaryHandler
import git4idea.commands.GitCommand

/**
 * [GitRunner] backed by the IDE's git integration, so the configured git executable
 * (incl. WSL) and its environment are used. Must not be called on the EDT.
 */
class Git4IdeaRunner(private val project: Project, private val root: VirtualFile) : GitRunner {

  override fun run(command: String, args: List<String>, stdin: ByteArray?): ByteArray {
    val handler = GitBinaryHandler(project, root, toGitCommand(command))
    handler.setSilent(command != "update-ref")
    handler.addParameters(args)
    // Always provide stdin (empty if not needed) so git never waits for input.
    handler.setInputProcessor { out -> out.use { if (stdin != null) it.write(stdin) } }
    return handler.run()
  }

  private fun toGitCommand(command: String): GitCommand = when (command) {
    "rev-parse" -> GitCommand.REV_PARSE
    "rev-list" -> GitCommand.REV_LIST
    "cat-file" -> GitCommand.CAT_FILE
    "ls-tree" -> GitCommand.LS_TREE
    "hash-object" -> GitCommand.HASH_OBJECT
    "config" -> GitCommand.CONFIG
    "update-ref" -> GitCommand.UPDATE_REF
    "branch" -> GitCommand.BRANCH
    else -> error("Unsupported git command: $command")
  }
}
