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

class MainActivity : AppCompatActivity() {
    private lateinit var urlInput: EditText
    private lateinit var getInfoBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var infoText: TextView
    private lateinit var webView: WebView
    
    private var currentUrl: String = ""
    private var captureCount: Int = 0
    private val capturedPages = mutableListOf<String>()
    private var isWebViewInitialized: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        getInfoBtn = findViewById(R.id.getInfoBtn)
        clearBtn = findViewById(R.id.clearBtn)
        infoText = findViewById(R.id.infoText)
        
        // Criar WebView uma única vez
        initializeWebView()
        
        // Forçar teclado quando EditText recebe foco
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showKeyboard()
            }
        }

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
            
            // Se a URL mudou, reseta o contador e carrega nova URL
            if (url != currentUrl) {
                currentUrl = url
                captureCount = 0
                capturedPages.clear()
                infoText.text = "Loading page..."
                webView.loadUrl(url)
                // Esperar a página carregar antes de capturar
                Handler(Looper.getMainLooper()).postDelayed({
                    extractPageInfo()
                }, 2000)
            } else {
                // Mesma URL: só captura o DOM atual (pode estar em página diferente da SPA)
                infoText.text = "Capturing current page state..."
                Handler(Looper.getMainLooper()).postDelayed({
                    extractPageInfo()
                }, 500)
            }
        }

        clearBtn.setOnClickListener {
            infoText.text = "Enter a URL and click GET INFO"
            currentUrl = ""
            captureCount = 0
            capturedPages.clear()
            webView.clearHistory()
            webView.loadUrl("about:blank")
        }
    }
    
    private fun initializeWebView() {
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.addJavascriptInterface(PageBridge(), "Android")
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d("MainActivity", "Page loaded: $url")
            }
        }
        
        isWebViewInitialized = true
    }
    
    private fun showKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(urlInput, InputMethodManager.SHOW_IMPLICIT)
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
        
        webView.evaluateJavascript(script, null)
    }

    inner class PageBridge {
        @JavascriptInterface
        fun onPageInfo(json: String) {
            Log.d("PageBridge", "Received page ${captureCount + 1}: $json")
            runOnUiThread {
                captureCount++
                capturedPages.add(json)
                
                // Mostra o JSON com informação de página e total capturado
                val headerInfo = "=== PAGE $captureCount ===\n\n"
                val footerInfo = "\n\n[Captured pages: $captureCount]\n[Navigate in the webpage, then click GET INFO to capture next page]\n[Click CLEAR to reset]"
                infoText.text = headerInfo + json + footerInfo
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.e("PageBridge", "Error: $message")
            runOnUiThread {
                infoText.text = "Error: $message"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
