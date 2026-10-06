package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.DispositionsBootstrap
import jo.codeide.core.domain.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Écrivain du bloc géré de `$GRADLE_USER_HOME/gradle.properties` (§ 6, E4 —
 * ADR 0089) : maintient `android.aapt2FromMavenOverride=<chemin aapt2>` dans
 * un **bloc délimité et géré**, réécriture idempotente — les autres lignes
 * de l'utilisateur (hors bloc) restent intactes, octet pour octet.
 *
 * Sans ce bloc, AGP prend l'`aapt2` x86_64 de Maven — inexécutable sur
 * aarch64 (constat E1) : le chemin posé vient **du plan** (§ 12.4,
 * `InstallPlan.aapt2Binary`), jamais d'un asset ni d'une constante.
 *
 * ```
 * # >>> CodeIDE - environnement (géré) >>>
 * android.aapt2FromMavenOverride=/…/android-sdk/build-tools/35.0.2/aapt2
 * # <<< CodeIDE - environnement (géré) <<<
 * ```
 */
internal class EcrivainConfigurationGradle private constructor(
    private val dispatchers: DispatcherProvider,
    private val fichier: File,
) {
    /**
     * Réécrit le bloc géré avec [cheminAapt2] — idempotent : même chemin =
     * fichier inchangé ; chemin différent = seule la ligne de l'override
     * change. Le fichier est créé s'il n'existe pas ; un répertoire parent
     * manquant est posé.
     *
     * @return `true` si le fichier final contient l'override demandé.
     */
    internal suspend fun ecrire(cheminAapt2: String): Boolean =
        withContext(dispatchers.io) {
            val dossier = fichier.parentFile
            if (dossier != null && !dossier.isDirectory) dossier.mkdirs()
            val lignes = if (fichier.isFile) fichier.readLines() else emptyList()
            val reecrites = remplacerBloc(lignes, cheminAapt2)
            if (reecrites != lignes || !fichier.isFile) {
                ecrireAtomiquement(reecrites)
            }
            fichier.readLines().any { it.startsWith("$CLE_OVERRIDE=") }
        }

    /**
     * L'override courant du bloc géré, ou `null` (absent ou fichier
     * illisible) — sert de vérification légère à l'étape de câblage.
     */
    internal fun overrideCourant(): String? {
        if (!fichier.isFile) return null
        return blocGere(fichier.readLines())
            .firstOrNull { it.startsWith("$CLE_OVERRIDE=") }
            ?.substringAfter('=')
    }

    /** Écriture atomique : fichier temporaire puis renommage (jamais un fichier à moitié écrit). */
    private fun ecrireAtomiquement(lignes: List<String>) {
        val temporaire = File(fichier.parentFile, "${fichier.name}.tmp")
        temporaire.bufferedWriter().use { ecrivain -> lignes.forEach { ecrivain.write(it + "\n") } }
        if (fichier.isFile) fichier.delete()
        if (!temporaire.renameTo(fichier)) temporaire.delete()
    }

    internal companion object {
        /** Clé de l'override AGP (§ 6 — la seule clé du bloc géré). */
        internal const val CLE_OVERRIDE: String = "android.aapt2FromMavenOverride"

        internal const val DEBUT_BLOC: String = "# >>> CodeIDE - environnement (géré) >>>"
        internal const val FIN_BLOC: String = "# <<< CodeIDE - environnement (géré) <<<"

        /**
         * Remplace le bloc géré de [lignes] par un bloc frais portant
         * [cheminAapt2] — **en place** s'il existe déjà (idempotence
         * byte pour byte à chemin inchangé), ajouté en fin sinon ; les
         * lignes hors bloc sont conservées, un ancien override posé à la
         * main **hors bloc** est neutralisé par commentaire (jamais
         * silencieusement écrasé).
         */
        internal fun remplacerBloc(
            lignes: List<String>,
            cheminAapt2: String,
        ): List<String> {
            val bloc = listOf(DEBUT_BLOC, "$CLE_OVERRIDE=$cheminAapt2", FIN_BLOC)
            val debut = lignes.indexOfFirst { it.trim() == DEBUT_BLOC }
            val fin = lignes.indexOfFirst { it.trim() == FIN_BLOC }
            return if (debut >= 0 && fin > debut) {
                neutraliserOverrides(lignes.subList(0, debut)) +
                    bloc +
                    neutraliserOverrides(lignes.subList(fin + 1, lignes.size))
            } else {
                val nettoyees = neutraliserOverrides(lignes)
                if (nettoyees.isEmpty()) bloc else nettoyees + listOf("") + bloc
            }
        }

        /** Neutralise par commentaire toute ligne d'override posée hors du bloc géré. */
        private fun neutraliserOverrides(lignes: List<String>): List<String> =
            lignes.map { ligne ->
                if (ligne.trim().startsWith(CLE_OVERRIDE)) "# neutralisé par CodeIDE (bloc géré) : $ligne" else ligne
            }

        /** Lignes du bloc géré courant, vide s'il n'existe pas. */
        internal fun blocGere(lignes: List<String>): List<String> {
            val debut = lignes.indexOfFirst { it.trim() == DEBUT_BLOC }
            val fin = lignes.indexOfFirst { it.trim() == FIN_BLOC }
            return if (debut < 0 || fin < debut) emptyList() else lignes.subList(debut + 1, fin)
        }
    }

    /** Fabrique de production : `$GRADLE_USER_HOME/gradle.properties` sous la racine du parcours. */
    @Singleton
    internal class Fabrique
        @Inject
        constructor(
            dispatchers: DispatcherProvider,
        ) {
            private val dispatchersIO = dispatchers

            /** Construit l'écrivain pour la racine du parcours (couture interne). */
            internal fun pourRacine(racine: File): EcrivainConfigurationGradle =
                EcrivainConfigurationGradle(
                    dispatchers = dispatchersIO,
                    fichier = File(DispositionsBootstrap.gradleUserHome(racine), "gradle.properties"),
                )
        }
}
