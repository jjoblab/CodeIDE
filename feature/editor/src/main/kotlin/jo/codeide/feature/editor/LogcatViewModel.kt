package jo.codeide.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import jo.codeide.core.domain.ArchiveSessionsJournal
import jo.codeide.core.domain.EtatSessionJournal
import jo.codeide.core.domain.FiltreLogcat
import jo.codeide.core.domain.FinSessionJournal
import jo.codeide.core.domain.LigneJournal
import jo.codeide.core.domain.NiveauJournal
import jo.codeide.core.domain.PontJournauxApplications
import jo.codeide.core.domain.SessionJournal
import jo.codeide.core.domain.SessionJournalArchivee
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * État observable de l'onglet Logcat (mission « Exécuter » R3, spec
 * EXECUTER.md § 4) — TOUT ce que la barre d'outils, les bandeaux et la
 * table ont besoin : sessions, sélection, lignes FILTRÉES, filtre,
 * pause, erreurs de motif.
 *
 * @property sessions sessions du registre (vivantes puis terminées)
 * @property idSelection identifiant de la session affichée (`null` :
 *           une session ARCHIVÉE est affichée, ou rien)
 * @property sessionArchivee session précédée affichée (sélecteur),
 *           `null` sinon — bandeau « Session précédente »
 * @property lignes lignes du tampon d'affichage PASSANT le filtre,
 *           numérotées pour un DiffUtil stable
 * @property filtre filtre courant (niveau, texte, regex)
 * @property enPause `true` : le défilement est figé — la collecte
 *           CONTINUE côté registre (bornée), la reprise rattrape
 * @property erreurMotif le motif regex actif est invalide — signalé
 *           en ligne, jamais en crash
 * @property lignesPerdues pertes annoncées de la session affichée
 * @property fin raison de fin de la session affichée (`null` : vivante)
 * @property archives sessions précédentes (fin bornée) — le sélecteur
 *           de processus les liste après les sessions du registre
 */
data class EtatLogcat(
    val sessions: List<SessionJournal> = emptyList(),
    val idSelection: String? = null,
    val sessionArchivee: SessionJournalArchivee? = null,
    val lignes: List<LigneLogcatNumerotee> = emptyList(),
    val filtre: FiltreLogcat = FiltreLogcat(),
    val enPause: Boolean = false,
    val erreurMotif: Boolean = false,
    val lignesPerdues: Long = 0L,
    val fin: FinSessionJournal? = null,
    val archives: List<SessionJournalArchivee> = emptyList(),
)

/**
 * Une ligne du tampon d'affichage, numérotée de façon STRICTEMENT
 * croissante par session — le numéro est l'identité stable du DiffUtil
 * (le contenu peut se répéter, la position peut être filtrée).
 */
data class LigneLogcatNumerotee(
    val numero: Long,
    val ligne: LigneJournal,
)

/**
 * ViewModel de l'onglet Logcat (mission « Exécuter » R3, ADR 0103) : le
 * vrai Logcat des applications exécutées, SANS adb.
 *
 * Trois rôles :
 *
 * 1. **Tampon d'affichage** (miroir borné du registre) : le filtre
 *    s'applique au tampon, « effacer » ne vide QUE le tampon (les
 *    sessions gardées ne sont pas touchées), la pause FIGE le tampon —
 *    la collecte continue côté registre, la reprise RATTRAPE par le
 *    delta incrémental ([PontJournauxApplications.lignesDepuis]) ;
 *
 * 2. **Sélection de session** : une par (paquet, pid, processus) ; une
 *    session VIVANTE nouvellement née prend la main si rien n'est
 *    affiché ou si l'affichage courant est une ARCHIVE (l'app vient
 *    d'être relancée) — jamais si l'utilisateur consulte une session
 *    du registre (son choix prime, même terminée) ;
 *
 * 3. **Archivage des fins** : chaque session terminée part à
 *    [ArchiveSessionsJournal] (dernière ligne + raison, borné) — les
 *    sessions précédentes restent consultables entre deux démarrages
 *    de CodeIDE.
 *
 * **Zéro course de rattrapage** (leçon R3) : l'état des sessions est
 * réémis À CHAQUE lot — c'est le signal de rattrapage ; le delta lu
 * sous le verrou du registre est exact, contrairement à un abonnement
 * au flux de lots (dont l'enregistrement et la lecture d'un instantané
 * ne sont pas atomiques : des lignes y tomberaient dans la fenêtre).
 *
 * Exemption detekt ciblée (règle 16, même forme que
 * PanneauToolingController v0.80.1) : `TooManyFunctions` — UNE fonction
 * par AFFORDANCE de la barre d'outils (spec § 4.1 : sélecteur de
 * processus, filtre texte, niveau, pause, effacer) et une par SECTION du
 * cycle (sélection, rattrapage, publication) ; les regrouper masquerait
 * la correspondance directe avec la spécification.
 */
@Suppress("TooManyFunctions")
@HiltViewModel
class LogcatViewModel
    @Inject
    constructor(
        private val pont: PontJournauxApplications,
        private val archive: ArchiveSessionsJournal,
    ) : ViewModel() {
        /** État observable — le fragment ne fait que rendre. */
        private val etatInterne = MutableStateFlow(EtatLogcat())

        val etat: StateFlow<EtatLogcat> = etatInterne.asStateFlow()

        /** Tampon d'affichage non filtré (miroir borné du registre). */
        private val tampon = ArrayDeque<LigneLogcatNumerotee>()

        /** Tampon FILTRÉ synchronisé — ne re-ballaye QUE sur action
         *  utilisateur (changement de filtre), jamais par lot. */
        private val lignesFiltrees = ArrayDeque<LigneLogcatNumerotee>()

        /** Compteur de numéros (identité DiffUtil, jamais remis à zéro
         *  tant que la session affichée ne change pas). */
        private var prochainNumero = 0L

        /** Position de rattrapage (compteur de lignes du registre). */
        private var positionRendue = 0

        /** Identifiants déjà archivés par CE ViewModel (anti-doublon). */
        private val dejaArchivees = mutableSetOf<String>()

        /** Identifiants des vivantes connues à la passe précédente —
         *  seule une vivante NOUVELLEMENT née déloge une archive. */
        private val vivantesConnues = mutableSetOf<String>()

        /** Archives chargées au démarrage (sélecteur de processus). */
        private var archives = listOf<SessionJournalArchivee>()

        /** Archive affichée (miroir interne, pour la re-sélection). */
        private var sessionArchiveeInterne: SessionJournalArchivee? = null

        init {
            // Sessions du registre : publication, archivage des fins,
            // autosélection, rattrapage du tampon.
            viewModelScope.launch {
                pont.sessions.collect { sessions -> surSessions(sessions) }
            }
            // Archives (sélecteur) : au démarrage, la plus récente
            // s'affiche si rien de vivant n'occupe l'onglet.
            viewModelScope.launch {
                archives = archive.charger()
                etatInterne.update { it.copy(archives = archives) }
                if (etatInterne.value.idSelection == null &&
                    sessionArchiveeInterne == null &&
                    etatInterne.value.sessions.none { it.etat == EtatSessionJournal.VIVANTE }
                ) {
                    archives.firstOrNull()?.let { afficherArchivee(it) }
                }
            }
        }

        /**
         * Sélectionne une session du registre (sélecteur de processus) —
         * le tampon repart du delta complet (tout ce que le registre
         * retient), les lots suivants s'y ajoutent.
         */
        fun selectionnerSession(id: String) {
            val session = etatInterne.value.sessions.firstOrNull { it.id == id } ?: return
            sessionArchiveeInterne = null
            tampon.clear()
            lignesFiltrees.clear()
            prochainNumero = 0L
            positionRendue = 0
            etatInterne.update { etat ->
                etat.copy(
                    idSelection = id,
                    sessionArchivee = null,
                    enPause = false,
                    lignesPerdues = session.lignesPerdues,
                    fin = session.fin,
                )
            }
            rattraper()
        }

        /** Sélectionne une session archivée (« session précédente »). */
        fun selectionnerArchivee(id: String) {
            val archivee = archives.firstOrNull { it.session.id == id } ?: return
            afficherArchivee(archivee)
        }

        /**
         * Filtre texte (sous-chaîne, ou regex si l'option est active) —
         * le fragment RENVOIE le couple complet à chaque frappe ET à
         * chaque bascule de l'option (l'état du champ vit dans la vue).
         */
        fun definirFiltreTexte(
            texte: String,
            regex: Boolean,
        ) {
            majFiltre { it.copy(texte = texte, regex = regex) }
        }

        /** Niveau minimal affiché (masque les niveaux inférieurs). */
        fun definirNiveauMinimal(niveau: NiveauJournal) {
            majFiltre { it.copy(niveauMinimal = niveau) }
        }

        /**
         * Bascule la pause du défilement : figée, les deltas ne touchent
         * plus le tampon (la collecte continue, bornée, côté registre) ;
         * reprise = RATTRAPAGE par le delta (jamais de trou muet).
         */
        fun basculerPause() {
            val enPause = !etatInterne.value.enPause
            etatInterne.update { it.copy(enPause = enPause) }
            if (!enPause) {
                // Reprise : rattrapage — le registre a collecté pendant
                // la pause, le delta ramène tout ce qui est retenu.
                rattraper()
            }
        }

        /**
         * Efface le tampon AFFICHÉ — les sessions gardées (registre,
         * archives) ne sont pas touchées, les lignes NOUVELLES
         * réapparaissent (la position de rattrapage est conservée).
         */
        fun effacer() {
            tampon.clear()
            lignesFiltrees.clear()
            republier()
        }

        // ------------------------------------------------------------------
        // Internes.
        // ------------------------------------------------------------------

        /** Réaction à chaque liste de sessions du registre. */
        private fun surSessions(sessions: List<SessionJournal>) {
            // 1. Publie la liste (sélecteur, bandeaux de fin et de
            //    pertes) AVANT toute autosélection : celle-ci doit voir
            //    les NOUVELLES sessions (selectionnerSession lit l'état).
            etatInterne.update { etat ->
                val selection = sessions.firstOrNull { it.id == etat.idSelection }
                etat.copy(
                    sessions = sessions,
                    lignesPerdues = selection?.lignesPerdues ?: 0L,
                    fin = selection?.fin,
                )
            }

            // 2. Archive les sessions VENUES de se terminer (la dernière
            //    ligne est lue dans l'instantané, avant tout nouveau lot).
            sessions
                .filter { it.etat == EtatSessionJournal.TERMINEE && it.id !in dejaArchivees }
                .forEach { session ->
                    dejaArchivees += session.id
                    viewModelScope.launch {
                        archive.archiver(session, pont.instantane(session.id).lastOrNull())
                        archives = archive.charger()
                        etatInterne.update { etat -> etat.copy(archives = archives) }
                    }
                }

            // 3. Autosélection : une vivante NOUVELLEMENT née prend la
            //    main si rien n'est affiché ou si l'affichage courant
            //    est une ARCHIVE (l'app vient d'être relancée) — jamais
            //    si l'utilisateur consulte une session du registre (son
            //    choix prime, même terminée).
            val vivantes = sessions.filter { it.etat == EtatSessionJournal.VIVANTE }
            val naissance = vivantes.any { it.id !in vivantesConnues }
            vivantesConnues.clear()
            vivantesConnues.addAll(vivantes.map { it.id })
            when {
                vivantes.isNotEmpty() && etatInterne.value.idSelection == null &&
                    sessionArchiveeInterne == null -> {
                    selectionnerSession(vivantes.last().id)
                }

                naissance && sessionArchiveeInterne != null -> {
                    selectionnerSession(vivantes.last().id)
                }

                vivantes.isEmpty() && etatInterne.value.idSelection == null &&
                    sessionArchiveeInterne == null -> {
                    // Rien de vivant : la plus récente archive s'affiche
                    // (consultable — jamais du vide muet).
                    archives.firstOrNull()?.let { afficherArchivee(it) }
                }
            }

            // 4. Rattrapage du tampon affiché (la réémission des sessions
            //    est le signal : chaque lot y repasse).
            rattraper()
        }

        /** Affiche une session archivée : bandeau gris + dernière ligne. */
        private fun afficherArchivee(archivee: SessionJournalArchivee) {
            sessionArchiveeInterne = archivee
            tampon.clear()
            lignesFiltrees.clear()
            prochainNumero = 0L
            positionRendue = 0
            val derniere = archivee.derniereLigne
            if (derniere != null) {
                val entree = LigneLogcatNumerotee(prochainNumero++, derniere)
                tampon.add(entree)
                lignesFiltrees.add(entree)
            }
            etatInterne.update { etat ->
                etat.copy(
                    idSelection = null,
                    sessionArchivee = archivee,
                    enPause = false,
                    lignesPerdues = archivee.session.lignesPerdues,
                    fin = archivee.session.fin,
                )
            }
            republier()
        }

        /**
         * Rattrapage incrémental : lit le delta des lignes reçues
         * STRICTEMENT après [positionRendue], l'ajoute au tampon (borné)
         * en filtrant AU PASSAGE (le tampon filtré suit, sans re-balayage
         * — le filtre ne se rallique que sur action utilisateur).
         */
        private fun rattraper() {
            val id = etatInterne.value.idSelection ?: return
            if (etatInterne.value.enPause) return
            val filtre = etatInterne.value.filtre
            val delta = pont.lignesDepuis(id, positionRendue)
            for (ligne in delta.lignes) {
                val entree = LigneLogcatNumerotee(prochainNumero++, ligne)
                tampon.add(entree)
                if (filtre.accepte(ligne)) {
                    lignesFiltrees.add(entree)
                }
                // Borne synchronisée : la ligne évincée du tampon quitte
                // AUSSI le tampon filtré si elle y était (identité d'objet).
                if (tampon.size > CAPACITE_TAMPON) {
                    val evincee = tampon.removeFirst()
                    if (lignesFiltrees.firstOrNull() === evincee) {
                        lignesFiltrees.removeFirst()
                    }
                }
            }
            positionRendue = delta.apres
            if (delta.lignes.isNotEmpty()) {
                republier()
            }
        }

        /**
         * Applique une transformation au filtre : l'état change, PUIS le
         * tampon filtré se RECALCULE en entier (action utilisateur —
         * contrairement aux lots, ce re-ballayage n'arrive pas en rafale).
         */
        private fun majFiltre(transformation: (FiltreLogcat) -> FiltreLogcat) {
            val filtre = transformation(etatInterne.value.filtre)
            etatInterne.update { it.copy(filtre = filtre, erreurMotif = filtre.erreurMotif) }
            lignesFiltrees.clear()
            tampon.forEach { entree ->
                if (filtre.accepte(entree.ligne)) lignesFiltrees.add(entree)
            }
            republier()
        }

        /** Publie les lignes filtrées (l'adaptateur differa par numéro). */
        private fun republier() {
            etatInterne.update { it.copy(lignes = lignesFiltrees.toList()) }
        }

        private companion object {
            /** Lignes du tampon d'affichage (miroir du registre). */
            const val CAPACITE_TAMPON = 5000
        }
    }
