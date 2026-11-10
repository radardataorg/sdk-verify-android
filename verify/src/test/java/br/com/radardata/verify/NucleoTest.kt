package br.com.radardata.verify

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** O nucleo, sem tela: token, erros, decisao nativo ou WebView, e o desfecho que sai uma vez. */
class NucleoTest {
    private class Falso(private val respostas: List<() -> RespostaHttp>) : Transporte {
        val chamadas = mutableListOf<Pair<String, String>>()
        private var i = 0

        override fun pedir(metodo: String, url: String, corpoJson: String?, timeoutMs: Int): RespostaHttp {
            chamadas += metodo to url
            return respostas[minOf(i++, respostas.size - 1)]()
        }
    }

    private fun json(status: Int, corpo: String) = { RespostaHttp(status, corpo) }

    private fun estado(status: String, etapa: String? = "liveness", outcome: String? = null) =
        json(200, """{"session_id":"s1","status":"$status","etapa_atual":${etapa?.let { "\"$it\"" } ?: "null"},"outcome":${outcome?.let { "\"$it\"" } ?: "null"},"steps":[{"tipo":"consent"},{"tipo":"liveness"}],"branding":{"cor":"#000"}}""")

    @Test
    fun `token sai da url ou vem sozinho`() {
        assertEquals("abc123", Runtime.tokenDaUrl("https://verify.radardata.com.br/s/abc123?x=1#y"))
        assertEquals("abc123", Runtime.tokenDaUrl("  abc123 "))
        assertEquals("https://verify.radardata.com.br/s/abc", Runtime.urlDaSessao("https://verify.radardata.com.br/s/abc"))
        assertEquals("https://verify.cliente.com/s/abc", Runtime.urlDaSessao("https://verify.cliente.com/s/abc", setOf("verify.cliente.com")))
        assertEquals("https://verify.radardata.com.br/s/abc", Runtime.urlDaSessao("abc"))
    }

    /** Seguranca: url de deep link de outro site, http ou content:// nao abre replica do Verify no app. */
    @Test
    fun `url fora do Verify e recusada`() {
        for (url in listOf("http://verify.radardata.com.br/s/abc", "content://app.provider/s/abc", "https://falso.exemplo/s/abc", "https://verify.radardata.com.br/outra/abc", "https://verify.radardata.com.br.falso.exemplo/s/abc")) {
            val erro = runCatching { Runtime.urlDaSessao(url) }.exceptionOrNull() as? ErroVerify
            assertEquals(url, "url_invalida", erro?.codigo)
        }
    }

    /** Seguranca: camera e localizacao so para a pagina da sessao. */
    @Test
    fun `permissao so para a origem da sessao`() {
        val sessao = NavegadorDoVerify.origemDe("https://verify.radardata.com.br/s/abc")
        assertTrue(NavegadorDoVerify.podeConceder(sessao, NavegadorDoVerify.origemDe("https://verify.radardata.com.br/")))
        assertFalse(NavegadorDoVerify.podeConceder(sessao, NavegadorDoVerify.origemDe("https://terceiro.exemplo/")))
        assertFalse(NavegadorDoVerify.podeConceder(sessao, NavegadorDoVerify.origemDe("https://verify.radardata.com.br:8443/")))
        assertFalse(NavegadorDoVerify.podeConceder(null, sessao))
    }

    @Test
    fun `base fora de https e recusada`() {
        assertEquals("base_invalida", (runCatching { Runtime("t", "http://api.exemplo/api") }.exceptionOrNull() as ErroVerify).codigo)
    }

    @Test
    fun `bootstrap chama o runtime pelo token da sessao e le o estado`() = runTest {
        val f = Falso(listOf(estado("in_progress")))
        val e = Runtime("tok/1", "https://api.exemplo/api/", transporte = f).bootstrap()

        assertEquals("GET" to "https://api.exemplo/api/verify/rt/tok%2F1/bootstrap", f.chamadas[0])
        assertEquals("s1", e.sessionId)
        assertEquals(listOf("consent", "liveness"), e.passos.map { it.tipo })
        assertEquals("#000", e.branding["cor"])
        assertFalse(e.terminou)
    }

    @Test
    fun `status vira a classe de erro certa`() = runTest {
        val casos = mapOf(410 to SessaoInvalida::class, 409 to PassoForaDeOrdem::class, 503 to BiometriaIndisponivel::class, 500 to ErroVerify::class)
        for ((status, classe) in casos) {
            val f = Falso(listOf(json(status, """{"error":{"code":"x","message":"msg $status"}}""")))
            val erro = runCatching { Runtime("t", transporte = f).bootstrap() }.exceptionOrNull() as ErroVerify
            assertEquals(classe, erro::class)
            assertEquals("msg $status", erro.message)
            assertEquals(status, erro.status)
        }
    }

    @Test
    fun `sem rede vira FalhaDeRede`() = runTest {
        val f = Falso(listOf { throw IOException("sem rede") })
        assertTrue(runCatching { Runtime("t", transporte = f).bootstrap() }.exceptionOrNull() is FalhaDeRede)
    }

    @Test
    fun `cancelado pelo backend nao e erro, expirada e`() {
        val cancelada = SessaoInvalida("x", "sessao_encerrada", 410, mapOf("error" to mapOf("status" to "canceled")))
        val expirada = SessaoInvalida("x", "sessao_encerrada", 410, mapOf("error" to mapOf("status" to "expired")))

        assertEquals(Desfecho.Cancelado, Orquestrador.desfechoDoErro(cancelada))
        assertTrue(Orquestrador.desfechoDoErro(expirada) is Desfecho.Erro)
    }

    @Test
    fun `prova de vida so roda nativo com provedor disponivel`() = runTest {
        val o = Orquestrador(Runtime("t", transporte = Falso(listOf(estado("in_progress")))))
        val e = o.estado()
        assertFalse(o.rodaNativo(e))

        val comNativo = Orquestrador(
            Runtime("t", transporte = Falso(listOf(estado("in_progress")))),
            object : ProvedorDeProvaDeVida {
                override suspend fun disponivel() = true
                override suspend fun executar(desafio: Map<String, Any?>) = Unit
            },
        )
        assertTrue(comNativo.rodaNativo(e))
        assertFalse(comNativo.rodaNativo(e.copy(etapaAtual = "consent")))
    }

    @Test
    fun `conferir segue em falha de rede e encerra na sessao invalida`() = runTest {
        val f = Falso(listOf({ throw IOException("x") }, json(410, """{"error":{"status":"canceled"}}""")))
        val o = Orquestrador(Runtime("t", transporte = f))

        assertNull(o.conferir().desfecho)
        assertEquals(Desfecho.Cancelado, o.conferir().desfecho)
    }

    @Test
    fun `controle abre o WebView e acompanha ate concluir`() = runTest {
        val f = Falso(listOf(estado("in_progress"), estado("in_progress"), estado("completed", null, "APROVADO")))
        val controle = ControleDaSessao("https://verify.cliente.com/s/abc", "https://api.exemplo/api", SemProvedorNativo, 2_000, setOf("verify.cliente.com"), f)
        val estados = mutableListOf<String>()

        val abertura = controle.abrir { estados += it.status }
        assertEquals(ControleDaSessao.Abertura.WebView("https://verify.cliente.com/s/abc"), abertura)

        val d = controle.acompanhar { estados += it.status } as Desfecho.Concluido
        assertEquals("APROVADO", d.estado.resultado)
        assertEquals(listOf("in_progress", "in_progress", "completed"), estados)
        assertTrue(f.chamadas.all { it.second.endsWith("/verify/rt/abc/bootstrap") })
    }

    @Test
    fun `sessao ja encerrada nem abre o WebView, e sessao vazia vira erro`() = runTest {
        val pronta = ControleDaSessao("abc", null, SemProvedorNativo, 5_000, transporte = Falso(listOf(estado("completed", null, "REPROVADO"))))
        assertTrue((pronta.abrir {} as ControleDaSessao.Abertura.Fim).desfecho is Desfecho.Concluido)

        val vazia = ControleDaSessao("  ", null, SemProvedorNativo, 5_000, transporte = Falso(listOf(estado("in_progress"))))
        assertTrue((vazia.abrir {} as ControleDaSessao.Abertura.Fim).desfecho is Desfecho.Erro)
    }
}
