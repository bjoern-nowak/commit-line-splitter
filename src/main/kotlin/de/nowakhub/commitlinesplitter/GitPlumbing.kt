package de.nowakhub.commitlinesplitter

import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

/** Runs a git sub-command in the repository root. Must throw on failure. */
interface GitRunner {
  fun run(command: String, args: List<String>, stdin: ByteArray? = null): ByteArray
}

class SplitException(message: String) : Exception(message)

/**
 * A parsed commit object. Identity lines and the message are kept as raw bytes (ISO-8859-1 strings / byte arrays)
 * so that rewritten commits reproduce author data and message encoding exactly.
 */
class RawCommit(
  val sha: String,
  val tree: String,
  val parents: List<String>,
  /** Raw value of the `author` header ("Name <mail> 1700000000 +0100"), ISO-8859-1 decoded. */
  val authorLine: String,
  /** Raw value of the `committer` header, ISO-8859-1 decoded. */
  val committerLine: String,
  /** Value of the optional `encoding` header. */
  val encoding: String?,
  val rawMessage: ByteArray,
) {
  /** Message decoded for display/editing. */
  val message: String
    get() = String(rawMessage, encoding?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8)

  /** Author line decoded as UTF-8 (git's default) for display. */
  val authorDisplay: String get() = String(authorLine.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
}

/**
 * A tree entry. [name] holds the raw name bytes decoded as ISO-8859-1 (byte-preserving).
 * [mode] uses git's object format (e.g. "100644", "40000").
 */
data class TreeEntry(val mode: String, val type: String, val sha: String, val name: String)

private val L1 = Charsets.ISO_8859_1

/** Low-level git object access using only plumbing commands. Never touches the working tree or the index. */
class GitPlumbing(private val git: GitRunner) {

  private fun text(command: String, vararg args: String): String =
    String(git.run(command, args.toList()), Charsets.UTF_8)

  fun resolveCommit(rev: String): String = text("rev-parse", "--verify", "--quiet", "$rev^{commit}").trim()
    .ifEmpty { throw SplitException("Cannot resolve commit $rev") }

  fun readCommit(rev: String): RawCommit {
    val sha = resolveCommit(rev)
    val bytes = git.run("cat-file", listOf("commit", sha))
    val raw = String(bytes, L1)
    val headerEnd = raw.indexOf("\n\n").let { if (it < 0) raw.length else it }
    val message = if (headerEnd + 2 <= raw.length) bytes.copyOfRange(headerEnd + 2, bytes.size) else ByteArray(0)
    var tree: String? = null
    val parents = ArrayList<String>()
    var author: String? = null
    var committer: String? = null
    var encoding: String? = null
    for (h in raw.substring(0, headerEnd).split('\n')) {
      when {
        h.startsWith("tree ") -> tree = h.removePrefix("tree ").trim()
        h.startsWith("parent ") -> parents += h.removePrefix("parent ").trim()
        h.startsWith("author ") -> author = h.removePrefix("author ")
        h.startsWith("committer ") -> committer = h.removePrefix("committer ")
        h.startsWith("encoding ") -> encoding = h.removePrefix("encoding ").trim()
      }
    }
    return RawCommit(
      sha,
      tree ?: throw SplitException("Commit $sha has no tree"),
      parents,
      author ?: throw SplitException("Commit $sha has no author"),
      committer ?: throw SplitException("Commit $sha has no committer"),
      encoding,
      message,
    )
  }

  fun listTree(tree: String): List<TreeEntry> {
    val out = String(git.run("ls-tree", listOf("-z", "--full-tree", tree)), L1)
    return out.split('\u0000').filter { it.isNotEmpty() }.map { rec ->
      val tab = rec.indexOf('\t')
      val (mode, type, sha) = rec.substring(0, tab).split(' ')
      TreeEntry(mode.trimStart('0').ifEmpty { "0" }, type, sha, rec.substring(tab + 1))
    }
  }

  /** Finds the blob entry for a repo-relative, '/'-separated path (UTF-8). */
  fun findEntry(tree: String, path: String): TreeEntry? {
    var current = tree
    val segments = pathSegments(path)
    for ((i, seg) in segments.withIndex()) {
      val e = listTree(current).firstOrNull { it.name == seg } ?: return null
      if (i == segments.lastIndex) return e
      if (e.type != "tree") return null
      current = e.sha
    }
    return null
  }

  fun readBlob(sha: String): ByteArray = git.run("cat-file", listOf("blob", sha))

  fun writeBlob(content: ByteArray): String =
    String(git.run("hash-object", listOf("-w", "--no-filters", "--stdin"), stdin = content), Charsets.UTF_8).trim()

  private fun writeTree(entries: List<TreeEntry>): String {
    val buf = java.io.ByteArrayOutputStream()
    for (e in entries.sortedWith(GIT_TREE_ORDER)) {
      buf.write("${e.mode} ".toByteArray(L1))
      buf.write(e.name.toByteArray(L1))
      buf.write(0)
      buf.write(hexToBytes(e.sha))
    }
    return String(git.run("hash-object", listOf("-t", "tree", "-w", "--stdin"), stdin = buf.toByteArray()), Charsets.UTF_8).trim()
  }

  /**
   * Returns a new root tree equal to [rootTree] except that [path] is set to [entry] (blob mode + sha),
   * or removed when [entry] is null. Directories left empty are dropped, missing directories are created.
   */
  fun replacePath(rootTree: String, path: String, entry: Pair<String, String>?): String =
    rewrite(rootTree, pathSegments(path), entry, isRoot = true)!!

  private fun rewrite(tree: String?, segments: List<String>, entry: Pair<String, String>?, isRoot: Boolean): String? {
    val entries = (tree?.let { listTree(it) } ?: emptyList()).toMutableList()
    val name = segments.first()
    val existing = entries.firstOrNull { it.name == name }
    entries.removeAll { it.name == name }
    if (segments.size == 1) {
      if (existing != null && existing.type == "tree") throw SplitException("Path '$name' is a directory")
      if (entry != null) entries += TreeEntry(entry.first, "blob", entry.second, name)
    }
    else {
      val subTree = existing?.takeIf { it.type == "tree" }?.sha
      if (existing != null && subTree == null && entry != null) throw SplitException("Path '$name' is not a directory")
      val newSub = rewrite(subTree, segments.drop(1), entry, isRoot = false)
      if (newSub != null) entries += TreeEntry("40000", "tree", newSub, name)
      else if (existing != null && subTree == null) entries += existing
    }
    if (entries.isEmpty() && !isRoot) return null
    return writeTree(entries)
  }

  /**
   * The committer identity git would use right now, as a raw header value ("Name <mail> 1700000000 +0100").
   *
   * Resolved like git does for `git commit`: name from `GIT_COMMITTER_NAME`, `committer.name` or `user.name`;
   * e-mail from `GIT_COMMITTER_EMAIL`, `committer.email`, `user.email` or `EMAIL`; current time and local zone.
   * (Uses `git config` instead of `git commit-tree`/`git var`, which the IDE's git integration
   * does not offer in all supported IDE versions.)
   */
  fun currentCommitterLine(
    env: (String) -> String? = System::getenv,
    now: ZonedDateTime = ZonedDateTime.now(),
  ): String {
    val config = readConfig()
    fun pick(envVar: String, vararg keys: String): String? =
      env(envVar)?.toByteArray(Charsets.UTF_8)?.let { String(it, L1) }
      ?: keys.firstNotNullOfOrNull { config[it] }
    val name = withoutCrud(pick("GIT_COMMITTER_NAME", "committer.name", "user.name").orEmpty())
    val email = withoutCrud(
      pick("GIT_COMMITTER_EMAIL", "committer.email", "user.email")
      ?: env("EMAIL")?.toByteArray(Charsets.UTF_8)?.let { String(it, L1) }
      ?: ""
    )
    if (name.isEmpty() || email.isEmpty()) {
      throw SplitException(
        "Git does not know who you are. Please set your identity, e.g.:\n" +
        "git config --global user.name \"Your Name\"\ngit config --global user.email \"you@example.com\""
      )
    }
    val offsetMinutes = now.offset.totalSeconds / 60
    val zone = String.format(Locale.ROOT, "%s%02d%02d",
                             if (offsetMinutes < 0) "-" else "+", abs(offsetMinutes) / 60, abs(offsetMinutes) % 60)
    return "$name <$email> ${now.toEpochSecond()} $zone"
  }

  /** All effective git config values (last one wins), keys as printed by git, values ISO-8859-1 decoded (byte-preserving). */
  private fun readConfig(): Map<String, String> {
    val out = String(git.run("config", listOf("--list", "-z")), L1)
    val result = HashMap<String, String>()
    for (rec in out.split('\u0000')) {
      if (rec.isEmpty()) continue
      val nl = rec.indexOf('\n')
      if (nl < 0) result[rec] = "" else result[rec.substring(0, nl)] = rec.substring(nl + 1)
    }
    return result
  }

  /** Writes a commit object directly, preserving [authorLine] byte-for-byte. */
  fun writeCommit(
    tree: String,
    parents: List<String>,
    authorLine: String,
    committerLine: String,
    message: ByteArray,
    encoding: String? = null,
  ): String {
    val buf = java.io.ByteArrayOutputStream()
    fun header(name: String, value: String) = buf.write("$name $value\n".toByteArray(L1))
    header("tree", tree)
    parents.forEach { header("parent", it) }
    header("author", authorLine)
    header("committer", committerLine)
    if (encoding != null) header("encoding", encoding)
    buf.write('\n'.code)
    buf.write(message)
    return String(git.run("hash-object", listOf("-t", "commit", "-w", "--stdin"), stdin = buf.toByteArray()), Charsets.UTF_8).trim()
  }

  /** "refs/heads/x" when on a branch, "HEAD" when detached. */
  fun headRef(): String = text("rev-parse", "--symbolic-full-name", "HEAD").trim().ifEmpty { "HEAD" }

  fun updateRef(ref: String, newValue: String, expectedOld: String, reflog: String) {
    git.run("update-ref", listOf("-m", reflog, ref, newValue, expectedOld))
  }

  /** Remote branches that already contain [sha]. */
  fun remoteBranchesContaining(sha: String): List<String> =
    text("branch", "-r", "--contains", sha, "--format=%(refname:short)").lines().map { it.trim() }.filter { it.isNotEmpty() }

  /** sha -> parents for every commit in `from..to`. */
  fun revListWithParents(from: String, to: String): Map<String, List<String>> =
    text("rev-list", "--parents", "$from..$to").lines().filter { it.isNotBlank() }.associate {
      val parts = it.trim().split(' ')
      parts.first() to parts.drop(1)
    }

  companion object {
    fun pathSegments(path: String): List<String> =
      String(path.trim('/').toByteArray(Charsets.UTF_8), L1).split('/').also {
        if (it.any(String::isEmpty)) throw SplitException("Invalid path '$path'")
      }

    /** Same normalization git applies to identity names and e-mails (`strbuf_addstr_without_crud` in ident.c). */
    fun withoutCrud(value: String): String {
      fun crud(c: Char) = c.code <= 32 || c in ",:;<>\"\\'"
      return value.trim(::crud).filterNot { it == '\n' || it == '<' || it == '>' }
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
      hex.substring(2 * i, 2 * i + 2).toInt(16).toByte()
    }

    /** Git sorts tree entries bytewise, comparing directory names as if they ended with '/'. */
    val GIT_TREE_ORDER: Comparator<TreeEntry> = Comparator { a, b ->
      val ka = if (a.type == "tree") a.name + "/" else a.name
      val kb = if (b.type == "tree") b.name + "/" else b.name
      ka.compareTo(kb) // ISO-8859-1 chars compare like unsigned bytes
    }
  }
}
