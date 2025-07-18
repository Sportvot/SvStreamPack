package io.github.thibaultbee.streampack.app.studio

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject
import io.github.thibaultbee.streampack.app.studio.DeepLinkParams
import androidx.core.net.toUri

class WebAppInterface(private val context: StudioActivity) {
    @JavascriptInterface
    fun postMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val data = json.optJSONObject("data")

            if(type == "START_STREAM" && data != null) {

                // If data contains a URL (connectionString), use DeepLinkParams to parse matchId, else fallback to direct extraction
                val url = data.optString("connectionString", null.toString())
                val matchId = run {
                    val uri = url.toUri()
                    DeepLinkParams.fromUri(uri).matchId
                }

                val intent = android.content.Intent(context, io.github.thibaultbee.streampack.app.ui.main.MainActivity::class.java)
                if (matchId != null) {
                    intent.putExtra("MATCH_ID", matchId)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Invalid message: $message", e)
        }
    }
} 