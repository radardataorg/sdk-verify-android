package br.com.radardata.verify.exemplo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.radardata.verify.ContratoVerify
import br.com.radardata.verify.Desfecho
import br.com.radardata.verify.SessaoDoVerify
import br.com.radardata.verify.VerifyRadarData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * App de exemplo. Pede a sessao ao backend de exemplo (o mesmo do SDK React Native, em
 * `sdk-verify-react-native/example/backend`, na porta 8799, via `adb reverse`) e abre o Verify
 * pelos dois caminhos: a Activity pronta e o componente Compose embutido.
 *
 * O app nunca ve o token de API: so a url da sessao.
 */
class ExemploActivity : ComponentActivity() {
    private var aoDesfechoDaActivity: (Desfecho) -> Unit = {}
    private val abrirVerify = registerForActivityResult(ContratoVerify()) { aoDesfechoDaActivity(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val escopo = rememberCoroutineScope()
                var backend by remember { mutableStateOf("http://localhost:8799") }
                var fluxo by remember { mutableStateOf("") }
                var cpf by remember { mutableStateOf("") }
                var mensagem by remember { mutableStateOf("Informe o fluxo e o CPF e abra o Verify.") }
                var embutida by remember { mutableStateOf<String?>(null) }

                aoDesfechoDaActivity = { mensagem = descrever(it) }

                val atual = embutida
                if (atual != null) {
                    VerifyRadarData(
                        sessao = atual,
                        onDesfecho = { mensagem = descrever(it); embutida = null },
                        onEstado = { mensagem = "Estado: ${it.status}, passo ${it.etapaAtual ?: "nenhum"}" },
                    )
                    return@MaterialTheme
                }

                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("RadarData Verify, Android", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(backend, { backend = it }, label = { Text("Backend de exemplo") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fluxo, { fluxo = it }, label = { Text("Fluxo (alias)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cpf, { cpf = it }, label = { Text("CPF") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            escopo.launch {
                                mensagem = "Criando sessão..."
                                runCatching { criarSessao(backend, fluxo, cpf) }
                                    .onSuccess { abrirVerify.launch(SessaoDoVerify(it)) }
                                    .onFailure { mensagem = "Backend: ${it.message}" }
                            }
                        }) { Text("Tela cheia") }
                        OutlinedButton(onClick = {
                            escopo.launch {
                                mensagem = "Criando sessão..."
                                runCatching { criarSessao(backend, fluxo, cpf) }
                                    .onSuccess { embutida = it }
                                    .onFailure { mensagem = "Backend: ${it.message}" }
                            }
                        }) { Text("Embutido (Compose)") }
                    }
                    Text(mensagem)
                }
            }
        }
    }

    private fun descrever(d: Desfecho): String = when (d) {
        is Desfecho.Concluido -> "Concluído: ${d.estado.resultado ?: d.estado.status}. Quem decide é o seu backend, pelo webhook."
        Desfecho.Cancelado -> "Cancelado pelo backend."
        is Desfecho.Erro -> "Erro: ${d.erro.message} (${d.erro.codigo ?: "sem código"})"
    }

    /** O SEU backend cria a sessao com o SDK do servidor e devolve so a url. */
    private suspend fun criarSessao(backend: String, fluxo: String, cpf: String): String = withContext(Dispatchers.IO) {
        val conexao = URL("${backend.trimEnd('/')}/sessoes").openConnection() as HttpURLConnection
        try {
            conexao.requestMethod = "POST"
            conexao.doOutput = true
            conexao.setRequestProperty("Content-Type", "application/json")
            val corpo = JSONObject().put("fluxo", fluxo.trim()).put("input", JSONObject().put("cpf", cpf.filter { it.isDigit() }))
            conexao.outputStream.use { it.write(corpo.toString().toByteArray()) }
            val texto = (if (conexao.responseCode >= 400) conexao.errorStream else conexao.inputStream).bufferedReader().use { it.readText() }
            if (conexao.responseCode >= 400) error(texto)
            JSONObject(texto).getString("url")
        } finally {
            conexao.disconnect()
        }
    }
}
