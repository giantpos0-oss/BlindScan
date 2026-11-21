import com.willwalsh.blindscan.ocr.*
import java.io.File

/** Runs the app's real ReadingOrder over every page in suite.tsv and writes the paragraphs. */
fun main(args: Array<String>) {
    val words = File(args[1]).readLines().map { it.substringBefore(' ') }.toHashSet()
    val pages = LinkedHashMap<String, MutableList<List<String>>>()
    File(args[0]).forEachLine(Charsets.UTF_8) { l -> val p = l.split("\t"); pages.getOrPut(p[0]) { ArrayList() }.add(p) }
    val out = StringBuilder()
    val t0 = System.nanoTime()
    for ((id, rows) in pages) {
        val boxes = rows.map { TextBox(it[2].toFloat(), it[3].toFloat(), it[4].toFloat(), it[5].toFloat(), it[6].toFloat(), 1f) }
        val texts = rows.map { it[1] }
        val layout = ReadingOrder.layout(boxes)
        val rowTexts = layout.rows.map { r ->
            val sb = StringBuilder(); var glue = false
            for (i in r.boxes) {
                if (sb.isNotEmpty() && !glue) sb.append(' ')
                sb.append(texts[i]); glue = i == r.dropCap && texts[i].length == 1 && texts[i][0].isLetter()
            }
            sb.toString()
        }
        ReadingOrder.paragraphs(layout, rowTexts) { it.lowercase() in words }.forEachIndexed { i, p -> out.append("$id\t$i\t$p\n") }
    }
    File(args[2]).writeText(out.toString(), Charsets.UTF_8)
    System.err.println("${pages.size} pages in ${(System.nanoTime() - t0) / 1_000_000} ms")
}
