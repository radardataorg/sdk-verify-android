package br.com.radardata.verify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * O que o WebView precisa pedir ao app. A Activity e o Compose implementam cada um com o
 * proprio jeito de pedir permissao e abrir o seletor de arquivo.
 */
internal interface PedidosDoAparelho {
    /** Pede a permissao do Android, se ainda nao tiver, e diz se ficou concedida. */
    suspend fun permissao(permissao: String): Boolean

    /** Abre o seletor de arquivo; nulo quando a pessoa desistiu. */
    suspend fun escolherArquivo(tipos: Array<String>): List<Uri>?
}

/**
 * Configura o WebView para o fluxo do Verify.
 *
 * O WebView do Android nao faz sozinho o que a pagina pede: a camera ao vivo da prova de vida
 * (`getUserMedia`), o "enviar um arquivo" do documento e a localizacao de alguns fluxos. Sem
 * isto, cada um falha calado, o que parece defeito do fluxo. A camera so e dada a pagina
 * https, e so o video: a pagina nao precisa de microfone.
 */
internal object NavegadorDoVerify {
    @SuppressLint("SetJavaScriptEnabled")
    /** `scheme://host[:porta]`, para comparar a pagina que pede com a da sessao. */
    fun origemDe(url: String): String? = runCatching {
        val u = java.net.URI(url)
        if (u.scheme == null || u.host == null) null else "${u.scheme}://${u.host.lowercase()}${if (u.port > 0) ":${u.port}" else ""}"
    }.getOrNull()

    /**
     * Camera e localizacao so para a pagina da sessao. Outra pagina aberta dentro do WebView
     * (um link do consentimento, por exemplo) nao ganha nada, mesmo que o app ja tenha a
     * permissao do Android.
     */
    fun podeConceder(origemDaSessao: String?, origemDoPedido: String?): Boolean =
        origemDaSessao != null && origemDaSessao == origemDoPedido && origemDaSessao.startsWith("https://")

    fun configurar(
        webView: WebView,
        urlDaSessao: String,
        escopo: CoroutineScope,
        pedidos: PedidosDoAparelho,
        aoFalhar: (ErroVerify) -> Unit,
    ) {
        val origem = origemDe(urlDaSessao)
        webView.settings.apply {
            javaScriptEnabled = true
            // Alguns aparelhos recusam `getUserMedia` sem armazenamento.
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            // O arquivo escolhido chega a pagina pelo seletor, sem precisar abrir content:// aqui.
            allowContentAccess = false
            setGeolocationEnabled(true)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val querCamera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources
                if (!querCamera || !podeConceder(origem, origemDe(request.origin.toString()))) {
                    request.deny()
                    return
                }
                escopo.launch {
                    if (pedidos.permissao(Manifest.permission.CAMERA)) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        request.deny()
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                // So da para pedir o que o app declarou no manifesto; sem isso, nega sem travar o fluxo.
                escopo.launch {
                    val ok = podeConceder(origem, origemDe(origin)) &&
                        (pedidos.permissao(Manifest.permission.ACCESS_FINE_LOCATION) || pedidos.permissao(Manifest.permission.ACCESS_COARSE_LOCATION))
                    callback.invoke(origin, ok, false)
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                escopo.launch {
                    val tipos = params.acceptTypes.filter { it.isNotBlank() }.toTypedArray().ifEmpty { arrayOf("*/*") }
                    callback.onReceiveValue(pedidos.escolherArquivo(tipos)?.toTypedArray())
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // Dentro do WebView, so a pagina da sessao. Outro site https abre no navegador, fora do
            // app, onde a pessoa ve o endereco; intent:, http: e o resto nao abrem.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val destino = request.url
                if (origemDe(destino.toString()) == origem) return false
                if (destino.scheme == "https") {
                    runCatching {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, destino).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    aoFalhar(ErroVerify("O fluxo do Verify não carregou: ${error.description}", "pagina_nao_carregou"))
                }
            }
        }
    }
}
