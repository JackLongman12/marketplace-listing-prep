package com.listingprep

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.listingprep.ui.AppViewModel
import com.listingprep.update.UpdateState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.listingprep.share.PanelSession
import com.listingprep.ui.App
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        UpdateState.appVisible = true
    }

    override fun onStop() {
        super.onStop()
        UpdateState.appVisible = false
        // Leaving the app is the moment to install a downloaded update: nothing is interrupted.
        if (!isChangingConfigurations) vm.installUpdateIfIdle()
    }

    /** Item id sent back by the copy panel's "Done" button. */
    private val postedId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Privacy: don't keep a snapshot of item photos / token setup in the Recents switcher.
        setRecentsScreenshotEnabled(false)
        postedId.value = intent?.let(::trustedPostedId)
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            val posted by postedId.collectAsState()
            MaterialTheme(colorScheme = colors) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    App(postedId = posted, onPostedHandled = { postedId.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        trustedPostedId(intent)?.let { postedId.value = it }
    }

    /** Ignores "mark as posted" requests that didn't come from our own copy panel. */
    private fun trustedPostedId(intent: Intent): String? {
        val id = intent.getStringExtra(EXTRA_POSTED_ID) ?: return null
        return id.takeIf { intent.getStringExtra(EXTRA_POSTED_NONCE) == PanelSession.nonce }
    }

    companion object {
        const val EXTRA_POSTED_ID = "posted_id"
        const val EXTRA_POSTED_NONCE = "posted_nonce"
    }
}
