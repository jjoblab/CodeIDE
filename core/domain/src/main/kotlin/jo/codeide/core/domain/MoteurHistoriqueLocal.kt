package jo.codeide.core.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Moteur de l'historique local (mission H1, ADR 0104) : blobs adressés
 * par empreinte SHA-256 (déduplication NATURELLE — un contenu identique
 * est stocké une fois) + index JSON réécrit **atomiquement** (écrire le
 * `.tmp` puis renommer — la mort du processus ne corrompt JAMAIS
 * l'index ; un blob orphelin est inoffensif et ramassé par la purge).
 *
 * ```
 * <racine>/<empreinte-de-la-cle-du-projet>/
 *     index.json     ← entrées (versionnées, réécriture atomique)
 *     blobs/<sha256> ← contenus distincts
 * ```
 *
 * Le contenu d'une entrée est l'état **AVANT** la transition (modèle
 * IntelliJ prouvé — l'état courant vit sur le disque, jamais doublé) ;
 * une suppression laisse une PIERRE TOMBALE. Aucune entrée si le
 * contenu ne change pas (même empreinte que la transition précédente
 * du même chemin — pas d'historique de bruit, ADR 0105).
 *
 * Intégrité au chargement : index absent/corrompu/version inconnue →
 * historique VIDE (jamais de fausses données), blobs orphelins purgés.
 *
 * @param racine dossier du stockage privé dédié (`files/historique`).
 * @param cleProjetCourante clé stable du projet OUVERT (l'URI de
 *        document de sa racine, ou `null` hors projet) — chaque projet
 *        possède son dossier d'historique ; l'index en mémoire est
 *        rechargé quand la clé change.
 * @param horloge temps injecté (purges par âge testables).
 * @param repartiteurs dispatcheurs (toute E/S hors fil principal).
 * @param politique conservation (ADR 0105), réglable.
 */
@Suppress("TooManyFunctions") // Port Historique : une fonction par opération + intégrité (index, blobs, purge).
public class MoteurHistoriqueLocal(
    private val racine: File,
    private val cleProjetCourante: () -> String?,
    private val horloge: TimeProvider,
    private val repartiteurs: DispatcherProvider,
    private val politique: PolitiqueHistorique = PolitiqueHistorique(),
) : HistoriqueLocal {
    private val json = Json { ignoreUnknownKeys = true }
    private val verrou = Mutex()

    /** Dossier du projet dont l'index est en mémoire (invalidation à la clé). */
    private var dossierCharge: File? = null

    /** Index en mémoire (chargé paresseusement, réécrit à chaque entrée). */
    private var indexEnMemoire: MutableList<EntreeHistorique>? = null

    /** Clé du projet courant, ou `null` (aucune capture hors projet). */
    private fun cleCourante(): String? = cleProjetCourante()

    override suspend fun enregistrer(
        cheminRelatif: String,
        type: TypeEntreeHistorique,
        contenu: String?,
        tailleOctets: Long,
        libelle: String?,
    ): EntreeHistorique? =
        verrou.withLock {
            withContext(repartiteurs.io) {
                if (cleCourante() == null) return@withContext null
                val precedente = entrees().lastOrNull { it.cheminRelatif == cheminRelatif }

                // Pas d'historique de bruit (ADR 0105) : même contenu et
                // même nature que la transition précédente du chemin.
                if (precedente?.type == type && precedente?.empreinte == empreinteDe(contenu)) {
                    return@withContext null
                }

                // Contenu disponible seulement sous le plafond par fichier
                // (au-delà : entrée honnête SANS contenu).
                val contenuStocke =
                    if (contenu != null && contenu.length > politique.tailleMaxFichierOctets) null else contenu
                val empreinteStockee = empreinteDe(contenuStocke)
                if (contenuStocke != null && empreinteStockee != null) {
                    ecrireBlob(empreinteStockee, contenuStocke)
                }

                val entree =
                    EntreeHistorique(
                        id = prochaineId(),
                        cheminRelatif = cheminRelatif,
                        type = type,
                        horodatageMs = horloge.nowMillis(),
                        empreinte = empreinteStockee,
                        tailleOctets = tailleOctets,
                        libelle = libelle,
                    )
                entrees() += entree
                reecrireIndex()
                entree
            }
        }

    override suspend fun listerRevisions(cheminRelatif: String): List<EntreeHistorique> =
        verrou.withLock {
            withContext(repartiteurs.io) {
                if (cleCourante() == null) {
                    return@withContext emptyList()
                }
                entrees().filter { it.cheminRelatif == cheminRelatif }.asReversed()
            }
        }

    override suspend fun listerRevisionsSous(
        cheminDossier: String,
        limite: Int,
    ): List<EntreeHistorique> =
        verrou.withLock {
            withContext(repartiteurs.io) {
                if (cleCourante() == null) {
                    return@withContext emptyList()
                }
                // Préfixe STRICT : « src » couvre « src/… », jamais
                // « srcX/… » ; L'EXACT le dossier lui-même (une
                // étiquette posée SUR le dossier paraît dans SON
                // historique — H4) ; vide = racine = tout le projet.
                val prefixe = if (cheminDossier.isBlank()) "" else "$cheminDossier/"
                entrees()
                    .asReversed()
                    .filter { it.cheminRelatif == cheminDossier || it.cheminRelatif.startsWith(prefixe) }
                    .take(limite.coerceAtLeast(0))
            }
        }

    override suspend fun lireContenu(id: Long): String? =
        verrou.withLock {
            withContext(repartiteurs.io) {
                if (cleCourante() == null) {
                    return@withContext null
                }
                val entree = entrees().firstOrNull { it.id == id } ?: return@withContext null
                entree.empreinte?.let { File(dossierBlobs(), it).takeIf(File::isFile)?.readText() }
            }
        }

    override suspend fun derniereEmpreinte(cheminRelatif: String): String? =
        verrou.withLock {
            withContext(repartiteurs.io) {
                if (cleCourante() == null) {
                    return@withContext null
                }
                entrees().lastOrNull { it.cheminRelatif == cheminRelatif }?.empreinte
            }
        }

    override suspend fun etiqueter(
        nom: String,
        cheminRelatif: String?,
    ): EntreeHistorique? =
        verrou.withLock {
            withContext(repartiteurs.io) {
                if (cleCourante() == null) {
                    return@withContext null
                }
                val entree =
                    EntreeHistorique(
                        id = prochaineId(),
                        cheminRelatif = cheminRelatif ?: CHEMIN_PROJET,
                        type = TypeEntreeHistorique.ETIQUETTE,
                        horodatageMs = horloge.nowMillis(),
                        empreinte = null,
                        tailleOctets = 0L,
                        libelle = nom,
                    )
                entrees() += entree
                reecrireIndex()
                entree
            }
        }

    override suspend fun purger(): Unit =
        verrou.withLock {
            withContext(repartiteurs.io) {
                if (cleCourante() == null) return@withContext
                val existantes = entrees()
                val maintenant = horloge.nowMillis()
                val ageMaxMs = politique.joursRetention * MILLIS_PAR_JOUR

                var conservees = existantes.filter { maintenant - it.horodatageMs <= ageMaxMs }
                // Quota : les entrées les plus anciennes d'abord, jusqu'à
                // ce que les blobs DISTINCTS restants respectent le quota.
                var referencees = empreintesReferencees(conservees)
                while (referencees.sumOf { it.taille } > politique.quotaOctets && conservees.isNotEmpty()) {
                    conservees = conservees.drop(1)
                    referencees = empreintesReferencees(conservees)
                }
                // Nombre maximal d'entrées.
                if (conservees.size > politique.nbMaxEntrees) {
                    conservees = conservees.takeLast(politique.nbMaxEntrees)
                }

                if (conservees.size != existantes.size) {
                    indexEnMemoire = conservees.toMutableList()
                    reecrireIndex()
                }
                supprimerBlobsOrphelins(referencees.map { it.empreinte }.toSet())
            }
        }

    // ------------------------------------------------------------------
    // Index et blobs (intégrité par renommage atomique)
    // ------------------------------------------------------------------

    /** Index en mémoire, chargé (et VALIDÉ) à la première utilisation —
     * RECHARGÉ quand la clé du projet change (dossier différent). */
    private fun entrees(): MutableList<EntreeHistorique> {
        val dossier = dossierProjet()
        val memoire = indexEnMemoire
        if (memoire != null && dossierCharge == dossier) return memoire
        dossierCharge = dossier
        val lues: List<EntreeHistorique> =
            try {
                val fichier = File(dossier, NOM_INDEX)
                if (!fichier.isFile) {
                    emptyList()
                } else {
                    val document = json.parseToJsonElement(fichier.readText()) as? JsonObject
                    val version =
                        (
                            document?.get(
                                "version",
                            ) as? kotlinx.serialization.json.JsonPrimitive
                        )?.content?.toIntOrNull()
                    val tableau = document?.get("entrees") as? JsonArray
                    if (version != VERSION_INDEX || tableau == null) {
                        emptyList()
                    } else {
                        json
                            .decodeFromJsonElement(
                                ListSerializer(EntreeSer.serializer()),
                                tableau,
                            ).map { it.versEntree() }
                    }
                }
            } catch (_: Exception) {
                // Index corrompu : historique VIDE (jamais de fausses
                // données — ADR 0104), blobs orphelins purgés ensuite.
                emptyList()
            }
        val chargees = lues.toMutableList()
        indexEnMemoire = chargees
        return chargees
    }

    /** Réécrit l'index : `.tmp` puis renommage (atomique). */
    private fun reecrireIndex() {
        val dossier = dossierProjet()
        dossier.mkdirs()
        val tableau =
            buildJsonArray {
                entrees().forEach { entree -> add(entree.versJson()) }
            }
        val document =
            buildJsonObject {
                put("version", VERSION_INDEX)
                put("prochaineId", prochaineId())
                put("entrees", tableau)
            }
        val fichier = File(dossier, NOM_INDEX)
        val temporaire = File(dossier, "$NOM_INDEX.tmp")
        temporaire.writeText(json.encodeToString(JsonObject.serializer(), document))
        if (!temporaire.renameTo(fichier)) {
            fichier.delete()
            temporaire.renameTo(fichier)
        }
    }

    /** Écrit un blob (dédup : existant → saut) : `.tmp` puis renommage. */
    private fun ecrireBlob(
        empreinte: String,
        contenu: String,
    ) {
        val dossier = dossierBlobs()
        dossier.mkdirs()
        val blob = File(dossier, empreinte)
        if (blob.isFile) return
        val temporaire = File(dossier, "$empreinte.tmp")
        temporaire.writeText(contenu)
        if (!temporaire.renameTo(blob)) {
            blob.delete()
            temporaire.renameTo(blob)
        }
    }

    /** Supprime les blobs référencés par aucune entrée conservée. */
    private fun supprimerBlobsOrphelins(gardees: Set<String>) {
        val dossier = dossierBlobs()
        dossier.listFiles()?.forEach { fichier -> if (fichier.name !in gardees) fichier.delete() }
    }

    /** Empreintes distinctes référencées (avec leur taille sur disque). */
    private fun empreintesReferencees(entrees: List<EntreeHistorique>): List<EmpreinteTaille> =
        entrees.mapNotNull { it.empreinte }.distinct().map { empreinte ->
            EmpreinteTaille(empreinte, File(dossierBlobs(), empreinte).takeIf(File::isFile)?.length() ?: 0L)
        }

    private fun prochaineId(): Long = (entrees().maxOfOrNull { it.id } ?: 0L) + 1L

    /** Dossier du projet courant (empreinte de sa clé) — null-safe par le garde d'appel. */
    private fun dossierProjet(): File = File(racine, empreinteDe(cleCourante() ?: ""))

    private fun dossierBlobs(): File = File(dossierProjet(), "blobs")

    /** Empreinte SHA-256 hexadécimale, ou `null` pour un contenu nul. */
    private fun empreinteDe(contenu: String?): String? =
        contenu?.let {
            MessageDigest
                .getInstance("SHA-256")
                .digest(it.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { octet -> "%02x".format(octet) }
        }

    /** (empreinte, taille) d'un blob référencé. */
    private data class EmpreinteTaille(
        val empreinte: String,
        val taille: Long,
    )

    /** Forme persistante d'une entrée (JSON stable, ADR 0104). */
    @Serializable
    private data class EntreeSer(
        val id: Long,
        val chemin: String,
        val type: String,
        val horodatage: Long,
        val empreinte: String? = null,
        val taille: Long = 0L,
        val libelle: String? = null,
    ) {
        fun versEntree(): EntreeHistorique =
            EntreeHistorique(
                id = id,
                cheminRelatif = chemin,
                type =
                    runCatching { TypeEntreeHistorique.valueOf(type) }
                        .getOrDefault(TypeEntreeHistorique.MODIFICATION),
                horodatageMs = horodatage,
                empreinte = empreinte,
                tailleOctets = taille,
                libelle = libelle,
            )
    }

    /** Sérialisation d'une entrée vers le tableau JSON de l'index. */
    private fun EntreeHistorique.versJson(): JsonObject =
        buildJsonObject {
            put("id", id)
            put("chemin", cheminRelatif)
            put("type", type.name)
            put("horodatage", horodatageMs)
            empreinte?.let { put("empreinte", it) }
            put("taille", tailleOctets)
            libelle?.let { put("libelle", it) }
        }

    private companion object {
        const val NOM_INDEX = "index.json"
        const val VERSION_INDEX = 1
        const val MILLIS_PAR_JOUR = 24L * 60L * 60L * 1000L
        const val CHEMIN_PROJET = ""
    }
}
