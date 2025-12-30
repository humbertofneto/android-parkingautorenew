package com.example.parkingautorenew

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient

class ParkingAutomationManager(
    private val webView: WebView,
    private val onSuccess: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "ParkingAutomation"
        private const val URL = "https://www.offstreet.io/location/LWLN9BUO"
        private const val REGION = "Alberta"
        private const val LOAD_DELAY = 2000L
        private const val STEP_DELAY = 1000L
    }

    private var currentPage = 1
    private var parkingDuration = "1 Hour"
    private var isExecuting = false

    fun start(plate: String, duration: String) {
        if (isExecuting) {
            Log.w(TAG, "Automation already running")
            return
        }

        Log.d(TAG, "=== Starting automation ===")
        Log.d(TAG, "Plate: $plate, Duration: $duration")

        isExecuting = true
        parkingDuration = duration
        currentPage = 1

        setupWebViewClient()
        webView.loadUrl(URL)
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished loading")
                
                // Aguardar um pouco para garantir que a página está renderizada
                Handler(Looper.getMainLooper()).postDelayed({
                    captureAndProcessPage()
                }, LOAD_DELAY)
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "WebView error: ${error?.description}")
                isExecuting = false
                onError("Erro ao carregar a página: ${error?.description}")
            }
        }
    }

    private fun captureAndProcessPage() {
        Log.d(TAG, "Capturing current page")

        val script = """
            (function(){
              try {
                // Detectar em qual página estamos baseado no conteúdo
                const title = document.title;
                const bodyText = document.body ? document.body.innerText : '';
                
                // Verificar elementos específicos de cada página
                const hasPlateInput = document.getElementById('plate') !== null;
                const hasDurationButtons = Array.from(document.querySelectorAll('button')).some(b => 
                  b.textContent.includes('Hour Parking')
                );
                const hasRegisterButton = Array.from(document.querySelectorAll('button')).some(b => 
                  b.textContent.toUpperCase() === 'REGISTER'
                );
                const hasEmailInput = document.getElementById('email') !== null;
                
                let detectedPage = 1;
                
                if (hasEmailInput) {
                  detectedPage = 5;
                } else if (hasRegisterButton) {
                  detectedPage = 4;
                } else if (hasDurationButtons) {
                  detectedPage = 3;
                } else if (hasPlateInput) {
                  detectedPage = 2;
                } else {
                  detectedPage = 1;
                }
                
                const info = {
                  page: detectedPage,
                  title: title,
                  url: window.location.href
                };
                
                return JSON.stringify(info);
              } catch(e) {
                return JSON.stringify({page: 0, error: e.message});
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Page detection result: $result")
            
            try {
                // Parse o JSON retornado
                val jsonResult = result?.trim('"')?.replace("\\", "") ?: "{}"
                Log.d(TAG, "Parsed result: $jsonResult")
                
                // Extrair o número da página do resultado
                val pageMatch = Regex(""""page":(\d+)""").find(jsonResult)
                if (pageMatch != null) {
                    val pageNum = pageMatch.groupValues[1].toInt()
                    currentPage = pageNum
                    Log.d(TAG, "Detected page: $currentPage")
                    onPageReady(currentPage)
                } else {
                    Log.e(TAG, "Could not detect page number")
                    onError("Não foi possível detectar a página atual")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing page result: ${e.message}")
                onError("Erro ao processar resposta: ${e.message}")
            }
        }
    }

    fun onPageReady(pageNumber: Int) {
        Log.d(TAG, "Page $pageNumber is ready")
        currentPage = pageNumber

        Handler(Looper.getMainLooper()).postDelayed({
            when (currentPage) {
                1 -> handlePage1()
                2 -> handlePage2()
                3 -> handlePage3()
                4 -> handlePage4()
                5 -> handlePage5()
                else -> {
                    Log.d(TAG, "Unknown page: $currentPage")
                    isExecuting = false
                    onError("Página desconhecida: $currentPage")
                }
            }
        }, STEP_DELAY)
    }

    private fun handlePage1() {
        Log.d(TAG, "Handling Page 1 (Welcome)")
        // Página 1 é apenas apresentação, precisamos esperar que o usuário/app clique algo
        // Ou detectar se há um botão para começar
        // Por enquanto, só aguardar um pouco e capturar próxima página
        Handler(Looper.getMainLooper()).postDelayed({
            currentPage = 2
            captureAndProcessPage()
        }, LOAD_DELAY)
    }

    private fun handlePage2() {
        Log.d(TAG, "Handling Page 2 (Vehicle Info)")
        
        val licensePlate = webView.getTag() as? String ?: "ABC1234"
        
        val script = """
            (function(){
              try {
                // Preencher placa
                const plateInput = document.getElementById('plate');
                if (plateInput) {
                  plateInput.value = '$licensePlate';
                  plateInput.dispatchEvent(new Event('input', { bubbles: true }));
                  plateInput.dispatchEvent(new Event('change', { bubbles: true }));
                }
                
                // Selecionar estado Alberta
                const regionSelect = document.getElementById('region');
                if (regionSelect) {
                  regionSelect.value = 'Alberta';
                  regionSelect.dispatchEvent(new Event('change', { bubbles: true }));
                }
                
                // Marcar "Remember Plate"
                const rememberCheckbox = document.getElementById('rememberPlate');
                if (rememberCheckbox) {
                  rememberCheckbox.checked = true;
                  rememberCheckbox.dispatchEvent(new Event('change', { bubbles: true }));
                }
                
                // Clicar botão Next
                const nextButton = Array.from(document.querySelectorAll('button')).find(b => 
                  b.textContent.toLowerCase() === 'next'
                );
                if (nextButton) {
                  nextButton.click();
                }
                
                if (typeof Android !== 'undefined' && Android.onStepComplete) {
                  Android.onStepComplete('page2_filled');
                }
              } catch(e) {
                if (typeof Android !== 'undefined' && Android.onError) {
                  Android.onError('Erro page 2: ' + e.message);
                }
              }
            })();
        """.trimIndent()

        Log.d(TAG, "Executing Page 2 automation script")
        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Page 2 script result: $result")
            
            // Aguardar Page 3 carregar
            Handler(Looper.getMainLooper()).postDelayed({
                currentPage = 3
                captureAndProcessPage()
            }, LOAD_DELAY)
        }
    }

    private fun handlePage3() {
        Log.d(TAG, "Handling Page 3 (Parking Duration)")
        
        val durationText = if (parkingDuration.contains("2")) "2 Hour Parking" else "1 Hour Parking"
        
        val script = """
            (function(){
              try {
                // Encontrar e clicar no botão de duração
                const durationButtons = Array.from(document.querySelectorAll('button')).filter(b => 
                  b.textContent.includes('Hour Parking')
                );
                
                const targetButton = durationButtons.find(b => 
                  b.textContent.includes('$durationText')
                );
                
                if (targetButton) {
                  targetButton.click();
                }
                
                // Clicar botão Park
                const parkButton = Array.from(document.querySelectorAll('button')).find(b => 
                  b.textContent.toUpperCase() === 'PARK'
                );
                if (parkButton) {
                  parkButton.click();
                }
                
                if (typeof Android !== 'undefined' && Android.onStepComplete) {
                  Android.onStepComplete('page3_selected');
                }
              } catch(e) {
                if (typeof Android !== 'undefined' && Android.onError) {
                  Android.onError('Erro page 3: ' + e.message);
                }
              }
            })();
        """.trimIndent()

        Log.d(TAG, "Executing Page 3 automation script - Duration: $durationText")
        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Page 3 script result: $result")
            
            Handler(Looper.getMainLooper()).postDelayed({
                currentPage = 4
                captureAndProcessPage()
            }, LOAD_DELAY)
        }
    }

    private fun handlePage4() {
        Log.d(TAG, "Handling Page 4 (Confirmation)")
        
        val script = """
            (function(){
              try {
                // Verificar se existe botão REGISTER (significa que há reserva existente)
                const registerButton = Array.from(document.querySelectorAll('button')).find(b => 
                  b.textContent.toUpperCase() === 'REGISTER'
                );
                
                if (registerButton) {
                  // Há reserva existente, clicar REGISTER
                  registerButton.click();
                } else {
                  // Não há reserva, clicar PARK
                  const parkButton = Array.from(document.querySelectorAll('button')).find(b => 
                    b.textContent.toUpperCase() === 'PARK'
                  );
                  if (parkButton) {
                    parkButton.click();
                  }
                }
                
                if (typeof Android !== 'undefined' && Android.onStepComplete) {
                  Android.onStepComplete('page4_confirmed');
                }
              } catch(e) {
                if (typeof Android !== 'undefined' && Android.onError) {
                  Android.onError('Erro page 4: ' + e.message);
                }
              }
            })();
        """.trimIndent()

        Log.d(TAG, "Executing Page 4 automation script")
        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Page 4 script result: $result")
            
            Handler(Looper.getMainLooper()).postDelayed({
                currentPage = 5
                captureAndProcessPage()
            }, LOAD_DELAY)
        }
    }

    private fun handlePage5() {
        Log.d(TAG, "Handling Page 5 (Email)")
        
        val script = """
            (function(){
              try {
                // Clicar Done/DONE sem enviar email
                const doneButton = Array.from(document.querySelectorAll('button')).find(b => 
                  b.textContent.toUpperCase() === 'DONE'
                );
                
                if (doneButton) {
                  doneButton.click();
                }
                
                if (typeof Android !== 'undefined' && Android.onStepComplete) {
                  Android.onStepComplete('page5_completed');
                }
              } catch(e) {
                if (typeof Android !== 'undefined' && Android.onError) {
                  Android.onError('Erro page 5: ' + e.message);
                }
              }
            })();
        """.trimIndent()

        Log.d(TAG, "Executing Page 5 automation script")
        webView.evaluateJavascript(script) { result ->
            Log.d(TAG, "Page 5 script result: $result")
            
            // Automação concluída com sucesso
            Log.d(TAG, "=== Automation completed successfully ===")
            isExecuting = false
            
            Handler(Looper.getMainLooper()).post {
                onSuccess()
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping automation")
        isExecuting = false
        webView.stopLoading()
        webView.loadUrl("about:blank")
    }
}
