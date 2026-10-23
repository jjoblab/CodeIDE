// CodeIDE — paramètres du build multi-modules.
// Modules de la section 5.1 du prompt maître (18 à la fin de la Phase 1) ;
// la Phase 2 (terminal intégré, prompt compagnon Terminal-1) ajoute
// core:bootstrap puis core:terminal-runtime et feature:terminal.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Dépôts frères en BINAIRE — VOIE PAR DÉFAUT (arbitrage propriétaire du
// 2026-10-18, « consommation binaire en premier ») : un frère modifié est
// REPUBLIÉ (publishToMavenLocal pour itérer en ~30 s, puis tag → CI →
// catalogue via scripts/bump-frere.sh pour stabiliser) ; le composite ne
// reste qu'un opt-in pour une session de chirurgie croisée multi-dépôts.
// GitHub Packages exige une authentification MÊME pour un paquet public
// (vérifié le 2026-10-17 : 401 sans jeton). Les filtres de contenu sont
// stricts — aucun lookup pour les autres groupes.
val jetonPackages: String? =
    providers.gradleProperty("github.packages.pat")
        .orElse(providers.environmentVariable("CODEIDE_PACKAGES_PAT"))
        .orNull

// Interrupteur du mode composite (opt-in) : propriété Gradle
// `freres.sources` ou variable d'environnement CODEIDE_FRERES_SOURCES.
// Sans lui, les frères sont TOUJOURS consommés en binaire — même quand
// un checkout traîne sur le disque (défaut « binaire en premier »).
val modeSources: Boolean =
    providers.gradleProperty("freres.sources").isPresent ||
        providers.environmentVariable("CODEIDE_FRERES_SOURCES").isPresent

// Binaire sans jeton possible : des artefacts frères déjà publiés en
// local (~/.m2 — publishToMavenLocal) suffisent aux trois groupes. Le
// registre mavenLocal déclaré plus bas ne résout QUE ces groupes —
// aucun autre artefact local ne masque Central.
val mavenLocalFreres: Boolean = listOf(
    "com/github/jjoblab/LayoutLib",
    "com/github/jjoblab/codeide-tooling",
    "com/github/jjoblab/codeide-lsp",
).any { File(System.getProperty("user.home"), ".m2/repository/$it").isDirectory }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Bibliothèque d'édition code-editor (prompt compagnon, section 2.2)
        // et terminal Termux (ADR 0036) : JitPack pour un build autonome
        // sans jeton — GitHub Packages resterait l'alternative documentée
        // pour un usage local. FILTRE STRICT (2026-10-17) : JitPack suit
        // la convention com.github.<user>.<repo> et matche donc AUSSI les
        // coordonnées des dépôts frères (codeide-tooling/lsp, LayoutLib) —
        // sans filtre, la résolution binaire partait en timeout sur
        // jitpack.io (JitPack tente de builder le dépôt à la volée) avant
        // d'atteindre le registre Packages.
        maven {
            url = uri("https://jitpack.io")
            content {
                // Seuls ces DEUX groupes viennent de JitPack (inventaire du
                // 2026-10-17 — tout autre com.github.* = registre Packages
                // des frères). La liste doit être tenue à jour : tout
                // nouveau groupe JitPack s'ajoute ici.
                includeGroup("com.github.jjoblab.code-editor")
                includeGroup("com.github.termux.termux-app")
            }
        }
        // Tooling Gradle (G2) : org.gradle:gradle-tooling-api ne vit que sur le
        // dépôt officiel de Gradle — les métadonnées Maven Central de cette
        // coordonnée sont périmées (7.3-snapshot de 2021). Vérifié le
        // 2026-09-24 (voir docs/TOOLING.md).
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases/") }
        // LSP Kotlin (K1, ADR 0115) : org.jetbrains:syntax-api — l'arbre de
        // construction syntaxique contre lequel la grammaire vendorée du
        // compilateur Kotlin est écrite. Pas sur Central (artefact de la
        // plateforme IntelliJ), publié sur intellij-dependencies ; accès
        // par le cache-redirector public de JetBrains (plus stable que
        // packages.jetbrains.team, même contenu). Filtre strict : rien
        // d'autre ne vient de là.
        maven {
            url = uri("https://cache-redirector.jetbrains.com/intellij-dependencies")
            content {
                // Le filtre doit admettre les coordonnées RACINES (métadonnées
                // KMP) ET leurs variantes par cible (-jvm…) — un filtre ne
                // nommant que la racine résout les métadonnées puis ne
                // trouve aucun jar. lang-syntax = arbre syntaxique KMP
                // (SyntaxNode/LeafSyntaxNode) dont syntax-api dépend.
                includeModuleByRegex("org\\.jetbrains", "syntax-api.*")
                includeModuleByRegex("org\\.jetbrains", "lang-syntax.*")
            }
        }
        // Chemin binaire, vitesse d'itération : mavenLocal EN TÊTE — le
        // publishToMavenLocal d'un frère modifié (même version) y fait
        // ombre au registre distant et est consommé immédiatement, sans
        // attendre la CI. FILTRE STRICT : seuls les trois groupes frères
        // y résolvent.
        mavenLocal {
            content {
                includeGroup("com.github.jjoblab.LayoutLib")
                includeGroup("com.github.jjoblab.codeide-tooling")
                includeGroup("com.github.jjoblab.codeide-lsp")
            }
        }
        if (jetonPackages != null) {
            maven {
                url = uri("https://maven.pkg.github.com/jjoblab/LayoutLib")
                content { includeGroup("com.github.jjoblab.LayoutLib") }
                credentials {
                    username = "jjoblab"
                    password = jetonPackages
                }
            }
            maven {
                url = uri("https://maven.pkg.github.com/jjoblab/codeide-tooling")
                content { includeGroup("com.github.jjoblab.codeide-tooling") }
                credentials {
                    username = "jjoblab"
                    password = jetonPackages
                }
            }
            maven {
                url = uri("https://maven.pkg.github.com/jjoblab/codeide-lsp")
                content { includeGroup("com.github.jjoblab.codeide-lsp") }
                credentials {
                    username = "jjoblab"
                    password = jetonPackages
                }
            }
        }
    }
}

// Convention plugins de build-logic (build inclus).
includeBuild("build-logic")

// LayoutLib (github.com/jjoblab/LayoutLib — projet personnel du
// propriétaire) : moteur de rendu des previews XML. MODE BINAIRE PAR
// DÉFAUT (arbitrage du 2026-10-18) : COORDONNÉES
// com.github.jjoblab.LayoutLib:* résolues de mavenLocal
// (publishToMavenLocal du frère) ou de GitHub Packages (publication
// v3.4.7 — workflow publish.yml du dépôt, jeton de lecture requis).
// Décision GitHub-only maintenue : AUCUNE publication JitPack. Le
// COMPOSITE (substitution des coordonnées par les projets du build
// inclus) ne s'active que sur opt-in `freres.sources` — l'épinglage
// vaut alors ref git ; en binaire, l'épinglage = version du catalogue.
// Le dossier est résolu dans cet ordre : propriété Gradle
// `layoutLib.dir`, variable d'environnement CODEIDE_LAYOUTLIB_DIR,
// puis défaut `../LayoutLib`.
val dossierLayoutLib: File =
    providers.gradleProperty("layoutLib.dir")
        .orElse(providers.environmentVariable("CODEIDE_LAYOUTLIB_DIR"))
        .map(rootDir::resolve)
        .orElse(rootDir.resolve("../LayoutLib"))
        .get()
if (modeSources && dossierLayoutLib.resolve("settings.gradle.kts").isFile) {
    includeBuild(dossierLayoutLib) {
        dependencySubstitution {
            substitute(module("com.github.jjoblab.LayoutLib:module-inflater")).using(project(":module-inflater"))
            substitute(module("com.github.jjoblab.LayoutLib:module-resources")).using(project(":module-resources"))
            substitute(module("com.github.jjoblab.LayoutLib:module-drawables")).using(project(":module-drawables"))
            substitute(module("com.github.jjoblab.LayoutLib:module-themes")).using(project(":module-themes"))
            substitute(module("com.github.jjoblab.LayoutLib:module-attributes")).using(project(":module-attributes"))
            substitute(module("com.github.jjoblab.LayoutLib:module-layout")).using(project(":module-layout"))
        }
    }
} else if (jetonPackages == null && !mavenLocalFreres) {
    throw GradleException(
        "LayoutLib introuvable en binaire : $dossierLayoutLib\n" +
            "Trois options : (1) un jeton GitHub read:packages (propriété " +
            "github.packages.pat ou variable CODEIDE_PACKAGES_PAT) pour " +
            "résoudre les coordonnées com.github.jjoblab.LayoutLib:* " +
            "depuis GitHub Packages ; (2) publier le frère en local — " +
            "publishToMavenLocal dans le dépôt LayoutLib — et relancer " +
            "sans jeton ; (3) -Pfreres.sources (ou CODEIDE_FRERES_SOURCES=1) " +
            "avec le checkout frère (git clone " +
            "https://github.com/jjoblab/LayoutLib.git ../LayoutLib, ou " +
            "propriété layoutLib.dir) — mode composite. " +
            "docs/PREVIEW.md §P2.",
    )
}

// Tooling Gradle (dépôt jjoblab/codeide-tooling — bascule du 2026-10-17,
// docs/archive/PLAN_SEPARATION_DEPOTS.md §9) : protocol, api, server et testing
// consommés en COORDONNÉES EXTERNES com.github.jjoblab.codeide-tooling:*
// (publication Packages v1.0.0, workflow publish.yml) — miroir exact du
// mécanisme LayoutLib ci-dessus. MODE BINAIRE PAR DÉFAUT (arbitrage du
// 2026-10-18) : résolution de mavenLocal (publishToMavenLocal du frère)
// ou de Packages (jeton de lecture requis) ; le COMPOSITE (build inclus,
// substitution) ne s'active que sur opt-in `freres.sources` —
// l'épinglage vaut alors ref git, sinon version du catalogue. Dossier :
// propriété `tooling.dir`, variable CODEIDE_TOOLING_DIR, défaut
// `../codeide-tooling`.
val dossierTooling: File =
    providers.gradleProperty("tooling.dir")
        .orElse(providers.environmentVariable("CODEIDE_TOOLING_DIR"))
        .map(rootDir::resolve)
        .orElse(rootDir.resolve("../codeide-tooling"))
        .get()
if (modeSources && dossierTooling.resolve("settings.gradle.kts").isFile) {
    includeBuild(dossierTooling) {
        dependencySubstitution {
            substitute(module("com.github.jjoblab.codeide-tooling:protocol")).using(project(":tooling:protocol"))
            substitute(module("com.github.jjoblab.codeide-tooling:api")).using(project(":tooling:api"))
            substitute(module("com.github.jjoblab.codeide-tooling:server")).using(project(":tooling:server"))
            substitute(module("com.github.jjoblab.codeide-tooling:testing")).using(project(":tooling:testing"))
        }
    }
} else if (jetonPackages == null && !mavenLocalFreres) {
    throw GradleException(
        "codeide-tooling introuvable en binaire : $dossierTooling\n" +
            "Trois options : (1) un jeton GitHub read:packages (propriété " +
            "github.packages.pat ou variable CODEIDE_PACKAGES_PAT) pour " +
            "résoudre les coordonnées com.github.jjoblab.codeide-tooling:* " +
            "depuis GitHub Packages ; (2) publier le frère en local — " +
            "publishToMavenLocal dans le dépôt codeide-tooling — et " +
            "relancer sans jeton ; (3) -Pfreres.sources (ou " +
            "CODEIDE_FRERES_SOURCES=1) avec le checkout frère (git clone " +
            "https://github.com/jjoblab/codeide-tooling.git " +
            "../codeide-tooling, ou propriété tooling.dir) — mode " +
            "composite.",
    )
}

// Dépôt jjoblab/codeide-lsp (bascule du 2026-10-17, même recette) : les
// 8 modules du domaine lsp (model, index, java vendored, ecj-art,
// testing, host, kotlin, xml) consommés en COORDONNÉES EXTERNES
// com.github.jjoblab.codeide-lsp:* (publication Packages 1.0.0).
// MODE BINAIRE PAR DÉFAUT (arbitrage du 2026-10-18) : mavenLocal
// (publishToMavenLocal du frère) ou Packages (jeton de lecture requis) ;
// COMPOSITE sur opt-in `freres.sources` uniquement. Dossier : propriété
// Gradle `lsp.dir`, variable CODEIDE_LSP_DIR, puis défaut
// `../codeide-lsp`.
val dossierLsp: File =
    providers.gradleProperty("lsp.dir")
        .orElse(providers.environmentVariable("CODEIDE_LSP_DIR"))
        .map(rootDir::resolve)
        .orElse(rootDir.resolve("../codeide-lsp"))
        .get()
if (modeSources && dossierLsp.resolve("settings.gradle.kts").isFile) {
    includeBuild(dossierLsp) {
        dependencySubstitution {
            substitute(module("com.github.jjoblab.codeide-lsp:model")).using(project(":lsp:model"))
            substitute(module("com.github.jjoblab.codeide-lsp:index")).using(project(":lsp:index"))
            substitute(module("com.github.jjoblab.codeide-lsp:java")).using(project(":lsp:java"))
            substitute(module("com.github.jjoblab.codeide-lsp:ecj-art")).using(project(":lsp:ecj-art"))
            substitute(module("com.github.jjoblab.codeide-lsp:testing")).using(project(":lsp:testing"))
            substitute(module("com.github.jjoblab.codeide-lsp:host")).using(project(":lsp:host"))
            substitute(module("com.github.jjoblab.codeide-lsp:kotlin")).using(project(":lsp:kotlin"))
            substitute(module("com.github.jjoblab.codeide-lsp:xml")).using(project(":lsp:xml"))
        }
    }
} else if (jetonPackages == null && !mavenLocalFreres) {
    throw GradleException(
        "codeide-lsp introuvable en binaire : $dossierLsp\n" +
            "Trois options : (1) un jeton GitHub read:packages (propriété " +
            "github.packages.pat ou variable CODEIDE_PACKAGES_PAT) pour " +
            "résoudre les coordonnées com.github.jjoblab.codeide-lsp:* " +
            "depuis GitHub Packages ; (2) publier le frère en local — " +
            "publishToMavenLocal dans le dépôt codeide-lsp — et relancer " +
            "sans jeton ; (3) -Pfreres.sources (ou CODEIDE_FRERES_SOURCES=1) " +
            "avec le checkout frère (git clone " +
            "https://github.com/jjoblab/codeide-lsp.git ../codeide-lsp, ou " +
            "propriété lsp.dir) — mode composite.",
    )
}

rootProject.name = "CodeIDE"

include(":app")

// Mission « Exécuter » R2 (ADR 0103) : bibliothèque de débogage Java pure
// injectée dans les builds debug des projets de l'utilisateur — zéro
// dépendance, zéro permission, jamais active hors debug. L'AAR est un
// artefact de build recopié vers les assets de l'app (copierAarVersAssets).
include(":applog-runtime")

// Socle transverse.
include(":core:model")
include(":core:domain")
// Mission « Cycle de vie » (prompt 3 §5.1, étape B — ADR 0114) : contrat de
// session de projet (machine à états observable) et registre de fermeture
// (Fermable : ordre inverse, délai par élément, drapeau « ne pas annuler »).
// Kotlin JVM PUR — les adaptateurs des ressources réelles vivront en
// étape C (feature:editor) et D (tooling).
include(":core:session")
// Mission IA (ADR 0122) : domaine IA — Kotlin JVM PUR, explicitApi ; les
// livrables (AiMessage, ports AiProvider, cas d'usage) arrivent au lot IA-1.
include(":core:ai")
include(":core:data")
include(":core:database")
include(":core:datastore")
// Mission IA (ADR 0122) : stockage IA — KeyVault (Keystore) et
// AiSettingsStore (DataStore dédié), lot IA-2 (ADR 0124 §1).
include(":core:ai-storage")
include(":core:storage")
include(":core:logging")
include(":core:crash")
include(":core:ui")
include(":core:testing")

// Phase 2 — terminal intégré (prompt compagnon Terminal-1, section 2.1) :
// localisation des outils et environnement de sous-processus.
include(":core:bootstrap")
include(":core:terminal-runtime")

// Fonctionnalités.
include(":feature:onboarding")
include(":feature:home")
include(":feature:newproject")
include(":feature:settings")
include(":feature:diagnostics")
include(":feature:install")
include(":feature:editor")
// Terminal T5 : écran plein écran du terminal (prompt Terminal-1, section 5).
include(":feature:terminal")
// Previews PE6-0 (maquette docs/mockups/apercu-xml.html § « Activité
// dédiée ») : PreviewActivity — le panneau d'aperçu XML quitte
// l'overlay d'EditorActivity pour son propre écran, sur le patron du
// terminal (AppNavigator.openPreview, session partagée par le port
// SourceApercu de :core:domain).
include(":feature:preview")
// Mission IA (ADR 0122) : tiroir IA (Chat/Agent/Historique), écrans
// Paramètres → IA, consentement — exception feature explicite :feature:ai
// → :core:ai. Échafaudage IA-0 ; fragment du tiroir au lot IA-3.
include(":feature:ai")

// Mission IA (ADR 0122/0123) : transport IA — Kotlin JVM PUR, UNIQUE
// point de sortie réseau IA (AiHttpTransport, HttpURLConnection) ;
// adaptateurs Anthropic et « compatible OpenAI » au lot IA-1.
include(":ai:providers")

// Outils (hors application — étape 9, ADR 0019 : harnais de vérification).
include(":tools:generateur")

// Phase 2 — tooling Gradle client-serveur (prompt compagnon Tooling,
// section 2.1) : protocol, api, server et testing ont rejoint le dépôt
// séparé jjoblab/codeide-tooling (bascule du 2026-10-17, recette §9 du
// plan) — seuls le client (G3) et le daemon (G4) restent ici, liés à
// :core:domain (audit de frontière §8 du plan).
include(":tooling:client")
include(":tooling:daemon")

// Mission « LSP » (moteurs Java/Kotlin/XML, hôte, index, modèle) : les
// 8 modules :lsp:* ont rejoint le dépôt séparé jjoblab/codeide-lsp
// (bascule du 2026-10-17, recette §9 du plan — composite en dev,
// coordonnées com.github.jjoblab.codeide-lsp:*). L'app et
// feature:editor ne voient que :lsp:host — désormais externe.
// :preview:xml (mission « Previews », PREVIEW.md étape PE1, P1–P5) :
// moteur de preview XML sur LayoutLib (jjoblab, build composite en source
// — la substitution des coordonnées com.github.jjoblab.LayoutLib:* vit
// plus haut dans ce fichier) — rendu bitmap off-screen sur fil dédié
// (in-process à PE1, processus :preview à PE2), validation XmlPullParser,
// dernier rendu valide conservé pendant les frappes. La vue (VuePreviewXml)
// et le moteur sont consommés par feature:editor ; la dépendance LayoutLib
// reste confinée ici.
include(":preview:xml")
// :preview:compose (mission « Previews », PREVIEW.md §4 — PC1/PC2, C1) :
// moteur de preview Compose PAR INTERPRÉTEUR sur l'arbre d'appels neutre
// du LSP Kotlin (previews(uri) v1.4.0) — le runtime Compose y est
// EMBARQUÉ comme bibliothèque d'interprétation (ADR 0002 intact : pas
// de Compose pour l'UI CodeIDE), le rendu vit dans le processus :preview
// (PC4 : garde-fous + sandbox par construction — le programme interprété
// n'exécute QUE les composables du registre), streaming bitmap vers la
// carte « goutte d'eau » de l'éditeur. La dépendance Compose reste
// confinée ici.
include(":preview:compose")
