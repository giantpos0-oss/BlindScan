package com.willwalsh.blindscan.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.willwalsh.blindscan.LocalSettings
import com.willwalsh.blindscan.Voice

/**
 * The user guide and release notes live in the repository's docs folder, as Markdown:
 * docs/user-guide.md and docs/release-notes.md. Those same files are bundled into the app
 * (see app/build.gradle.kts), shown on this tab, and opened in the browser from GitHub,
 * so there is only ever one copy to keep up to date.
 */
private const val DOCS_URL = "https://github.com/giantpos0-oss/BlindScan/blob/main/docs/"
const val USER_GUIDE_URL = DOCS_URL + "user-guide.md"
const val RELEASE_NOTES_URL = DOCS_URL + "release-notes.md"

@Composable
fun HelpScreen(onUp: () -> Unit) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val announcer = LocalAnnouncer.current
    val view = LocalView.current
    val blocks = remember { parseGuide(readAsset(context, "user-guide.md")) }

    fun open(url: String) {
        if (!openInBrowser(context, url)) {
            announcer.say(
                "No web browser found. The user guide is below, on this screen.",
                view, settings.documentVoice == Voice.SCREEN_READER,
            )
        }
    }

    // A plain Column, not a lazy list, so every heading is there for heading navigation.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        TopBar("Help", onUp = onUp)
        BigButton("Open user guide in browser", onClick = { open(USER_GUIDE_URL) })
        BigButton("Open release notes in browser", onClick = { open(RELEASE_NOTES_URL) })
        Text(
            "BlindScan version ${appVersion(context)}",
            fontSize = 17.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp)) {
            for (b in blocks) GuideBlockView(b)
        }
    }
}

/** Opens [url] in Chrome or whichever browser is the default. False if there's no browser. */
fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

private fun readAsset(context: Context, name: String): String =
    runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }
        .getOrDefault("# Help\n\nThe user guide couldn't be loaded. Use Open user guide in browser above.")

private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

/** One piece of the guide: a heading (level 1 to 3), a paragraph, or a bullet point. */
sealed class GuideBlock {
    data class Heading(val level: Int, val text: String) : GuideBlock()
    data class Paragraph(val text: String) : GuideBlock()
    data class Bullet(val text: String) : GuideBlock()
}

/**
 * Reads the simple Markdown the guide is written in: # headings, - bullet points, and
 * paragraphs separated by blank lines. Bold markers, code marks and link syntax are dropped
 * so screen readers don't speak them.
 */
fun parseGuide(markdown: String): List<GuideBlock> {
    val out = ArrayList<GuideBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out.add(GuideBlock.Paragraph(clean(para.toString())))
        para.clear()
    }
    for (raw in markdown.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("#") -> {
                flush()
                val level = line.takeWhile { it == '#' }.length
                out.add(GuideBlock.Heading(level.coerceAtMost(3), clean(line.drop(level))))
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flush()
                out.add(GuideBlock.Bullet(clean(line.drop(2))))
            }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(line) }
        }
    }
    flush()
    return out
}

private val LINK = Regex("""\[([^\]]+)]\([^)]+\)""")

private fun clean(s: String): String =
    s.replace(LINK, "$1").replace("**", "").replace("`", "").trim()

@Composable
private fun GuideBlockView(b: GuideBlock) {
    when (b) {
        is GuideBlock.Heading -> Text(
            b.text,
            fontSize = when (b.level) { 1 -> 26.sp; 2 -> 23.sp; else -> 21.sp },
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).semantics { heading() },
        )
        is GuideBlock.Paragraph -> Text(b.text, fontSize = 19.sp, modifier = Modifier.padding(vertical = 4.dp))
        is GuideBlock.Bullet -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            // The bullet is for sighted and low-vision readers only; screen readers just read the text.
            Text("•", fontSize = 19.sp, modifier = Modifier.padding(start = 4.dp, end = 10.dp).clearAndSetSemantics { })
            Text(b.text, fontSize = 19.sp, modifier = Modifier.weight(1f))
        }
    }
}
