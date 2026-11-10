package br.com.radardata.verify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * O que abrir no Verify: a url ou o token da sessao, como o SEU backend devolveu.
 *
 * O app nunca cria a sessao: isso exigiria o token de API da empresa dentro do aparelho, e
 * token de API em aparelho de usuario final e token vazado.
 */
data class SessaoDoVerify(
    val sessao: String,
    /** Base da api. Troque para apontar a um ambiente seu. */
    val base: String? = null,
    /** De quanto em quanto tempo perguntar ao servidor se acabou, em ms. Padrao 5000, minimo 2000. */
    val intervaloDeConferenciaMs: Long = ControleDaSessao.INTERVALO_PADRAO_MS,
    /** Dominio proprio do seu Verify, se tiver. `verify.radardata.com.br` vale sempre. */
    val dominiosPermitidos: Set<String> = emptySet(),
    /** Bloqueia print, gravacao de tela e a miniatura de recentes: a tela mostra documento e rosto. */
    val protegerTela: Boolean = true,
)

/**
 * Abre o Verify numa tela cheia e devolve o desfecho:
 *
 * ```
 * private val verify = registerForActivityResult(ContratoVerify()) { desfecho -> ... }
 * verify.launch(SessaoDoVerify(urlQueOSeuBackendDevolveu))
 * ```
 *
 * Voltar pelo botao do Android devolve `Desfecho.Erro` com codigo `saiu_do_fluxo`: a sessao
 * continua aberta no servidor, e o app pode abrir de novo a mesma url.
 */
class ContratoVerify : ActivityResultContract<SessaoDoVerify, Desfecho>() {
    override fun createIntent(context: Context, input: SessaoDoVerify): Intent =
        Intent(context, VerifyActivity::class.java)
            .putExtra(EXTRA_SESSAO, input.sessao)
            .putExtra(EXTRA_BASE, input.base)
            .putExtra(EXTRA_INTERVALO, input.intervaloDeConferenciaMs)
            .putExtra(EXTRA_DOMINIOS, input.dominiosPermitidos.toTypedArray())
            .putExtra(EXTRA_PROTEGER, input.protegerTela)

    override fun parseResult(resultCode: Int, intent: Intent?): Desfecho {
        val dados = intent?.extras
        if (resultCode != Activity.RESULT_OK || dados == null) {
            return Desfecho.Erro(ErroVerify("A pessoa saiu do fluxo antes de terminar.", "saiu_do_fluxo"))
        }
        return when (dados.getString(R_TIPO)) {
            "concluido" -> Desfecho.Concluido(
                EstadoSessao(
                    sessionId = dados.getString(R_SESSAO).orEmpty(),
                    status = dados.getString(R_STATUS).orEmpty(),
                    etapaAtual = null,
                    resultado = dados.getString(R_RESULTADO),
                    motivo = dados.getString(R_MOTIVO),
                ),
            )
            "cancelado" -> Desfecho.Cancelado
            else -> {
                val mensagem = dados.getString(R_ERRO).orEmpty().ifEmpty { "Falha no Verify." }
                val codigo = dados.getString(R_CODIGO)
                val http = dados.getInt(R_HTTP, 0).takeIf { it > 0 }
                // A classe volta inteira: quem integra decide por `is SessaoInvalida`, como diz o README.
                Desfecho.Erro(
                    when (dados.getString(R_CLASSE)) {
                        "SessaoInvalida" -> SessaoInvalida(mensagem, codigo, http, emptyMap())
                        "PassoForaDeOrdem" -> PassoForaDeOrdem(mensagem, codigo, http, emptyMap())
                        "BiometriaIndisponivel" -> BiometriaIndisponivel(mensagem, codigo, http, emptyMap())
                        "FalhaDeRede" -> FalhaDeRede(mensagem, null)
                        "SemCamera" -> SemCamera(mensagem)
                        else -> ErroVerify(mensagem, codigo, http)
                    },
                )
            }
        }
    }

    internal companion object {
        const val EXTRA_SESSAO = "radardata.verify.sessao"
        const val EXTRA_BASE = "radardata.verify.base"
        const val EXTRA_INTERVALO = "radardata.verify.intervalo"
        const val EXTRA_DOMINIOS = "radardata.verify.dominios"
        const val EXTRA_PROTEGER = "radardata.verify.proteger"
        const val R_TIPO = "radardata.verify.tipo"
        const val R_SESSAO = "radardata.verify.session_id"
        const val R_STATUS = "radardata.verify.status"
        const val R_RESULTADO = "radardata.verify.resultado"
        const val R_MOTIVO = "radardata.verify.motivo"
        const val R_ERRO = "radardata.verify.erro"
        const val R_CODIGO = "radardata.verify.codigo"
        const val R_HTTP = "radardata.verify.http"
        const val R_CLASSE = "radardata.verify.classe"

        fun paraIntent(d: Desfecho): Intent = Intent().apply {
            when (d) {
                is Desfecho.Concluido -> {
                    putExtra(R_TIPO, "concluido")
                    putExtra(R_SESSAO, d.estado.sessionId)
                    putExtra(R_STATUS, d.estado.status)
                    putExtra(R_RESULTADO, d.estado.resultado)
                    putExtra(R_MOTIVO, d.estado.motivo)
                }
                Desfecho.Cancelado -> putExtra(R_TIPO, "cancelado")
                is Desfecho.Erro -> {
                    putExtra(R_TIPO, "erro")
                    putExtra(R_ERRO, d.erro.message)
                    putExtra(R_CODIGO, d.erro.codigo)
                    putExtra(R_HTTP, d.erro.status ?: 0)
                    putExtra(R_CLASSE, d.erro::class.java.simpleName)
                }
            }
        }
    }
}

/** A tela cheia do Verify. Abra pelo [ContratoVerify]; ela nao e exportada. */
class VerifyActivity : ComponentActivity() {
    private lateinit var pedidos: PedidosComLaunchers
    private var webView: WebView? = null
    private var acompanhamento: Job? = null
    private var encerrado = false

    private val pedirPermissao = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pedidos.permissaoRespondida(it)
    }
    private val pedirArquivo = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        pedidos.arquivoEscolhido(it)
    }
    private lateinit var controle: ControleDaSessao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(ContratoVerify.EXTRA_PROTEGER, true)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        pedidos = PedidosComLaunchers(this).also {
            it.lancarPermissao = { p -> pedirPermissao.launch(p) }
            it.lancarArquivo = { tipos -> pedirArquivo.launch(tipos) }
        }

        val raiz = FrameLayout(this)
        raiz.addView(ProgressBar(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(raiz)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Voltar dentro do fluxo navega a pagina; fora dela, sai sem desfecho.
                val w = webView
                if (w != null && w.canGoBack()) {
                    w.goBack()
                } else {
                    encerrarLocal(Desfecho.Erro(ErroVerify("A pessoa saiu do fluxo antes de terminar.", "saiu_do_fluxo")))
                }
            }
        })

        controle = ControleDaSessao(
            sessao = intent.getStringExtra(ContratoVerify.EXTRA_SESSAO).orEmpty(),
            base = intent.getStringExtra(ContratoVerify.EXTRA_BASE),
            provedor = SemProvedorNativo,
            intervaloMs = intent.getLongExtra(ContratoVerify.EXTRA_INTERVALO, ControleDaSessao.INTERVALO_PADRAO_MS),
            dominiosPermitidos = intent.getStringArrayExtra(ContratoVerify.EXTRA_DOMINIOS).orEmpty().toSet() + Runtime.DOMINIO_PADRAO,
        )

        lifecycleScope.launch {
            when (val abertura = controle.abrir {}) {
                is ControleDaSessao.Abertura.Fim -> encerrar(abertura.desfecho)
                is ControleDaSessao.Abertura.WebView -> {
                    val w = WebView(this@VerifyActivity)
                    NavegadorDoVerify.configurar(w, abertura.url, lifecycleScope, pedidos) { encerrarLocal(Desfecho.Erro(it)) }
                    raiz.removeAllViews()
                    raiz.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    webView = w
                    w.loadUrl(abertura.url)
                    acompanhamento = launch { encerrar(controle.acompanhar {}) }
                }
            }
        }
    }

    /** Desfecho que nasceu no aparelho: o servidor tem a palavra final, se responder em 3 s. */
    private fun encerrarLocal(local: Desfecho) {
        if (encerrado) return
        acompanhamento?.cancel()
        lifecycleScope.launch { encerrar(controle.confirmar(local)) }
    }

    private fun encerrar(d: Desfecho) {
        if (encerrado) return
        encerrado = true
        acompanhamento?.cancel()
        setResult(Activity.RESULT_OK, ContratoVerify.paraIntent(d))
        finish()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}
