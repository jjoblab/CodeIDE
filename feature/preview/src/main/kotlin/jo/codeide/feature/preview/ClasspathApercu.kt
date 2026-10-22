package jo.codeide.feature.preview

import jo.codeide.preview.xml.EntreeClasspath
import jo.codeide.preview.xml.GenreEntreeClasspath
import java.io.File

/**
 * Le classpath du RENDU d'aperçu (v0.166.3 — « régler la question MockView
 * pour utiliser les vraies vues ») : l'APK DEBUG DEXÉ du projet, quand un
 * build existe, devient l'entrée de [MoteurPreviewXml.definirClasspath] —
 * le pipeline G1/Phase 2 (v0.132.0) était en place mais JAMAIS ALIMENTÉ :
 * `definirClasspath` n'avait aucun appelant, le classloader projet restait
 * `null` et TOUTE vue custom du projet (`com.example.FooView`) dégradait
 * en MockView ambre (G15) alors que le maillage complet (DexViewFactory,
 * CacheClasspath, Parcel RequeteRendu) attendait ses entrées.
 *
 * **Pourquoi l'APK et pas le classpath LSP** (`.codeide/local/
 * lsp-classpath.json`, ADR 0058) : ces entrées sont des jars/dossiers de
 * `.class` JVM — `PathClassLoader` sur ART ne charge QUE du dex, le
 * dexage D8 promis en v0.133 n'est jamais venu ; l'APK debug du build
 * ON-DEVICE (AGP le dexifie déjà) contient les classes du projet ET ses
 * dépendances aux BONNES versions, chargeable NATIVEMENT. Sans APK (projet
 * jamais buildé), la liste est VIDE : le repli honnête reste MockView +
 * problème « classe absente » — et les widgets Material/AndroidX déjà
 * rendus par le ViewFactory de LayoutLib via le classloader de l'IDE ne
 * changent pas.
 *
 * La fraîcheur : l'APK reflète le DERNIER build (l'aperçu d'une vue
 * modifiée depuis reste celui du build, comme tout éditeur qui rend le
 * code COMPILÉ) ; le rendu repart à chaque nouveau build car la signature
 * content-hash de [jo.codeide.preview.xml.CacheClasspath] change.
 *
 * FONCTIONS PURES (java.io uniquement) — testables sans Robolectric.
 */
internal object ClasspathApercu {
    /**
     * Les entrées de rendu du projet [racine] : l'APK debug le plus
     * récent en une entrée [GenreEntreeClasspath.APK], ou une liste VIDE
     * (aucun build — repli MockView documenté).
     */
    internal fun entrees(racine: String): List<EntreeClasspath> =
        apkDebugLePlusRecent(racine)
            ?.let { listOf(EntreeClasspath(it, GenreEntreeClasspath.APK)) }
            ?: emptyList()

    /**
     * L'APK debug le plus RÉCENT du projet : les sorties standards d'AGP
     * (`build/outputs/apk/debug/`), module `app` d'abord, PUIS tout module
     * direct de la racine, PUIS la racine elle-même — le plus récent
     * gagne sur les doublons (multi-modules, variantes suffixées
     * `-unsigned`/`-signed`).
     *
     * @return le chemin POSIX de l'APK, ou `null` si aucun build ne l'a
     *         produit.
     */
    @Suppress("ReturnCount") // 3 issues naturelles : vide, module app, scan modules/racine
    internal fun apkDebugLePlusRecent(racine: String): String? {
        val base = racine.trimEnd('/')
        if (base.isEmpty()) return null

        // 1. Le module `app` standard (projet-modèle CodeIDE).
        val direct = File(base, CHEMIN_APP)
        if (direct.isDirectory) {
            lePlusRecent(direct)?.let { return it }
        }

        // 2. TOUT module direct de la racine (`app`, `feature`, `core`…
        //    multi-modules) : le dossier des sorties qui existe gagne.
        val parModule =
            File(base)
                .listFiles { dossier -> dossier.isDirectory && dossier.name != "build" }
                .orEmpty()
                .asSequence()
                .map { File(it, CHEMIN_BUILD) }
                .filter { it.isDirectory }
                .flatMap {
                    it
                        .listFiles { fichier -> fichier.isFile && fichier.name.endsWith(".apk") }
                        .orEmpty()
                        .asSequence()
                }.maxByOrNull { it.lastModified() }
        if (parModule != null) return parModule.path

        // 3. La racine elle-même (projet Android sans module applicatif
        //    — `com.android.application` posé à la racine).
        val racineSorties = File(base, CHEMIN_BUILD)
        if (racineSorties.isDirectory) {
            lePlusRecent(racineSorties)?.let { return it }
        }
        return null
    }

    /** L'APK le plus récent d'un dossier de sorties, ou `null`. */
    private fun lePlusRecent(dossier: File): String? =
        dossier
            .listFiles { fichier -> fichier.isFile && fichier.name.endsWith(".apk") }
            .orEmpty()
            .maxByOrNull { it.lastModified() }
            ?.path

    /** `build/outputs/apk/debug` — le chemin standard des sorties APK. */
    private const val CHEMIN_BUILD: String = "build/outputs/apk/debug"

    /** `app/build/outputs/apk/debug` — le chemin standard du module app. */
    private const val CHEMIN_APP: String = "app/$CHEMIN_BUILD"
}
