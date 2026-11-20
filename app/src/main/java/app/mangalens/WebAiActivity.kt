package app.mangalens

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/** Shows a chat website so the user can sign in (or pass a human check); the login is kept in the shared cookie jar. */
class WebAiActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SITE = "site"
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = WebAi.byId(intent.getStringExtra(EXTRA_SITE) ?: "")
        if (site == null) {
            finish()
            return
        }
        val prefs = Prefs(this)
        fun dp(v: Int) = Ui.dp(this, v)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(this@WebAiActivity, R.color.bg))
            fitsSystemWindows = true
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(12), dp(6))
        }
        header.addView(Ui.iconButton(this, R.drawable.ic_back, "Back") { finish() })
        header.addView(
            Ui.singleLine(Ui.text(this, "Sign in: ${site.title}", 17f, R.color.on_bg, true)).apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(Pill(this, true).apply {
            set(if (site.loginNeeded) "I'm signed in" else "Done", null)
            onTap {
                prefs.setWebSigned(site.id, true)
                WebAi.flushCookies()
                finish()
            }
        })
        root.addView(header)
        root.addView(
            Ui.text(
                this,
                "Sign in or pass any check, send one test message to see it answers, then tap the button. " + site.note,
                12f, R.color.on_muted,
            ).apply { setPadding(dp(16), 0, dp(16), dp(8)) },
        )
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(3)))

        web = WebView(this)
        WebAi.configure(web)
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }
        }
        web.webViewClient = WebViewClient()
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        web.loadUrl(site.url)
    }

    override fun onPause() {
        super.onPause()
        WebAi.flushCookies()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
