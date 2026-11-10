package br.com.radardata.verify

/** Erros do SDK do Verify. */
open class ErroVerify(
    mensagem: String,
    val codigo: String? = null,
    val status: Int? = null,
    val corpo: Map<String, Any?> = emptyMap(),
    causa: Throwable? = null,
) : Exception(mensagem, causa)

/**
 * O token da sessao nao serve mais: expirou, foi cancelada ou nunca existiu.
 *
 * A acao do app e outra: nao e tentar de novo, e pedir uma sessao nova ao SEU backend. O
 * app nunca cria sessao, porque isso exigiria o token de API da empresa dentro do aparelho.
 */
class SessaoInvalida(mensagem: String, codigo: String?, status: Int?, corpo: Map<String, Any?>) :
    ErroVerify(mensagem, codigo, status, corpo)

/** O passo pedido nao e o passo em que a sessao esta (409 do runtime). */
class PassoForaDeOrdem(mensagem: String, codigo: String?, status: Int?, corpo: Map<String, Any?>) :
    ErroVerify(mensagem, codigo, status, corpo)

/** A pessoa negou a camera, ou o aparelho nao tem uma. */
class SemCamera(mensagem: String) : ErroVerify(mensagem)

/** Biometria indisponivel do nosso lado (503). */
class BiometriaIndisponivel(mensagem: String, codigo: String?, status: Int?, corpo: Map<String, Any?>) :
    ErroVerify(mensagem, codigo, status, corpo)

/** Nao houve resposta: timeout, DNS, sem rede. */
class FalhaDeRede(mensagem: String, causa: Throwable?) : ErroVerify(mensagem, "falha_de_rede", null, emptyMap(), causa)

internal fun erroDoStatus(status: Int, mensagem: String, codigo: String?, corpo: Map<String, Any?>): ErroVerify = when (status) {
    401, 404, 410 -> SessaoInvalida(mensagem, codigo, status, corpo)
    409 -> PassoForaDeOrdem(mensagem, codigo, status, corpo)
    503 -> BiometriaIndisponivel(mensagem, codigo, status, corpo)
    else -> ErroVerify(mensagem, codigo, status, corpo)
}
