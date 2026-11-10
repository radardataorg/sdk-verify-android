# RadarData Verify para Android

O RadarData Verify dentro do seu app Android nativo, em Kotlin: prova de vida, foto de
documento e coleta de dados. É o mesmo SDK do `@radardata/verify-react-native`, para quem não
usa React Native.

## Instalação

Pelo JitPack. No `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

No `build.gradle.kts` do app:

```kotlin
dependencies {
    implementation("com.github.radardata:sdk-verify-android:0.1.0")
}
```

Android 7 (API 24) ou mais novo.

## O app não cria a sessão, e isso é de propósito

Quem cria a sessão é o **seu backend**, com o SDK do servidor (`@radardata/sdk` em Node ou
`radardata/sdk-php`), e o app recebe só a url dela. Criar a sessão no app exigiria o token de
API da empresa dentro do aparelho, e token de API em aparelho de usuário final é token vazado:
com ele dá para gastar o saldo da conta.

```kotlin
// No seu backend (Node), não no app:
// const sessao = await radar.verify.criarSessao("seu-fluxo", { cpf }, { reference_id: pedidoId })
// devolva sessao.url ao app
```

## Uso

### Tela cheia, com uma linha

```kotlin
import br.com.radardata.verify.ContratoVerify
import br.com.radardata.verify.Desfecho
import br.com.radardata.verify.SessaoDoVerify

class CadastroActivity : ComponentActivity() {
    private val verify = registerForActivityResult(ContratoVerify()) { desfecho ->
        when (desfecho) {
            is Desfecho.Concluido -> mostrarAguarde(desfecho.estado.resultado) // APROVADO, REPROVADO, REVISAO_MANUAL
            Desfecho.Cancelado -> voltarAoInicio()                         // o seu backend cancelou
            is Desfecho.Erro -> pedirSessaoNova(desfecho.erro)              // expirou, sem rede, saiu do fluxo
        }
    }

    fun abrir(urlDoBackend: String) = verify.launch(SessaoDoVerify(urlDoBackend))
}
```

Voltar pelo botão do Android sai do fluxo e devolve `Desfecho.Erro` com código
`saiu_do_fluxo`. A sessão continua aberta no servidor, e o app pode abrir a mesma url de novo.

### Embutido numa tela Compose

```kotlin
import br.com.radardata.verify.VerifyRadarData

@Composable
fun ProvaDeVida(url: String, aoTerminar: (Desfecho) -> Unit) {
    VerifyRadarData(
        sessao = url,
        onDesfecho = aoTerminar,                 // uma vez só por sessão
        onEstado = { estado -> /* opcional: acompanhar o passo atual */ },
    )
}
```

## Quem decide a aprovação

**O seu backend, pelo webhook** (`session.completed`), nunca o app. O desfecho que chega ao
aparelho serve para a tela: quem controla o aparelho controla o que o app vê. Libere o cadastro
no servidor, depois de conferir a sessão pela API.

## Permissões

O SDK já declara `INTERNET` e `CAMERA` e pede a câmera na hora em que a página precisa dela.
Você não precisa fazer nada para a prova de vida.

**Localização**, só se o seu fluxo pede: declare no `AndroidManifest.xml` do app.

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

Sem a declaração, o SDK nega a localização e o fluxo segue sem ela, em vez de travar.

O "enviar um arquivo" do passo de documento abre o seletor de arquivos do Android.

## Como o SDK sabe que terminou

Ele **pergunta ao servidor** a cada 5 segundos, em vez de espiar a navegação da página.
Depender da url de redirecionamento ou de mensagem da página amarraria o SDK ao HTML do fluxo,
que muda sem aviso; o estado da sessão é contrato. Falha de rede numa conferência não encerra
nada: a próxima tenta de novo.

## Erros

`Desfecho.Erro.erro` é um `ErroVerify`, com `codigo` e `status` quando a api respondeu:

| Classe | Quando | O que fazer |
|---|---|---|
| `SessaoInvalida` | sessão expirou ou não existe (401, 404, 410) | pedir uma sessão nova ao seu backend |
| `PassoForaDeOrdem` | o passo pedido não é o atual (409) | abrir de novo |
| `BiometriaIndisponivel` | biometria fora do ar do nosso lado (503) | tentar mais tarde |
| `FalhaDeRede` | sem resposta | conferir a rede e abrir de novo |
| `ErroVerify` com código `saiu_do_fluxo` | a pessoa voltou pelo botão do Android | abrir de novo a mesma url |
| `ErroVerify` com código `pagina_nao_carregou` | a página do fluxo não abriu | abrir de novo |

## O que é nativo hoje

Nesta versão o fluxo roda em WebView, que é a mesma página testada em produção, com prova de
vida completa. O caminho nativo está preparado: passe um `provedorDeProvaDeVida` ao
`VerifyRadarData` e o passo de prova de vida sai do WebView sem mudar mais nada.

## App de exemplo

`exemplo/` pede a sessão ao backend de exemplo do SDK React Native
(`sdk-verify-react-native/example/backend`, porta 8799) e abre o Verify pelos dois caminhos.

```bash
adb reverse tcp:8799 tcp:8799
./gradlew :exemplo:installDebug
```

## Desenvolvimento

```bash
./gradlew :verify:testDebugUnitTest      # testes do núcleo, na JVM
./gradlew :verify:assembleRelease         # o .aar
./gradlew :verify:publishToMavenLocal     # o que o JitPack publica
```

## Licença

MIT.
