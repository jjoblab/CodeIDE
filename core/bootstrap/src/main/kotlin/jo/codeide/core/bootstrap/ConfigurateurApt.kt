package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.model.AppError.BootstrapReason
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Configuration APT du bootstrap installé (prompt compagnon Terminal-1,
 * section 3.4, étapes 6 et 7) :
 *
 * - écriture du `sources.list` vers le dépôt CodeIDE **avec
 *   `[trusted=yes]`** (l'archive du bootstrap embarque une ligne sans
 *   cette option — constat du 2026-09-23 sur l'archive réelle — et une
 *   éventuelle URL antérieure incorrecte est corrigée automatiquement :
 *   le fichier est réécrit dès que son contenu diffère de la ligne
 *   canonique) ;
 * - **création des répertoires APT standard** (`preferences.d`,
 *   `apt.conf.d`, `sources.list.d`, `trusted.gpg.d` — v0.47.0, retour
 *   d'appareil réel : l'archive du bootstrap ne les pose pas tous et
 *   `pkg install` avertit « W: Unable to read
 *   …/etc/apt/preferences.d/ - DirectoryExists (2: No such file or
 *   directory) » à CHAQUE commande) ;
 * - `apt update` puis `apt install -y <paquet>`, un paquet à la fois
 *   (un paquet absent du dépôt est rapporté non installé sans faire
 *   échouer les autres) ;
 * - les commandes passent par [NativeProcessLauncher] dans
 *   l'environnement canonique du bootstrap — jamais de `Runtime.exec`
 *   direct.
 *
 * L'écriture est atomique (fichier temporaire + renommage) : un
 * `sources.list` à moitié écrit rendrait tout `apt` ultérieur muet.
 */
internal class ConfigurateurApt(
    private val lanceur: NativeProcessLauncher,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * Crée les répertoires APT standard du préfixe (v0.47.0 — correctif du
     * warning `preferences.d` en retour d'appareil réel).
     *
     * L'archive du bootstrap Termux pose `etc/apt/sources.list` mais PAS
     * toujours les répertoires de configuration supplémentaires qu'apt
     * parcourt à CHAQUE commande : sans `etc/apt/preferences.d/`, chaque
     * `pkg install` imprime « W: Unable to read …/preferences.d/ -
     * DirectoryExists (2: No such file or directory) ». Idempotent
     * (`mkdirs`), sans danger sur un préfixe sain — appelé à la pose du
     * `sources.list` ET à chaque démarrage par
     * [InstallateurBootstrap.refreshTerminalScripts] : les préfixes déjà
     * installés sont guéris SANS réinstallation.
     */
    suspend fun assurerRepertoiresApt(prefixe: File) {
        withContext(dispatchers.io) {
            REPERTOIRES_APT.forEach { relatif ->
                File(prefixe, relatif).mkdirs()
            }
        }
    }

    /**
     * Écrit (ou corrige) le `sources.list` du préfixe.
     *
     * @param prefixe racine `$PREFIX` du bootstrap installé.
     * @param ligneDepotApt ligne canonique complète
     * (`deb [trusted=yes] <url> <suite> <composante>`).
     * @return `true` si le fichier a été (ré)écrit, `false` s'il était
     * déjà conforme — le test de correction s'appuie sur ce retour.
     */
    suspend fun ecrireSourcesList(
        prefixe: File,
        ligneDepotApt: String,
    ): Boolean =
        withContext(dispatchers.io) {
            // v0.47.0 : AVANT le test de conformité — un préfixe installé
            // par une version antérieure peut être conforme sur le
            // `sources.list` tout en manquant les répertoires standard :
            // guérir à CHAQUE passage, même sans réécriture.
            assurerRepertoiresApt(prefixe)
            val sourcesList = File(prefixe, "etc/apt/sources.list")
            val contenuAttendu = "$EN_TETE_SOURCES\n$ligneDepotApt\n"
            if (sourcesList.isFile && sourcesList.readText() == contenuAttendu) {
                return@withContext false
            }
            // Le répertoire peut manquer (réinstallation partielle) : il
            // est créé avant l'écriture atomique.
            sourcesList.parentFile?.mkdirs()
            val temporaire = File(sourcesList.parentFile, "sources.list.codeide")
            try {
                temporaire.bufferedWriter().use { ecrivain -> ecrivain.write(contenuAttendu) }
                if (sourcesList.exists() && !sourcesList.delete()) {
                    throw EchecBootstrap(BootstrapReason.PermissionRefusee, "sources.list existant non remplaçable")
                }
                if (!temporaire.renameTo(sourcesList)) {
                    throw EchecBootstrap(BootstrapReason.PermissionRefusee, "bascule du sources.list impossible")
                }
            } finally {
                temporaire.delete()
            }
            true
        }

    /**
     * Exécute `apt update` contre le dépôt configuré.
     *
     * @param consommateur receptacle des lignes de sortie (journal
     * d'écran de l'installation) — la sortie d'apt est le seul moyen de
     * comprendre un échec du dépôt sur l'appareil.
     * @throws EchecBootstrap code de sortie non nul (dépôt injoignable,
     * signature refusée…).
     */
    suspend fun miseAJour(
        prefixe: File,
        consommateur: (String) -> Unit,
    ) {
        val sortie = executerApt(prefixe, listOf("update"), consommateur)
        if (sortie.code != 0) {
            throw EchecBootstrap(
                BootstrapReason.EchecApt,
                "apt update → code ${sortie.code} — ${sortie.erreurs.joinToString(" / ")}",
            )
        }
    }

    /**
     * Installe un paquet d'outil.
     *
     * @param consommateur receptacle des lignes de sortie (journal
     * d'écran de l'installation).
     * @return `null` si le paquet est installé (code de sortie nul), ou
     * l'échec typé sinon (paquet absent du dépôt, dépendance cassée…) —
     * l'appelant consigne l'état par paquet sans faire échouer les
     * autres.
     */
    suspend fun installerPaquet(
        prefixe: File,
        paquet: String,
        consommateur: (String) -> Unit,
    ): EchecBootstrap? {
        val sortie = executerApt(prefixe, listOf("install", "-y", paquet), consommateur)
        return if (sortie.code == 0) {
            null
        } else {
            EchecBootstrap(
                BootstrapReason.EchecApt,
                "apt install $paquet → code ${sortie.code} — ${sortie.erreurs.joinToString(" / ")}",
            )
        }
    }

    /** Lance `$PREFIX/bin/apt` et supervise sa sortie complète. */
    private suspend fun executerApt(
        prefixe: File,
        arguments: List<String>,
        consommateur: (String) -> Unit,
    ): SupervisionProcessus.Sortie {
        val commande = listOf(File(prefixe, "bin/apt").absolutePath) + arguments
        val processus =
            try {
                lanceur.launch(commande, workingDir = prefixe)
            } catch (e: IOException) {
                throw EchecBootstrap(BootstrapReason.PermissionRefusee, "apt introuvable : ${e.message}", cause = e)
            }
        return SupervisionProcessus.attendre(processus, consommateur)
    }

    private companion object {
        /** En-tête de commentaire du `sources.list` écrit. */
        private const val EN_TETE_SOURCES = "# CodeIDE main repository"

        /** Répertoires APT standard du préfixe (v0.47.0 — le warning
         *  `preferences.d` de retour d'appareil réel ; `sources.list.d`,
         *  `apt.conf.d` et `trusted.gpg.d` suivent la même exIGENCE
         *  d'apt, la guérison les pose ensemble). */
        private val REPERTOIRES_APT =
            listOf(
                "etc/apt/preferences.d",
                "etc/apt/apt.conf.d",
                "etc/apt/sources.list.d",
                "etc/apt/trusted.gpg.d",
            )
    }
}
