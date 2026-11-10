package br.com.radardata.verify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Uma resposta HTTP crua: o status e o corpo em texto. */
data class RespostaHttp(val status: Int, val corpo: String)

/**
 * Quem faz a chamada HTTP. Existe para os testes trocarem a rede por respostas prontas; o
 * padrao usa `HttpURLConnection`, sem dependencia nenhuma.
 */
fun interface Transporte {
    @Throws(IOException::class)
    fun pedir(metodo: String, url: String, corpoJson: String?, timeoutMs: Int): RespostaHttp
}

internal object TransportePadrao : Transporte {
    override fun pedir(metodo: String, url: String, corpoJson: String?, timeoutMs: Int): RespostaHttp {
        val conexao = URL(url).openConnection() as HttpURLConnection
        try {
            conexao.requestMethod = metodo
            conexao.connectTimeout = timeoutMs
            conexao.readTimeout = timeoutMs
            conexao.setRequestProperty("Accept", "application/json")
            conexao.setRequestProperty("User-Agent", "radardata-verify-android/${BuildConfig.VERSAO}")
            if (corpoJson != null) {
                conexao.doOutput = true
                conexao.setRequestProperty("Content-Type", "application/json")
                conexao.outputStream.use { it.write(corpoJson.toByteArray()) }
            }
            val status = conexao.responseCode
            val fluxo = if (status >= 400) conexao.errorStream else conexao.inputStream
            return RespostaHttp(status, fluxo?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conexao.disconnect()
        }
    }
}

/**
 * Cliente do RUNTIME do Verify (`/api/verify/rt/{token}`).
 *
 * A credencial aqui e o TOKEN DA SESSAO, que vem no link, e nao o token de API da empresa. O
 * app nunca carrega o token de API: ele pede ao SEU backend uma sessao (criada com o
 * `@radardata/sdk` ou o `radardata/sdk-php`) e recebe de volta a url ou o token dela. Token de
 * API em aparelho de usuario final e token vazado: com ele da para gastar o saldo da empresa.
 */
class Runtime(
    private val token: String,
    base: String = BASE_PADRAO,
    private val timeoutMs: Int = 30_000,
    private val transporte: Transporte = TransportePadrao,
) {
    private val base = base.trimEnd('/')

    init {
        if (token.isBlank()) throw ErroVerify("Token da sessão do Verify é obrigatório.")
    }

    /** O estado atual da sessao e a lista de passos. */
    suspend fun bootstrap(): EstadoSessao = EstadoSessao.de(pedir("GET", "bootstrap"))

    /** Cria o desafio de prova de vida e devolve o que o nativo precisa. */
    suspend fun livenessStart(): Map<String, Any?> = pedir("POST", "liveness/start")

    /**
     * Fecha a prova de vida: o SERVIDOR busca o resultado e avalia. O app nao decide nada:
     * quem decide aprovacao de biometria no aparelho do usuario final e o proprio usuario.
     */
    suspend fun livenessResultado(): EstadoSessao = EstadoSessao.de(pedir("POST", "liveness/result"))

    private suspend fun pedir(metodo: String, caminho: String, corpo: Map<String, Any?>? = null): Map<String, Any?> =
        withContext(Dispatchers.IO) {
            val url = "$base/verify/rt/${URLEncoder.encode(token, "UTF-8")}/$caminho"
            val resposta = try {
                transporte.pedir(metodo, url, corpo?.let { JSONObject(it).toString() }, timeoutMs)
            } catch (e: IOException) {
                throw FalhaDeRede("Não foi possível falar com o RadarData: ${e.message}", e)
            }

            val dados = paraMapa(resposta.corpo)
            if (resposta.status !in 200..299) {
                @Suppress("UNCHECKED_CAST")
                val erro = (dados["error"] as? Map<String, Any?>).orEmpty()
                throw erroDoStatus(
                    resposta.status,
                    erro["message"] as? String ?: dados["message"] as? String ?: "O RadarData respondeu ${resposta.status}.",
                    erro["code"] as? String,
                    dados,
                )
            }
            dados
        }

    companion object {
        const val BASE_PADRAO = "https://api.radardata.com.br/api"

        private const val FLUXO_PADRAO = "https://verify.radardata.com.br/s/"

        /**
         * O token da sessao a partir da url que o seu backend devolveu. A url e
         * `https://<dominio>/s/<token>`; aceitar as duas formas poupa o app de saber disso.
         */
        fun tokenDaUrl(urlOuToken: String): String {
            val limpo = urlOuToken.trim()
            if (!limpo.contains('/')) return limpo
            return limpo.substringBefore('?').substringBefore('#').split('/').lastOrNull { it.isNotEmpty() } ?: ""
        }

        /**
         * A pagina do fluxo. A url vale como veio, porque ja traz o dominio proprio de quem tem
         * um; o token sozinho abre no padrao, que atende toda sessao.
         */
        fun urlDaSessao(urlOuToken: String): String {
            val limpo = urlOuToken.trim()
            return if (limpo.contains('/')) limpo else FLUXO_PADRAO + limpo
        }

        internal fun paraMapa(texto: String): Map<String, Any?> = try {
            if (texto.isBlank()) emptyMap() else paraKotlin(JSONObject(texto)) as Map<String, Any?>
        } catch (e: Exception) {
            emptyMap()
        }

        private fun paraKotlin(valor: Any?): Any? = when (valor) {
            is JSONObject -> valor.keys().asSequence().associateWith { paraKotlin(valor.opt(it)) }
            is JSONArray -> (0 until valor.length()).map { paraKotlin(valor.opt(it)) }
            JSONObject.NULL -> null
            else -> valor
        }
    }
}
