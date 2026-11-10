package br.com.radardata.verify

/**
 * Um passo do fluxo. `liveness` e `document` precisam da camera; o resto e coleta de dado e
 * nao precisa de nada do aparelho, e e por isso que o WebView cobre esses sem perda.
 */
data class Passo(
    val tipo: String,
    val chave: String? = null,
    val config: Map<String, Any?> = emptyMap(),
)

/** O estado da sessao, como o runtime o descreve. */
data class EstadoSessao(
    val sessionId: String,
    /** pending, in_progress, completed, failed, expired, canceled. */
    val status: String,
    /** O passo em que a sessao esta, ou nulo quando acabou. */
    val etapaAtual: String?,
    /** APROVADO, REPROVADO, REVISAO_MANUAL; nulo enquanto nao concluiu. */
    val resultado: String?,
    /** Por que nao aprovou, em texto neutro para mostrar a pessoa. */
    val motivo: String?,
    val passos: List<Passo> = emptyList(),
    /** Cores e logo do cliente, para o app nao parecer de outra marca. */
    val branding: Map<String, Any?> = emptyMap(),
    val cru: Map<String, Any?> = emptyMap(),
) {
    /**
     * Acabou, de qualquer jeito: nao ha mais o que a pessoa possa fazer. `failed` e a sessao que
     * o servidor encerrou sem aprovar (tentativas de prova de vida esgotadas, por exemplo): ela
     * nunca vira `expired`, e sem ela aqui o app ficaria esperando para sempre.
     */
    val terminou: Boolean get() = status in setOf("completed", "failed", "expired", "canceled")

    internal companion object {
        @Suppress("UNCHECKED_CAST")
        fun de(d: Map<String, Any?>): EstadoSessao = EstadoSessao(
            sessionId = d["session_id"]?.toString() ?: "",
            status = d["status"]?.toString() ?: "",
            etapaAtual = d["etapa_atual"] as? String,
            resultado = d["outcome"] as? String,
            motivo = d["motivo"] as? String,
            passos = (d["steps"] as? List<*>).orEmpty().mapNotNull { p ->
                (p as? Map<String, Any?>)?.let {
                    Passo(it["tipo"]?.toString() ?: "", it["chave"] as? String, (it["config"] as? Map<String, Any?>).orEmpty())
                }
            },
            branding = (d["branding"] as? Map<String, Any?>).orEmpty(),
            cru = d,
        )
    }
}

/** Como o fluxo terminou. Sai uma vez so por sessao. */
sealed interface Desfecho {
    /**
     * A pessoa terminou: `estado.status` e `completed`, ou `failed` quando o servidor encerrou sem
     * aprovar (o motivo vem em `estado.motivo`). A decisao que vale e a do SEU backend, pelo webhook.
     */
    data class Concluido(val estado: EstadoSessao) : Desfecho

    /** O seu backend cancelou a sessao pela API. Nao e falha. */
    data object Cancelado : Desfecho

    /** Nao deu para seguir: sessao expirada, sem rede, pagina que nao carregou. */
    data class Erro(val erro: ErroVerify) : Desfecho
}
