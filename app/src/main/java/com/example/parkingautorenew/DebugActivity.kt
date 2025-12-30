package com.example.parkingautorenew

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DebugActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var getInfoBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var infoText: TextView
    private lateinit var webView: WebView
    
    private var currentUrl: String = ""
    private var captureCount: Int = 0
    private val capturedPages = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        urlInput = findViewById(R.id.urlInput)
        getInfoBtn = findViewById(R.id.getInfoBtn)
        clearBtn = findViewById(R.id.clearBtn)
        infoText = findViewById(R.id.infoText)
        webView = findViewById(R.id.webView)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Debug Mode"
        
        initializeWebView()
        setupListeners()
    }

    private fun initializeWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        
        webView.addJavascriptInterface(PageBridge(), "Android")
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                infoText.text = "Page loaded. Click GET INFO to capture."
            }
        }
    }

    private fun setupListeners() {
        getInfoBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            
            if (url.isEmpty()) {
                infoText.text = "Please enter a URL"
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                infoText.text = "URL must start with http:// or https://"
                return@setOnClickListener
            }
            
            if (url != currentUrl) {
                currentUrl = url
                captureCount = 0
                capturedPages.clear()
                infoText.text = "Loading page..."
                webView.loadUrl(url)
                Handler(Looper.getMainLooper()).postDelayed({
                    extractPageInfo()
                }, 2000)
            } else {
                infoText.text = "Capturing current page state..."
                Handler(Looper.getMainLooper()).postDelayed({
                    extractPageInfo()
                }, 500)
            }
        }

        clearBtn.setOnClickListener {
            infoText.text = "Enter a URL and click GET INFO"
            urlInput.text.clear()
            currentUrl = ""
            captureCount = 0
            capturedPages.clear()
            webView.clearHistory()
            webView.loadUrl("about:blank")
        }
    }

    private fun extractPageInfo() {
        val script = """
            (function(){
              try {
                const inputs = Array.from(document.querySelectorAll('input'));
                const buttons = Array.from(document.querySelectorAll('button, a[role="button"], input[type="submit"], input[type="button"]'));
                const selects = Array.from(document.querySelectorAll('select'));
                
                const info = {
                  page: ${captureCount + 1},
                  title: document.title,
                  url: window.location.href,
                  inputs: inputs.map(i => ({
                    type: i.type || 'text',
                    placeholder: i.placeholder || '',
                    name: i.name || '',
                    id: i.id || '',
                    value: i.value || ''
                  })),
                  buttons: buttons.map(b => ({
                    text: (b.innerText || b.value || b.textContent || '').trim(),
                    id: b.id || '',
                    className: b.className || ''
                  })),
                  selects: selects.map(s => ({
                    name: s.name || '',
                    id: s.id || '',
                    options: Array.from(s.options).map(o => o.text)
                  }))
                };
                
                if (typeof Android !== 'undefined' && Android.onPageInfo) {
                  Android.onPageInfo(JSON.stringify(info, null, 2));
                }
              } catch(e) {
                if (typeof Android !== 'undefined' && Android.onError) {
                  Android.onError(e.message || 'Unknown error');
                }
              }
            })();
        """.trimIndent()
        
        webView.evaluateJavascript(script) { result ->
            Log.d("DebugActivity", "JavaScript result: $result")
        }
    }

    inner class PageBridge {
        @JavascriptInterface
        fun onPageInfo(json: String) {
            runOnUiThread {
                captureCount++
                capturedPages.add(json)
                
                val headerInfo = "=== PAGE $captureCount ===\n\n"
                val footerInfo = "\n\n[Captured pages: $captureCount]"
                infoText.text = headerInfo + json + footerInfo
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                infoText.text = "Error: $message"
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
