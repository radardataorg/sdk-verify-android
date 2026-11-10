package br.com.radardata.verify

/**
 * O contrato do provedor NATIVO de prova de vida.
 *
 * Nesta versao a prova de vida roda no WebView, que e a pagina testada em producao. Este
 * contrato existe para o caminho nativo poder ser ligado sem mudar a API publica: o dia em
 * que houver implementacao nativa, quem ja integrou troca uma linha.
 */
interface ProvedorDeProvaDeVida {
    /** Da para usar este provedor neste aparelho, agora? */
    suspend fun disponivel(): Boolean

    /**
     * Roda a prova de vida e volta quando a pessoa terminou. NAO devolve veredito: quem
     * avalia e o servidor, no `POST /liveness/result`.
     *
     * @param desafio o que o `POST /liveness/start` devolveu
     */
    suspend fun executar(desafio: Map<String, Any?>)
}

/** O padrao, e nao um erro: com ele o fluxo cai no WebView, que faz a prova de vida de verdade. */
object SemProvedorNativo : ProvedorDeProvaDeVida {
    override suspend fun disponivel() = false

    override suspend fun executar(desafio: Map<String, Any?>) =
        throw ErroVerify("Nenhum provedor nativo de prova de vida configurado.")
}

/**
 * Decide, passo a passo, o que roda NATIVO e o que cai no WEBVIEW, e pergunta ao servidor se
 * acabou. Separado da tela de proposito: a decisao e testavel sem renderizar nada.
 */
class Orquestrador(
    private val runtime: Runtime,
    private val provedorNativo: ProvedorDeProvaDeVida = SemProvedorNativo,
) {
    suspend fun estado(): EstadoSessao = runtime.bootstrap()

    /**
     * O passo atual roda nativo? Pergunta ao provedor, e nao so ao tipo do passo: aparelho sem
     * camera, ou alguem que negou a permissao, tem de cair no WebView em vez de travar.
     */
    suspend fun rodaNativo(estado: EstadoSessao): Boolean {
        val etapa = estado.etapaAtual ?: return false
        if (estado.terminou) return false
        val passo = estado.passos.firstOrNull { (it.chave ?: it.tipo) == etapa || it.tipo == etapa } ?: return false
        if (passo.tipo !in NATIVOS) return false
        return provedorNativo.disponivel()
    }

    /** O servidor cria o desafio, o nativo executa, e o SERVIDOR busca o resultado e avalia. */
    suspend fun provaDeVidaNativa(): EstadoSessao {
        val desafio = runtime.livenessStart()
        provedorNativo.executar(desafio)
        // O servidor confere que o resultado e do desafio que ele mesmo criou.
        return runtime.livenessResultado(desafio["session_id"]?.toString().orEmpty())
    }

    /**
     * Pergunta ao servidor se acabou. Falha de rede nao encerra nada: a pessoa pode estar no
     * elevador, e a proxima rodada tenta de novo. Sessao expirada ou cancelada e outra coisa:
     * o servidor responde 410, e repetir nunca muda a resposta.
     */
    suspend fun conferir(): Conferencia = try {
        val estado = runtime.bootstrap()
        Conferencia(estado, desfechoDoEstado(estado))
    } catch (e: SessaoInvalida) {
        Conferencia(null, desfechoDoErro(e))
    } catch (e: ErroVerify) {
        Conferencia(null, null)
    }

    data class Conferencia(val estado: EstadoSessao?, val desfecho: Desfecho?)

    companion object {
        /** Passos que este pacote sabe rodar sem abrir WebView. */
        private val NATIVOS = setOf("liveness")

        /** O desfecho de um estado, ou nulo enquanto a sessao esta aberta. */
        fun desfechoDoEstado(estado: EstadoSessao): Desfecho? = when {
            !estado.terminou -> null
            estado.status == "canceled" -> Desfecho.Cancelado
            else -> Desfecho.Concluido(estado)
        }

        /**
         * Quem cancela e o backend de quem integra, pela API: isso nao e falha, e vira
         * `Cancelado`. Expirada continua erro, porque o app precisa pedir uma sessao nova.
         */
        fun desfechoDoErro(e: Throwable): Desfecho {
            if (e is SessaoInvalida && e.status == 410) {
                @Suppress("UNCHECKED_CAST")
                val status = (e.corpo["error"] as? Map<String, Any?>)?.get("status")
                if (status == "canceled") return Desfecho.Cancelado
            }
            return Desfecho.Erro(e as? ErroVerify ?: ErroVerify(e.message ?: e.toString(), causa = e))
        }
    }
}
