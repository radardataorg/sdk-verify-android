plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

val versaoDoSdk = "0.1.0"

// O JitPack builda pela tag e passa a versao dela em VERSION (v0.1.0): publica com essa, para a
// coordenada que o cliente escreve resolver. Fora do JitPack, vale a do arquivo.
val versaoPublicada = System.getenv("VERSION") ?: versaoDoSdk

group = "com.github.radardataorg"
version = versaoPublicada

android {
    namespace = "br.com.radardata.verify"
    compileSdk = 36

    defaultConfig {
        // Android 7: cobre praticamente todo aparelho em uso, e o WebView dele ja tem getUserMedia.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "VERSAO", "\"$versaoDoSdk\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Modifier e Composable aparecem na assinatura publica: quem usa precisa deles no classpath.
    api(platform("androidx.compose:compose-bom:2025.06.01"))
    api("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // O org.json do Android so existe no aparelho: nos testes de JVM, o de verdade.
    testImplementation("org.json:json:20250517")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.radardataorg"
                artifactId = "sdk-verify-android"
                version = versaoPublicada
            }
        }
    }
}
