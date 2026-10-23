package jo.codeide.core.ai

import jo.codeide.core.domain.EntreeHistorique
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.HistoriqueLocal
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.mimeFichierTexte
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.getOrNull
import javax.inject.Inject

/**
 * Un fichier du ChangeSet tel que la REVUE le présente (IA-7b, ADR 0125
 * « Réversibilité ») : résolution SAF faite, conflit détecté AVANT toute
 * écriture — un fichier modifié depuis la proposition est marqué
 * [conflit] et n'est jamais écrasé silencieusement.
 *
 * @property cheminRelatif chemin POSIX relatif à la racine du projet.
 * @property type nature du changement proposé.
 * @property contenuPropose contenu à écrire (CREER/MODIFIER), vide pour SUPPRIMER.
 * @property raison motif donné par l'agent pour ce changement.
 * @property conflit `true` = l'application de CE fichier est refusée.
 * @property motifConflit explication lisible si [conflit].
 * @property uri URI du fichier existant (MODIFIER/SUPPRIMER) ; `null` pour CREER.
 * @property segmentsManquants parents à créer pour un CREER (mkdir -p).
 * @property uriParent URI du dossier parent direct (CREER), `null` sinon.
 * @property nomFichier nom du dernier segment (CREER), `null` sinon.
 */
public data class FichierRevueIa(
    public val cheminRelatif: String,
    public val type: TypeChangementIa,
    public val contenuPropose: String,
    public val raison: String,
    public val conflit: Boolean,
    public val motifConflit: String?,
    public val uri: String?,
    public val segmentsManquants: List<String>,
    public val uriParent: String?,
    public val nomFichier: String?,
)

/**
 * Revue complète d'un ChangeSet (écran Agent, spec E2) : fichiers,
 * conflits et applicables — la décision « tout appliquer / fichier par
 * fichier / rien » se prend sur cette donnée.
 */
public data class RevueChangeSetIa(public val fichiers: List<FichierRevueIa>) {
    /** Fichiers marqués conflit (jamais proposés à l'application). */
    public val conflits: List<FichierRevueIa> get() = fichiers.filter { it.conflit }

    /** Fichiers applicables (non conflits). */
    public val applicables: List<FichierRevueIa> get() = fichiers.filter { !it.conflit }

    public val vide: Boolean get() = fichiers.isEmpty()
}

/**
 * État d'un fichier capturé AVANT application (IA-7b) — l'instantané qui
 * rend « Annuler cette exécution » EXACT (création → suppression,
 * modification → contenu précédent, suppression → recréation).
 *
 * @property cheminRelatif chemin POSIX relatif du fichier.
 * @property type type du changement qui a été appliqué.
 * @property uri URI du fichier après application (application réussie).
 * @property uriParent URI du dossier parent (recréation d'une suppression).
 * @property nomFichier dernier segment (recréation).
 * @property contenuAvant contenu du fichier avant l'application
 *           (`null` pour une création — il n'existait pas).
 */
public data class FichierAvantIa(
    public val cheminRelatif: String,
    public val type: TypeChangementIa,
    public val uri: String?,
    public val uriParent: String?,
    public val nomFichier: String?,
    public val contenuAvant: String?,
)

/** Instantané d'une application réussie — porté par l'exécution pendant toute la session. */
public data class ExecChangeSetIa(public val etiquete: String, public val fichiers: List<FichierAvantIa>) {
    public val vide: Boolean get() = fichiers.isEmpty()
}

/** Issue d'une application ou d'une annulation. */
public sealed interface ResultatApplicationIa {
    /** Tout le retenu a été écrit ; [nbFichiers] = fichiers touchés. */
    public data class Succes(public val etiquete: String, public val nbFichiers: Int) : ResultatApplicationIa

    /** Refus ou échec en cours de route (les écritures déjà faites restent annulables). */
    public data class Echec(public val motif: String) : ResultatApplicationIa
}

/**
 * Applique un [ChangeSetIa] au projet via le port [FileSystem] (IA-7b,
 * ADR 0125 « Réversibilité ») :
 *
 * 1. **Préparation** — chaque changement est résolu (arborescence SAF
 *    segment par segment) et confronté à l'état ACTUEL : divergence
 *    d'empreinte SHA-256, fichier créé entre-temps, introuvable ⇒
 *    « conflit », jamais d'écrasement silencieux.
 * 2. **Garde-fou** — l'application pose d'abord une étiquette
 *    « IA · <date> » dans l'historique local ; si l'historique est en
 *    échec, l'application est REFUSÉE sans aucune écriture (ADR 0125 :
 *    chaque application doit rester un point de restauration).
 * 3. **Application** — écritures via le FileSystem (déjà décoré par
 *    l'historique côté hôte), état d'AVANT capturé par fichier pour
 *    l'annulation exacte.
 * 4. **Annulation** — [annuler] rejoue l'instantané en ordre inverse.
 *
 * Contexte d'exécution attendu : méthodes suspendantes (E/S SAF), hors
 * fil principal ; [CancellationException] toujours relancée.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — l'applicateur
 * concentre préparation, application et annulation (les 3 faces du
 * contrat « Réversibilité » d'ADR 0125) ; l'éclater disperserait le
 * garde-fou.
 */
@Suppress("TooManyFunctions")
public class AppliqueurChangeSetIa
@Inject
constructor(
    private val fichiers: FileSystem,
    private val historique: HistoriqueLocal,
    private val horloge: TimeProvider,
) {
    /**
     * Prépare la revue du [changeSet] sous la racine [uriRacine]
     * (`null` = aucun projet ouvert ⇒ revue vide, l'appelant refuse).
     */
    public suspend fun preparer(changeSet: ChangeSetIa, uriRacine: String?): RevueChangeSetIa {
        if (uriRacine == null || changeSet.vide) return RevueChangeSetIa(emptyList())
        val revus =
            changeSet.changements.map { changement ->
                when (changement.type) {
                    TypeChangementIa.CREER -> {
                        preparerCreation(changement, uriRacine)
                    }

                    TypeChangementIa.MODIFIER -> {
                        preparerEcriture(changement, uriRacine, TypeChangementIa.MODIFIER)
                    }

                    TypeChangementIa.SUPPRIMER -> {
                        preparerEcriture(
                            changement,
                            uriRacine,
                            TypeChangementIa.SUPPRIMER,
                        )
                    }
                }
            }
        return RevueChangeSetIa(revus)
    }

    /**
     * Applique les changements DONT le chemin est dans [retenus]
     * (`null` = tous les applicables — la revue n'offre jamais un
     * conflit). Étiquette d'historique d'abord, écritures ensuite ;
     * au premier échec, stop propre avec motif (les écritures déjà
     * faites restent annulables via l'instantané retourné).
     *
     * @return l'instantané d'exécution (annulation) + l'issue.
     */
    @Suppress("ReturnCount")
    // Exemption detekt ciblée (règle 16) : ReturnCount — gardes successives, chaque issue est un retour dédié.
    public suspend fun appliquer(
        changeSet: ChangeSetIa,
        uriRacine: String?,
        retenus: Set<String>? = null,
    ): Pair<ExecChangeSetIa?, ResultatApplicationIa> {
        if (uriRacine == null) {
            return Pair(null, ResultatApplicationIa.Echec("aucun projet ouvert"))
        }
        val revue = preparer(changeSet, uriRacine)
        val cibles =
            revue.applicables.filter { revu -> retenus == null || revu.cheminRelatif in retenus }
        if (cibles.isEmpty()) {
            return Pair(null, ResultatApplicationIa.Echec("aucun fichier applicable dans la sélection"))
        }

        // Garde-fou ADR 0125 : PAS d'étiquette ⇒ PAS d'écriture.
        val etiquete = poserEtiquette() ?: return Pair(null, ResultatApplicationIa.Echec(MOTIF_HISTORIQUE))
        val faits = mutableListOf<FichierAvantIa>()
        for (revu in cibles) {
            val avant =
                when (revu.type) {
                    TypeChangementIa.CREER -> creer(revu)
                    TypeChangementIa.MODIFIER -> ecrire(revu)
                    TypeChangementIa.SUPPRIMER -> supprimer(revu)
                }
            if (avant == null) {
                return Pair(
                    ExecChangeSetIa(etiquete, faits),
                    ResultatApplicationIa.Echec("échec d'écriture : ${revu.cheminRelatif}"),
                )
            }
            faits.add(avant)
        }
        return Pair(ExecChangeSetIa(etiquete, faits), ResultatApplicationIa.Succes(etiquete, faits.size))
    }

    /**
     * « Annuler cette exécution » (ADR 0125) : rejoue l'instantané
     * [execution] en ordre inverse — création supprimée, modification
     * remise à son contenu d'avant, suppression recréée. Une
     * annulation pose elle-même son étiquette (trace honnête).
     */
    @Suppress("ReturnCount")
    // Exemption detekt ciblée (règle 16) : ReturnCount — garde historique, échec fichier par fichier.
    public suspend fun annuler(execution: ExecChangeSetIa): ResultatApplicationIa {
        if (historique.etiqueter("IA · annulation") == null) {
            return ResultatApplicationIa.Echec(MOTIF_HISTORIQUE)
        }
        for (avant in execution.fichiers.asReversed()) {
            val ok =
                when (avant.type) {
                    TypeChangementIa.CREER -> {
                        avant.uri?.let { uri -> fichiers.delete(uri) is jo.codeide.core.model.AppResult.Success }
                            ?: false
                    }

                    TypeChangementIa.MODIFIER -> {
                        avant.uri?.let { uri ->
                            val ecrit = fichiers.writeText(uri, avant.contenuAvant.orEmpty())
                            ecrit is jo.codeide.core.model.AppResult.Success
                        } ?: false
                    }

                    TypeChangementIa.SUPPRIMER -> {
                        recree(avant)
                    }
                }
            if (!ok) {
                return ResultatApplicationIa.Echec("annulation incomplète : ${avant.cheminRelatif}")
            }
        }
        return ResultatApplicationIa.Succes(execution.etiquete, execution.fichiers.size)
    }

    /**
     * Étiquette « IA · <date> » — `null` = historique en échec (garde ADR 0125).
     *
     * Exemption detekt ciblée (règle 16) : SwallowedException — l'incident
     * d'historique est CONVERTI en refus modélisé (garde-fou ADR 0125),
     * jamais remonté en exception vers l'UI.
     */
    @Suppress("SwallowedException")
    private suspend fun poserEtiquette(): String? {
        val instant =
            java.time.Instant
                .ofEpochMilli(horloge.nowMillis())
                .atZone(java.time.ZoneId.systemDefault())
        val nom =
            "IA · " +
                instant.format(
                    java.time.format.DateTimeFormatter
                        .ofPattern("dd/MM HH:mm"),
                )
        return try {
            val entree: EntreeHistorique? = historique.etiqueter(nom)
            if (entree != null) nom else null
        } catch (incident: Exception) {
            // converti en refus modélisé (garde-fou ADR 0125) — jamais d'exception vers l'UI.
            null
        }
    }

    @Suppress("ReturnCount")
    // Exemption detekt ciblée (règle 16) : ReturnCount — chaque étape a son refus dédié.
    private suspend fun creer(revu: FichierRevueIa): FichierAvantIa? {
        var parent = revu.uriParent ?: return null
        // mkdir -p : les segments absents d'une création sont créés (révus à l'écran).
        for (segment in revu.segmentsManquants) {
            val cree = fichiers.createDirectory(parent, segment)
            if (cree !is AppResult.Success) return null
            parent = cree.value
        }
        val fichier =
            fichiers.createFile(parent, revu.nomFichier ?: return null, mimeFichierTexte(revu.cheminRelatif))
        val uri = (fichier as? AppResult.Success)?.value ?: return null
        if (fichiers.writeText(uri, revu.contenuPropose) !is AppResult.Success) return null
        return FichierAvantIa(revu.cheminRelatif, revu.type, uri, revu.uriParent, revu.nomFichier, null)
    }

    @Suppress("ReturnCount")
    // Exemption detekt ciblée (règle 16) : ReturnCount — chaque étape a son refus dédié.
    private suspend fun ecrire(revu: FichierRevueIa): FichierAvantIa? {
        val uri = revu.uri ?: return null
        val lu = fichiers.readText(uri)
        val avant = (lu as? AppResult.Success)?.value ?: return null
        if (fichiers.writeText(uri, revu.contenuPropose) !is AppResult.Success) return null
        return FichierAvantIa(revu.cheminRelatif, revu.type, uri, null, null, avant)
    }

    @Suppress("ReturnCount")
    // Exemption detekt ciblée (règle 16) : ReturnCount — chaque étape a son refus dédié.
    private suspend fun supprimer(revu: FichierRevueIa): FichierAvantIa? {
        val uri = revu.uri ?: return null
        // La recréation exige le contenu : un fichier NON textuel est
        // refusé à l'application (jamais de suppression irrécupérable).
        val lu = fichiers.readText(uri)
        val avant = (lu as? AppResult.Success)?.value ?: return null
        if (fichiers.delete(uri) !is AppResult.Success) return null
        return FichierAvantIa(revu.cheminRelatif, revu.type, uri, revu.uriParent, revu.nomFichier, avant)
    }

    @Suppress("ReturnCount")
    // Exemption detekt ciblée (règle 16) : ReturnCount — chaque élément requis a son refus dédié.
    private suspend fun recree(avant: FichierAvantIa): Boolean {
        val parent = avant.uriParent ?: return false
        val nom = avant.nomFichier ?: return false
        val contenu = avant.contenuAvant ?: return false
        val fichier = fichiers.createFile(parent, nom, mimeFichierTexte(avant.cheminRelatif))
        val uri = (fichier as? AppResult.Success)?.value ?: return false
        return fichiers.writeText(uri, contenu) is AppResult.Success
    }

    /**
     * Exemption detekt ciblée (règle 16) : ReturnCount — chaque issue
     * (chemin, parent, existant, divergent) est un refus dédié, mêmes
     * gardes que le résolveur de chemins.
     */
    @Suppress("ReturnCount")
    private suspend fun preparerEcriture(
        changement: ChangementIa,
        uriRacine: String,
        type: TypeChangementIa,
    ): FichierRevueIa {
        val resolu =
            resous(uriRacine, changement.cheminRelatif) ?: return FichierRevueIa(
                changement.cheminRelatif,
                type,
                changement.contenu,
                changement.raison,
                conflit = true,
                motifConflit = "fichier introuvable sous la racine",
                uri = null,
                segmentsManquants = emptyList(),
                uriParent = null,
                nomFichier = null,
            )
        val actuel = fichiers.readText(resolu.uri).getOrNull()
        if (actuel == null) {
            return conflitNonTextuel(changement, type, resolu.uri)
        }
        val divergent = empreinte(actuel) != changement.empreinteAttendue
        return FichierRevueIa(
            changement.cheminRelatif,
            type,
            changement.contenu,
            changement.raison,
            conflit = divergent,
            motifConflit = if (divergent) "modifié depuis la proposition" else null,
            uri = resolu.uri,
            segmentsManquants = emptyList(),
            uriParent = resolu.uriParent,
            nomFichier = resolu.nomFichier,
        )
    }

    @Suppress("ReturnCount")
    private suspend fun preparerCreation(changement: ChangementIa, uriRacine: String): FichierRevueIa {
        val segments = changement.cheminRelatif.split('/').filter { it.isNotEmpty() }
        val nomFichier = segments.lastOrNull()
        val parents = segments.dropLast(1)
        var courant = uriRacine
        val manquants = mutableListOf<String>()
        for (segment in parents) {
            val enfants = fichiers.list(courant).getOrNull() ?: return conflitRacine(changement)
            val enfant = enfants.firstOrNull { it.name == segment }
            if (enfant == null) {
                manquants.add(segment)
                // Les segments suivants seront créés en cascade — on
                // descend virtuellement (l'URI parent reste le dernier réel).
                continue
            }
            if (!enfant.isDirectory) {
                return FichierRevueIa(
                    changement.cheminRelatif,
                    TypeChangementIa.CREER,
                    changement.contenu,
                    changement.raison,
                    conflit = true,
                    motifConflit = "un fichier porte déjà ce nom de dossier : $segment",
                    uri = null,
                    segmentsManquants = emptyList(),
                    uriParent = null,
                    nomFichier = null,
                )
            }
            courant = enfant.uri
        }
        val existeDeja =
            nomFichier != null &&
                (fichiers.list(courant).getOrNull() ?: emptyList())
                    .any { it.name == nomFichier && !it.isDirectory }
        return FichierRevueIa(
            changement.cheminRelatif,
            TypeChangementIa.CREER,
            changement.contenu,
            changement.raison,
            conflit = existeDeja,
            motifConflit = if (existeDeja) "le fichier existe déjà" else null,
            uri = null,
            segmentsManquants = manquants,
            uriParent = courant,
            nomFichier = nomFichier,
        )
    }

    private fun conflitRacine(changement: ChangementIa): FichierRevueIa = FichierRevueIa(
        changement.cheminRelatif,
        changement.type,
        changement.contenu,
        changement.raison,
        conflit = true,
        motifConflit = "racine illisible (projet fermé ?)",
        uri = null,
        segmentsManquants = emptyList(),
        uriParent = null,
        nomFichier = null,
    )

    private fun conflitNonTextuel(changement: ChangementIa, type: TypeChangementIa, uri: String): FichierRevueIa =
        FichierRevueIa(
            changement.cheminRelatif,
            type,
            changement.contenu,
            changement.raison,
            conflit = true,
            motifConflit = "contenu non textuel — action manuelle requise",
            uri = uri,
            segmentsManquants = emptyList(),
            uriParent = null,
            nomFichier = null,
        )

    /** Résolution d'un chemin EXISTANT : fichier, parent direct et dernier segment. */
    private data class ResoluIa(val uri: String, val uriParent: String, val nomFichier: String)

    /** Résout un chemin EXISTANT, ou `null` (segment absent / racine illisible). */
    @Suppress("ReturnCount")
    private suspend fun resous(uriRacine: String, cheminRelatif: String): ResoluIa? {
        val segments = cheminRelatif.split('/').filter { it.isNotEmpty() }
        var courant = uriRacine
        var parent = uriRacine
        for (segment in segments) {
            val enfants = fichiers.list(courant).getOrNull() ?: return null
            parent = courant
            courant = enfants.firstOrNull { it.name == segment }?.uri ?: return null
        }
        return ResoluIa(courant, parent, segments.lastOrNull() ?: "")
    }

    private companion object {
        /** Motif du garde-fou « historique indisponible » (ADR 0125). */
        const val MOTIF_HISTORIQUE = "historique local indisponible — application refusée (ADR 0125)"

        /** Empreinte SHA-256 (hex minuscule), même algorithme que les outils d'écriture. */
        fun empreinte(contenu: String): String = java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(contenu.toByteArray(Charsets.UTF_8))
            .joinToString("") { octet -> "%02x".format(octet) }
    }
}
