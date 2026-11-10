package br.com.radardata.verify

import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * O RadarData Verify dentro da sua tela Compose.
 *
 * ```
 * VerifyRadarData(sessao = urlQueOSeuBackendDevolveu, onDesfecho = { desfecho -> ... })
 * ```
 *
 * @param sessao a url OU o token da sessao, como o SEU backend devolveu
 * @param onDesfecho chamado uma vez so por sessao
 * @param onEstado chamado a cada mudanca de estado, para o app acompanhar sem decidir
 * @param base base da api, para apontar a um ambiente seu
 * @param provedorDeProvaDeVida prova de vida nativa; sem ela, o fluxo todo roda no WebView
 * @param intervaloDeConferenciaMs de quanto em quanto tempo perguntar ao servidor, minimo 2000
 * @param dominiosPermitidos dominio proprio do seu Verify, se tiver; `verify.radardata.com.br` vale sempre
 * @param protegerTela bloqueia print e gravacao de tela enquanto o Verify esta aberto
 */
@Composable
fun VerifyRadarData(
    sessao: String,
    onDesfecho: (Desfecho) -> Unit,
    modifier: Modifier = Modifier,
    onEstado: ((EstadoSessao) -> Unit)? = null,
    base: String? = null,
    provedorDeProvaDeVida: ProvedorDeProvaDeVida = SemProvedorNativo,
    intervaloDeConferenciaMs: Long = ControleDaSessao.INTERVALO_PADRAO_MS,
    dominiosPermitidos: Set<String> = emptySet(),
    protegerTela: Boolean = true,
) {
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()

    // Callbacks em estado atualizado, e nao como chave dos efeitos: lambda inline muda a cada
    // recomposicao, e com ela na chave a sessao reabria a cada resposta, num laco contra o servidor.
    val aoDesfecho by rememberUpdatedState(onDesfecho)
    val aoEstado by rememberUpdatedState(onEstado)
    val provedor by rememberUpdatedState(provedorDeProvaDeVida)

    val pedidos = remember(contexto) { PedidosComLaunchers(contexto) }
    val pedirPermissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pedidos.permissaoRespondida(it) }
    val pedirArquivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pedidos.arquivoEscolhido(it) }
    pedidos.lancarPermissao = { pedirPermissao.launch(it) }
    pedidos.lancarArquivo = { pedirArquivo.launch(it) }

    // O desfecho sai uma vez por sessao, e sobrevive a rotacao: sem isso, girar a tela depois do
    // fim reabria a sessao, o servidor dizia "concluida" de novo e o app recebia dois desfechos.
    val encerrado = rememberSaveable(sessao) { mutableStateOf(false) }
    val encerrar: (Desfecho) -> Unit = { d ->
        if (!encerrado.value) {
            encerrado.value = true
            aoDesfecho(d)
        }
    }

    var url by remember(sessao, base) { mutableStateOf<String?>(null) }
    val controle = remember(sessao, base, intervaloDeConferenciaMs, dominiosPermitidos) {
        ControleDaSessao(sessao, base, object : ProvedorDeProvaDeVida {
            override suspend fun disponivel() = provedor.disponivel()
            override suspend fun executar(desafio: Map<String, Any?>) = provedor.executar(desafio)
        }, intervaloDeConferenciaMs, dominiosPermitidos + Runtime.DOMINIO_PADRAO)
    }

    // A tela mostra documento e rosto: sem print nem gravacao enquanto o Verify esta aberto.
    if (protegerTela) {
        DisposableEffect(contexto) {
            val janela = (contexto as? android.app.Activity)?.window
            janela?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { janela?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    LaunchedEffect(controle) {
        if (encerrado.value) return@LaunchedEffect
        when (val abertura = controle.abrir { aoEstado?.invoke(it) }) {
            is ControleDaSessao.Abertura.Fim -> encerrar(abertura.desfecho)
            is ControleDaSessao.Abertura.WebView -> url = abertura.url
        }
    }

    // `encerrado` na chave: ao encerrar por outro caminho, a conferencia para.
    LaunchedEffect(controle, url, encerrado.value) {
        if (url == null || encerrado.value) return@LaunchedEffect
        encerrar(controle.acompanhar { aoEstado?.invoke(it) })
    }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val atual = url
        if (atual == null) {
            AndroidView(factory = { android.widget.ProgressBar(it) })
        } else {
            var criado by remember { mutableStateOf<WebView?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).also { w ->
                        NavegadorDoVerify.configurar(w, atual, escopo, pedidos) { erro ->
                            escopo.launch { encerrar(controle.confirmar(Desfecho.Erro(erro))) }
                        }
                        w.loadUrl(atual)
                        criado = w
                    }
                },
            )
            DisposableEffect(Unit) {
                onDispose { criado?.destroy() }
            }
        }
    }
}
