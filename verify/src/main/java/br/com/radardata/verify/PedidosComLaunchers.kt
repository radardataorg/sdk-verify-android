package br.com.radardata.verify

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pede permissao e arquivo pelos launchers de resultado do AndroidX. A Activity registra com
 * `registerForActivityResult`, o Compose com `rememberLauncherForActivityResult`, e os dois
 * entregam o resultado aqui. Um pedido por vez: a pagina pode pedir camera e localizacao
 * quase juntas, e o Android so mostra um dialogo.
 */
internal class PedidosComLaunchers(private val contexto: Context) : PedidosDoAparelho {
    var lancarPermissao: ((String) -> Unit)? = null
    var lancarArquivo: ((String) -> Unit)? = null

    private val fila = Mutex()
    private var esperaPermissao: CompletableDeferred<Boolean>? = null
    private var esperaArquivo: CompletableDeferred<Uri?>? = null

    fun permissaoRespondida(concedida: Boolean) {
        esperaPermissao?.complete(concedida)
    }

    fun arquivoEscolhido(uri: Uri?) {
        esperaArquivo?.complete(uri)
    }

    override suspend fun permissao(permissao: String): Boolean {
        if (ContextCompat.checkSelfPermission(contexto, permissao) == PackageManager.PERMISSION_GRANTED) return true
        // Permissao que o app nao declarou nunca e concedida: pedir so faria o Android negar calado.
        if (!declarada(permissao)) return false
        val lancar = lancarPermissao ?: return false

        return fila.withLock {
            val espera = CompletableDeferred<Boolean>().also { esperaPermissao = it }
            lancar(permissao)
            espera.await()
        }
    }

    override suspend fun escolherArquivo(tipos: Array<String>): List<Uri>? {
        val lancar = lancarArquivo ?: return null
        return fila.withLock {
            val espera = CompletableDeferred<Uri?>().also { esperaArquivo = it }
            // O seletor do Android aceita um tipo; a pagina pede imagem, e PDF quando o fluxo aceita.
            lancar(if (tipos.size == 1) tipos[0] else "*/*")
            espera.await()?.let { listOf(it) }
        }
    }

    private fun declarada(permissao: String): Boolean = try {
        val info = contexto.packageManager.getPackageInfo(contexto.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(permissao) == true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
