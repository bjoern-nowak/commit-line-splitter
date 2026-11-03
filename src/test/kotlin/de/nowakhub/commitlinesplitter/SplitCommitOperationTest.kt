package de.nowakhub.commitlinesplitter

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs the real `git` binary against a throw-away repository. */
class ProcessGitRunner(private val dir: File, private val env: Map<String, String> = emptyMap()) : GitRunner {
  override fun run(command: String, args: List<String>, stdin: ByteArray?): ByteArray {
    val pb = ProcessBuilder(listOf("git", command) + args).directory(dir)
    pb.environment().putAll(env)
    val p = pb.start()
    p.outputStream.use { if (stdin != null) it.write(stdin) }
    val err = Thread { p.errorStream.readBytes() }.also { it.start() }
    val out = p.inputStream.readBytes()
    err.join()
    val code = p.waitFor()
    if (code != 0) throw RuntimeException("git $command $args failed with $code")
    return out
  }
}

class SplitCommitOperationTest {
  private lateinit var dir: File
  private lateinit var runner: ProcessGitRunner
  private lateinit var plumbing: GitPlumbing
  private lateinit var op: SplitCommitOperation

  private fun sh(vararg cmd: String, env: Map<String, String> = emptyMap()): String {
    val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true)
    pb.environment().putAll(env)
    val p = pb.start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed:\n$out" }
    return out
  }

  private fun git(vararg args: String) = sh("git", *args).trim()

  private fun write(path: String, content: String) {
    File(dir, path).apply { parentFile.mkdirs() }.writeBytes(content.toByteArray(Charsets.UTF_8))
  }

  private fun commit(msg: String, author: String = "Alice <alice@example.com>", date: String = "1700000000 +0100"): String {
    git("add", "-A")
    sh("git", "commit", "-q", "-m", msg, "--author", author, env = mapOf("GIT_AUTHOR_DATE" to date))
    return git("rev-parse", "HEAD")
  }

  private fun show(rev: String, path: String) = git("show", "$rev:$path")
  private fun showRaw(rev: String, path: String): ByteArray {
    val p = ProcessBuilder("git", "show", "$rev:$path").directory(dir).start()
    return p.inputStream.readBytes().also { check(p.waitFor() == 0) }
  }

  @Before fun setUp() {
    dir = Files.createTempDirectory("split-lines").toFile()
    git("init", "-q", "-b", "main")
    git("config", "user.name", "Committer")
    git("config", "user.email", "committer@example.com")
    git("config", "commit.gpgsign", "false")
    runner = ProcessGitRunner(dir)
    plumbing = GitPlumbing(runner)
    op = SplitCommitOperation(plumbing)
  }

  @After fun tearDown() { dir.deleteRecursively() }

  private fun select(input: FileSplitInput, vararg addedOrDeletedTexts: String): Set<Int> =
    addedOrDeletedTexts.map { t ->
      input.model.lines.indexOfFirst { it.isChange && it.rawText.trimEnd('\n', '\r') == t }.also { require(it >= 0) { t } }
    }.toSet()

  @Test fun `splits a middle commit and keeps descendants and working tree`() {
    write("src/f.txt", "one\ntwo\nthree\nfour\n")
    write("other.txt", "x\n")
    commit("A")
    write("src/f.txt", "one\nTWO\nthree\nfour\nfive\n")
    write("other.txt", "y\n")
    val b = commit("B: several things", author = "Bob <bob@example.com>", date = "1700001000 +0200")
    write("other.txt", "z\n")
    commit("D")
    write("other.txt", "uncommitted\n") // dirty working tree must survive
    val headTreeBefore = git("rev-parse", "HEAD^{tree}")

    val input = op.loadInput(b, "src/f.txt")
    val result = op.execute(b, input, select(input, "five"), "B: several things\n", "Add five")

    assertEquals(headTreeBefore, git("rev-parse", "HEAD^{tree}"))
    assertEquals(listOf("D", "Add five", "B: several things", "A"), git("log", "--format=%s").lines())
    assertEquals("one\nTWO\nthree\nfour", show("HEAD~2", "src/f.txt"))
    assertEquals("y", show("HEAD~2", "other.txt"))
    assertEquals(git("rev-parse", "$b^{tree}"), git("rev-parse", "HEAD~1^{tree}"))
    assertEquals("Bob <bob@example.com> 1700001000 +0200", git("log", "-1", "--format=%an <%ae> %ad", "--date=raw", "HEAD~2"))
    assertEquals("Bob <bob@example.com>", git("log", "-1", "--format=%an <%ae>", "HEAD~1"))
    assertEquals(" M other.txt", sh("git", "status", "--porcelain").trimEnd())
    assertEquals(1, result.rewrittenDescendants)
    git("fsck", "--strict")

    op.undo(result)
    assertEquals(listOf("D", "B: several things", "A"), git("log", "--format=%s").lines())
  }

  @Test fun `splitting HEAD commit with deletion and modification in one hunk`() {
    write("f.txt", "keep\nold1\nold2\nend\n")
    commit("A")
    write("f.txt", "keep\nnew1\nnew2\nend\n")
    val b = commit("B")
    val input = op.loadInput(b, "f.txt")
    op.execute(b, input, select(input, "old2", "new2"), "B", "B part 2")
    assertEquals("keep\nold2\nnew1\nend", show("HEAD~1", "f.txt"))
    assertEquals("keep\nnew1\nnew2\nend", show("HEAD", "f.txt"))
    assertEquals(listOf("B part 2", "B", "A"), git("log", "--format=%s").lines())
  }

  @Test fun `new file fully extracted disappears from the original commit`() {
    write("a.txt", "a\n")
    commit("A")
    write("pkg/deep/new.txt", "n1\nn2\n")
    write("a.txt", "a2\n")
    val b = commit("B")
    val input = op.loadInput(b, "pkg/deep/new.txt")
    assertNull(input.baseContent)
    op.execute(b, input, input.model.changedLineIndices().toSet(), "B", "Add new file")
    assertEquals("", git("ls-tree", "-r", "--name-only", "HEAD~1", "pkg"))
    assertEquals("a2", show("HEAD~1", "a.txt"))
    assertEquals("n1\nn2", show("HEAD", "pkg/deep/new.txt"))
    git("fsck", "--strict")
  }

  @Test fun `partially extracted deletion of a file recreates it and its directory`() {
    write("dir/sub/gone.txt", "g1\ng2\ng3\n")
    write("dir/sub/zz.txt", "stay\n")
    write("dir/a.txt", "a\n")
    commit("A")
    git("rm", "-q", "-r", "dir/sub")
    val b = commit("B: remove sub")
    val input = op.loadInput(b, "dir/sub/gone.txt")
    assertNull(input.targetContent)
    op.execute(b, input, select(input, "g2"), "B: remove sub", "remove rest of gone.txt")
    assertEquals("g2", show("HEAD~1", "dir/sub/gone.txt"))
    assertEquals(listOf("dir/a.txt", "dir/sub/gone.txt"), git("ls-tree", "-r", "--name-only", "HEAD~1").lines())
    assertEquals(listOf("dir/a.txt"), git("ls-tree", "-r", "--name-only", "HEAD").lines())
    git("fsck", "--strict")
  }

  @Test fun `crlf and missing final newline are preserved byte exact`() {
    git("config", "core.autocrlf", "false")
    write("w.txt", "a\r\nb")
    commit("A")
    write("w.txt", "a\r\nB\r\nc")
    val b = commit("B")
    val input = op.loadInput(b, "w.txt")
    op.execute(b, input, select(input, "c"), "B", "B2")
    val reduced = String(showRaw("HEAD~1", "w.txt"), Charsets.ISO_8859_1)
    // "b" (no EOL) was replaced by "B\r\n"; the "c" addition is postponed
    assertEquals("a\r\nB\r\n", reduced)
    assertArrayEquals("a\r\nB\r\nc".toByteArray(), showRaw("HEAD", "w.txt"))
  }

  @Test fun `root commit can be split`() {
    write("r.txt", "1\n2\n")
    val a = commit("root")
    val input = op.loadInput(a, "r.txt")
    op.execute(a, input, select(input, "2"), "root", "second line")
    assertEquals(listOf("second line", "root"), git("log", "--format=%s").lines())
    assertEquals("1", show("HEAD~1", "r.txt"))
    assertEquals("", git("log", "-1", "--format=%P", "HEAD~1"))
  }

  @Test fun `merge between commit and HEAD is rejected`() {
    write("f.txt", "1\n")
    commit("A")
    write("f.txt", "1\n2\n")
    val b = commit("B")
    git("checkout", "-q", "-b", "side")
    write("s.txt", "s\n"); commit("S")
    git("checkout", "-q", "main")
    write("m.txt", "m\n"); commit("M")
    sh("git", "merge", "-q", "--no-ff", "-m", "merge", "side")
    val input = op.loadInput(b, "f.txt")
    try {
      op.execute(b, input, select(input, "2"), "B", "B2")
      fail("expected SplitException")
    }
    catch (e: SplitException) {
      assertTrue(e.message!!.contains("merge"))
    }
  }

  @Test fun `commit not in HEAD is rejected`() {
    write("f.txt", "1\n"); commit("A")
    git("checkout", "-q", "-b", "side")
    write("f.txt", "1\n2\n"); val s = commit("S")
    git("checkout", "-q", "main")
    write("g.txt", "g\n"); commit("G")
    val input = op.loadInput(s, "f.txt")
    try {
      op.execute(s, input, select(input, "2"), "S", "S2")
      fail("expected SplitException")
    }
    catch (e: SplitException) {
      assertTrue(e.message, e.message!!.contains("not part of the current branch"))
    }
  }

  @Test fun `tree order with similarly named files and dirs is valid`() {
    write("a-b.txt", "x\n"); write("a/b.txt", "y\n"); write("a.txt", "z\n")
    commit("A")
    write("a/b.txt", "y\ny2\n"); write("a-b.txt", "x2\n")
    val b = commit("B")
    val input = op.loadInput(b, "a/b.txt")
    op.execute(b, input, select(input, "y2"), "B", "B2")
    git("fsck", "--strict")
    assertEquals("y", show("HEAD~1", "a/b.txt"))
    assertEquals("x2", show("HEAD~1", "a-b.txt"))
  }

  @Test fun `non-ascii author and latin1-encoded descendant message are preserved byte exact`() {
    write("f.txt", "1\n"); commit("A")
    write("f.txt", "1\n2\n3\n"); val b = commit("B", author = "Jörg Müller <j@example.com>", date = "1700000500 -0500")
    write("g.txt", "g\n")
    git("add", "-A")
    val msgFile = File(dir.parentFile, "${dir.name}-msg.txt").apply { writeBytes("Größe\n".toByteArray(Charsets.ISO_8859_1)) }
    try { sh("git", "-c", "i18n.commitEncoding=ISO-8859-1", "commit", "-q", "-F", msgFile.path) } finally { msgFile.delete() }
    val dBefore = git("cat-file", "commit", "HEAD").substringAfter("\ncommitter ").substringAfter("\n")
    assertTrue(dBefore, dBefore.startsWith("encoding ISO-8859-1"))

    val input = op.loadInput(b, "f.txt")
    op.execute(b, input, select(input, "3"), "B", "B2")

    val bRaw = git("cat-file", "commit", "HEAD~2")
    assertTrue(bRaw, bRaw.contains("\nauthor Jörg Müller <j@example.com> 1700000500 -0500\n"))
    val dRaw = ProcessBuilder("git", "cat-file", "commit", "HEAD").directory(dir).start().inputStream.readBytes()
    val dText = String(dRaw, Charsets.ISO_8859_1)
    assertTrue(dText, dText.contains("\nencoding ISO-8859-1\n"))
    assertTrue(dText, dText.endsWith("Größe\n"))
    assertEquals("Größe", git("log", "-1", "--format=%s", "HEAD"))
    git("fsck", "--strict")
  }

  @Test fun `extracting everything of a single-file commit is rejected`() {
    write("f.txt", "1\n"); commit("A")
    write("f.txt", "1\n2\n"); val b = commit("B")
    val input = op.loadInput(b, "f.txt")
    try {
      op.execute(b, input, input.model.changedLineIndices().toSet(), "B", "B2")
      fail("expected SplitException")
    }
    catch (e: SplitException) {
      assertTrue(e.message, e.message!!.contains("empty"))
    }
    assertEquals(b, git("rev-parse", "HEAD"))
  }

  @Test fun `binary files are rejected`() {
    File(dir, "bin.dat").writeBytes(byteArrayOf(1, 0, 2)); commit("A")
    File(dir, "bin.dat").writeBytes(byteArrayOf(1, 0, 3)); val b = commit("B")
    try {
      op.loadInput(b, "bin.dat")
      fail("expected SplitException")
    }
    catch (e: SplitException) {
      assertTrue(e.message!!.contains("Binary"))
    }
  }

  @Test fun `remote branch containment is reported`() {
    write("f.txt", "1\n"); val a = commit("A")
    git("update-ref", "refs/remotes/origin/main", a)
    assertEquals(listOf("origin/main"), plumbing.remoteBranchesContaining(a))
  }

  // --- committer identity (must match what `git commit` would record) ---

  private val identRegex = Regex("^(.*) (\\d+) ([+-]\\d{4})$")

  /** `git var GIT_COMMITTER_IDENT`, ISO-8859-1 decoded like our raw header values. */
  private fun gitIdent(env: Map<String, String> = emptyMap()): String {
    val pb = ProcessBuilder("git", "var", "GIT_COMMITTER_IDENT").directory(dir)
    pb.environment().putAll(env)
    val p = pb.start()
    val out = p.inputStream.readBytes()
    check(p.waitFor() == 0)
    return String(out, Charsets.ISO_8859_1).trim()
  }

  private fun assertSameIdent(expected: String, actual: String) {
    val e = identRegex.matchEntire(expected)!!.groupValues
    val a = identRegex.matchEntire(actual)!!.groupValues
    assertEquals("name and e-mail", e[1], a[1])
    assertEquals("time zone", e[3], a[3])
    assertTrue("timestamp $actual vs $expected", kotlin.math.abs(e[2].toLong() - a[2].toLong()) <= 60)
  }

  @Test fun `committer identity matches git`() {
    assertSameIdent(gitIdent(), plumbing.currentCommitterLine())
  }

  @Test fun `committer identity honours committer config, environment, crud and non-ASCII names like git`() {
    git("config", "user.name", " .Björn Nowak, ")
    assertSameIdent(gitIdent(), plumbing.currentCommitterLine())

    git("config", "committer.name", "Commit Person")
    git("config", "committer.email", "<commit@example.com>")
    assertSameIdent(gitIdent(), plumbing.currentCommitterLine())

    val env = mapOf("GIT_COMMITTER_NAME" to "Env Name", "GIT_COMMITTER_EMAIL" to "env@example.com")
    assertSameIdent(gitIdent(env), plumbing.currentCommitterLine(env = { env[it] ?: System.getenv(it) }))
  }

  @Test fun `committer time zone is formatted like git`() {
    val now = java.time.ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, java.time.ZoneOffset.ofHoursMinutes(-3, -30))
    val line = plumbing.currentCommitterLine(env = { null }, now = now)
    assertEquals("Committer <committer@example.com> ${now.toEpochSecond()} -0330", line)
  }

  @Test fun `missing identity is reported`() {
    val home = Files.createTempDirectory("split-lines-home").toFile()
    try {
      git("config", "--unset", "user.name")
      // isolate from the global/system git config of the machine running the tests
      val isolated = ProcessGitRunner(dir, mapOf("HOME" to home.path, "XDG_CONFIG_HOME" to home.path, "GIT_CONFIG_NOSYSTEM" to "1"))
      GitPlumbing(isolated).currentCommitterLine(env = { null })
      fail("expected SplitException")
    }
    catch (e: SplitException) {
      assertTrue(e.message, e.message!!.contains("user.name"))
    }
    finally {
      home.deleteRecursively()
    }
  }
}
