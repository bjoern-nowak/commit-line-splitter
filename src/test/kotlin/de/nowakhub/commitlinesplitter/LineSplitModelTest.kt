package de.nowakhub.commitlinesplitter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LineSplitModelTest {
  private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
  private fun str(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

  private fun model(base: String, target: String) = LineSplitModel(b(base), b(target))

  private fun changed(m: LineSplitModel, kind: LineKind, text: String): Int =
    m.lines.indexOfFirst { it.kind == kind && it.rawText.trimEnd('\n', '\r') == text }.also { require(it >= 0) { "$kind '$text' not found" } }

  @Test fun `splitting keeps line terminators`() {
    assertEquals(listOf("a\n", "b\r\n", "c"), splitLinesKeepingTerminators(b("a\nb\r\nc")))
    assertEquals(emptyList<String>(), splitLinesKeepingTerminators(b("")))
    assertEquals(listOf("a\n"), splitLinesKeepingTerminators(b("a\n")))
  }

  @Test fun `extract nothing yields target, extract all yields base`() {
    val m = model("1\n2\n3\n4\n", "1\nTWO\n3\n4\nfive\n")
    assertEquals("1\nTWO\n3\n4\nfive\n", str(m.buildIntermediate(emptySet())))
    assertEquals("1\n2\n3\n4\n", str(m.buildIntermediate(m.changedLineIndices().toSet())))
  }

  @Test fun `extract a single added line keeps it out of the intermediate`() {
    val m = model("a\nb\n", "a\nx\nb\ny\n")
    val y = changed(m, LineKind.ADDED, "y")
    assertEquals("a\nx\nb\n", str(m.buildIntermediate(setOf(y))))
  }

  @Test fun `extracting a deletion keeps the base line`() {
    val m = model("a\nb\nc\n", "a\nc\n")
    val del = changed(m, LineKind.DELETED, "b")
    assertEquals("a\nb\nc\n", str(m.buildIntermediate(setOf(del))))
    assertEquals("a\nc\n", str(m.buildIntermediate(emptySet())))
  }

  @Test fun `partial modification inside one hunk`() {
    val m = model("keep\nold1\nold2\nend\n", "keep\nnew1\nnew2\nend\n")
    // extract replacement of old2 -> new2: keep old2 and do not add new2
    val sel = setOf(changed(m, LineKind.DELETED, "old2"), changed(m, LineKind.ADDED, "new2"))
    assertEquals("keep\nold2\nnew1\nend\n", str(m.buildIntermediate(sel)))
  }

  @Test fun `crlf and missing final newline are byte exact`() {
    val base = "a\r\nb"
    val target = "a\r\nb\r\nc"
    val m = model(base, target)
    assertArrayEquals(b(target), m.buildIntermediate(emptySet()))
    assertArrayEquals(b(base), m.buildIntermediate(m.changedLineIndices().toSet()))
  }

  @Test fun `new file from empty base`() {
    val m = model("", "x\ny\n")
    assertEquals(2, m.changedLineIndices().size)
    assertEquals("x\n", str(m.buildIntermediate(setOf(changed(m, LineKind.ADDED, "y")))))
    assertEquals("", str(m.buildIntermediate(m.changedLineIndices().toSet())))
  }

  @Test fun `hunks group adjacent changed lines`() {
    val m = model("1\n2\n3\n4\n5\n", "1\nX\n3\n4\nY\n")
    assertEquals(2, m.hunks.size)
    assertEquals(listOf("2", "X"), m.hunks[0].map { m.lines[it].rawText.trim() })
    assertEquals(listOf("5", "Y"), m.hunks[1].map { m.lines[it].rawText.trim() })
  }

  @Test fun `display text strips terminators and marks missing eol`() {
    val m = model("a", "a\n")
    val del = m.lines.single { it.kind == LineKind.DELETED }
    val add = m.lines.single { it.kind == LineKind.ADDED }
    assertEquals(false, del.hasEol)
    assertEquals(true, add.hasEol)
    assertEquals("a", m.displayText(del, Charsets.UTF_8))
  }

  @Test fun `display text decodes utf8`() {
    val m = LineSplitModel(ByteArray(0), "äöü\n".toByteArray(Charsets.UTF_8))
    assertEquals("äöü", m.displayText(m.lines.single(), Charsets.UTF_8))
  }
}
