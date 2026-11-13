package jo.codeide.sorties

import jo.codeide.core.domain.CompteursTaches
import jo.codeide.core.domain.EtapeSync
import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.ExecutionSortie
import jo.codeide.core.domain.GravitePosition
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.NiveauSortie
import jo.codeide.core.domain.PhaseExecution
import jo.codeide.core.domain.PositionSource
import jo.codeide.core.domain.ReferenceJournauxServeur
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.StatutTache
import jo.codeide.core.domain.TroncatureAnnoncee
import jo.codeide.core.domain.TypeExecution
import jo.codeide.core.domain.VerdictExecution
import org.json.JSONArray
import org.json.JSONObject

/**
 * La sérialisation JSON des exécutions du registre (ADR 0161 décision 6 :
 * « JSON Lines compressé, compteurs et verdict en en-tête JSON unique »)
 * — pure, sans état, sans dépendance Android au-delà d'`org.json`
 * (Robolectric la fournit aux tests). Clés COMPACTES (le stockage des
 * lignes est le volume dominant) et constantes NOMMÉES (discipline du
 * protocole : aucun littéral magique).
 *
 * Les lignes persistées sont des [LigneSortie] complètes : la relecture
 * ([RegistreExecutionsFichiers.ouvrirLignes]) rend des lignes prêtes pour
 * le lecteur (tâche, positions, niveaux, octets) — une exécution
 * re-ouverte montre le même résumé et le même regroupement que le live
 * (ADR 0161 « Conséquences »). La lecture est TOLÉRANTE : champ manquant
 * ou valeur d'énumération plus récente = valeur neutre, jamais
 * d'exception vers l'appelant (c'est une consultation, ADR 0161).
 */
// Exemption detekt ciblée (règle 16) : UNE fonction de sérialisation par
// champ du modèle des exécutions (ADR 0161) — les découper éparpillerait
// le format JSONL que ce seul objet documente.
@Suppress("TooManyFunctions")
internal object SerialisationExecutions {

    // ----- lignes -------------------------------------------------------

    /** UNE ligne vers SON enregistrement JSONL (une ligne de texte). */
    internal fun ligneVersJson(ligne: LigneSortie): JSONObject = JSONObject()
        .put(CLE_SOURCE, ligne.source.name)
        .put(CLE_MESSAGE, ligne.message)
        .put(CLE_HORODATAGE, ligne.horodatageMs)
        .put(CLE_SESSION, ligne.identiteSession ?: JSONObject.NULL)
        .put(CLE_EXECUTION, ligne.idExecution ?: JSONObject.NULL)
        .put(CLE_NIVEAU, ligne.niveau.name)
        .put(CLE_ETIQUETTE, ligne.etiquette ?: JSONObject.NULL)
        .put(CLE_TACHE, ligne.tache ?: JSONObject.NULL)
        .put(CLE_STATUT, ligne.statutTache?.name ?: JSONObject.NULL)
        .put(CLE_DUREE, ligne.dureeMs ?: JSONObject.NULL)
        .put(CLE_OCTETS_RECUS, ligne.octetsRecus ?: JSONObject.NULL)
        .put(CLE_OCTETS_TOTAL, ligne.octetsTotal ?: JSONObject.NULL)
        .put(CLE_POSITIONS, positionsVersJson(ligne.positionsSource))

    /** UN enregistrement JSONL vers la ligne (champ manquant = valeur
     *  neutre — la relecture est tolérante, jamais bloquante). */
    internal fun ligneDepuisJson(json: JSONObject): LigneSortie = LigneSortie(
        source = enumOu(SourceSortie.BUILD) { json.optString(CLE_SOURCE, "BUILD") },
        message = json.optString(CLE_MESSAGE, ""),
        horodatageMs = json.optLong(CLE_HORODATAGE, 0L),
        identiteSession = chaineNulle(json, CLE_SESSION),
        idExecution = chaineNulle(json, CLE_EXECUTION),
        niveau = enumOu(NiveauSortie.INDETERMINE) { json.optString(CLE_NIVEAU, "INDETERMINE") },
        etiquette = chaineNulle(json, CLE_ETIQUETTE),
        tache = chaineNulle(json, CLE_TACHE),
        statutTache = enumNul(json, CLE_STATUT) { StatutTache.valueOf(it) },
        dureeMs = longNul(json, CLE_DUREE),
        octetsRecus = longNul(json, CLE_OCTETS_RECUS),
        octetsTotal = longNul(json, CLE_OCTETS_TOTAL),
        positionsSource = positionsDepuisJson(json.optJSONArray(CLE_POSITIONS)),
    )

    private fun positionsVersJson(positions: List<PositionSource>): JSONArray = JSONArray().apply {
        positions.forEach { position ->
            put(
                JSONObject()
                    .put(CLE_FICHIER, position.fichier)
                    .put(CLE_LIGNE_POS, position.ligne)
                    .put(CLE_COLONNE, position.colonne ?: JSONObject.NULL)
                    .put(CLE_GRAVITE, position.gravite.name),
            )
        }
    }

    private fun positionsDepuisJson(tableau: JSONArray?): List<PositionSource> {
        if (tableau == null) return emptyList()
        return (0 until tableau.length()).mapNotNull { indice ->
            runCatching {
                val json = tableau.getJSONObject(indice)
                PositionSource(
                    fichier = json.optString(CLE_FICHIER, ""),
                    ligne = json.optInt(CLE_LIGNE_POS, 0),
                    colonne = json.optInt(CLE_COLONNE, 0).takeIf { it > 0 },
                    gravite = enumOu(GravitePosition.ERREUR) { json.optString(CLE_GRAVITE, "ERREUR") },
                )
            }.getOrNull()
        }
    }

    // ----- en-têtes -----------------------------------------------------

    /** L'en-tête d'exécution vers son objet JSON (l'index). */
    internal fun executionVersJson(execution: ExecutionSortie): JSONObject = JSONObject()
        .put(CLE_ID, execution.id)
        .put(CLE_TYPE, execution.type.name)
        .put(CLE_PROJET, execution.projet ?: JSONObject.NULL)
        .put(CLE_TACHES, JSONArray().apply { execution.tachesDemandees.forEach { put(it) } })
        .put(CLE_DEBUT, execution.debutMs)
        .put(CLE_FIN, execution.finMs ?: JSONObject.NULL)
        .put(CLE_ETAT_EXECUTION, execution.etat?.let { etatVersJson(it) } ?: JSONObject.NULL)
        .put(CLE_TRONCATURE, execution.troncature?.let { troncatureVersJson(it) } ?: JSONObject.NULL)
        .put(CLE_JOURNAUX, execution.journauxServeur?.let { journauxVersJson(it) } ?: JSONObject.NULL)
        .put(CLE_TOTAL_LIGNES, execution.totalLignes)
        .put(CLE_OCTETS, execution.octetsPersistes)

    /** UN objet JSON de l'index vers l'en-tête (corrompu = `null`, la
     *  relecture de l'index est tolérante entrée par entrée). */
    internal fun executionDepuisJson(json: JSONObject): ExecutionSortie? = runCatching {
        ExecutionSortie(
            id = json.getString(CLE_ID),
            type = enumOu(TypeExecution.BUILD) { json.optString(CLE_TYPE, "BUILD") },
            projet = chaineNulle(json, CLE_PROJET),
            tachesDemandees = chainesDuTableau(json.optJSONArray(CLE_TACHES)),
            debutMs = json.optLong(CLE_DEBUT, 0L),
            finMs = longNul(json, CLE_FIN),
            etat = if (json.isNull(
                    CLE_ETAT_EXECUTION,
                )
            ) {
                null
            } else {
                etatDepuisJson(json.getJSONObject(CLE_ETAT_EXECUTION))
            },
            troncature = if (json.isNull(CLE_TRONCATURE)) {
                null
            } else {
                val t = json.getJSONObject(CLE_TRONCATURE)
                TroncatureAnnoncee(t.optLong(CLE_TOTAL, 0), t.optInt(CLE_TETE, 0), t.optInt(CLE_QUEUE, 0))
            },
            journauxServeur = if (json.isNull(CLE_JOURNAUX)) {
                null
            } else {
                val j = json.getJSONObject(CLE_JOURNAUX)
                ReferenceJournauxServeur(j.optLong(CLE_DEBUT, 0), longNul(j, CLE_FIN))
            },
            totalLignes = json.optLong(CLE_TOTAL_LIGNES, 0),
            octetsPersistes = json.optLong(CLE_OCTETS, 0),
        )
    }.getOrNull()

    private fun troncatureVersJson(troncature: TroncatureAnnoncee): JSONObject = JSONObject()
        .put(CLE_TOTAL, troncature.totalLignes)
        .put(CLE_TETE, troncature.tete)
        .put(CLE_QUEUE, troncature.queue)

    private fun journauxVersJson(journaux: ReferenceJournauxServeur): JSONObject = JSONObject()
        .put(CLE_DEBUT, journaux.debutMs)
        .put(CLE_FIN, journaux.finMs ?: JSONObject.NULL)

    // ----- état d'exécution ----------------------------------------------

    /** L'état ÉVÉNEMENTS (verdict, compteurs, phases) vers JSON. */
    private fun etatVersJson(etat: EtatExecutionGradle): JSONObject {
        val statuts = JSONObject()
        etat.statutsParTache.forEach { (chemin, statut) -> statuts.put(chemin, statut.name) }
        val durees = JSONObject()
        etat.dureesParTache.forEach { (chemin, duree) -> durees.put(chemin, duree) }
        return JSONObject()
            .put(CLE_VERDICT, etat.verdict?.name ?: JSONObject.NULL)
            .put(CLE_DUREE_EXEC, etat.dureeMs ?: JSONObject.NULL)
            .put(CLE_COMPTEURS, etat.compteurs?.let { compteursVersJson(it) } ?: JSONObject.NULL)
            .put(CLE_ERREURS, etat.erreurs)
            .put(CLE_AVERTISSEMENTS, etat.avertissements)
            .put(CLE_STATUTS, statuts)
            .put(CLE_DUREES_TACHES, durees)
            .put(CLE_PHASES, phasesVersJson(etat.phases))
    }

    private fun compteursVersJson(compteurs: CompteursTaches): JSONObject = JSONObject()
        .put(CLE_ACTIONNABLES, compteurs.actionnables ?: JSONObject.NULL)
        .put(CLE_EXECUTEES, compteurs.executees ?: JSONObject.NULL)
        .put(CLE_AJOUR, compteurs.aJour ?: JSONObject.NULL)
        .put(CLE_CACHE, compteurs.depuisCache ?: JSONObject.NULL)
        .put(CLE_SANS_SOURCE, compteurs.sansSource ?: JSONObject.NULL)
        .put(CLE_SAUTEES, compteurs.sautees ?: JSONObject.NULL)

    private fun phasesVersJson(phases: List<PhaseExecution>): JSONArray = JSONArray().apply {
        phases.forEach { phase ->
            put(
                JSONObject()
                    .put(CLE_ETAPE, phase.etape.name)
                    .put(CLE_DUREE, phase.dureeMs)
                    .put(CLE_TERMINEE, phase.terminee),
            )
        }
    }

    /** UN état depuis JSON (champ manquant = valeur neutre). */
    private fun etatDepuisJson(json: JSONObject): EtatExecutionGradle = EtatExecutionGradle(
        verdict = enumNul(json, CLE_VERDICT) { VerdictExecution.valueOf(it) },
        dureeMs = longNul(json, CLE_DUREE_EXEC),
        compteurs = if (json.isNull(CLE_COMPTEURS)) null else compteursDepuisJson(json.getJSONObject(CLE_COMPTEURS)),
        erreurs = json.optInt(CLE_ERREURS, 0),
        avertissements = json.optInt(CLE_AVERTISSEMENTS, 0),
        statutsParTache = enumParCle(json.optJSONObject(CLE_STATUTS)) { StatutTache.valueOf(it) },
        dureesParTache = longsParCle(json.optJSONObject(CLE_DUREES_TACHES)),
        phases = phasesDepuisJson(json.optJSONArray(CLE_PHASES)),
    )

    /** Les phases depuis un tableau JSON (tolérant entrée par entrée). */
    private fun phasesDepuisJson(phasesJson: JSONArray?): List<PhaseExecution> {
        if (phasesJson == null) return emptyList()
        return (0 until phasesJson.length()).mapNotNull { indice ->
            runCatching {
                val p = phasesJson.getJSONObject(indice)
                PhaseExecution(
                    etape = enumOu(EtapeSync.OUTILS) { p.optString(CLE_ETAPE, "OUTILS") },
                    dureeMs = p.optLong(CLE_DUREE, 0),
                    terminee = p.optBoolean(CLE_TERMINEE, false),
                )
            }.getOrNull()
        }
    }

    private fun compteursDepuisJson(c: JSONObject): CompteursTaches = CompteursTaches(
        actionnables = intNul(c, CLE_ACTIONNABLES),
        executees = intNul(c, CLE_EXECUTEES),
        aJour = intNul(c, CLE_AJOUR),
        depuisCache = intNul(c, CLE_CACHE),
        sansSource = intNul(c, CLE_SANS_SOURCE),
        sautees = intNul(c, CLE_SAUTEES),
    )

    // ----- helpers tolérants --------------------------------------------

    /** La chaîne du champ, ou `null` (absent ou JSON null). */
    private fun chaineNulle(json: JSONObject, cle: String): String? =
        if (json.isNull(cle)) null else json.optString(cle, "").takeIf { it.isNotEmpty() }

    /** Le long du champ, ou `null` (absent ou JSON null). */
    private fun longNul(json: JSONObject, cle: String): Long? =
        if (json.isNull(cle)) null else json.optLong(cle, 0L).takeIf { json.has(cle) }

    /** L'int du champ, ou `null` (absent ou JSON null). */
    private fun intNul(json: JSONObject, cle: String): Int? =
        if (json.isNull(cle)) null else json.optInt(cle, 0).takeIf { json.has(cle) }

    /** L'énumération du champ, ou `null` (valeur inconnue acceptée — la
     *  relecture survit aux modèles plus récents). */
    private inline fun <reified T : Enum<T>> enumNul(json: JSONObject, cle: String, lire: (String) -> T): T? {
        if (json.isNull(cle) || !json.has(cle)) return null
        return runCatching { lire(json.getString(cle)) }.getOrNull()
    }

    /** L'énumération avec repli (valeur inconnue = repli documenté). */
    private inline fun <reified T : Enum<T>> enumOu(repli: T, lire: () -> String): T =
        runCatching { T::class.java.enumConstants?.firstOrNull { it.name == lire() } }.getOrNull() ?: repli

    /** Les chaînes d'un tableau JSON (tolérant). */
    private fun chainesDuTableau(tableau: JSONArray?): List<String> {
        if (tableau == null) return emptyList()
        return (0 until tableau.length()).mapNotNull { indice ->
            runCatching { tableau.optString(indice) }.getOrNull()
        }
    }

    /** Les énumérations d'un objet JSON par clé (tolérant). */
    private inline fun <reified T : Enum<T>> enumParCle(objet: JSONObject?, lire: (String) -> T): Map<String, T> {
        if (objet == null) return emptyMap()
        val resultats = HashMap<String, T>()
        objet.keys().forEach { cle ->
            runCatching { resultats[cle] = lire(objet.getString(cle)) }
        }
        return resultats
    }

    /** Les longs d'un objet JSON par clé (tolérant). */
    private fun longsParCle(objet: JSONObject?): Map<String, Long> {
        if (objet == null) return emptyMap()
        val resultats = HashMap<String, Long>()
        objet.keys().forEach { cle -> resultats[cle] = objet.optLong(cle, 0) }
        return resultats
    }

    private const val CLE_SOURCE = "s"
    private const val CLE_MESSAGE = "m"
    private const val CLE_HORODATAGE = "h"
    private const val CLE_SESSION = "i"
    private const val CLE_EXECUTION = "x"
    private const val CLE_NIVEAU = "n"
    private const val CLE_ETIQUETTE = "e"
    private const val CLE_TACHE = "t"
    private const val CLE_STATUT = "st"
    private const val CLE_DUREE = "d"
    private const val CLE_OCTETS_RECUS = "or"
    private const val CLE_OCTETS_TOTAL = "ot"
    private const val CLE_POSITIONS = "p"
    private const val CLE_FICHIER = "f"
    private const val CLE_LIGNE_POS = "l"
    private const val CLE_COLONNE = "c"
    private const val CLE_GRAVITE = "g"

    private const val CLE_ID = "id"
    private const val CLE_TYPE = "type"
    private const val CLE_PROJET = "projet"
    private const val CLE_TACHES = "taches"
    private const val CLE_DEBUT = "debut"
    private const val CLE_FIN = "fin"
    private const val CLE_ETAT_EXECUTION = "etat"
    private const val CLE_TRONCATURE = "troncature"
    private const val CLE_JOURNAUX = "journaux"
    private const val CLE_TOTAL_LIGNES = "total"
    private const val CLE_OCTETS = "octets"

    private const val CLE_VERDICT = "verdict"
    private const val CLE_DUREE_EXEC = "duree"
    private const val CLE_COMPTEURS = "compteurs"
    private const val CLE_ACTIONNABLES = "actionnables"
    private const val CLE_EXECUTEES = "executees"
    private const val CLE_AJOUR = "aJour"
    private const val CLE_CACHE = "cache"
    private const val CLE_SANS_SOURCE = "sansSource"
    private const val CLE_SAUTEES = "sautees"
    private const val CLE_ERREURS = "erreurs"
    private const val CLE_AVERTISSEMENTS = "avertissements"
    private const val CLE_STATUTS = "statuts"
    private const val CLE_DUREES_TACHES = "dureesTaches"
    private const val CLE_PHASES = "phases"
    private const val CLE_ETAPE = "etape"
    private const val CLE_TERMINEE = "terminee"
    private const val CLE_TOTAL = "total"
    private const val CLE_TETE = "tete"
    private const val CLE_QUEUE = "queue"
}
