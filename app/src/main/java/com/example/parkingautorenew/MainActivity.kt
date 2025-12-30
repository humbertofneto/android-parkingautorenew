package com.example.parkingautorenew

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private var webView: WebView? = null
    
    private var currentUrl: String = ""
    private var captureCount: Int = 0
    private val capturedPages = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        getInfoBtn = findViewById(R.id.getInfoBtn)
        clearBtn = findViewById(R.id.clearBtn)
        infoText = findViewById(R.id.infoText)

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
            
            // Se a URL mudou, reseta o contador
            if (url != currentUrl) {
                currentUrl = url
                captureCount = 0
                capturedPages.clear()
            }
            
            infoText.text = "Loading page..."
            fetchPageInfo(url)
        }

        clearBtn.setOnClickListener {
            infoText.text = "Enter a URL and click GET INFO"
            webView?.destroy()
            webView = null
            currentUrl = ""
            captureCount = 0
            capturedPages.clear()
        }
    }

    private fun fetchPageInfo(url: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(this)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.cacheMode = WebSettings.LOAD_DEFAULT
                wv.addJavascriptInterface(PageBridge(), "Android")
                
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        Log.d("MainActivity", "Page loaded: $url")
                    // Pequeno delay para conteúdo dinâmico
                    Handler(Looper.getMainLooper()).postDelayed({
                      extractPageInfo()
                    }, 1500)
                    }
                }
                
                wv.loadUrl(url)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error: ${e.message}", e)
                infoText.text = "Error: ${e.message}"
            }
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
        
        webView?.evaluateJavascript(script, null)
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
                val footerInfo = "\n\n[Captured pages: $captureCount]\n[Click GET INFO again to capture the next page after navigating]\n[Click CLEAR to reset]"
                infoText.text = headerInfo + json + footerInfo
                
                webView?.destroy()
                webView = null
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.e("PageBridge", "Error: $message")
            runOnUiThread {
                infoText.text = "Error: $message"
                webView?.destroy()
                webView = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }
}
