package io.github.thibaultbee.streampack.app.studio

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

class WebAppInterface(private val context: Context) {
    @JavascriptInterface
    fun postMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            val data = json.opt("data")
            Log.d("WebAppInterface", "Received message: type=$type, data=$data")
        } catch (e: Exception) {
            Log.e("WebAppInterface", "Invalid message: $message", e)
        }
    }
} 