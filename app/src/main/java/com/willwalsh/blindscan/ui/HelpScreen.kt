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
        "The Document actions button at the top right offers: Read aloud, Save to device, Share, Copy text, " +
            "Scan another document, and Discard.",
        "With TalkBack, every paragraph also has these as actions. Set your reading control to Actions, then " +
            "swipe up or down to pick one and double tap. Or open the TalkBack menu and choose Actions.",
        "Save to device saves a text file wherever you choose. Share sends the text to any app, such as email or messages.",
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
