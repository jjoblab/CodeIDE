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
     * @throws EchecBootstrap code de sortie non nul (dépôt injoignable,
     * signature refusée…).
     */
    suspend fun miseAJour(prefixe: File) {
        val sortie = executerApt(prefixe, listOf("update"))
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
     * @return `null` si le paquet est installé (code de sortie nul), ou
     * l'échec typé sinon (paquet absent du dépôt, dépendance cassée…) —
     * l'appelant consigne l'état par paquet sans faire échouer les
     * autres.
     */
    suspend fun installerPaquet(
        prefixe: File,
        paquet: String,
    ): EchecBootstrap? {
        val sortie = executerApt(prefixe, listOf("install", "-y", paquet))
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
    ): SupervisionProcessus.Sortie {
        val commande = listOf(File(prefixe, "bin/apt").absolutePath) + arguments
        val processus =
            try {
                lanceur.launch(commande, workingDir = prefixe)
            } catch (e: IOException) {
                throw EchecBootstrap(BootstrapReason.PermissionRefusee, "apt introuvable : ${e.message}", cause = e)
            }
        return SupervisionProcessus.attendre(processus)
    }

    private companion object {
        /** En-tête de commentaire du `sources.list` écrit. */
        private const val EN_TETE_SOURCES = "# CodeIDE main repository"
    }
}
