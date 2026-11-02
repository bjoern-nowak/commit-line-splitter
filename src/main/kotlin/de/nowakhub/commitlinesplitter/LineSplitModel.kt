package de.nowakhub.commitlinesplitter

import com.intellij.util.diff.Diff
import java.nio.charset.Charset

enum class LineKind { CONTEXT, DELETED, ADDED }

/**
 * One line of the unified view. [rawText] is the line's exact bytes (incl. terminator) decoded as ISO-8859-1,
 * which maps bytes 1:1 to chars, so re-encoding gives back the original bytes.
 */
data class DiffLine(
  val kind: LineKind,
  val rawText: String,
  /** 0-based line number in the parent version, or -1 for added lines. */
  val baseLine: Int,
  /** 0-based line number in the commit version, or -1 for deleted lines. */
  val targetLine: Int,
) {
  val hasEol: Boolean get() = rawText.endsWith("\n")
  val isChange: Boolean get() = kind != LineKind.CONTEXT
}

fun splitLinesKeepingTerminators(bytes: ByteArray): List<String> {
  val text = String(bytes, Charsets.ISO_8859_1)
  val result = ArrayList<String>()
  var start = 0
  while (start < text.length) {
    val nl = text.indexOf('\n', start)
    val end = if (nl < 0) text.length else nl + 1
    result += text.substring(start, end)
    start = end
  }
  return result
}

/**
 * Line-level diff between the parent version ([base]) and the commit version ([target]) of one file,
 * laid out as a unified diff (within a block: deleted lines first, then added lines – like `git diff`).
 *
 * A selection is a set of indices into [lines] that point at changed lines which should be *extracted*,
 * i.e. postponed into the new follow-up commit. [buildIntermediate] returns the file content for the
 * reduced original commit; the follow-up commit always ends at [target].
 */
class LineSplitModel(base: ByteArray, target: ByteArray) {
  val lines: List<DiffLine>

  /** Groups of adjacent changed line indices. */
  val hunks: List<List<Int>>

  init {
    val baseLines = splitLinesKeepingTerminators(base)
    val targetLines = splitLinesKeepingTerminators(target)
    val out = ArrayList<DiffLine>(maxOf(baseLines.size, targetLines.size) + 16)

    var b = 0
    var t = 0
    fun emitContextUntil(baseEnd: Int) {
      while (b < baseEnd) {
        out += DiffLine(LineKind.CONTEXT, baseLines[b], b, t)
        b++; t++
      }
    }

    var change: Diff.Change? = Diff.buildChanges(baseLines.toTypedArray<Any>(), targetLines.toTypedArray<Any>())
    while (change != null) {
      emitContextUntil(change.line0)
      repeat(change.deleted) { out += DiffLine(LineKind.DELETED, baseLines[b], b, -1); b++ }
      repeat(change.inserted) { out += DiffLine(LineKind.ADDED, targetLines[t], -1, t); t++ }
      change = change.link
    }
    emitContextUntil(baseLines.size)
    check(t == targetLines.size) { "Diff did not consume the whole target ($t of ${targetLines.size})" }
    lines = out

    val groups = ArrayList<List<Int>>()
    var current = ArrayList<Int>()
    for ((i, l) in lines.withIndex()) {
      if (l.isChange) current += i
      else if (current.isNotEmpty()) { groups += current; current = ArrayList() }
    }
    if (current.isNotEmpty()) groups += current
    hunks = groups
  }

  fun changedLineIndices(): List<Int> = lines.indices.filter { lines[it].isChange }

  fun hunkOf(lineIndex: Int): List<Int>? = hunks.firstOrNull { lineIndex in it }

  /** Content of the file for the reduced original commit: everything except the extracted lines applied. */
  fun buildIntermediate(extracted: Set<Int>): ByteArray {
    val sb = StringBuilder()
    for ((i, l) in lines.withIndex()) {
      val keep = when (l.kind) {
        LineKind.CONTEXT -> true
        LineKind.DELETED -> i in extracted   // deletion postponed -> line still present
        LineKind.ADDED -> i !in extracted    // addition applied now
      }
      if (keep) sb.append(l.rawText)
    }
    return sb.toString().toByteArray(Charsets.ISO_8859_1)
  }

  /** Human-readable line text (decoded with the file charset, terminator removed). */
  fun displayText(line: DiffLine, charset: Charset): String {
    val decoded = String(line.rawText.toByteArray(Charsets.ISO_8859_1), charset)
    return decoded.removeSuffix("\n").removeSuffix("\r")
  }
}
