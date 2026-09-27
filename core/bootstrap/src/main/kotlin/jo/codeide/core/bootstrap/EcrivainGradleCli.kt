package jo.codeide.core.bootstrap

import jo.codeide.core.domain.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Écrivain de la commande `gradle` du terminal (correctif C4 du prompt
 * Terminal — retour utilisateur : « gradle: command not found » malgré un
 * projet déjà construit).
 *
 * Le bootstrap n'installe JAMAIS de paquet `gradle` : chaque projet peut
 * exiger une version différente (c'est l'intérêt du wrapper) et une version
 * système figée serait régulièrement la mauvaise. À la place, le script
 * `$PREFIX/bin/gradle` découvre Gradle au moment de l'appel :
 *
 * 1. `./gradlew` du répertoire courant, s'il est exécutable (le projet
 *    décide de SA version) ;
 * 2. sinon la distribution du wrapper la plus récemment utilisée dans
 *    `$GRADLE_USER_HOME/wrapper/dists` (posée par un build ou une sync
 *    lancés depuis l'app — `ls -dt` ordonne par date, cohérent avec un
 *    utilisateur qui travaille sur un projet à la fois) ;
 * 3. sinon un message qui EXPLIQUE quoi faire (jamais un « command not
 *    found » sec), code de sortie 127 (commande introuvable, convention
 *    shell).
 *
 * L'ancien projet résolvait exactement ce cas par ce script de découverte
 * (`BootstrapScripts.java`, ~420-442) — comportement reproduit, adapté au
 * bootstrap natif direct (pas de proot).
 *
 * Le shebang pointe vers le `sh` DU bootstrap (chemin absolu calculé à
 * l'écriture — un shebang ne connaît ni `$PREFIX` ni les variables) ;
 * le bit d'exécution suit le précédent `Aapt2Deployeur` (0755 via
 * [OperationsSysteme.chmod]).
 *
 * @param operations opérations système natives (chmod).
 * @param journal journal applicatif (règle 15 : identifiants seulement).
 */
internal class EcrivainGradleCli(
    private val operations: OperationsSysteme,
    private val journal: AppLogger,
) {
    /**
     * Écrit (ou réécrit — idempotent par contenu) la commande `gradle`.
     *
     * @param racine racine du bootstrap (`filesDir` — le préfixe s'en
     *        dérive, le shebang y est résolu en absolu).
     */
    suspend fun ecrire(racine: File) {
        withContext(Dispatchers.IO) {
            val prefixe = DispositionsBootstrap.prefix(racine)
            val cible = File(prefixe, "bin/gradle")
            cible.parentFile?.mkdirs()

            val shebang = "#!${File(prefixe, "bin/sh").absolutePath}"
            val attendu = script(shebang)
            if (cible.isFile && cible.readText() == attendu && cible.canExecute()) {
                return@withContext
            }

            val temporaire = File(cible.parentFile, "gradle.codeide")
            try {
                temporaire.bufferedWriter().use { ecrivain -> ecrivain.write(attendu) }
                // 0o755 : lisible et exécutable par tous, inscriptible par
                // l'app seule — même pose que les binaires du bootstrap.
                operations.chmod(temporaire, MODE_EXECUTABLE_PARTAGE)
                if (cible.exists() && !cible.delete()) {
                    throw IOException("gradle existant non remplaçable")
                }
                if (!temporaire.renameTo(cible)) {
                    throw IOException("bascule du gradle impossible")
                }
            } finally {
                temporaire.delete()
            }

            journal.i(TAG) { "commande gradle posée (découverte wrapper/dists)" }
        }
    }

    /** Contenu canonique du script de découverte. */
    private fun script(shebang: String): String {
        val dollar = "$"
        return """
            $shebang
            # Commande gradle de CodeIDE — découverte, jamais de version figée
            # (correctif C4 du prompt Terminal ; régénérée à chaque
            # installation de base : ne pas éditer).

            # 1. Le wrapper DU projet courant décide de sa version.
            if [ -x "./gradlew" ]; then
              exec ./gradlew "$dollar@"
            fi

            # 2. Sinon, la distribution la plus récemment utilisée par le
            #    wrapper (posée par un build/sync lancés depuis l'app).
            dist=$(ls -dt "${dollar}GRADLE_USER_HOME"/wrapper/dists/*/*/ 2>/dev/null | head -n1)
            if [ -n "${dollar}dist" ] && [ -x "$dollar{dist}bin/gradle" ]; then
              exec "$dollar{dist}bin/gradle" "$dollar@"
            fi

            # 3. Rien trouvé : expliquer quoi faire (jamais un échec muet).
            echo "gradle: aucune distribution trouvée." >&2
            echo "" >&2
            echo "  1. Place-toi dans un projet avec ./gradlew et relance : ./gradlew $dollar*" >&2
            echo "  2. Ou lance d'abord une synchronisation ou un build depuis l'app CodeIDE :" >&2
            echo "     le wrapper téléchargera Gradle, et cette commande le retrouvera ensuite." >&2
            exit 127
            """.trimIndent() + "\n"
    }

    private companion object {
        private const val TAG = "GradleCli"

        /** 0o755 — exécutable par tous (précédent `Aapt2Deployeur`). */
        private const val MODE_EXECUTABLE_PARTAGE = 493
    }
}
