import com.willwalsh.blindscan.ocr.*
import java.io.File
fun main(args: Array<String>) {
    val words = File("/home/claude/scan/test/words_en.txt").readLines().map { it.substringBefore(' ') }.toHashSet()
    for (f in args) {
        val rows = File(f).readLines().map { it.split("\t") }
        val boxes = rows.map { TextBox(it[1].toFloat(), it[2].toFloat(), it[3].toFloat(), it[4].toFloat(), it[5].toFloat(), 1f) }
        val idx = boxes.indices.shuffled()
        val sb = idx.map { boxes[it] }; val st = idx.map { rows[it][0] }
        val layout = ReadingOrder.layout(sb)
        val rowTexts = layout.rows.map { r -> r.boxes.joinToString(" ") { st[it] } }
        println("=== $f (tilt ${"%.1f".format(Math.toDegrees(layout.tilt.toDouble()))} degrees)")
        ReadingOrder.paragraphs(layout, rowTexts) { it.lowercase() in words }.forEachIndexed { i, p -> println("[${i + 1}] $p") }
    }
}
