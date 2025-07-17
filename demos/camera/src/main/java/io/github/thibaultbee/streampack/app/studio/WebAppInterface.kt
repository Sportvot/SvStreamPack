package io.github.thibaultbee.streampack.app.studio

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

class WebAppInterface(private val context: StudioActivity) {
    @JavascriptInterface
    fun postMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val data = json.optJSONObject("data")

            if(type == "START_STREAM" && data != null) {
                Log.d("WebAppInterface", "Received message: type=$type, data=$data")

                val matchId = data.optString("matchId")
                val intent = android.content.Intent(context, io.github.thibaultbee.streampack.app.ui.main.MainActivity::class.java)
                intent.putExtra("MATCH_ID", matchId)

                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Invalid message: $message", e)
        }
    }
} 