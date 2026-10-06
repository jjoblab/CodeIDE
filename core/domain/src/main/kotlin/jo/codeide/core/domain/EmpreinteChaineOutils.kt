package jo.codeide.core.domain

import java.io.File
import java.security.MessageDigest

/**
 * Empreinte de la chaîne d'outils (§ 6, E4 — ADR 0089) : hachage SHA-256
 * des éléments qui définissent l'environnement des processus Gradle —
 * `JAVA_HOME`, `ANDROID_HOME`, chemin du binaire `aapt2` et versions
 * installées (recensement des phases vérifiées).
 *
 * Le daemon Gradle (`tooling:daemon`) est **relancé** quand cette empreinte
 * change : un daemon démarré avec l'ancien environnement garderait ses
 * variables figées (JVM héritée de `JAVA_HOME` à son lancement) et
 * compilerait avec des outils périmés.
 *
 * Fonction pure : les entrées viennent de l'appelant (localisateur, état
 * vérifié), jamais d'une lecture disque cachée — testable isolément.
 */
public object EmpreinteChaineOutils {
    /**
     * Calcule l'empreinte.
     *
     * @param javaHome racine du JDK vérifié, ou `null` (absent).
     * @param androidHome racine du SDK Android, ou `null` (absent).
     * @param aapt2 chemin du binaire `aapt2` résolu depuis le plan, ou `null`.
     * @param versions versions recensées par les phases vérifiées (composant
     * → version, ex. `"jdk"` → `"17.0.20"`) — l'ordre d'insertion est
     * stabilisé par tri des clés.
     * @return l'empreinte hexadécimale SHA-256 (64 caractères).
     */
    public fun calculer(
        javaHome: File?,
        androidHome: File?,
        aapt2: File?,
        versions: Map<String, String>,
    ): String {
        val lignes =
            buildList {
                add("aapt2=${aapt2?.absolutePath ?: "-"}")
                add("android=${androidHome?.absolutePath ?: "-"}")
                add("java=${javaHome?.absolutePath ?: "-"}")
                versions.keys.sorted().forEach { cle -> add("version:$cle=${versions.getValue(cle)}") }
            }
        val digest = MessageDigest.getInstance("SHA-256")
        return digest
            .digest(lignes.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Détecteur de changement d'empreinte (§ 6, E4) : mémorise la dernière
 * empreinte observée et signale les changements — le premier
 * observation **n'est pas un changement** (le daemon vient de démarrer
 * avec l'environnement courant).
 *
 * Machine à état minimale, volontairement sans coroutine : l'appelant
 * (application) collecte l'état du parcours, calcule l'empreinte et
 * agit sur `true` (relancer le daemon).
 */
public class DetecteurChangementEmpreinte {
    private var derniere: String? = null

    /**
     * Soumet une empreinte fraîchement calculée.
     *
     * @param empreinte empreinte courante (jamais vide).
     * @return `true` si l'empreinte diffère de la précédente observation
     * (hors la première), `false` sinon.
     */
    public fun traiter(empreinte: String): Boolean {
        val precedente = derniere
        derniere = empreinte
        return precedente != null && precedente != empreinte
    }
}
