# Tooling Gradle (client-serveur)

Référence du prompt compagnon « Tooling Gradle (client-serveur) » v1.0 —
addendum des prompts maître, EditorActivity et Terminal. Ce document
trace les versions **vérifiées** (exigence §8 : jamais mémorisées) et
l'avancement des étapes G1-G6.

## Versions vérifiées (2026-09-24, jour de G1)

| Composant | Version retenue | Vérification |
|---|---|---|
| `org.gradle:gradle-tooling-api` | **9.7.1** | `repo.gradle.org/gradle/libs-releases` — dernière stable (9.8.0 encore en RC), **exactement alignée** sur le Gradle du wrapper du projet (9.7.1). Attention : les métadonnées Maven Central de cette coordonnée sont périmées (dernière « release » affichée : 7.3-snapshot de 2021) — le dépôt de référence est celui de Gradle. |
| Dépôt à ajouter (G2) | `https://repo.gradle.org/gradle/libs-releases/` | `dependencyResolutionManagement` de `settings.gradle.kts` — Maven Central ne suffit pas. |
| JDK minimal du **daemon Gradle réel** | **Java 17** (Gradle 9.x) | Le bootstrap installe `openjdk-17` : compatible sans changement. L'orchestrateur (`tooling:server`) sera compilé jvmTarget 17. |
| Plugin JAR unique (fat jar, G2) | **`com.gradleup.shadow` 9.6.1** | Successeur communautaire maintenu de `com.github.johnrengelman.shadow` (fin de vie), vérifié sur le portail de plugins Gradle. |
| `kotlinx-serialization-json` | **1.9.0** | Cataloge du projet (compagnon du Kotlin 2.2.10) — utilisée par `tooling:protocol` dès G1. |
| `kotlinx-coroutines-core` (JVM) | celle du catalogue | Variante **JVM** pour `tooling:server` (§4.7) — pas la variante Android. |

## Décisions d'architecture (reprises du prompt, §1 — validées par
l'expérience de la version antérieure)

- L'app Android est le **serveur** du socket, le process JVM le
  **client** — écoute ouverte *avant* le lancement du process, aucun
  fichier de découverte, aucun polling.
- **Unix Domain Socket** (`LocalSocket`/`LocalServerSocket`), pas TCP ;
  repli TCP `127.0.0.1` documenté mais non retenu.
- **JSON `kotlinx.serialization`** avec discriminant de type natif —
  gRPC/Protobuf écarté (transport Netty-epoll natif par ABI, coût
  disproportionné).
- **Négociation de version au handshake** + secret aléatoire échangé
  en argument de lancement (namespace abstrait Android non protégé par
  permissions — §4.4 du prompt).

## Étapes (§9 — ordre non négociable)

| # | Étape | Version | État | Contenu |
|---|---|---|---|---|
| G1 | `tooling:protocol` + `tooling:testing` | 0.26.0 | **Terminé** | Framing (garde DoS 16 Mo, troncature typée, EOF propre distinguée), catalogue des 24 messages (requêtes + événements, `ErrorCode` typé), `ProtocolJson` (`ignoreUnknownKeys`), constantes ; **24 fichiers dorés** figeant le format câble (tout renommage/retrait de champ fait échouer le test d'adéquation) ; 4 fixtures Gradle réelles (minimal, erreur de compilation, multi-module, tâche longue annulable) copiées en temporaire, jamais construites en place. Tests bloquants au vert avant toute ligne server/client (§3) : 8 round-trip, 10 framing, 3 fixtures. ADR 0039. |
| G2 | `tooling:server` (JVM) | 0.27.0 | — | Orchestrateur Tooling API, tests d'intégration réels contre les fixtures (§7.2), fat jar `com.gradleup.shadow`, `repo.gradle.org` dans les dépôts. |
| G3 | `tooling:client` | 0.28.0 | — | `GradleSocketServer`, `SharedFlow` non conflatant (buffer 4096, `SUSPEND`), `GradleToolingRepository` (core:domain). |
| G4 | `tooling:daemon` | 0.29.0 | — | `DaemonManager` sur `NativeProcessLauncher` (jamais redéfini), `JarDeployer`, health check, premier bout-en-bout réel. |
| G5 | `GradleService` + intégration éditeur | 0.30.0 | — | Onglets Sortie/Problèmes fonctionnels, diagnostics inline `session.setDiagnostics`, journal unifié, actions Synchroniser/Exécuter. |
| G6 | Robustesse et audit | 0.31.0 | — | Chaos (§7.5), timeouts, `docs/TOOLING.md` final, archive. Y sont aussi absorbés les points restants de T7 (ADR targetSdk sur appareil réel, revue mémoire LeakCanary des sessions) : ils exigent un appareil — même moment de vérité. |

> Ordre révisé le 2026-09-24 à la demande de l'utilisateur : le tooling
> démarre après T6 (le prompt exigeait « Terminal terminé » ; T7 est un
> audit finitions dont les points ouverts exigent l'appareil — ils sont
> absorbés par G6). Les anciennes étapes 26-29 du plan générique
> (diagnostics, exécution, LSP, formatage) : diagnostics/Sortie couverts
> par G5, exécution par G2-G4, LSP et formatage gardent leurs prompts
> compagnons dédiés après le tooling Gradle.
