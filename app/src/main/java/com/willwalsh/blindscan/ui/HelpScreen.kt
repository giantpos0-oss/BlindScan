package com.willwalsh.blindscan.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HELP = listOf(
    "Welcome" to listOf(
        "BlindScan turns print into speech. Everything happens on your phone. The app has no internet " +
            "access at all, so nothing you scan ever leaves the device, and it is free.",
        "There are four tabs along the bottom of the screen: Live mode, Scan document, Help, and Settings.",
    ),
    "Live mode" to listOf(
        "Point the camera at anything with words on it: a package, a sign, a screen, a letter. As soon as the " +
            "text is steady in view, it is read out. Move to something new and the new text is read.",
        "The controls work like a DVR. Rewind goes back one sentence. Play and Pause starts and stops speech. " +
            "Fast forward skips ahead one sentence. Using any of them pauses live reading, so new text doesn't " +
            "interrupt you while you go back over something. Press Resume live reading to carry on.",
        "Light on turns on the phone's flashlight. In dim places the light comes on by itself (you can turn " +
            "that off in Settings).",
    ),
    "Scan document" to listOf(
        "Use this for letters, bills, menus, labels and pages of a book. Lay the page on a table, hold the " +
            "phone flat with the screen facing up, about a foot above the page, top of the phone pointing away from you.",
        "The app talks you into position. Follow the prompts until you hear \"Ready. All the text is in view\" " +
            "and feel a double buzz, then press Scan document now, or press either volume button.",
        "Check view gives a full report whenever you want one: roughly how many lines of text it can see, " +
            "whether any text runs off an edge, whether the page's edges are visible, and the lighting.",
        "Open last document brings back your most recent scan.",
        "To clear a scan and start fresh, press the Scan document tab again, even while a document is showing. " +
            "Everything you scanned is cleared, ready for a new page.",
    ),
    "Shaky hands welcome" to listOf(
        "No stand needed. When you press Scan document now or a volume button, BlindScan waits for a " +
            "steady moment (at most two and a half seconds) and takes the picture right then. If your hand " +
            "moved while the picture was being taken, it quietly takes a second one and keeps the sharper.",
        "If pressing the button tips the phone or knocks the page out of view, BlindScan tells you how to " +
            "fix it and takes the picture once you've settled again.",
        "Glossy paper can reflect the light and wash out words. If words go missing on a shiny page, turn " +
            "the light off or tilt the page slightly away from the overhead light.",
        "Positioning prompts come one movement at a time. After each one there's time to move before the next.",
    ),
    "Fast results" to listOf(
        "As soon as you scan, the document opens and the first lines are read out while the rest of the page " +
            "is still being read. With Android text to speech the controls work straight away. With a screen " +
            "reader, the whole page is announced when it's finished.",
    ),
    "Several pages" to listOf(
        "Batch scanning: turn on the Batch scanning switch on the Scan document tab. Scan each page in turn; " +
            "you'll hear \"Page 1 captured\", \"Page 2 captured\" and so on, and each page is read in the " +
            "background while you turn to the next one. When you're done, press Read pages. The pages open as " +
            "one document, with a heading for each page so you can jump between them.",
        "Adding a page to a document you've already scanned: choose Add another page from Document actions, " +
            "then scan. The new page is read on its own, then added to the document.",
    ),
    "Unclear words" to listOf(
        "When a word comes out unclear, BlindScan compares what it saw with similar English words and fixes " +
            "the word only when the evidence is clearly better. Numbers are never changed into other numbers, " +
            "and words it read confidently, like names, are left alone. You can turn this off in Settings.",
    ),
    "What the positioning prompts mean" to listOf(
        "\"Lift the phone up a little\": the text runs off both sides, so move the phone higher above the page.",
        "\"Bring the phone a little closer to the page\": the text is small in the picture. Lower the phone toward the page.",
        "\"Move the phone a little to the left\" or \"to the right\": slide the phone sideways, keeping it the same height.",
        "\"Move the phone a little away from you\": slide it toward the far edge of the page, the top of the page.",
        "\"Move the phone a little toward you\": slide it toward your body, the bottom of the page.",
        "\"Hold the phone flatter, screen facing up\": the phone is tipped. Level it out over the page.",
        "\"Hold still\": the picture is blurry from movement. Keep the phone steady for a moment.",
        "\"No text in view yet\": point the camera at the page, holding it flat about a foot above it.",
    ),
    "Volume buttons" to listOf(
        "While the Scan document tab is showing the camera, either volume button takes the picture, even with " +
            "TalkBack on. On every other screen the volume buttons work normally. You can turn this off in Settings.",
    ),
    "Reading your document" to listOf(
        "When a scan finishes, the text is read straight away: by your screen reader, or by Android text to " +
            "speech with the DVR controls, whichever you chose in Settings.",
        "The Document actions button at the top right offers: Read aloud, Save to device, Share text, Copy text, " +
            "Add another page, Share scan for testing, New scan, and Discard.",
        "With TalkBack, every paragraph also has these as actions. Set your reading control to Actions, then " +
            "swipe up or down to pick one and double tap. Or open the TalkBack menu and choose Actions.",
        "Save to device saves a text file wherever you choose. Share text sends the text to any app, such as email or messages.",
        "Share scan for testing sends the original photos with a report of what was recognised, how sure the " +
            "app was, and how long it took. It's for sending a tricky scan to the developer so the reading can be improved.",
    ),
    "Tips for the best results" to listOf(
        "Flat pages read best. Smooth out folds if you can.",
        "For small print, hold the phone a little closer, wait for Ready, and hold still while it scans.",
        "Glossy paper can reflect the light. If a scan comes back patchy, try with the light off, or tilt the page slightly.",
        "Upside-down and sideways pages are fine: the app turns them the right way round by itself.",
    ),
)

@Composable
fun HelpScreen() {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { ScreenHeading("Help", Modifier.padding(start = 0.dp)) }
        items(HELP) { (title, paragraphs) ->
            Text(
                title, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
            )
            for (p in paragraphs) Text(p, fontSize = 19.sp, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}
