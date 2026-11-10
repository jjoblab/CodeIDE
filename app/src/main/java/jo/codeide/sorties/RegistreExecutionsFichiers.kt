package jo.codeide.sorties

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ExecutionSortie
import jo.codeide.core.domain.ExpurgateurSorties
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.LogRepository
import jo.codeide.core.domain.ReferenceJournauxServeur
import jo.codeide.core.domain.RegistreExecutions
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.TroncatureSorties
import jo.codeide.core.domain.versNiveauSortie
import jo.codeide.core.model.LogEntry
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * L'implémentation FICHIER du registre des exécutions (ADR 0161, lot L3)
 * — le même patron que `ArchiveSessionsJournalFichiers` du Logcat :
 * stockage PRIVÉ, bornes honorées, écriture ATOMIQUE de l'index
 * (`.tmp` + renommage), contenu corrompu lu comme une liste vide (c'est
 * une consultation, jamais une donnée critique — jamais d'exception vers
 * l'appelant).
 *
 * **Le format** (ADR 0161 décision 6) : un fichier JSON Lines compressé
 * par exécution ([StockageLignesExecutions], une écriture PAR LOT) et un
 * index JSON unique ([charger] le lit, [demarrer] et [conclure] le
 * réécrivent) — les compteurs et le verdict voyagent dans l'en-tête.
 *
 * **Les bornes** (ADR 0161 décisions 1–3) :
 * - [N_EXECUTIONS] exécutions au plus — la plus ancienne est sacrifiée
 *   d'abord à chaque conclusion ;
 * - [PLAFOND_LIGNES] lignes par exécution — au-delà, la troncature
 *   épargne la TÊTE et la QUEUE (les lignes du milieu partent) et est
 *   ANNONCÉE dans [ExecutionSortie.troncature] avec ses bornes exactes ;
 * - [PLAFOND_OCTETS] pour TOUT l'historique — l'exécution la plus
 *   ancienne part d'abord (réglages fins au lot L6, M13).
 *
 * **L'expurgation À L'ÉCRITURE** ([ExpurgateurSorties]) : chaque ligne
 * passe ICI, à la frontière du disque — relativisation des chemins du
 * projet (racine [ExecutionSortie.projet]) puis `LogRedactor`. L'affichage
 * live n'est JAMAIS altéré (ADR 0161 décision 4) ; idempotence vérifiée
 * par les tests.
 *
 * **Les journaux serveur** ([chargerJournauxServeur]) : les lignes du tag
 * `gradle-server` dans la plage de l'exécution sont LUES dans le journal
 * applicatif ([LogRepository]) et adaptées à la famille SERVEUR — jamais
 * copiées dans le registre (ADR 0161, alternative rejetée).
 *
 * @param contexte le contexte applicatif (filesDir).
 * @param journalDepot le dépôt du journal applicatif (source des lignes
 *        `gradle-server`).
 * @param journal les pans du registre sont journalisés (ADR 0080).
 * @param dispatchers répartition des fils (règle 5 : injectés).
 */
// Exemption detekt ciblée (règle 16) : UNE fonction par opérande du port
// [RegistreExecutions] plus les mécaniques fichiers (index atomique,
// plafond) — l'implémentation est la traduction littérale du port.
@Singleton
@Suppress("TooManyFunctions")
internal class RegistreExecutionsFichiers
@Inject
constructor(
    @ApplicationContext contexte: Context,
    private val journalDepot: LogRepository,
    private val journal: AppLogger,
    private val dispatchers: DispatcherProvider,
) : RegistreExecutions {
    private val dossier = File(contexte.filesDir, DOSSIER)
    private val stockage = StockageLignesExecutions(dossier)

    override suspend fun charger(): List<ExecutionSortie> = withContext(dispatchers.io) {
        lireIndex()
    }

    override suspend fun ouvrirLignes(id: String): List<LigneSortie> = withContext(dispatchers.io) {
        stockage.lire(id)
    }

    override suspend fun demarrer(execution: ExecutionSortie) = withContext(dispatchers.io) {
        val index = lireIndex().filterNot { it.id == execution.id }
        ecrireIndex((listOf(execution) + index).take(N_EXECUTIONS))
    }

    override suspend fun ajouterLignes(execution: ExecutionSortie, lignes: List<LigneSortie>) =
        withContext(dispatchers.io) {
            // Expurgation À L'ÉCRITURE (ADR 0161 décision 4) : la seule
            // frontière où le contenu s'altère — le live reste intact.
            stockage.ajouter(execution.id, lignes.map { ligne -> ExpurgateurSorties.expurger(ligne, execution.projet) })
        }

    override suspend fun conclure(execution: ExecutionSortie): ExecutionSortie = withContext(dispatchers.io) {
        var conclue = execution
        // La troncature est appliquée puis ANNONCÉE (bornes exactes,
        // règle 14 — jamais de perte silencieuse).
        val troncature = TroncatureSorties.borner(execution.totalLignes, PLAFOND_LIGNES)
        if (troncature != null && execution.troncature == null) {
            stockage.tronquer(execution.id, troncature.tete, troncature.queue)
            conclue = conclue.copy(troncature = troncature)
        }
        conclue = conclue.copy(octetsPersistes = stockage.octets(conclue.id))
        // L'index : la plus récente d'abord, bornée (N exécutions).
        val autres = lireIndex().filterNot { it.id == conclue.id }
        val gardees = (listOf(conclue) + autres).take(N_EXECUTIONS)
        ecrireIndex(gardees)
        purgerSiBesoin(gardees)
        conclue
    }

    override suspend fun effacer(id: String?): Unit = withContext(dispatchers.io) {
        if (id == null) {
            dossier.listFiles()?.forEach { fichier -> runCatching { fichier.delete() } }
        } else {
            stockage.effacer(id)
            ecrireIndex(lireIndex().filterNot { it.id == id })
        }
    }

    override suspend fun chargerJournauxServeur(execution: ExecutionSortie): List<LigneSortie> =
        withContext(dispatchers.io) {
            val reference = execution.journauxServeur ?: return@withContext emptyList()
            journalDepot
                .readAll()
                .filter { entree -> entree.tag == TAG_SERVEUR && entree.dans(reference) }
                .map { entree -> entree.versLigneServeur() }
        }

    /** Une entrée du journal tombe-t-elle dans la plage de l'exécution ? */
    private fun LogEntry.dans(reference: ReferenceJournauxServeur): Boolean =
        timestampMillis >= reference.debutMs && (reference.finMs?.let { timestampMillis <= it } ?: true)

    /** L'adaptation d'une entrée `gradle-server` vers SA famille (le
     *  niveau miroir de l'échelle unique, ADR 0160). */
    private fun LogEntry.versLigneServeur(): LigneSortie = LigneSortie(
        source = SourceSortie.SERVEUR,
        message = message,
        horodatageMs = timestampMillis,
        identiteSession = sessionId,
        niveau = level.versNiveauSortie(),
        etiquette = tag,
    )

    /** Lit l'index (corrompu ou absent : liste vide — consultation). */
    private fun lireIndex(): List<ExecutionSortie> {
        val fichier = fichierIndex()
        val contenu =
            fichier
                .takeIf { it.isFile }
                ?.let { lu -> runCatching { lu.readText() }.getOrNull() }
                ?: return emptyList()
        return runCatching {
            val tableau = JSONArray(contenu)
            (0 until tableau.length()).mapNotNull { indice ->
                SerialisationExecutions.executionDepuisJson(tableau.getJSONObject(indice))
            }
        }.getOrDefault(emptyList())
    }

    /** Écrit l'index ATOMIQUEMENT (`.tmp` + renommage — la mort du
     *  processus ne corrompt jamais l'index, patron du Logcat). */
    private fun ecrireIndex(executions: List<ExecutionSortie>) {
        val tableau = JSONArray()
        executions.forEach { execution -> tableau.put(SerialisationExecutions.executionVersJson(execution)) }
        val fichier = fichierIndex()
        fichier.parentFile?.mkdirs()
        val temporaire = File(fichier.parentFile, "${fichier.name}.tmp")
        runCatching {
            temporaire.writeText(tableau.toString())
            if (!temporaire.renameTo(fichier)) {
                temporaire.delete()
            }
        }.onFailure {
            temporaire.delete()
        }
    }

    /**
     * Le plafond global d'octets (ADR 0161 décision 3) : au-delà, les
     * exécutions les plus ANCIENNES partent d'abord (l'index est trié
     * récentes d'abord) — l'effacement reste explicite côté interface,
     * le plafond est une borne de rétention, pas un effacement caché.
     */
    private fun purgerSiBesoin(gardees: List<ExecutionSortie>) {
        var taille = gardees.sumOf { it.octetsPersistes }
        if (taille <= PLAFOND_OCTETS) return
        val aGarder = ArrayList<ExecutionSortie>()
        for (execution in gardees) {
            if (taille > PLAFOND_OCTETS && aGarder.isNotEmpty()) {
                stockage.effacer(execution.id)
                taille -= execution.octetsPersistes
            } else {
                aGarder.add(execution)
            }
        }
        if (aGarder.size != gardees.size) {
            journal.i(TAG) {
                "plafond d'octets atteint : ${gardees.size - aGarder.size} exécutions les plus anciennes purgées"
            }
            ecrireIndex(aGarder)
            stockage.toutEffacerSauf(aGarder.map { it.id }.toSet())
        }
    }

    /** Le fichier d'index de l'historique. */
    private fun fichierIndex(): File = File(dossier, NOM_INDEX)

    private companion object {
        /** Sous-dossier privé du registre (filesDir/sorties). */
        const val DOSSIER = "sorties"

        /** Nom du fichier d'index JSON. */
        const val NOM_INDEX = "executions.json"

        /** Le tag des journaux du serveur de build (ADR 0040). */
        const val TAG_SERVEUR = "gradle-server"

        /** Exécutions conservées (les plus anciennes sacrifiées d'abord,
         *  ADR 0161 décision 1 — réglage fin au lot L6, M13). */
        const val N_EXECUTIONS = 10

        /** Plafond de lignes PAR exécution (tête et queue conservées au-
         *  delà, ADR 0161 décision 2 — réglage fin au lot L6). */
        const val PLAFOND_LIGNES = 20_000

        /** Plafond GLOBAL de l'historique (octets, ADR 0161 décision 3 —
         *  l'exécution la plus ancienne part d'abord ; réglage au lot
         *  L6). 8 Mo : dix exécutions pleines tronquées tiennent sous. */
        const val PLAFOND_OCTETS = 8L * 1024L * 1024L

        /** Étiquette des pans du registre. */
        const val TAG = "RegistreSorties"
    }
}
