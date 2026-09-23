package jo.codeide.core.bootstrap

import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.model.AppError.BootstrapReason
import jo.codeide.core.model.EtapeInstallation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Extraction de l'archive du bootstrap vers le répertoire de
 * préparation, puis bascule atomique vers `$PREFIX` (prompt compagnon
 * Terminal-1, section 3.4, étapes 2 à 4 — algorithme de référence
 * repris de l'installateur Termux, vérifié sur l'archive réelle du
 * dépôt `codeide-packages`) :
 *
 * 1. les entrées du zip sont **relatives au préfixe** (`bin`, `etc`,
 *    `lib`…) et extraites vers le staging ;
 * 2. les liens symboliques ne sont **pas** des entrées zip natives :
 *    ils sont décrits dans `SYMLINKS.txt` (une ligne par lien, format
 *    `cible←chemin du lien`, séparateur U+2190) — le manifeste est
 *    analysé au fil de l'extraction, les liens créés après celle-ci ;
 * 3. le bit d'exécution est posé sur `bin/`, `libexec`, les assistants
 *    `lib/apt/apt-helper` et `lib/apt/methods`, et le script de second
 *    stage (`etc/termux/termux-bootstrap/second-stage/…`, chemin
 *    constaté dans l'archive réelle) ;
 * 4. un `SYMLINKS.txt` absent est une archive corrompue (Termux :
 *    « No SYMLINKS.txt encountered ») ;
 * 5. la bascule détruit d'abord tout préfixe existant (reprise après
 *    échec), puis renomme le staging — atomique sur le même système
 *    de fichiers.
 */
internal class ExtracteurBootstrap(
    private val operations: OperationsSysteme,
    private val dispatchers: DispatcherProvider,
) {
    /** Extraction complète + préparation des liens, en flux d'étapes. */
    fun extraire(
        archive: File,
        staging: File,
    ): Flow<EtapeInstallation> =
        flow {
            try {
                extraireDans(archive, staging) { etape -> emit(etape) }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: EchecBootstrap) {
                throw e
            } catch (e: ZipException) {
                throw EchecBootstrap(BootstrapReason.ArchiveCorrompue, "archive zip invalide : ${e.message}", cause = e)
            } catch (e: IOException) {
                throw traduire(e)
            }
        }.flowOn(dispatchers.io)

    /** Cœur d'extraction — les échecs d'E/S sont traduits par [extraire]. */
    private suspend fun extraireDans(
        archive: File,
        staging: File,
        surEtape: suspend (EtapeInstallation) -> Unit,
    ) {
        val liens = mutableListOf<Pair<String, String>>()
        var entrees = 0
        ZipInputStream(FileInputStream(archive).buffered()).use { zip ->
            var entree: ZipEntry? = zip.nextEntry
            while (entree != null) {
                val nom = entree.name
                when {
                    nom == FICHIER_SYMLINKS -> {
                        liens += analyserSymlinks(zip)
                    }

                    entree.isDirectory -> {
                        verifierCheminSur(nom)
                        garantirRepertoire(File(staging, nom))
                    }

                    else -> {
                        // Garde anti-traversée AVANT toute création : un nom
                        // contenant `..` rendrait les mkdirs intermédiaires
                        // invalides et masquerait la vraie raison.
                        verifierCheminSur(nom)
                        extraireFichier(zip, nom, staging)
                        entrees++
                        surEtape(EtapeInstallation.Extraction(entrees))
                    }
                }
                entree = zip.nextEntry
            }
        }
        if (liens.isEmpty()) {
            throw EchecBootstrap(BootstrapReason.ArchiveCorrompue, "$FICHIER_SYMLINKS absent de l'archive")
        }
        surEtape(EtapeInstallation.LiensSymboliques)
        for ((cible, chemin) in liens) {
            // Le manifeste des liens subit la même garde anti-traversée
            // que les entrées zip (un manifeste hostile ne sort pas du
            // staging non plus).
            verifierCheminSur(chemin)
            val lien = File(staging, chemin)
            garantirRepertoire(lien.parentFile)
            operations.creerLienSymbolique(cible, lien)
        }
    }

    /** Écrit une entrée de fichier régulier vers le staging, permissions comprises. */
    private fun extraireFichier(
        zip: ZipInputStream,
        nom: String,
        staging: File,
    ) {
        val cible = File(staging, nom)
        garantirRepertoire(cible.parentFile)
        FileOutputStream(cible).use { sortie ->
            zip.copyTo(sortie)
        }
        if (estExecutable(nom)) {
            operations.chmod(cible, MODE_EXECUTABLE)
        }
    }

    /** Traduit une erreur d'E/S d'extraction en échec typé. */
    private fun traduire(e: IOException): EchecBootstrap =
        when {
            e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true -> {
                EchecBootstrap(BootstrapReason.EspaceDisqueInsuffisant, "écriture interrompue : ${e.message}")
            }

            e.message?.contains("EACCES") == true -> {
                EchecBootstrap(BootstrapReason.PermissionRefusee, "écriture refusée : ${e.message}")
            }

            else -> {
                EchecBootstrap(BootstrapReason.ArchiveCorrompue, "échec d'extraction : ${e.message}")
            }
        }

    /**
     * Bascule le staging vers le préfixe définitif : tout préfixe
     * existant est détruit (reprise après échec — le verrou du second
     * stage vit sous le préfixe et disparaît avec lui, une nouvelle
     * installation peut donc rejouer ce script), puis le staging est
     * renommé.
     */
    suspend fun basculer(
        staging: File,
        prefixe: File,
    ) {
        if (prefixe.exists() && !supprimerRecursivement(prefixe)) {
            throw EchecBootstrap(BootstrapReason.PermissionRefusee, "préfixe existant non supprimable : $prefixe")
        }
        if (!staging.renameTo(prefixe)) {
            throw EchecBootstrap(BootstrapReason.PermissionRefusee, "bascule du staging impossible vers : $prefixe")
        }
    }

    /** Analyse le manifeste des liens symboliques (une ligne par lien).
     *
     * Lecture ligne à ligne **sans fermer** le flux zip : l'entrée
     * `SYMLINKS.txt` se termine à la frontière de l'entrée suivante, que
     * `getNextEntry()` doit pouvoir atteindre (algorithme de l'installateur
     * Termux — `readLines()` de la bibliothèque standard fermerait le flux).
     */
    private fun analyserSymlinks(flux: ZipInputStream): List<Pair<String, String>> {
        val lecteur = BufferedReader(InputStreamReader(flux, Charsets.UTF_8))
        val liens = mutableListOf<Pair<String, String>>()
        var ligne = lecteur.readLine()
        while (ligne != null) {
            if (ligne.isNotBlank()) {
                val parties = ligne.split(SEPARATEUR_SYMLINK)
                if (parties.size != 2) {
                    throw EchecBootstrap(BootstrapReason.ArchiveCorrompue, "ligne SYMLINKS malformée : $ligne")
                }
                liens += parties[0] to parties[1]
            }
            ligne = lecteur.readLine()
        }
        return liens
    }

    /** Garde anti-traversée : les entrées doivent rester sous le staging. */
    private fun verifierCheminSur(nom: String) {
        if (nom.startsWith("/") || nom.contains("..")) {
            throw EchecBootstrap(BootstrapReason.ArchiveCorrompue, "chemin d'entrée interdit : $nom")
        }
    }

    private fun garantirRepertoire(repertoire: File?) {
        if (repertoire != null && !repertoire.exists() && !repertoire.mkdirs()) {
            throw EchecBootstrap(BootstrapReason.EspaceDisqueInsuffisant, "répertoire impossible à créer : $repertoire")
        }
    }

    private fun supprimerRecursivement(racine: File): Boolean {
        if (racine.isDirectory) {
            racine.listFiles()?.forEach { enfant -> supprimerRecursivement(enfant) }
        }
        return racine.delete()
    }

    private fun estExecutable(nom: String): Boolean =
        nom.startsWith("bin/") ||
            nom.startsWith("libexec") ||
            nom.startsWith("lib/apt/apt-helper") ||
            nom.startsWith("lib/apt/methods") ||
            nom == CHEMIN_SECOND_STAGE

    private companion object {
        private const val FICHIER_SYMLINKS = "SYMLINKS.txt"
        private const val SEPARATEUR_SYMLINK = "←"

        /** Chemin du second stage, relatif au préfixe (constaté dans l'archive réelle). */
        private const val CHEMIN_SECOND_STAGE =
            "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh"

        /** Mode des binaires extraits — 0700 octal (Termux). */
        private const val MODE_EXECUTABLE = 448
    }
}
