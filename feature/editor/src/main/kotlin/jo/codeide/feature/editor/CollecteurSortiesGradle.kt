package jo.codeide.feature.editor

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.EtapeSyncTooling
import jo.codeide.core.domain.EtatBuild
import jo.codeide.core.domain.EtatExecutionGradle
import jo.codeide.core.domain.EtatTacheBuild
import jo.codeide.core.domain.EvenementSyncFlux
import jo.codeide.core.domain.ExecutionSortie
import jo.codeide.core.domain.GravitePosition
import jo.codeide.core.domain.LigneSortie
import jo.codeide.core.domain.LigneSortieBuild
import jo.codeide.core.domain.ParseurFluxGradle
import jo.codeide.core.domain.ReferenceJournauxServeur
import jo.codeide.core.domain.ResultatSynchronisation
import jo.codeide.core.domain.SourceSortie
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.domain.TelechargementBuild
import jo.codeide.core.domain.TimeProvider
import jo.codeide.core.domain.TypeExecution
import jo.codeide.core.domain.VerdictExecution
import jo.codeide.core.domain.versLigneSortie
import jo.codeide.core.domain.versNiveauSortie
import jo.codeide.core.model.AppResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le COLLECTEUR du flux réel Gradle vers le Centre de sorties (lot L3,
 * rapport L2 § 5.5 : « le L3 publie le vrai flux tooling par re-lien du
 * même port — le lecteur ne change pas ») — branché SUR LA POMPE
 * ([PompeBuildTooling], le seul entonnoir process-wide qui voit tout :
 * lignes, états, ÉVÉNEMENTS de tâches, téléchargements, flux de sync).
 *
 * **CONTRAT DE SCOPE — @Singleton OBLIGATOIRE** (retour d'appareil du
 * 2026-11-11 : « la console n'affiche pas les sorties du gradle tooling
 * en direct ») : la classe est injectée DEUX FOIS — la pompe qui la
 * NOURRIT ([PompeBuildTooling]) et le flux que le lecteur LIT
 * ([FluxSortiesCentre]). Sans scope, Hilt fabrique une instance PAR
 * POINT D'INJECTION : la pompe arme et nourrit la sienne
 * (`demarrerBuild`, `surLigneBuild`) pendant que le lecteur écoute une
 * AUTRE instance jamais alimentée — cliché vide, aucun lot, console
 * muette pour TOUT le câble tooling (ni direct, ni fin de build). Le
 * banc n'a pas pu le voir : il passe par la source factice ; les tests
 * assemblent le collecteur à la main. Le scope `@Singleton` est LE
 * câble : une seule instance process-wide, partagée par les deux bouts.
 *
 * Trois responsabilités, dans l'ordre du câble :
 *
 * 1. **adapter + enrichir** chaque ligne vers le modèle unique
 *    ([LigneSortie]) avec le couplage ÉVÉNEMENT + TEXTE (arbitrage Q6) :
 *    le champ `tache` est posé par [ParseurFluxGradle] — frontière posée
 *    par l'événement `EtatTacheBuild` EN_COURS, annoncée par le texte
 *    `> Task :…` — et les positions cliquables sont extraites au passage
 *    ; une ligne structurelle (verdict, résumé, bloc d'échec) ne PORTE
 *    jamais de tâche (le regroupement les place lui-même) ;
 * 2. **publier par lots** (héritage ADR 0074 : jamais une ligne à la fois
 *    sur le chemin chaud) et tenir un cliché borné de la session courante
 *    pour qu'un lecteur qui s'attache en PLEIN build voie l'existant ;
 * 3. **porter l'état d'exécution** ([EtatExecutionGradle] — verdict,
 *    compteurs, statuts/durées par tâche, phases de sync) et conduire
 *    l'[HistorienExecutions] : en-tête posé au départ, lots persistés
 *    (l'expurgation se fait au registre), conclusion sur le FAIT du
 *    câble.
 *
 * La console actuelle reste branchée EN PARALLÈLE ([GradleService],
 * inchangé — migration par parité, prompt § 7 L3) : le collecteur ne
 * détourne rien, il OBSERVE le même câble.
 *
 * @param horloge horloge injectée (instants de début/fin d'exécution, et
 *        la cadence du lotissement : seules les DIFFÉRENCES comptent).
 * @param historien le conducteur du registre (ADR 0161).
 * @param journal la collecte est journalisée, jamais muette (héritage
 *        ADR 0080 : une mort silencieuse est une panne invisible).
 */
@Suppress("TooManyFunctions")
@Singleton
public class CollecteurSortiesGradle
@Inject
constructor(
    private val horloge: TimeProvider,
    private val historien: HistorienExecutions,
    private val journal: AppLogger,
) {
    private val parseur = ParseurFluxGradle()
    private val lot = ArrayList<LigneSortie>(TAILLE_LOT)
    private val cliche = ArrayList<LigneSortie>(FENETRE_REJEU)
    private var racineProjet: String? = null
    private var tachesDemandees: List<String> = emptyList()
    private var idExecution: String? = null
    private var typeExecution: TypeExecution? = null
    private var debutExecutionMs: Long = 0
    private var totalLignes: Long = 0
    private var telechargementsCompteur = 0

    /** L'instant du DERNIER lot envoyé (horloge du câble) — un lot part
     *  aussi dès que la période est écoulée : un build lent reste VISIBLE
     *  au fil de l'eau (parité de latence console), un build bavard ne
     *  paie qu'une émission par [TAILLE_LOT] lignes. */
    private var dernierLotMs: Long = 0

    /** Les lots adaptés — le flux RÉEL du Centre de sorties. */
    private val _lots = MutableSharedFlow<List<LigneSortie>>(
        replay = 0,
        extraBufferCapacity = CAPACITE_LOTS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    public val lots: SharedFlow<List<LigneSortie>> = _lots.asSharedFlow()

    /** L'état d'exécution courant (résumé, regroupement, registre). */
    private val _etatExecution = MutableStateFlow<EtatExecutionGradle?>(null)
    public val etatExecution: StateFlow<EtatExecutionGradle?> = _etatExecution.asStateFlow()

    /**
     * Le cliché borné de la session courante (même borne que le rejeu de
     * la console) — le premier lot d'un lecteur qui s'attache en pleine
     * exécution.
     */
    public fun clicheSession(): List<LigneSortie> = synchronized(cliche) { ArrayList(cliche) }

    /**
     * Démarrage d'un build suivi — l'état repart vierge, le parseur se
     * réinitialise, l'en-tête du registre est posé (verdict et durée
     * encore inconnus — l'exécution qui meurt reste listée).
     */
    public fun demarrerBuild(buildId: String, taches: List<String>) {
        vider()
        demarrerExecution(buildId, TypeExecution.BUILD, taches)
    }

    /**
     * Démarrage d'une sync annoncée PAR LE SERVEUR — la racine du projet
     * du câble devient la racine d'expurgation (les builds suivants en
     * héritent ; un build lancé AVANT toute sync n'a pas de racine
     * connue : l'expurgateur masque alors tout chemin absolu).
     */
    public fun demarrerSync(projectDir: String?) {
        vider()
        if (!projectDir.isNullOrBlank()) racineProjet = projectDir
        demarrerExecution("sync-" + UUID.randomUUID(), TypeExecution.SYNC, emptyList())
    }

    /** Une ligne du build suivi : adaptation enrichie + accumulation. */
    public fun surLigneBuild(ligne: LigneSortieBuild) {
        if (ligne.buildId != idExecution) return
        val analyse = parseur.traiterLigne(ligne.ligne, ligne.horodatageMs)
        ingere(
            LigneSortie(
                source = SourceSortie.BUILD,
                message = ligne.ligne,
                horodatageMs = ligne.horodatageMs,
                idExecution = ligne.buildId,
                niveau = ligne.flux.versNiveauSortie(),
                // Une ligne structurelle ne PORTE pas de tâche : le
                // regroupement la place lui-même (verdict hors groupe,
                // bloc d'échec épinglé — arbitrage Q6).
                tache = analyse.tacheCourante
                    .takeUnless { analyse.verdict != null || analyse.resume != null || analyse.dansBlocEchec },
                positionsSource = analyse.positions,
            ),
        )
    }

    /** L'état d'un build : compteurs de la synthèse, verdict terminal —
     *  la conclusion écrit l'en-tête final du registre. */
    public fun surEtatBuild(etat: EtatBuild) {
        if (etat.buildId != idExecution) return
        _etatExecution.update { courant -> (courant ?: EtatExecutionGradle()).surEtatBuild(etat) }
        if (etat.statut != StatutBuild.EN_COURS) {
            vider()
            conclure()
        }
    }

    /** L'événement d'une tâche : la frontière ÉVÉNEMENT fait foi (Q6),
     *  la durée MESURÉE par l'orchestrateur s'enregistre à la conclusion. */
    public fun surTache(evenement: EtatTacheBuild) {
        if (evenement.buildId != idExecution) return
        parseur.surTache(evenement)
        _etatExecution.update { courant -> (courant ?: EtatExecutionGradle()).surTache(evenement) }
    }

    /** Un téléchargement : UNE ligne par artefact terminé (base de parité
     *  console v0.46.0 — l'ouverture en place attend le transport réel,
     *  dégradation honnête de la spec SORTIES § 4.7). */
    public fun surTelechargement(telechargement: TelechargementBuild) {
        val compteur = telechargement.compteur ?: return
        val artefactNouveau = telechargement.buildId == idExecution && compteur > telechargementsCompteur
        if (!artefactNouveau) return
        telechargementsCompteur = compteur
        ingere(
            telechargement.versLigneSortie(source = SourceSortie.BUILD, idExecution = telechargement.buildId)
                .copy(horodatageMs = horloge.nowMillis()),
        )
    }

    /** Le flux de sync ORDONNÉ (v0.48.0, ADR 0079) : départ, lignes,
     *  étapes, TERMINAL — la conclusion arrive après tout ce qu'elle
     *  conclut (l'ordre du câble est respecté). */
    public fun surEvenementSync(evenement: EvenementSyncFlux) {
        when (evenement) {
            is EvenementSyncFlux.Debut -> demarrerSync(evenement.projectDir)
            is EvenementSyncFlux.Ligne -> surLigneSync(evenement.sortie)
            is EvenementSyncFlux.Etape -> surEtapeSync(evenement.etape)
            is EvenementSyncFlux.Terminal -> surTerminalSync(evenement.resultat)
        }
    }

    /** Conclut l'exécution courante dans le registre (le FAIT du câble). */
    public fun conclure() {
        val id = idExecution ?: return
        historien.conclure(entete(id, finMs = horloge.nowMillis()))
        idExecution = null
        typeExecution = null
        telechargementsCompteur = 0
    }

    /** Une ligne de sync : adaptation (le canal Sync a SA famille de
     *  source, ADR 0079). */
    private fun surLigneSync(sortie: jo.codeide.core.domain.LigneSortieSync) {
        ingere(sortie.versLigneSortie(idExecution = idExecution))
    }

    /** Une étape de sync : l'état porte la phase (résumé, consultation) —
     *  le lot se vide pour garder l'ordre ligne/état du câble. */
    private fun surEtapeSync(etape: EtapeSyncTooling) {
        _etatExecution.update { courant -> (courant ?: EtatExecutionGradle()).surEtapeSync(etape) }
        vider()
    }

    /** Le terminal de sync : verdict + durée du FAIT du serveur, puis
     *  conclusion du registre. */
    private fun surTerminalSync(resultat: AppResult<ResultatSynchronisation>) {
        val reussie = (resultat as? AppResult.Success)?.value?.reussie
        val duree = (resultat as? AppResult.Success)?.value?.dureeMs
        _etatExecution.update { courant ->
            (courant ?: EtatExecutionGradle()).copy(
                verdict = verdictSync(reussie) ?: courant?.verdict,
                dureeMs = duree ?: courant?.dureeMs,
            )
        }
        vider()
        conclure()
    }

    /** Démarrage commun (build ou sync) : état vierge, parseur neuf,
     *  en-tête du registre posé. */
    private fun demarrerExecution(id: String, type: TypeExecution, taches: List<String>) {
        idExecution = id
        typeExecution = type
        tachesDemandees = taches
        debutExecutionMs = horloge.nowMillis()
        // La fenêtre de lotissement ouvre ICI : la première ligne d'un
        // build lent se montre dans une période (parité de latence
        // console — S8 du banc jugera sur le vrai transport).
        dernierLotMs = debutExecutionMs
        totalLignes = 0
        telechargementsCompteur = 0
        parseur.reinitialiser()
        _etatExecution.value = EtatExecutionGradle()
        historien.demarrer(entete(id, finMs = null))
        journal.d(TAG) { "collecte du flux ${type.name} ouverte ($id)" }
    }

    /** L'en-tête d'exécution courant (registre). */
    private fun entete(id: String, finMs: Long?): ExecutionSortie = ExecutionSortie(
        id = id,
        type = typeExecution ?: TypeExecution.BUILD,
        projet = racineProjet,
        tachesDemandees = tachesDemandees,
        debutMs = debutExecutionMs,
        finMs = finMs,
        etat = _etatExecution.value,
        journauxServeur = ReferenceJournauxServeur(debutMs = debutExecutionMs, finMs = finMs),
        totalLignes = totalLignes,
    )

    /** Ingestion commune : gravité comptée À L'INGESTION (les positions
     *  du câble — les compteurs d'événements font foi), lotissement,
     *  cliché borné. */
    private fun ingere(ligne: LigneSortie) {
        val erreur = ligne.positionsSource.any { it.gravite == GravitePosition.ERREUR }
        val avert = !erreur && ligne.positionsSource.any { it.gravite == GravitePosition.AVERTISSEMENT }
        if (erreur || avert) {
            _etatExecution.update { courant -> (courant ?: EtatExecutionGradle()).compterGravite(erreur = erreur) }
        }
        totalLignes++
        synchronized(lot) {
            lot.add(ligne)
            val maintenant = horloge.nowMillis()
            if (lot.size >= TAILLE_LOT || (dernierLotMs > 0 && maintenant - dernierLotMs >= PERIODE_LOT_MS)) {
                // envoyerLot pose dernierLotMs (l'horloge fait foi).
                envoyerLot()
            }
        }
    }

    /** Le verdict d'une sync depuis le FAIT du serveur. */
    private fun verdictSync(reussie: Boolean?): VerdictExecution? = when (reussie) {
        true -> VerdictExecution.REUSSI
        false -> VerdictExecution.ECHOUE
        null -> null
    }

    /** Vide le lot courant (frontière d'exécution, structure, étape). */
    private fun vider() {
        synchronized(lot) {
            envoyerLot()
        }
    }

    /** Envoie le lot courant sur le flux ET au registre (verrou lot
     *  tenu) — une écriture PAR LOT, jamais par ligne. */
    private fun envoyerLot() {
        if (lot.isEmpty()) return
        val envoye = ArrayList(lot)
        lot.clear()
        dernierLotMs = horloge.nowMillis()
        // Le cliché suit la PUBLICATION (jamais le lot en attente) : un
        // lecteur qui s'attache reçoit cliché + lots futurs, jamais deux
        // fois la même ligne (critère § 10.1.1 — attrapé par les tests).
        synchronized(cliche) {
            cliche.addAll(envoye)
            if (cliche.size > FENETRE_REJEU) {
                cliche.subList(0, cliche.size - FENETRE_REJEU).clear()
            }
        }
        _lots.tryEmit(envoye)
        val id = idExecution ?: return
        historien.lignes(entete(id, finMs = null), envoye)
    }

    private companion object {
        /** Taille d'un lot publié (héritage ADR 0074 : append par lot). */
        const val TAILLE_LOT = 256

        /** Période d'un lot au fil de l'eau (ms, horloge du câble) — la
         *  latence d'affichage ne dépasse pas une période (parité console
         *  : la console émet chaque ligne ; S8 du banc jugera la
         *  latence sur le VRAI transport, ADR 0152). */
        const val PERIODE_LOT_MS = 50L

        /** Capacité du tampon d'émission (rafales absorbées, DROP_OLDEST
         *  — un lecteur lent ne bloque JAMAIS la pompe, ADR 0057). */
        const val CAPACITE_LOTS = 32

        /** Le cliché de session (parité du rejeu console). */
        const val FENETRE_REJEU = 2_000

        /** Étiquette des pans de collecte. */
        const val TAG = "CollecteSorties"
    }
}
