package jo.codeide.sorties

import jo.codeide.core.domain.LigneSortie
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Le stockage des LIGNES d'une exécution (ADR 0161 décision 6) — UN
 * fichier JSON Lines compressé (gzip) par exécution, en stockage privé.
 *
 * **Multi-membres** : chaque lot ingéré ([ajouter]) écrit UN membre gzip
 * de plus (format concaténé standard — un lecteur gzip le déroule
 * entièrement) ; le registre n'écrit JAMAIS ligne par ligne et ne
 * réécrit jamais le fichier au fil de l'eau (une écriture par lot,
 * append seul).
 *
 * **Troncature à la conclusion** ([tronquer]) : au-delà du plafond de
 * lignes, les lignes du MILIEU partent en premier (tête et queue
 * conservées, ADR 0161 décision 2) — le fichier est réécrit une fois,
 * à la conclusion seulement.
 *
 * **Lecture tolérante** ([lire]) : un membre tronqué par la mort du
 * processus rend les lignes déjà décodées — jamais d'exception vers
 * l'appelant (c'est une consultation, ADR 0161).
 *
 * @param dossier le dossier privé des fichiers de lignes (créé au
 *        besoin).
 */
internal class StockageLignesExecutions(private val dossier: File) {

    /** Le fichier de lignes d'une exécution. */
    private fun fichier(id: String): File = File(dossier, "$id.jsonl.gz")

    /** La taille du fichier de lignes (octets), 0 si absent. */
    internal fun octets(id: String): Long = fichier(id).takeIf { it.isFile }?.length() ?: 0L

    /**
     * Ajoute UN LOT de lignes (UN membre gzip de plus) — les lignes
     * arrivent DÉJÀ expurgées ([ExpurgateurSorties] au niveau du
     * registre) : ce stockage n'altère jamais un contenu.
     */
    internal fun ajouter(id: String, lignes: List<LigneSortie>) {
        if (lignes.isEmpty()) return
        val fichier = fichier(id)
        fichier.parentFile?.mkdirs()
        // Exemption detekt ciblée (règle 16) : la panne d'écriture d'UN
        // membre n'est pas propagée par DESIGN (consultation ADR 0161) —
        // le lot suivant retentera, les lots précédents restent intacts
        // (append seul, jamais de réécriture en vol). L'exception ne
        // porte aucune information de plus que le diagnostic du journal
        // applicatif, qui est émis par le registre appelant.
        @Suppress("SwallowedException")
        try {
            ecrireMembre(fichier, lignes, ajouter = true)
        } catch (t: Throwable) {
            // Panne d'écriture : le lot suivant retentera, les lots
            // précédents restent intacts (append seul — jamais de
            // réécriture en vol).
        }
    }

    /** Écrit UNE passe de lignes (membre gzip de plus en append, ou
     *  réécriture complète à la troncature — le seul appelant des
     *  flux gzip de ce stockage). */
    private fun ecrireMembre(fichier: File, lignes: List<LigneSortie>, ajouter: Boolean) {
        FileOutputStream(fichier, ajouter).use { sortie ->
            GZIPOutputStream(sortie).use { gzip ->
                lignes.forEach { ligne ->
                    gzip.write(SerialisationExecutions.ligneVersJson(ligne).toString().toByteArray(Charsets.UTF_8))
                    gzip.write(SEPARATEUR_LIGNE)
                }
            }
        }
    }

    /** Relit TOUTES les lignes de l'exécution (ordre du flux). */
    // Exemption detekt ciblée (règle 16) : le membre tronqué (mort du
    // processus en pleine écriture) rend ce qu'il a décodé PAR DESIGN
    // (lecture tolérante, ADR 0161 décision 6) — jamais d'exception vers
    // l'appelant, c'est une consultation.
    @Suppress("SwallowedException")
    internal fun lire(id: String): List<LigneSortie> {
        val fichier = fichier(id)
        if (!fichier.isFile) return emptyList()
        val lignes = ArrayList<LigneSortie>()
        try {
            GZIPInputStream(fichier.inputStream().buffered()).use { gzip ->
                BufferedReader(InputStreamReader(gzip, Charsets.UTF_8)).useLines { lignesTexte ->
                    lignesTexte.forEach { texte ->
                        if (texte.isBlank()) return@forEach
                        runCatching {
                            lignes.add(SerialisationExecutions.ligneDepuisJson(JSONObject(texte)))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // Membre tronqué : ce qui a été décodé reste rendu — jamais
            // d'exception (c'est une consultation, ADR 0161).
        }
        return lignes
    }

    /**
     * Tronque le fichier à la conclusion : garde la TÊTE et la QUEUE
     * ([TroncatureAnnoncee]), les lignes du milieu partent — appelé UNE
     * fois, à la conclusion seulement (ADR 0161 décision 2).
     */
    internal fun tronquer(id: String, tete: Int, queue: Int) {
        val toutes = lire(id)
        val total = toutes.size
        if (total <= tete + queue) return
        val conservees = toutes.subList(0, tete.coerceAtMost(total)) +
            toutes.subList((total - queue).coerceAtLeast(0), total)
        val fichier = fichier(id)
        fichier.parentFile?.mkdirs()
        runCatching {
            ecrireMembre(fichier, conservees, ajouter = false)
        }
    }

    /** Efface le fichier de lignes de l'exécution. */
    internal fun effacer(id: String) {
        runCatching { fichier(id).delete() }
    }

    /** Efface TOUT le stockage de lignes (l'exécution la plus ancienne
     *  part d'abord — le plafond global, ADR 0161 décision 3). */
    internal fun toutEffacerSauf(idsGardes: Set<String>) {
        val fichiers = dossier.listFiles { fichier -> fichier.extension == "gz" } ?: return
        fichiers.forEach { fichier ->
            val id = fichier.nameWithoutExtension.removeSuffix(".jsonl")
            if (id !in idsGardes) {
                runCatching { fichier.delete() }
            }
        }
    }

    private companion object {
        /** Le séparateur JSONL (une ligne de texte par enregistrement). */
        val SEPARATEUR_LIGNE = "\n".toByteArray(Charsets.UTF_8)
    }
}
