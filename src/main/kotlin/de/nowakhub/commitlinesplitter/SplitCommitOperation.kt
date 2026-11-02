package de.nowakhub.commitlinesplitter

/** The file of the target commit being split, with both versions loaded. */
class FileSplitInput(
  /** Repo-relative path, '/'-separated. */
  val path: String,
  /** Content in the parent commit, or null if the file did not exist there. */
  val baseContent: ByteArray?,
  /** Content in the target commit, or null if the commit deleted the file. */
  val targetContent: ByteArray?,
  /** Git mode to use if the file has to exist in the reduced commit. */
  val mode: String,
) {
  val model = LineSplitModel(baseContent ?: ByteArray(0), targetContent ?: ByteArray(0))
}

data class SplitResult(
  val ref: String,
  val oldHead: String,
  val newHead: String,
  val reducedCommit: String,
  val extractedCommit: String,
  val rewrittenDescendants: Int,
)

/**
 * Splits commit [targetSha] into
 *  1. the original commit, where [FileSplitInput.path] only contains the non-extracted changes, and
 *  2. a new commit directly after it, containing the extracted lines (its tree equals the original tree).
 *
 * Because the second commit has exactly the original tree, all descendants up to HEAD are re-created with
 * unchanged trees: no conflicts are possible and the working tree / index stay untouched.
 */
class SplitCommitOperation(private val git: GitPlumbing) {

  fun loadInput(targetSha: String, path: String): FileSplitInput {
    val commit = git.readCommit(targetSha)
    if (commit.parents.size > 1) throw SplitException("Merge commits cannot be split")
    val parentTree = commit.parents.firstOrNull()?.let { git.readCommit(it).tree }
    val before = parentTree?.let { git.findEntry(it, path) }
    val after = git.findEntry(commit.tree, path)
    for (e in listOfNotNull(before, after)) {
      if (e.type != "blob" || e.mode == "120000") throw SplitException("Only regular files can be split")
    }
    if (before == null && after == null) throw SplitException("'$path' is not changed in this commit")
    val baseContent = before?.let { git.readBlob(it.sha) }
    val targetContent = after?.let { git.readBlob(it.sha) }
    if (listOfNotNull(baseContent, targetContent).any { looksBinary(it) }) throw SplitException("Binary files cannot be split line by line")
    return FileSplitInput(path, baseContent, targetContent, (after ?: before)!!.mode)
  }

  /** Descendants of [targetSha] up to HEAD, oldest first. Throws if history is not linear. */
  fun linearDescendants(targetSha: String, headSha: String): List<String> {
    val graph = git.revListWithParents(targetSha, headSha)
    val chain = ArrayList<String>()
    var cur = headSha
    while (cur != targetSha) {
      val parents = graph[cur] ?: throw SplitException("The commit is not part of the current branch (HEAD)")
      if (parents.size != 1) throw SplitException("There is a merge commit between this commit and HEAD; cannot rewrite history safely")
      chain += cur
      cur = parents.single()
    }
    if (chain.size != graph.size) throw SplitException("History between this commit and HEAD is not linear")
    return chain.asReversed()
  }

  fun execute(
    targetSha: String,
    input: FileSplitInput,
    extracted: Set<Int>,
    reducedMessage: String,
    extractedMessage: String,
  ): SplitResult {
    val model = input.model
    val changed = model.changedLineIndices().toSet()
    val selection = extracted intersect changed
    if (selection.isEmpty()) throw SplitException("No lines selected to extract")

    val ref = git.headRef()
    val oldHead = git.resolveCommit("HEAD")
    val descendants = linearDescendants(targetSha, oldHead)
    val target = git.readCommit(targetSha)

    // Tree of the reduced original commit
    val intermediate = model.buildIntermediate(selection)
    val keepFile = when {
      input.baseContent != null && input.targetContent != null -> true
      else -> intermediate.isNotEmpty() // added or deleted in commit: exists only if some content remains
    }
    val blobEntry = if (keepFile) input.mode to git.writeBlob(intermediate) else null
    val reducedTree = git.replacePath(target.tree, input.path, blobEntry)
    val parentTree = target.parents.firstOrNull()?.let { git.readCommit(it).tree }
    if (reducedTree == parentTree) {
      throw SplitException("All changes of the commit would move to the new commit, leaving the original commit empty. Use 'Edit Commit Message' instead.")
    }

    // Like a rebase: authors (incl. dates) are kept, the committer becomes the current user.
    val committer = git.currentCommitterLine()
    fun utf8(msg: String) = ensureTrailingNewline(msg).toByteArray(Charsets.UTF_8)
    val reduced = git.writeCommit(reducedTree, target.parents, target.authorLine, committer, utf8(reducedMessage))
    val extractedCommit = git.writeCommit(target.tree, listOf(reduced), target.authorLine, committer, utf8(extractedMessage))

    var parent = extractedCommit
    for (sha in descendants) {
      val c = git.readCommit(sha)
      parent = git.writeCommit(c.tree, listOf(parent), c.authorLine, committer, c.rawMessage, c.encoding)
    }

    git.updateRef(ref, parent, oldHead, "split-lines: split ${targetSha.take(10)}")
    return SplitResult(ref, oldHead, parent, reduced, extractedCommit, descendants.size)
  }

  /** Restores the branch to its state before [result], if nothing else moved it since. */
  fun undo(result: SplitResult) {
    git.updateRef(result.ref, result.oldHead, result.newHead, "split-lines: undo split")
  }

  companion object {
    fun ensureTrailingNewline(s: String): String = if (s.endsWith("\n")) s else s + "\n"

    /** Same heuristic as git: a NUL byte in the first 8000 bytes means binary. */
    fun looksBinary(bytes: ByteArray): Boolean = (0 until minOf(bytes.size, 8000)).any { bytes[it] == 0.toByte() }
  }
}
