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

// Dépôts frères en BINAIRE (fallback du mode composite) : GitHub Packages
// exige une authentification MÊME pour un paquet public (vérifié le
// 2026-10-17 : 401 sans jeton). Les filtres de contenu sont stricts —
// aucun lookup pour les autres groupes — et le bloc est ABSENT tant qu'
// aucun jeton n'est fourni : le mode composite (frères clonés, la
// convention CI et sandbox) n'en a jamais besoin.
val jetonPackages: String? =
    providers.gradleProperty("github.packages.pat")
        .orElse(providers.environmentVariable("CODEIDE_PACKAGES_PAT"))
        .orNull

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
// propriétaire) : moteur de rendu des previews XML. MODE HYBRIDE
// (arbitrage propriétaire 2026-10-17) : COMPOSITE (source) par défaut
// quand le checkout frère est présent — décision GitHub-only maintenue
// pour le dev (AUCUNE publication JitPack) ; sinon COORDONNÉES BINAIRES
// com.github.jjoblab.LayoutLib:* résolues depuis GitHub Packages
// (publication v3.4.7 — workflow publish.yml du dépôt), qui exigent le
// jeton de lecture (voir jetonPackages plus haut). Le dossier est
// résolu dans cet ordre : propriété Gradle `layoutLib.dir`, variable
// d'environnement CODEIDE_LAYOUTLIB_DIR, puis défaut `../LayoutLib`
// (convention de la CI et de la sandbox). En composite, la
// SUBSTITUTION remplace les coordonnées par les projets du build inclus
// et ignore la version déclarée : l'épinglage = ref git (tag v3.4.7 =
// version du catalogue) ; en binaire, l'épinglage = version du
// catalogue.
val dossierLayoutLib: File =
    providers.gradleProperty("layoutLib.dir")
        .orElse(providers.environmentVariable("CODEIDE_LAYOUTLIB_DIR"))
        .map(rootDir::resolve)
        .orElse(rootDir.resolve("../LayoutLib"))
        .get()
if (dossierLayoutLib.resolve("settings.gradle.kts").isFile) {
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
} else if (jetonPackages == null) {
    throw GradleException(
        "LayoutLib introuvable : $dossierLayoutLib\n" +
            "Deux options : cloner le dépôt frère (git clone " +
            "https://github.com/jjoblab/LayoutLib.git ../LayoutLib) ou pointer " +
            "la propriété Gradle layoutLib.dir — mode composite ; OU fournir " +
            "un jeton GitHub read:packages (propriété github.packages.pat ou " +
            "variable CODEIDE_PACKAGES_PAT) pour résoudre les coordonnées " +
            "com.github.jjoblab.LayoutLib:* depuis GitHub Packages — " +
            "docs/PREVIEW.md §P2.",
    )
}

// Tooling Gradle (dépôt jjoblab/codeide-tooling — bascule du 2026-10-17,
// docs/PLAN_SEPARATION_DEPOTS.md §9) : protocol, api, server et testing
// consommés en COORDONNÉES EXTERNES com.github.jjoblab.codeide-tooling:*,
// intégrés en SOURCE par build composite en dev — miroir exact du mécanisme
// LayoutLib ci-dessus (propriété Gradle `tooling.dir`, variable
// d'environnement CODEIDE_TOOLING_DIR, puis défaut `../codeide-tooling`).
// MODE HYBRIDE : composite quand le frère est présent ; sinon binaire
// (publication Packages v1.0.0, workflow publish.yml — jeton de lecture
// requis). L'épinglage : ref git en composite, version du catalogue en
// binaire.
val dossierTooling: File =
    providers.gradleProperty("tooling.dir")
        .orElse(providers.environmentVariable("CODEIDE_TOOLING_DIR"))
        .map(rootDir::resolve)
        .orElse(rootDir.resolve("../codeide-tooling"))
        .get()
if (dossierTooling.resolve("settings.gradle.kts").isFile) {
    includeBuild(dossierTooling) {
        dependencySubstitution {
            substitute(module("com.github.jjoblab.codeide-tooling:protocol")).using(project(":tooling:protocol"))
            substitute(module("com.github.jjoblab.codeide-tooling:api")).using(project(":tooling:api"))
            substitute(module("com.github.jjoblab.codeide-tooling:server")).using(project(":tooling:server"))
            substitute(module("com.github.jjoblab.codeide-tooling:testing")).using(project(":tooling:testing"))
        }
    }
} else if (jetonPackages == null) {
    throw GradleException(
        "codeide-tooling introuvable : $dossierTooling\n" +
            "Deux options : cloner le dépôt frère (git clone " +
            "https://github.com/jjoblab/codeide-tooling.git ../codeide-tooling) " +
            "ou pointer la propriété Gradle tooling.dir — mode composite ; OU " +
            "fournir un jeton GitHub read:packages (propriété " +
            "github.packages.pat ou variable CODEIDE_PACKAGES_PAT) pour " +
            "résoudre les coordonnées com.github.jjoblab.codeide-tooling:* " +
            "depuis GitHub Packages.",
    )
}

// Dépôt jjoblab/codeide-lsp (bascule du 2026-10-17, même recette) : les
// 8 modules du domaine lsp (model, index, java vendored, ecj-art,
// testing, host, kotlin, xml) consommés en COORDONNÉES EXTERNES
// com.github.jjoblab.codeide-lsp:*, intégrés en SOURCE par build
// composite en dev (propriété Gradle `lsp.dir`, variable
// d'environnement CODEIDE_LSP_DIR, puis défaut `../codeide-lsp`).
// MODE HYBRIDE : composite quand le frère est présent ; sinon binaire
// (publication Packages 1.0.0 — jeton de lecture requis).
val dossierLsp: File =
    providers.gradleProperty("lsp.dir")
        .orElse(providers.environmentVariable("CODEIDE_LSP_DIR"))
        .map(rootDir::resolve)
        .orElse(rootDir.resolve("../codeide-lsp"))
        .get()
if (dossierLsp.resolve("settings.gradle.kts").isFile) {
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
} else if (jetonPackages == null) {
    throw GradleException(
        "codeide-lsp introuvable : $dossierLsp\n" +
            "Deux options : cloner le dépôt frère (git clone " +
            "https://github.com/jjoblab/codeide-lsp.git ../codeide-lsp) ou " +
            "pointer la propriété Gradle lsp.dir — mode composite ; OU fournir " +
            "un jeton GitHub read:packages (propriété github.packages.pat ou " +
            "variable CODEIDE_PACKAGES_PAT) pour résoudre les coordonnées " +
            "com.github.jjoblab.codeide-lsp:* depuis GitHub Packages.",
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
include(":core:data")
include(":core:database")
include(":core:datastore")
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
