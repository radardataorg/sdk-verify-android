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
    implementation("com.github.radardataorg:sdk-verify-android:v0.1.0")
}
```

Requisitos do app: Android 7 (API 24) ou mais novo, `compileSdk` 35 ou mais e Kotlin 2.1 ou
mais (o SDK é compilado com Kotlin 2.2).

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
            is Desfecho.Concluido -> mostrarAguarde(desfecho.estado.resultado) // status completed ou failed
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

## Segurança da tela

- **Só abre url do Verify.** A url precisa ser https, no formato `/s/<token>`, em
  `verify.radardata.com.br` ou num domínio que você informar. Qualquer outra vira
  `Desfecho.Erro` com código `url_invalida`, sem chamar a api. Se o seu app recebe a url por
  deep link, não repasse sem conferir: é assim que uma réplica do Verify entraria no seu app.
- **Domínio próprio:** `SessaoDoVerify(url, dominiosPermitidos = setOf("verify.suaempresa.com.br"))`,
  ou o mesmo parâmetro no `VerifyRadarData`.
- **Câmera e localização só para a página do fluxo.** Link para outro site abre no navegador,
  fora do app, onde a pessoa vê o endereço.
- **Sem print nem gravação de tela** enquanto o Verify está aberto, porque a tela mostra
  documento e rosto. Para desligar: `protegerTela = false`.

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

Sem a declaração, o SDK nega a localização. Se a localização do fluxo é opcional, ele segue
sem ela; **se é obrigatória, a pessoa não passa do consentimento**, então declare. Para a Play
Store não exigir GPS do aparelho, declare também:

```xml
<uses-feature android:name="android.hardware.location" android:required="false" />
```

O "enviar um arquivo" do passo de documento abre o seletor de arquivos do Android.

## Desfechos

- `Desfecho.Concluido`: a pessoa terminou. `estado.status` é `completed`, ou `failed` quando o
  servidor encerrou sem aprovar (tentativas esgotadas, por exemplo), com o motivo em
  `estado.motivo`. Pela Activity, o estado vem sem `passos`, `branding` e `cru`.
- `Desfecho.Cancelado`: o seu backend cancelou a sessão pela API.
- `Desfecho.Erro`: não deu para seguir (tabela abaixo).

Voltar, página que não carregou ou WebView que caiu: antes de entregar o erro, o SDK pergunta ao
servidor por até 3 s. Se a pessoa já tinha terminado, chega o `Concluido`.

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
| `ErroVerify` com código `pagina_caiu` | o WebView parou (falta de memória no aparelho) | abrir de novo |
| `ErroVerify` com código `url_invalida` | a url não é de uma sessão do Verify | conferir o que o backend devolveu |

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
