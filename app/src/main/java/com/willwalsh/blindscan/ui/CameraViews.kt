package com.willwalsh.blindscan.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.willwalsh.blindscan.camera.CameraHolder

/** The camera picture. Hidden from the screen reader: everything it shows is spoken instead. */
@Composable
fun CameraPreview(holder: CameraHolder, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black).clearAndSetSemantics { }) {
        AndroidView(
            factory = {
                (holder.previewView.parent as? android.view.ViewGroup)?.removeView(holder.previewView)
                holder.previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Shows [content] once camera permission is granted; otherwise explains and asks. */
@Composable
fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) {
        content()
    } else {
        Column(Modifier.padding(16.dp)) {
            Text(
                "BlindScan needs the camera to read print. Pictures never leave your phone: " +
                    "this app has no internet access at all.",
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            BigButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
        }
    }
}
