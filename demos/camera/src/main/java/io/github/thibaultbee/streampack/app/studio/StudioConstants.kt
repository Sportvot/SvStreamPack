package io.github.thibaultbee.streampack.app.studio

object StudioConstants {
    private const val IS_TEST = false

    private const val MAIN_WEBVIEW_URL_TEST = "https://studio-test.sportvot.com"
    private const val MAIN_WEBVIEW_URL_PROD = "https://studio.sportvot.com"
    val MAIN_WEBVIEW_URL: String
        get() = if (IS_TEST) MAIN_WEBVIEW_URL_TEST else MAIN_WEBVIEW_URL_PROD

    private const val SSO_URL_PROD =
        "https://accounts.sportvot.com/studio?redirect_url=$MAIN_WEBVIEW_URL_PROD"
    private const val SSO_URL_TEST =
        "https://accounts-test.sportvot.com/studio?redirect_url=$MAIN_WEBVIEW_URL_TEST"
    val SSO_URL: String
        get() = if (IS_TEST) SSO_URL_TEST else SSO_URL_PROD

    private const val TEST_URL = "https://interapis.test.sportvot.com"
    private const val PROD_URL = "https://interapis.sportvot.com"
    val BASE_URL: String
        get() = if (IS_TEST) TEST_URL else PROD_URL
}
