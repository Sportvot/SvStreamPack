package io.github.thibaultbee.streampack.app

object StudioConstants {
    const val IS_TEST = false

    const val MAIN_WEBVIEW_URL_TEST = "https://studio-test.sportvot.com"
    const val MAIN_WEBVIEW_URL_PROD = "https://studio.sportvot.com"
    val MAIN_WEBVIEW_URL: String
        get() = if (IS_TEST) MAIN_WEBVIEW_URL_TEST else MAIN_WEBVIEW_URL_PROD

    const val SSO_URL_PROD =
        "https://accounts.sportvot.com/studio?redirect_url=$MAIN_WEBVIEW_URL_PROD"
    const val SSO_URL_TEST =
        "https://accounts-test.sportvot.com/studio?redirect_url=$MAIN_WEBVIEW_URL_TEST"
    val SSO_URL: String
        get() = if (IS_TEST) SSO_URL_TEST else SSO_URL_PROD

    const val TEST_URL = "https://interapis.test.sportvot.com"
    const val PROD_URL = "https://interapis.sportvot.com"
    val BASE_URL: String
        get() = if (IS_TEST) TEST_URL else PROD_URL
}
