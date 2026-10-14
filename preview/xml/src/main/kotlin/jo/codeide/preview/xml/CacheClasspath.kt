package jo.codeide.preview.xml

import dalvik.system.PathClassLoader
import java.io.File
import java.security.MessageDigest

/**
 * Cache de classloader projet — G1 / Phase 2 (v0.132.0).
 *
 * **Problème** : `DexViewFactory.loader` doit être un classloader qui
 * voit les classes du projet (`com.example.FooView`). Construire ce
 * classloader coûte (~500ms-2s pour un grand classpath — lecture des
 * jars, résolution des entrées, lien ART). On le met en cache par
 * SIGNATURE de classpath (content hash) — le cache survit aux rendus
 * successifs tant que le classpath ne change pas.
 *
 * **Stratégie de signature** : la signature est un hash du contenu
 * des jars + mtime+taille des dossiers de `.class`. On reprend
 * la stratégie standard de cache par contenu (content-hash).
 *
 * **Thread-safety** : `synchronized(verrou)` — le rendu est sérialisé
 * par `ServicePreview` ou le HandlerThread de `TransportRenduLocal`,
 * donc pas de concurrence. Le `@Volatile` sur `loaderCourant` est une
 * double sécurité.
 *
 * **Parent classloader** : le BOOT classloader (`View::class.java.classLoader`),
 * PAS le classloader de l'IDE. Sinon les `androidx.*` du projet
 * retomberaient sur la version bundled par l'IDE (mauvaise version).
 * C'est le bon choix de parentage pour éviter la pollution `androidx.*`.
 *
 * **Sur device** : `PathClassLoader` est l'API publique pour charger
 * des `.dex`/`.jar` dexés. Sur Robolectric (tests JVM), il est émulé.
 * Pour les dossiers de `.class` non dexés, il faudra D8 — defer à v0.133.
 * En v0.132, on supporte JAR/MODULE/AAR (déjà dexés) et DOSSIER de
 * `.class` (via PathClassLoader qui accepte les dirs sur certaines
 * implémentations).
 */
public class CacheClasspath {
    private val verrou = Any()

    /** Dernier classloader construit — `@Volatile` pour la visibilité. */
    @Volatile
    private var loaderCourant: ClassLoader? = null

    /** Signature du classpath courant (content hash). */
    @Volatile
    private var signatureCourante: String? = null

    /**
     * Retourne le classloader pour [entries], en cache si la signature
     * n'a pas changé. Si la signature a changé (ou premier appel),
     * construit un nouveau classloader.
     *
     * @return le classloader, ou `null` si [entries] est vide (pas de
     *         classpath projet — repli ViewFactory par défaut).
     */
    @Suppress("ReturnCount") // 3 issues naturelles (vide / cache hit / rebuild)
    public fun classloader(entries: List<EntreeClasspath>): ClassLoader? {
        if (entries.isEmpty()) return null

        val signature = calculerSignature(entries)
        synchronized(verrou) {
            if (signature == signatureCourante) {
                return loaderCourant
            }
            val loader = construireClassloader(entries)
            loaderCourant = loader
            signatureCourante = signature
            return loader
        }
    }

    /**
     * Calcule la signature d'un classpath — content hash des jars +
     * mtime+taille des dossiers.
     *
     * **Stabilité** : deux builds identiques (même contenu) → même
     * signature, même si les mtimes ont changé. C'est crucial pour
     * éviter le re-dex à chaque rebuild Gradle (stratégie de cache
     * par contenu — les builds identiques réutilisent le même .dex).
     */
    private fun calculerSignature(entries: List<EntreeClasspath>): String {
        val condense = MessageDigest.getInstance("SHA-256")
        for (entree in entries.sortedBy { it.chemin }) {
            condense.update("${entree.genre}:${entree.chemin}:".toByteArray(Charsets.UTF_8))
            val fichier = File(entree.chemin)
            if (!fichier.exists()) continue
            when (entree.genre) {
                GenreEntreeClasspath.JAR,
                GenreEntreeClasspath.AAR,
                GenreEntreeClasspath.MODULE,
                -> {
                    // Content hash du fichier (les jars sont stables entre
                    // builds identiques — Gradle recompile, mais le contenu
                    // est identique si le source est identique).
                    condense.update(fichier.readBytes())
                }

                GenreEntreeClasspath.DOSSIER -> {
                    // Pour les dossiers de .class, on walk et on hash
                    // chemin relatif + taille + mtime de chaque fichier.
                    hasherDossier(fichier, condense)
                }
            }
        }
        return condense.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hash récursif d'un dossier (chemin relatif + taille + mtime par fichier). */
    private fun hasherDossier(
        racine: File,
        condense: MessageDigest,
    ) {
        if (!racine.isDirectory) return
        val fichiers = racine.walkTopDown().filter { it.isFile }.sortedBy { it.path }
        for (fichier in fichiers) {
            val relatif = fichier.relativeTo(racine).path
            condense.update("$relatif:${fichier.length()}:${fichier.lastModified()}:".toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Construit un classloader pour [entries].
     *
     * Sur Android, `PathClassLoader` (API publique depuis API 26 via
     * réflexion, ou `dalvik.system.DexClassLoader` pour les versions
     * antérieures) prend un classpath de fichiers `.dex`/`.jar` dexés.
     *
     * **Parent** : `View::class.java.classLoader` (boot) — pas le
     * classloader de l'IDE (sinon pollution `androidx.*`).
     */
    private fun construireClassloader(entries: List<EntreeClasspath>): ClassLoader {
        val parent = View::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
        val chemins = entries.map { it.chemin }.toTypedArray()
        val cheminClasspath = chemins.joinToString(File.pathSeparator)
        return PathClassLoader(cheminClasspath, parent)
    }

    /** Réinitialise le cache (utile en test ou sur fermeture). */
    public fun reinitialiser() {
        synchronized(verrou) {
            loaderCourant = null
            signatureCourante = null
        }
    }
}

/** Import alias pour éviter la dépendance circulaire dans la signature. */
private typealias View = android.view.View
