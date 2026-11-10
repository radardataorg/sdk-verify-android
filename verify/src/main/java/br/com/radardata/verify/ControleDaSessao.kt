package br.com.radardata.verify

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * O andamento de uma sessao, igual para a Activity e para o Compose: abre, decide entre nativo
 * e WebView, e depois pergunta ao servidor se acabou.
 *
 * Como sabe que terminou: PERGUNTA AO SERVIDOR, em intervalo, em vez de espiar a navegacao do
 * WebView. Depender da url de redirecionamento ou de mensagem da pagina amarraria o pacote ao
 * HTML do runtime, que muda sem aviso; o estado da sessao e contrato.
 */
internal class ControleDaSessao(
    private val sessao: String,
    base: String?,
    provedor: ProvedorDeProvaDeVida,
    intervaloMs: Long,
    private val dominiosPermitidos: Set<String> = setOf(Runtime.DOMINIO_PADRAO),
    transporte: Transporte = TransportePadrao,
) {
    /** Cada conferencia e um acesso na auditoria da sessao e conta no limite de taxa do runtime. */
    private val intervalo = intervaloMs.coerceAtLeast(INTERVALO_MINIMO_MS)

    // Sessao vazia faz o Runtime recusar: vira desfecho de erro em `abrir`, nao excecao na tela.
    private val orquestrador: Result<Orquestrador> = runCatching {
        Orquestrador(Runtime(Runtime.tokenDaUrl(sessao), base ?: Runtime.BASE_PADRAO, transporte = transporte), provedor)
    }

    sealed interface Abertura {
        data class WebView(val url: String) : Abertura
        data class Fim(val desfecho: Desfecho) : Abertura
    }

    suspend fun abrir(aoEstado: (EstadoSessao) -> Unit): Abertura {
        val o = orquestrador.getOrElse { return Abertura.Fim(Orquestrador.desfechoDoErro(it)) }
        // Antes de qualquer chamada: url de outro site nao chega nem a consultar a api.
        val url = try {
            Runtime.urlDaSessao(sessao, dominiosPermitidos)
        } catch (e: ErroVerify) {
            return Abertura.Fim(Desfecho.Erro(e))
        }
        return try {
            val estado = o.estado()
            aoEstado(estado)
            Orquestrador.desfechoDoEstado(estado)?.let { return Abertura.Fim(it) }

            if (o.rodaNativo(estado)) {
                val depois = o.provaDeVidaNativa()
                aoEstado(depois)
                Orquestrador.desfechoDoEstado(depois)?.let { return Abertura.Fim(it) }
            }

            // Sobrou passo que este pacote nao roda nativo: WebView.
            Abertura.WebView(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Abertura.Fim(Orquestrador.desfechoDoErro(e))
        }
    }

    /**
     * Uma pergunta por vez: a proxima so e agendada quando a anterior volta, para rede lenta
     * nao empilhar requisicoes. Termina com o desfecho; cancelar a corrotina para tudo.
     */
    suspend fun acompanhar(aoEstado: (EstadoSessao) -> Unit): Desfecho {
        val o = orquestrador.getOrElse { return Orquestrador.desfechoDoErro(it) }
        while (true) {
            delay(intervalo)
            val (estado, desfecho) = o.conferir()
            estado?.let(aoEstado)
            if (desfecho != null) return desfecho
        }
    }

    /**
     * Antes de entregar um desfecho que nasceu no aparelho (voltar, pagina que nao carregou,
     * WebView que caiu), pergunta ao servidor: a pessoa pode ter terminado segundos antes, e
     * mandar o app refazer a biometria de uma sessao concluida e pior do que esperar 3 s.
     */
    suspend fun confirmar(local: Desfecho): Desfecho {
        val o = orquestrador.getOrElse { return local }
        return withTimeoutOrNull(CONFIRMACAO_MS) { o.conferir().desfecho } ?: local
    }

    companion object {
        /** Quanto esperar o servidor antes de entregar um desfecho local. */
        const val CONFIRMACAO_MS = 3_000L

        /** Cinco segundos: a pessoa ja ve o resultado na propria pagina enquanto o app nao fica sabendo. */
        const val INTERVALO_PADRAO_MS = 5_000L

        /** Abaixo disto a conferencia disputa o limite de taxa com o proprio fluxo. */
        const val INTERVALO_MINIMO_MS = 2_000L
    }
}
