package com.listingprep

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.listingprep.ui.App
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Item id sent back by the copy panel's "Done" button. */
    private val postedId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        postedId.value = intent?.getStringExtra(EXTRA_POSTED_ID)
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
        intent.getStringExtra(EXTRA_POSTED_ID)?.let { postedId.value = it }
    }

    companion object {
        const val EXTRA_POSTED_ID = "posted_id"
    }
}
