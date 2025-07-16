package io.github.thibaultbee.streampack.app.ui.main

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import android.widget.FrameLayout
import android.view.ViewGroup
import android.view.Gravity

class WebViewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val aspectRatio = 16f / 9f
        val screenWidth = resources.displayMetrics.widthPixels
        val webViewHeight = (screenWidth / aspectRatio).toInt()

        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            webViewHeight,
            Gravity.CENTER
        )
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.loadUrl("https://template-engine.sportvot.com/preview/67b3175024aa9a0001282af7") // Change to your desired URL

        val frameLayout = FrameLayout(this)
        frameLayout.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        frameLayout.addView(webView)

        setContentView(frameLayout)
    }
} 