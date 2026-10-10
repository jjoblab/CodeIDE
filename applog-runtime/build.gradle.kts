// applog-runtime — bibliothèque de débogage injectée dans les builds debug
// des projets de l'utilisateur (mission « Exécuter » R2, ADR 0103).
//
// CONTRAINTES STRUCTURELLES (ADR 0103) :
// - Java PUR : cette bibliothèque vit dans l'APPLICATION de l'utilisateur —
//   zéro dépendance externe, pas même kotlin-stdlib (une app Java pure ne
//   doit rien recevoir de plus que ce qu'elle a déjà).
// - Java 8 : consommable par n'importe quel projet Android moderne.
// - minSdk 21 : le manifeste de la bibliothèque fusionne dans celui de
//   l'app cible sans y élever le minSdk.
// - JAMAIS active hors debug : le pont vérifie FLAG_DEBUGGABLE à l'exécution.
//
// L'AAR produit est un ARTEFACT DE BUILD : recopié vers les assets de
// l'app (`copierAarVersAssets`, comme le JAR orchestrateur — ADR 0040),
// jamais versionné dans le dépôt. Le POM est écrit à la main : SANS aucune
// dépendance — c'est lui qui garantit « zéro permission, zéro dépendance
// ajoutée à l'app de l'utilisateur ».
//
// Version de la coordonnée maven (jo.codeide:applog-runtime) : miroir de
// jo.codeide.tooling.protocol.ApplogCoordonnees (outil/protocole) —
// incrémenter les DEUX ensemble.

plugins {
    id("com.android.library")
}

/** Version de la coordonnée expédiée (miroir : tooling/protocol ApplogCoordonnees). */
val VERSION_APPLOG = "1.0.0"

android {
    namespace = "jo.codeide.applog"
    compileSdk = 37
    compileSdkMinor = 2

    buildFeatures {
        // Interfaces AIDL du pont (jumelles de celles du module app —
        // même paquet, même protocole Binder).
        aidl = true
    }

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    // Java 8 partout : le bytecode doit être consommable par n'importe
    // quel projet Android, même compilé avec une chaîne ancienne.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // L'AAR expédié est TOUJOURS le variant release (les contrôles debug
    // se font à l'EXÉCUTION sur FLAG_DEBUGGABLE, pas à la compilation).
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // RIEN. C'est la garantie donnée à l'utilisateur (ADR 0103) : la
    // bibliothèque n'ajoute AUCUNE dépendance à son application.
    testImplementation(libs.junit4)
}

// ---------------------------------------------------------------------------
// Livraison de l'artefact vers les assets de l'app (miroir de la convention
// du JAR orchestrateur, ADR 0040) : l'AAR + le POM écrit à la main sont
// recopiés vers app/src/main/assets/applog/ ; le daemon de l'app les
// déploiera en dépôt maven local (filesDir/applog-repo).
//
// Cache de configuration (Gradle 9) : TOUTES les valeurs capturées par les
// actions de tâche sont résolues à la CONFIGURATION (vals locales du bloc
// de configuration) — une action qui relirait une propriété de script à
// l'exécution serait refusée par le cache de configuration.
// ---------------------------------------------------------------------------
val dossierAssetsAppLog = rootProject.file("app/src/main/assets/applog")

tasks.register<Copy>("copierAarVersAssets") {
    group = "build"
    description = "Recopie l'AAR applog-runtime + le POM vers les assets de l'app (mission Exécuter R2)."
    // Le variant RELEASE : c'est lui qui est expédié (les contrôles debug
    // se font à l'EXÉCUTION sur FLAG_DEBUGGABLE, pas à la compilation).
    dependsOn("assembleRelease")
    val nomAarFinal = "applog-runtime-$VERSION_APPLOG.aar"
    val ciblePom = dossierAssetsAppLog.resolve("applog-runtime-$VERSION_APPLOG.pom")
    val contenuPom =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <!-- POM écrit à la main (ADR 0103) : AUCUNE dépendance — la
               bibliothèque injectée n'apporte rien d'autre que ses
               classes à l'app cible. -->
          <modelVersion>4.0.0</modelVersion>
          <groupId>jo.codeide</groupId>
          <artifactId>applog-runtime</artifactId>
          <version>$VERSION_APPLOG</version>
          <packaging>aar</packaging>
        </project>
        """.trimIndent()
    from(layout.buildDirectory.file("outputs/aar/applog-runtime-release.aar")) {
        rename { nomAarFinal }
    }
    into(dossierAssetsAppLog)
    doLast {
        ciblePom.writeText(contenuPom)
    }
}

tasks.register("controlerAarAssets") {
    group = "verification"
    description = "Contrôle ADR 0103 : l'AAR applog-runtime et son POM sont présents et non vides dans les assets."
    dependsOn("copierAarVersAssets")
    val aarAttendu = dossierAssetsAppLog.resolve("applog-runtime-$VERSION_APPLOG.aar")
    val pomAttendu = dossierAssetsAppLog.resolve("applog-runtime-$VERSION_APPLOG.pom")
    doLast {
        if (!aarAttendu.isFile) {
            throw org.gradle.api.GradleException(
                "AAR applog-runtime absent des assets : $aarAttendu (exécuter :applog-runtime:copierAarVersAssets)",
            )
        }
        if (aarAttendu.length() == 0L) {
            throw org.gradle.api.GradleException("AAR applog-runtime vide dans les assets : $aarAttendu")
        }
        if (!pomAttendu.isFile) {
            throw org.gradle.api.GradleException("POM applog-runtime absent des assets : $pomAttendu")
        }
    }
}

