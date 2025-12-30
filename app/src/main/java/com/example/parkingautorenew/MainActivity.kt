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
        Log.d("MainActivity", "=== onCreate() START ===")
        
        setContentView(R.layout.activity_main)
        Log.d("MainActivity", "Layout inflated from activity_main.xml")

        urlInput = findViewById(R.id.urlInput)
        getInfoBtn = findViewById(R.id.getInfoBtn)
        clearBtn = findViewById(R.id.clearBtn)
        infoText = findViewById(R.id.infoTextView)
        webView = findViewById(R.id.webView)
        Log.d("MainActivity", "All UI elements found and bound")
        
        // Configurar WebView que já está no layout
        initializeWebView()
        
        // Forçar teclado quando EditText recebe foco
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                Log.d("MainActivity", "urlInput gained focus")
                showKeyboard()
            }
        }
        Log.d("MainActivity", "Focus listener attached to urlInput")

        getInfoBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            Log.d("MainActivity", "GET INFO clicked - URL: $url")
            
            if (url.isEmpty()) {
                infoText.text = "Please enter a URL"
                Log.w("MainActivity", "URL is empty")
                return@setOnClickListener
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                infoText.text = "URL must start with http:// or https://"
                Log.w("MainActivity", "Invalid URL format: $url")
                return@setOnClickListener
            }
            
            // Se a URL mudou, reseta o contador e carrega nova URL
            if (url != currentUrl) {
                Log.d("MainActivity", "New URL detected. Loading: $url")
                currentUrl = url
                captureCount = 0
                capturedPages.clear()
                infoText.text = "Loading page..."
                webView.loadUrl(url)
                Log.d("MainActivity", "loadUrl() called for: $url")
                // Esperar a página carregar antes de capturar
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d("MainActivity", "Delay 2000ms completed, calling extractPageInfo()")
                    extractPageInfo()
                }, 2000)
            } else {
                // Mesma URL: só captura o DOM atual (pode estar em página diferente da SPA)
                Log.d("MainActivity", "Same URL. Capturing current DOM state...")
                infoText.text = "Capturing current page state..."
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d("MainActivity", "Delay 500ms completed, calling extractPageInfo()")
                    extractPageInfo()
                }, 500)
            }
        }

        clearBtn.setOnClickListener {
            Log.d("MainActivity", "CLEAR clicked - Resetting state")
            infoText.text = "Enter a URL and click GET INFO"
            urlInput.text.clear()
            currentUrl = ""
            captureCount = 0
            capturedPages.clear()
            webView.clearHistory()
            webView.loadUrl("about:blank")
        }
    }
    
    private fun initializeWebView() {
        Log.d("MainActivity", "=== initializeWebView() START ===")
        Log.d("MainActivity", "Configuring WebView from layout")
        
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            Log.d("MainActivity", "WebView settings applied: JS enabled=$javaScriptEnabled, DOM Storage enabled=$domStorageEnabled")
        }
        
        webView.addJavascriptInterface(PageBridge(), "Android")
        Log.d("MainActivity", "PageBridge interface added to WebView")
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("MainActivity", "onPageStarted: $url")
            }
            
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d("MainActivity", "onPageFinished: $url - Ready to extract data")
                infoText.text = "Page loaded. Click GET INFO to capture."
            }
            
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                Log.e("MainActivity", "WebView error - URL: ${request?.url}, Error code: ${error?.errorCode}, Description: ${error?.description}")
                infoText.text = "Error: ${error?.description}"
            }
        }
        
        isWebViewInitialized = true
        Log.d("MainActivity", "=== initializeWebView() COMPLETE ===")
    }
    
    private fun showKeyboard() {
        Log.d("MainActivity", "showKeyboard() called")
        Log.d("MainActivity", "urlInput focused: ${urlInput.isFocused}, hasFocus: ${urlInput.hasFocus()}")
        
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        Log.d("MainActivity", "InputMethodManager obtained")
        
        val result = imm.showSoftInput(urlInput, InputMethodManager.SHOW_IMPLICIT)
        Log.d("MainActivity", "showSoftInput result: $result (true = shown, false = hidden)")
        
        // Try alternative approach
        urlInput.post {
            imm.showSoftInput(urlInput, InputMethodManager.SHOW_FORCED)
            Log.d("MainActivity", "Alternative showSoftInput called with SHOW_FORCED")
        }
    }

    private fun extractPageInfo() {
        Log.d("MainActivity", "=== extractPageInfo() START - Page attempt #${captureCount + 1} ===")
        Log.d("MainActivity", "isWebViewInitialized=$isWebViewInitialized, webView existence=${::webView.isInitialized}")
        
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
                } else {
                  if (typeof Android === 'undefined') {
                    console.error('Android bridge not found');
                  } else if (!Android.onPageInfo) {
                    console.error('Android.onPageInfo method not found');
                  }
                }
              } catch(e) {
                if (typeof Android !== 'undefined' && Android.onError) {
                  Android.onError(e.message || 'Unknown error');
                }
              }
            })();
        """.trimIndent()
        
        Log.d("MainActivity", "Calling webView.evaluateJavascript()...")
        webView.evaluateJavascript(script) { result ->
            Log.d("MainActivity", "evaluateJavascript callback - Result: $result")
        }
        Log.d("MainActivity", "=== extractPageInfo() END ===")
    }

    inner class PageBridge {
        @JavascriptInterface
        fun onPageInfo(json: String) {
            Log.d("PageBridge", "=== onPageInfo RECEIVED ===")
            Log.d("PageBridge", "JSON length: ${json.length} chars")
            Log.d("PageBridge", "First 200 chars: ${json.take(200)}")
            Log.d("PageBridge", "Full JSON: $json")
            
            runOnUiThread {
                captureCount++
                capturedPages.add(json)
                Log.d("PageBridge", "captureCount incremented to: $captureCount")
                
                // Mostra o JSON com informação de página e total capturado
                val headerInfo = "=== PAGE $captureCount ===\n\n"
                val footerInfo = "\n\n[Captured pages: $captureCount]\n[Navigate in the webpage, then click GET INFO to capture next page]\n[Click CLEAR to reset]"
                infoText.text = headerInfo + json + footerInfo
                
                Log.d("PageBridge", "UI updated with captured page #$captureCount")
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.e("PageBridge", "=== onError RECEIVED ===")
            Log.e("PageBridge", "Error message: $message")
            
            runOnUiThread {
                infoText.text = "Error: $message"
                Log.e("PageBridge", "UI updated with error message")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
