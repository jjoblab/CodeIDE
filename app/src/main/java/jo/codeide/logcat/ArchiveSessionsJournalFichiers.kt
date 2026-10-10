package jo.codeide.logcat

import android.content.Context
import jo.codeide.core.domain.ArchiveSessionsJournal
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.FinSessionJournal
import jo.codeide.core.domain.IdentiteSessionJournal
import jo.codeide.core.domain.LigneJournal
import jo.codeide.core.domain.NiveauJournal
import jo.codeide.core.domain.SessionJournal
import jo.codeide.core.domain.SessionJournalArchivee
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Implémentation fichier de l'archive des sessions de journaux terminées
 * (mission « Exécuter » R3, ADR 0103) : un JSON unique dans le stockage
 * PRIVÉ (`filesDir/logcat/`), BORNÉ ([BORNE_SESSIONS] entrées, les plus
 * anciennes sacrifiées), réécrit ATOMIQUEMENT (`.tmp` + renommage — la
 * mort du processus ne corrompt jamais l'archive).
 *
 * Ce qui n'est PAS archivé : le tampon de lignes (seule la DERNIÈRE ligne
 * est conservée, pour l'aperçu du sélecteur) — un historique complet de
 * journaux sur disque serait un trou de confidentialité que personne
 * n'a demandé.
 *
 * Fichier corrompu ou illisible : liste vide, fichier régénéré au
 * prochain archivage (c'est un bonus de consultation, pas une donnée
 * critique — jamais d'exception vers l'appelant).
 *
 * @property contexte contexte applicatif (filesDir).
 * @property dispatchers couture des tests.
 */
internal class ArchiveSessionsJournalFichiers(
    private val contexte: Context,
    private val dispatchers: DispatcherProvider,
) : ArchiveSessionsJournal {
    /** Fichier JSON de l'archive. */
    private val fichier: File
        get() = File(File(contexte.filesDir, DOSSIER), NOM_FICHIER)

    override suspend fun charger(): List<SessionJournalArchivee> =
        withContext(dispatchers.io) {
            // Illisible (absent, IO) : liste vide — bonus de consultation,
            // jamais une donnée critique.
            val contenu =
                fichier
                    .takeIf { it.isFile }
                    ?.let { lu -> runCatching { lu.readText() }.getOrNull() }
                    ?: return@withContext emptyList()
            runCatching {
                val tableau = JSONArray(contenu)
                val sessions = ArrayList<SessionJournalArchivee>(tableau.length())
                for (i in 0 until tableau.length()) {
                    sessions.add(depuisJson(tableau.getJSONObject(i)))
                }
                // La plus récente d'abord (ordre d'affichage du sélecteur).
                sessions.sortByDescending { it.session.fin?.horodatageMs ?: 0L }
                sessions
            }.getOrDefault(emptyList())
        }

    override suspend fun archiver(
        session: SessionJournal,
        derniereLigne: LigneJournal?,
    ) = withContext(dispatchers.io) {
        val actuelles = charger() + SessionJournalArchivee(session, derniereLigne)
        // Dédupliquée par identifiant (une ré-archive remplace l'ancienne),
        // bornée aux plus récentes, écrite atomiquement.
        val gardees =
            actuelles
                .associateBy { it.session.id }
                .values
                .sortedByDescending { it.session.fin?.horodatageMs ?: 0L }
                .take(BORNE_SESSIONS)
        ecrire(gardees)
    }

    /** Écriture atomique : `.tmp` puis renommage — un échec d'écriture
     *  ne corrompt JAMAIS l'archive courante (le temporaire part,
     *  l'ancienne version reste). */
    private fun ecrire(sessions: List<SessionJournalArchivee>) {
        val tableau = JSONArray()
        sessions.forEach { tableau.put(versJson(it)) }
        fichier.parentFile?.mkdirs()
        val temporaire = File(fichier.parentFile, "$NOM_FICHIER.tmp")
        runCatching {
            temporaire.writeText(tableau.toString())
            if (!temporaire.renameTo(fichier)) {
                // Renommage refusé (fichier cible verrouillé ?) : l'ancienne
                // archive reste — la prochaine tentative repartira propre.
                temporaire.delete()
            }
        }.onFailure {
            temporaire.delete()
        }
    }

    private fun versJson(archivee: SessionJournalArchivee): JSONObject =
        JSONObject()
            .put(
                CLE_SESSION,
                JSONObject()
                    .put(CLE_ID, archivee.session.id)
                    .put(
                        CLE_IDENTITE,
                        JSONObject()
                            .put(CLE_PAQUET, archivee.session.identite.paquet)
                            .put(CLE_PID, archivee.session.identite.pid)
                            .put(CLE_PROCESSUS, archivee.session.identite.nomProcessus),
                    ).put(
                        CLE_FIN,
                        archivee.session.fin?.let { fin ->
                            JSONObject()
                                .put(CLE_RAISON, fin.raison)
                                .put(CLE_HORODATAGE, fin.horodatageMs)
                        } ?: JSONObject.NULL,
                    ).put(CLE_NOMBRE_LIGNES, archivee.session.nombreLignes)
                    .put(CLE_LIGNES_PERDUES, archivee.session.lignesPerdues),
            ).put(
                CLE_DERNIERE_LIGNE,
                archivee.derniereLigne?.let { ligne ->
                    JSONObject()
                        .put(CLE_HORODATAGE, ligne.horodatageMs)
                        .put(CLE_PID, ligne.pid)
                        .put(CLE_TID, ligne.tid)
                        .put(CLE_NIVEAU, ligne.niveau.name)
                        .put(CLE_ETIQUETTE, ligne.etiquette)
                        .put(CLE_MESSAGE, ligne.message)
                } ?: JSONObject.NULL,
            )

    private fun depuisJson(json: JSONObject): SessionJournalArchivee {
        val sessionJson = json.getJSONObject(CLE_SESSION)
        val identiteJson = sessionJson.getJSONObject(CLE_IDENTITE)
        val finOpt =
            if (sessionJson.isNull(CLE_FIN)) {
                null
            } else {
                val finJson = sessionJson.getJSONObject(CLE_FIN)
                FinSessionJournal(
                    raison = finJson.getString(CLE_RAISON),
                    horodatageMs = finJson.getLong(CLE_HORODATAGE),
                )
            }
        val derniereOpt =
            if (json.isNull(CLE_DERNIERE_LIGNE)) {
                null
            } else {
                val ligneJson = json.getJSONObject(CLE_DERNIERE_LIGNE)
                LigneJournal(
                    horodatageMs = ligneJson.getLong(CLE_HORODATAGE),
                    pid = ligneJson.getInt(CLE_PID),
                    tid = ligneJson.getInt(CLE_TID),
                    niveau =
                        runCatching { NiveauJournal.valueOf(ligneJson.getString(CLE_NIVEAU)) }
                            .getOrDefault(NiveauJournal.INFO),
                    etiquette = ligneJson.optString(CLE_ETIQUETTE, ""),
                    message = ligneJson.optString(CLE_MESSAGE, ""),
                )
            }
        return SessionJournalArchivee(
            session =
                SessionJournal(
                    id = sessionJson.getString(CLE_ID),
                    identite =
                        IdentiteSessionJournal(
                            paquet = identiteJson.getString(CLE_PAQUET),
                            pid = identiteJson.getInt(CLE_PID),
                            nomProcessus = identiteJson.optString(CLE_PROCESSUS, identiteJson.getString(CLE_PAQUET)),
                        ),
                    etat = EtatSessionJournal.TERMINEE,
                    fin = finOpt,
                    nombreLignes = sessionJson.optInt(CLE_NOMBRE_LIGNES, 0),
                    lignesPerdues = sessionJson.optLong(CLE_LIGNES_PERDUES, 0L),
                    sortiePrecedente = null,
                ),
            derniereLigne = derniereOpt,
        )
    }

    private companion object {
        /** Sessions conservées (les plus anciennes sacrifiées d'abord). */
        const val BORNE_SESSIONS = 6

        /** Sous-dossier privé de l'archive. */
        const val DOSSIER = "logcat"

        /** Nom du fichier JSON. */
        const val NOM_FICHIER = "sessions-precedentes.json"

        const val CLE_SESSION = "session"
        const val CLE_ID = "id"
        const val CLE_IDENTITE = "identite"
        const val CLE_PAQUET = "paquet"
        const val CLE_PID = "pid"
        const val CLE_TID = "tid"
        const val CLE_PROCESSUS = "processus"
        const val CLE_FIN = "fin"
        const val CLE_RAISON = "raison"
        const val CLE_HORODATAGE = "horodatage"
        const val CLE_NOMBRE_LIGNES = "nombreLignes"
        const val CLE_LIGNES_PERDUES = "lignesPerdues"
        const val CLE_DERNIERE_LIGNE = "derniereLigne"
        const val CLE_NIVEAU = "niveau"
        const val CLE_ETIQUETTE = "etiquette"
        const val CLE_MESSAGE = "message"
    }
}
