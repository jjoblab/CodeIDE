package jo.codeide.feature.preview

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.datastore.SettingsDataStore
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.RegistreSourcesApercu
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.SourceApercu
import jo.codeide.core.model.AppSettings
import jo.codeide.preview.xml.CalquesApercu
import jo.codeide.preview.xml.CatalogueAttributs
import jo.codeide.preview.xml.ClassificateurPreview
import jo.codeide.preview.xml.EditionAttributsXml
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.ExtractionRessource
import jo.codeide.preview.xml.InsertionXml
import jo.codeide.preview.xml.MoteurPreviewXml
import jo.codeide.preview.xml.PlacementPopup
import jo.codeide.preview.xml.RenommageIdentifiant
import jo.codeide.preview.xml.RequeteRendu
import jo.codeide.preview.xml.ZonesDepot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * ViewModel de l'écran d'aperçu XML (PE6-0, maquette
 * `docs/mockups/apercu-xml.html` § « Activité dédiée ») : le MOTEUR et
 * son dernier rendu vivent ICI (piège « rotation » de la maquette —
 * chaque rotation ne relance PAS une inflation LayoutLib), la session
 * d'édition reste celle du `EditorViewModel` de `:feature:editor` —
 * consommée par le port [SourceApercu] du registre singleton.
 *
 * Pièges traités (maquette § « Pièges à traiter ») :
 * - **mort du processus** : le registre est VIDE au retour (Android ne
 *   recrée que l'activité du dessus) → [finir] passe à `true`, l'écran
 *   se termine et relance l'éditeur — jamais de panneau vide ;
 * - **rotation** : moteur + état ici, l'activité se rebranche ;
 * - **changement de fichier** : le titre devient un sélecteur des
 *   layouts ouverts — [sourcesPreviewables] (registre) + [ouvrirFichier].
 *
 * Le sens inverse de la sélection (éditeur → preview) DISPARAÎT avec
 * l'overlay : l'éditeur n'est plus visible — la FEUILLE inférieure
 * remplace (PE6d : arbre, attributs, problèmes, source).
 *
 * PE6d — SÉLECTION PARTAGÉE : la vue et la feuille consomment la MÊME
 * sélection ([indiceSelectionne]) ; [selectionner] pose le SPAN dans
 * la session (l'éditeur le retrouve à son retour) et met à jour l'état.
 * PE7b — DÉPÔT de la palette : [appliquerDepot] cible le conteneur
 * ([ZonesDepot.cibleSous]), façonne l'extrait (identifiant libre,
 * contraintes + marges en dp du point de dépôt pour un parent
 * ConstraintLayout) et compose l'édition ([InsertionXml]) — UNE écriture
 * dans la session, le rendu repart par le chemin normal (mutation →
 * écouteur → moteur).
 * PE7c — PLACEMENT PAR POPUP : [appliquerPlacement] reprend le MÊME
 * façonnage pour une cible EXPLICITE ([PlacementPopup.cible] — Avant,
 * Dedans, Après) et enregistre le widget dans les RÉCENTS de la palette ;
 * les réglages de la bulle et du mode se PERSISTENT dans
 * `SettingsDataStore` (maquette : « son mode et sa position sont
 * enregistrés dans SettingsDataStore »).
 * PE7d — ÉDITION : chaque action ([definirAttributSelection],
 * [ecrireCoteSelection], [ecrireDimensionSelection], [dupliquerSelection],
 * [supprimerSelection]) compose UNE édition de texte ([EditionAttributsXml])
 * passée à [SourceApercu.remplacer] — « une seule entrée dans l'historique
 * de l'éditeur » — puis rend immédiatement ([MoteurPreviewXml.soumettreImmediat]).
 *
 * Exemptions detekt ciblées (règle 16) : TooManyFunctions — ViewModel de
 * COUTURE (brancher la source, suivre le projet, sélecteur de fichier,
 * réglages du moteur, dépôt/placement, éditions PE7d, relance éditeur :
 * chaque face a SA méthode, précédent CoordonnateurLsp) ; ReturnCount de
 * [resoudreLabelActivite] et des actions d'édition — chaque garde est
 * une issue DISTINCTE du refus (X4).
 */
@Suppress("TooManyFunctions", "ReturnCount")
@HiltViewModel
public class PreviewViewModel
    // Exemption detekt ciblée (règle 16) : LongParameterList — les 8
    // dépendances HILT du ViewModel (PE6e-f ajoute SettingsDataStore pour
    // les réglages persistés bulle/mode/PE6e-f) ; les regrouper masquerait
    // l'injection.
    @Suppress("LongParameterList")
    @Inject
    constructor(
        @param:ApplicationContext private val contexte: Context,
        private val dispatchers: DispatcherProvider,
        private val journal: AppLogger,
        private val registre: RegistreSourcesApercu,
        private val resolveurChemin: ResoudreRepertoireProjet,
        private val reglagesDataStore: SettingsDataStore,
        sauvegarde: SavedStateHandle,
    ) : ViewModel() {
        /** Identifiant du projet (piège « mort du processus » : relance de l'éditeur). */
        public val projectId: String = sauvegarde.get<String>(CLE_PROJET) ?: ""

        /** URI du document demandé (extra de [jo.codeide.core.ui.AppNavigator.openPreview]). */
        public val uriInitial: String = sauvegarde.get<String>(CLE_URI) ?: ""

        /** Le moteur de rendu — vit HORS de l'activité (rotation sûre). */
        val moteur =
            MoteurPreviewXml(
                contexte = contexte,
                dispatchers = dispatchers,
                journal = journal,
                scope = viewModelScope,
            )

        /** La source branchée (null = aucune — panneau sans session). */
        public var source: SourceApercu? = null
            private set

        /** Effet « se terminer et relancer l'éditeur » (piège mort du processus). */
        private val finirInterne = MutableStateFlow(false)

        /** L'écran doit se terminer (source disparue) — collecté une fois par l'activité. */
        public val finir: StateFlow<Boolean> = finirInterne.asStateFlow()

        /** La sélection PARTAGÉE (vue ↔ feuille) : l'indice dans l'arbre rendu, -1 aucune. */
        private val selectionInterne = MutableStateFlow(-1)

        /** La sélection partagée (PE6d — la feuille et la vue s'y alignent). */
        public val indiceSelectionne: StateFlow<Int> = selectionInterne.asStateFlow()

        /** Le DERNIER rendu valide (sélection, dépôt : vues + spans). */
        public var dernierRendu: EtatPreviewXml.RenduValide? = null
            private set

        /** La classe du conteneur visé par le DERNIER dépôt RÉUSSI (libellé du toast). */
        public var dernierParentDepot: String? = null
            private set

        /** URI du fichier affiché (titre + sélecteur). */
        public var uriCourante: String? = null
            private set

        /** Écouteur de mutation branché à la source courante (détaché au changement). */
        private var ecouteur: SourceApercu.EcouteurMutation? = null

        /** Job du suivi projet (résolution FUSE suspendante — annulé au changement). */
        private var suiviProjet: kotlinx.coroutines.Job? = null

        // ── PE10c : l'état RÉACTIF de l'historique d'édition ─────────────
        // ⚠️ DÉCLARÉ AVANT le bloc init — CONTRAT d'ordre d'initialisation :
        // `init { brancher(uriInitial) }` s'exécute AU CONSTRUCTEUR et
        // écrit cet état via [delierSource]/[majHistorique] ; une
        // déclaration placée APRÈS l'init valait null au premier appel
        // (crash 0.134.39, rapport 9774dd61 : NPE sur setValue pendant
        // <init> — l'aperçu ne s'ouvrait plus du tout).

        /**
         * L'état des piles d'annulation de la source branchée (PE10c —
         * miroir `updateEditBar` de la maquette : les icônes
         * annuler/rétablir se mettent à jour avec l'historique).
         */
        public data class HistoriqueEdition(
            val peutAnnuler: Boolean,
            val peutRetablir: Boolean,
        )

        /** L'état interne de l'historique (relu à chaque mutation). */
        private val historiqueInterne = MutableStateFlow(HistoriqueEdition(false, false))

        /** L'historique observables (la barre d'édition s'y aligne). */
        public val historiqueEdition: StateFlow<HistoriqueEdition> = historiqueInterne.asStateFlow()

        init {
            brancher(uriInitial)
            viewModelScope.launch {
                moteur.etat.collect { etat ->
                    if (etat is EtatPreviewXml.RenduValide) dernierRendu = etat
                }
            }
            // PE7c : les réglages persistés de la bulle et du mode de la
            // palette (DataStore tolérant — la lecture retombe sur les défauts).
            viewModelScope.launch {
                reglagesDataStore.observe().collect { reglage ->
                    reglagesApercuInterne.value =
                        ReglagesApercu(
                            bulleFixe = reglage.apercuBulleFixe,
                            bulleADroite = reglage.apercuBulleADroite,
                            bulleY = reglage.apercuBulleY,
                            paletteParPopup = reglage.apercuPaletteParPopup,
                        )
                }
            }
        }

        /**
         * Branche la source d'un document (initial ou sélecteur) :
         * délie l'ancienne, suit le projet, classifie, rend immédiatement.
         */
        public fun ouvrirFichier(uri: String) {
            if (uri == uriCourante && source != null) return
            brancher(uri)
        }

        /** Les documents OUVERTS (registre) qui se laissent prévisualiser — le sélecteur de titre. */
        public fun sourcesPreviewables(): List<SourceApercu> =
            registre
                .sources()
                .filter { source -> ClassificateurPreview.genreDe(source.cheminRelatif, source.texte()) != null }

        /** Le nom affichable du fichier courant (dernier segment du chemin relatif). */
        public fun nomFichierCourant(): String = source?.cheminRelatif?.substringAfterLast('/') ?: ""

        /** Tap sur le rendu → le SPAN source est posé DANS la session (retour éditeur : sélection active). */
        public fun definirSelection(
            debut: Int,
            fin: Int,
        ) {
            source?.definirSelection(debut, fin)
        }

        /**
         * La SÉLECTION partagée (PE6d) : l'indice de la vue dans l'arbre
         * rendu — le SPAN suit dans la session (l'éditeur le retrouve),
         * l'état est publié (la feuille s'aligne ; l'activité re-surligne).
         * Indice sans vue ou sans span : la sélection SE TAIT (jamais un
         * faux span — X4), l'indice reste affiché (l'arbre le montre).
         */
        public fun selectionner(indice: Int) {
            val vue = dernierRendu?.vues?.getOrNull(indice)
            if (vue != null && vue.selectable) {
                source?.definirSelection(vue.debutElement, vue.finElement)
            }
            selectionInterne.value = indice
        }

        /** Le texte VIVANT du document (onglet Source de la feuille — PE6d). */
        public fun texteCourant(): String = source?.texte() ?: ""

        /**
         * Le DÉPÔT de la palette (PE7b) : cible le conteneur sous le
         * point, façonne l'extrait (identifiant libre — miroir `nextId`,
         * contraintes + marges en dp calculées avec la densité du PROFIL
         * pour un parent ConstraintLayout — maquette PE7a), compose
         * l'ÉDITION UNIQUE ([InsertionXml.inserer]) et l'écrit dans la
         * session ([SourceApercu.remplacer] — une seule entrée
         * d'historique). Le rendu repart par le chemin normal (mutation →
         * écouteur → moteur). Refus silencieux hors cible ou span périmé
         * (X4 — jamais d'écriture hasardeuse).
         *
         * Exemption detekt ciblée (règle 16) : ReturnCount — chaque garde
         * (rendu, source, cible, parent, span, édition) est une issue
         * DISTINCTE du refus.
         */
        @Suppress("ReturnCount")
        public fun appliquerDepot(
            contenu: ZonesDepot.ContenuDepot,
            x: Float,
            y: Float,
        ): Boolean {
            val rendu = dernierRendu ?: return false
            val sourceBranchee = source ?: return false
            val vues = rendu.vues
            val cible = ZonesDepot.cibleSous(vues, x, y) ?: return false
            val parent = vues.getOrNull(cible.indiceParent) ?: return false
            if (!parent.selectable) return false
            val texte = sourceBranchee.texte()
            var extrait = InsertionXml.renumeroter(contenu.extraitXml, texte)
            if (parent.classe.endsWith("ConstraintLayout")) {
                val densite = moteur.profil.densiteDpi
                val margeHaut = CalquesApercu.pxVersDp((y - parent.haut).roundToInt().coerceAtLeast(0), densite)
                val margeStart = CalquesApercu.pxVersDp((x - parent.gauche).roundToInt().coerceAtLeast(0), densite)
                extrait = InsertionXml.contraindre(extrait, margeHaut, margeStart)
            }
            val edition =
                InsertionXml.inserer(
                    texte,
                    InsertionXml.SpanParent(parent.debutElement, parent.finElement),
                    cible.index,
                    extrait,
                ) ?: return false
            val ecrit = sourceBranchee.remplacer(edition.debut, edition.fin, edition.remplacement)
            if (ecrit) {
                dernierParentDepot = parent.classe
                marquerRecent(contenu.idWidget)
            }
            return ecrit
        }

        // ── PE6e-f : orientation et échelle de police ────────────────────

        /** Orientation paysage courante (libellé de la puce). */
        public val paysage: Boolean get() = moteur.paysage

        /** Échelle de police courante (libellé de la puce). */
        public val echellePolice: Float get() = moteur.echellePolice

        /** Bascule l'orientation (puce Portrait/Paysage — rendu immédiat). */
        public fun definirPaysage(paysage: Boolean) {
            moteur.definirPaysage(paysage)
        }

        /**
         * RÉACTIVE le processus `:preview` (PE11i — bouton « Réessayer » du
         * bandeau, ADR 0134 D6) : la politique d'isolation repart du régime
         * nominal et le rendu courant est relancé.
         */
        public fun reactiverIsolation() {
            moteur.reactiverIsolation()
        }

        /** Cycler l'échelle de police (puce Police ×n : 1,0 → 1,3 → 2,0 → 1,0). */
        public fun cyclerEchellePolice(): Float {
            val suivante =
                when {
                    moteur.echellePolice <= 1f -> ECHELLE_POLICE_MOYENNE
                    moteur.echellePolice <= ECHELLE_POLICE_MOYENNE -> ECHELLE_POLICE_GRANDE
                    else -> RequeteRendu.ECHELLE_POLICE_DEFAUT
                }
            moteur.definirEchellePolice(suivante)
            return suivante
        }

        // ── PE7c : réglages persistés (bulle, mode), récents, placement ──

        /** Les réglages persistés de l'aperçu (bulle, mode). */
        public data class ReglagesApercu(
            val bulleFixe: Boolean,
            val bulleADroite: Boolean,
            val bulleY: Float,
            val paletteParPopup: Boolean,
        )

        /** L'état courant des réglages (lu depuis le DataStore). */
        private val reglagesApercuInterne =
            MutableStateFlow(
                ReglagesApercu(
                    bulleFixe = false,
                    bulleADroite = false,
                    bulleY = AppSettings.BULLE_Y_DEFAUT,
                    paletteParPopup = false,
                ),
            )

        /** Les réglages observables (la bulle et la palette s'y alignent). */
        public val reglagesApercu: StateFlow<ReglagesApercu> = reglagesApercuInterne.asStateFlow()

        /** La puce bulle a basculé fixe ↔ flottante (persistance immédiate). */
        public fun poserBulleFixe(fixe: Boolean) {
            viewModelScope.launch {
                reglagesDataStore.update { it.copy(apercuBulleFixe = fixe) }
            }
        }

        /** La bulle a été reposée (aimantation — côté + hauteur persistés). */
        public fun poserBulle(
            aDroite: Boolean,
            yNormalise: Float,
        ) {
            viewModelScope.launch {
                reglagesDataStore.update {
                    it.copy(
                        apercuBulleADroite = aDroite,
                        apercuBulleY = yNormalise.coerceIn(0f, 1f),
                    )
                }
            }
        }

        /** Le mode d'insertion de la palette a changé (persistance immédiate). */
        public fun poserModePalette(parPopup: Boolean) {
            viewModelScope.launch {
                reglagesDataStore.update { it.copy(apercuPaletteParPopup = parPopup) }
            }
        }

        /** Re-lit l'état des piles de la source (mutation, annulation, déliaison). */
        private fun majHistorique() {
            val sourceBranchee = source
            historiqueInterne.value =
                if (sourceBranchee == null) {
                    HistoriqueEdition(false, false)
                } else {
                    HistoriqueEdition(sourceBranchee.peutAnnuler(), sourceBranchee.peutRetablir())
                }
        }

        /**
         * Les RÉCENTS de la palette (miroir `recents`, SESSION COURANTE —
         * la persistance reste celle de la maquette, ici en mémoire).
         */
        private val recentsInterne = MutableStateFlow<List<String>>(emptyList())

        /** Les récents observables (la grille de la palette s'y aligne). */
        public val recents: StateFlow<List<String>> = recentsInterne.asStateFlow()

        /** Note un widget au TÊTE des récents (4 max — miroir `insertAt`). */
        private fun marquerRecent(idWidget: String?) {
            if (idWidget == null) return
            val suivant = (listOf(idWidget) + recentsInterne.value.filter { it != idWidget }).take(RECENTS_MAX)
            recentsInterne.value = suivant
        }

        /**
         * Le PLACEMENT PAR POPUP (PE7c — miroir `insertAt(w, parentRid,
         * index)`) : la cible vient de [PlacementPopup.cible] (Avant,
         * Dedans, Après) — le MÊME façonnage que le dépôt PE7b
         * ([renumeroter] + [contraindre] pour un parent ConstraintLayout,
         * marges par DÉFAUT de la maquette : `mt=16`, `ml/mr=24` à la
         * racine) et l'édition UNIQUE passe par le port. Refus hors
         * cible/span périmé (X4 — jamais d'écriture hasardeuse).
         */
        public fun appliquerPlacement(
            contenu: ZonesDepot.ContenuDepot,
            cible: ZonesDepot.CibleDepot,
        ): Boolean {
            val rendu = dernierRendu ?: return false
            val sourceBranchee = source ?: return false
            val parent = rendu.vues.getOrNull(cible.indiceParent) ?: return false
            if (!parent.selectable) return false
            val texte = sourceBranchee.texte()
            var extrait = InsertionXml.renumeroter(contenu.extraitXml, texte)
            if (parent.classe.endsWith("ConstraintLayout")) {
                extrait =
                    InsertionXml.contraindre(
                        extrait,
                        MARGE_PLACEMENT_HAUT_DP,
                        MARGE_PLACEMENT_COTE_DP,
                    )
            }
            val edition =
                InsertionXml.inserer(
                    texte,
                    InsertionXml.SpanParent(parent.debutElement, parent.finElement),
                    cible.index,
                    extrait,
                ) ?: return false
            val ecrit = sourceBranchee.remplacer(edition.debut, edition.fin, edition.remplacement)
            if (ecrit) {
                dernierParentDepot = parent.classe
                marquerRecent(contenu.idWidget)
            }
            return ecrit
        }

        // ── PE7d : éditions d'attributs, dupliquer, supprimer, annuler ──

        /** Le libellé d'une vue éditée (toasts — miroir `label(v)`). */
        public fun libelleVue(indice: Int): String {
            val vue = dernierRendu?.vues?.getOrNull(indice) ?: return ""
            val idNom = vue.idRes.substringAfterLast('/')
            return if (idNom.isEmpty()) vue.classe else vue.classe + " · @+id/" + idNom
        }

        /** La vue sélectionnée est-elle éditable (ni racine, ni hors arbre) ? */
        public fun selectionEditable(): Boolean {
            val indice = selectionInterne.value
            if (indice <= 0) return false
            val vue = dernierRendu?.vues?.getOrNull(indice) ?: return false
            return vue.selectable && vue.indiceParent >= 0
        }

        /** La valeur ACTUELLE d'un attribut de la vue sélectionnée (volet Modifier). */
        public fun valeurAttributSelection(nomAttribut: String): String? {
            val vue = dernierRendu?.vues?.getOrNull(selectionInterne.value) ?: return null
            val sourceBranchee = source ?: return null
            return EditionAttributsXml.valeurAttribut(
                sourceBranchee.texte(),
                InsertionXml.SpanParent(vue.debutElement, vue.finElement),
                nomAttribut,
            )
        }

        /**
         * DÉFINIT un attribut sur la sélection (PE7e — la voie générale :
         * remplacement, ajout à la position conventionnelle, retrait si
         * valeur vide, xmlns posé au besoin). La RACINE reste éditable
         * (Disposition/Affichage — miroir piège § 5 du prompt PE7e).
         */
        public fun definirAttributSelection(
            nomAttribut: String,
            valeur: String,
        ): Boolean =
            editerSelection(autoriserRacine = true) { rendu, span ->
                EditionAttributsXml.definir(rendu, span, nomAttribut, valeur)
            }

        /**
         * RENOMME l'identifiant de la sélection (PE8a) : déclaration
         * `@+id/x` + TOUTES les références `@id/x` du fichier en UNE
         * édition — la racine y a droit aussi. Le code (`R.id.x`) et les
         * autres fichiers ne sont pas touchés (périmètre du prompt PE7e
         * § 3, signalé dans l'interface).
         */
        public fun renommerIdentifiantSelection(nouveau: String): Boolean =
            editerSelection(autoriserRacine = true) { rendu, span ->
                RenommageIdentifiant.renommer(rendu, span, nouveau)
            }

        /** Écrit UN CÔTÉ de marge/padding sur la sélection (PE7e — miroir `.mrow`). */
        public fun ecrireCoteSelection(
            base: String,
            cote: EditionAttributsXml.Cote,
            valeurDp: Int,
        ): Boolean =
            editerSelection(autoriserRacine = true) { rendu, span ->
                EditionAttributsXml.ecrireCote(rendu, span, base, cote, valeurDp)
            }

        /**
         * Les QUATRE ancres de contrainte de la sélection (PE8b — lecture
         * dans le texte vivant : cible + marge courantes). `null` = span
         * périmé (le volet affiche la lecture seule).
         */
        public fun lireContraintesSelection(): List<EditionAttributsXml.ContrainteLue>? {
            val vue = dernierRendu?.vues?.getOrNull(selectionInterne.value) ?: return null
            val sourceBranchee = source ?: return null
            return EditionAttributsXml.lireContraintes(
                sourceBranchee.texte(),
                InsertionXml.SpanParent(vue.debutElement, vue.finElement),
            )
        }

        /**
         * Les identifiants DISPONIBLES comme cible de contrainte (PE8b) :
         * les FRÈRES de la sélection qui portent un id (le parent se
         * cible par la valeur plateforme `parent`). Le CONTENEUR de
         * contrainte du rendu est la vérité (miroir l'aligneur).
         */
        public fun ciblesContrainteSelection(): List<String> {
            val rendu = dernierRendu ?: return emptyList()
            val indice = selectionInterne.value
            val vue = rendu.vues.getOrNull(indice) ?: return emptyList()
            return rendu.vues
                .withIndex()
                .filter { (i, v) -> v.indiceParent == vue.indiceParent && i != indice && v.idRes.isNotBlank() }
                .map { it.value.idRes }
        }

        /**
         * Écrit l'ANCRE [cote] de la sélection (PE8b) : [cible] et/ou
         * [margeDp] (`null` = aspect intouché, `0` = marge retirée) —
         * UNE édition au port, rendu immédiat.
         */
        public fun ecrireContrainteSelection(
            cote: EditionAttributsXml.Cote,
            cible: EditionAttributsXml.CibleContrainte?,
            margeDp: Int?,
        ): Boolean =
            editerSelection { rendu, span ->
                EditionAttributsXml.ecrireContrainte(rendu, span, cote, cible, margeDp)
            }

        /**
         * La session strings.xml OUVERTE du même projet (PE8c — le
         * registre ne connaît que les documents OUVERTS) : même racine
         * projet, chemin `res/values/strings.xml` dérivé de celui du
         * layout édité. `null` = fermée (l'extraction est masquée).
         */
        private fun sourceStringsOuverte(): SourceApercu? {
            val courant = source ?: return null
            val projet = courant.uriProjet ?: return null
            val cheminStrings = cheminStringsDe(courant.cheminRelatif) ?: return null
            return registre.sources().firstOrNull {
                it.uriProjet == projet && it.cheminRelatif == cheminStrings
            }
        }

        /** `…/res/<qualifier>/x.xml` → `…/res/values/strings.xml` (même arbre res). */
        private fun cheminStringsDe(chemin: String): String? {
            val marqueur = "/res/"
            val debut = chemin.indexOf(marqueur)
            if (debut < 0) return null
            return chemin.substring(0, debut + marqueur.length) + "values/strings.xml"
        }

        /**
         * L'EXTRACTION est POSSIBLE (PE8c) : le littéral courant est
         * éditable (non vide, pas déjà une référence) ET la session
         * strings.xml du projet est ouverte.
         */
        public fun extractionPossible(nomAttribut: String): Boolean {
            val valeur = valeurAttributSelection(nomAttribut) ?: return false
            if (valeur.isEmpty() || valeur.startsWith("@") || valeur.startsWith("?")) return false
            return sourceStringsOuverte() != null
        }

        /**
         * EXTRAIT le littéral de la sélection vers `@string/nomRessource`
         * (PE8c) : l'ENTRÉE part d'abord dans strings.xml (jamais de
         * référence orpheline si l'entrée échoue), puis la RÉFÉRENCE
         * remplace le littéral dans le layout — rendu immédiat. Deux
         * documents = deux entrées d'historique (documenté dans PREVIEW.md).
         */
        public fun extraireEnRessource(
            nomAttribut: String,
            nomRessource: String,
        ): Boolean {
            val sourceStrings = sourceStringsOuverte() ?: return false
            val rendu = dernierRendu ?: return false
            val sourceBranchee = source ?: return false
            val vue = rendu.vues.getOrNull(selectionInterne.value) ?: return false
            if (!vue.selectable) return false
            val texte = sourceBranchee.texte()
            val span = InsertionXml.SpanParent(vue.debutElement, vue.finElement)
            val valeurLitterale =
                EditionAttributsXml.valeurAttribut(texte, span, nomAttribut) ?: return false
            // 1) L'ENTRÉE d'abord, avec la valeur d'ORIGINE.
            val entree =
                ExtractionRessource.entreeStrings(sourceStrings.texte(), nomRessource, valeurLitterale)
                    ?: return false
            if (!sourceStrings.remplacer(entree.debut, entree.fin, entree.remplacement)) return false
            // 2) La RÉFÉRENCE dans le layout (une seule édition, rendu immédiat).
            val reference = ExtractionRessource.extraire(texte, span, nomAttribut, nomRessource) ?: return false
            if (!sourceBranchee.remplacer(reference.debut, reference.fin, reference.remplacement)) return false
            moteur.soumettreImmediat { sourceBranchee.texte() }
            return true
        }

        /**
         * Les attributs BRUTS de la sélection (PE7e — miroir `attrsOf` :
         * contraintes lecture seule, valeurs courantes des côtés).
         * `null` = span périmé (le volet passe en lecture seule).
         */
        public fun attributsSelection(): List<EditionAttributsXml.AttributSource>? {
            val vue = dernierRendu?.vues?.getOrNull(selectionInterne.value) ?: return null
            val sourceBranchee = source ?: return null
            return EditionAttributsXml.lire(
                sourceBranchee.texte(),
                InsertionXml.SpanParent(vue.debutElement, vue.finElement),
            )
        }

        /** La CLASSE de la vue sélectionnée (le catalogue en dérive les sections). */
        public fun classeSelection(): String? = dernierRendu?.vues?.getOrNull(selectionInterne.value)?.classe

        /**
         * Le GENRE du parent de la sélection (le catalogue en dérive les
         * sections Contraintes / Dans le parent) — `null` sans rendu.
         */
        public fun genreParentSelection(): CatalogueAttributs.GenreParent? {
            val rendu = dernierRendu ?: return null
            val indice = selectionInterne.value
            val vue = rendu.vues.getOrNull(indice) ?: return null
            if (vue.indiceParent < 0) return CatalogueAttributs.GenreParent.AUCUN
            val parent = rendu.vues.getOrNull(vue.indiceParent)
            return CatalogueAttributs.genreParent(parent?.classe, parentEstRacine = vue.indiceParent == 0)
        }

        /** Écrit une dimension (wrap/match/fixe) sur la sélection (miroir `seg2`). */
        public fun ecrireDimensionSelection(
            axe: EditionAttributsXml.Axe,
            mode: EditionAttributsXml.ModeDimension,
            valeurDp: Int?,
        ): Boolean =
            editerSelection { rendu, span ->
                EditionAttributsXml.ecrireDimension(rendu, span, axe, mode, valeurDp)
            }

        /** DUPLIQUE la vue sélectionnée (miroir `dupSel`) — le libellé de la copie. */
        public fun dupliquerSelection(): Boolean =
            editerSelection { rendu, span ->
                EditionAttributsXml.dupliquer(rendu, span)
            }

        /** SUPPRIME la vue sélectionnée (miroir `delSel`). */
        public fun supprimerSelection(): Boolean {
            val ok =
                editerSelection { rendu, span ->
                    EditionAttributsXml.supprimer(rendu, span)
                }
            if (ok) selectionInterne.value = -1
            return ok
        }

        /** Annule la DERNIÈRE édition (port — l'historique reste celui de l'éditeur). */
        public fun annuler(): Boolean {
            val ok = source?.annuler() ?: false
            majHistorique()
            return ok
        }

        /** Rétablit la dernière édition annulée (port). */
        public fun retablir(): Boolean {
            val ok = source?.retablir() ?: false
            majHistorique()
            return ok
        }

        /**
         * Le CORPS commun des éditions PE7d : la vue sélectionnée doit
         * rester ALIGNÉE au texte VIVANT (re-marche de l'aligneur dans
         * chaque fonction pure) — l'édition passe au port
         * ([SourceApercu.remplacer], une seule entrée d'historique) puis
         * le rendu repart IMMÉDIATEMENT (miroir `soumettreImmediat` de la
         * maquette PE7d).
         */
        private fun editerSelection(
            autoriserRacine: Boolean = false,
            composer: (texte: String, span: InsertionXml.SpanParent) ->
            EditionAttributsXml.EditionAttributsXmlResultat?,
        ): Boolean {
            val rendu = dernierRendu ?: return false
            val sourceBranchee = source ?: return false
            val indice = selectionInterne.value
            val vue = rendu.vues.getOrNull(indice) ?: return false
            // PE7e : la RACINE reste éditable pour ses attributs
            // (Disposition/Affichage) — pas pour dupliquer/supprimer.
            if (!vue.selectable || (!autoriserRacine && vue.indiceParent < 0)) return false
            val texte = sourceBranchee.texte()
            val edition =
                composer(texte, InsertionXml.SpanParent(vue.debutElement, vue.finElement)) ?: return false
            val ecrit = sourceBranchee.remplacer(edition.debut, edition.fin, edition.remplacement)
            if (!ecrit) return false
            // Le rendu repart IMMÉDIATEMENT (l'écouteur de mutation ne
            // re-soumet qu'après le debounce — le même texte sera skippé
            // par l'anti-boucle du moteur).
            moteur.soumettreImmediat { sourceBranchee.texte() }
            return true
        }

        override fun onCleared() {
            delierSource()
            suiviProjet?.cancel()
            moteur.close()
        }

        /** Branche la source [uri] : déliaison, écoute de mutation, projet, rendu immédiat. */
        private fun brancher(uri: String) {
            val nouvelleSource = registre.sourceDe(uri)
            if (nouvelleSource == null) {
                // Piège « mort du processus » : Android ne recrée que
                // l'activité du dessus — le registre est vide. L'écran se
                // termine et relance l'éditeur (jamais de panneau vide).
                journal.w(TAG) { "source absente du registre (processus mort ?) — fin d'écran : $uri" }
                finirInterne.value = true
                return
            }
            delierSource()
            source = nouvelleSource
            uriCourante = uri
            majHistorique()
            val ecouteurNouveau =
                SourceApercu.EcouteurMutation {
                    // Fournisseur paresseux : le texte n'est tiré qu'après debounce.
                    moteur.soumettre { nouvelleSource.texte() }
                    // PE10c : toute mutation (frappe, édition de la palette,
                    // annulation) met la barre d'édition à jour.
                    majHistorique()
                }
            ecouteur = ecouteurNouveau
            nouvelleSource.ajouterEcouteur(ecouteurNouveau)
            suivreProjet(nouvelleSource)
            moteur.definirGenre(ClassificateurPreview.genreDe(nouvelleSource.cheminRelatif, nouvelleSource.texte()))
            moteur.soumettreImmediat { nouvelleSource.texte() }
        }

        /** Délie l'écouteur de la source courante (changement de fichier, onCleared). */
        private fun delierSource() {
            val ancienne = source
            val ancienEcouteur = ecouteur
            if (ancienne != null && ancienEcouteur != null) {
                ancienne.retirerEcouteur(ancienEcouteur)
            }
            ecouteur = null
            historiqueInterne.value = HistoriqueEdition(false, false)
        }

        /**
         * Suit le projet porteur (PE5/G2/G5, repris du contrôleur PE1) :
         * `res/` résolu FUSE, thème activité et label du manifest — le
         * rendu reprend le chrome du vrai appareil.
         *
         * CORRECTIF 0.134.31 (rapport 0.134.30, journal « res=null ») :
         * 1) le dossier `res/` n'est PLUS deviné `<racine>/res` (les
         * projets des modèles vivent en `app/src/main/res`) — il est
         * DÉRIVÉ du chemin du layout édité (`…/res/<famille>/f.xml` →
         * `…/res`, la même vérité que [cheminStringsDe] PE8c), avec
         * repli sur les candidats standards ; 2) le PREMIER rendu part
         * AVANT la résolution FUSE (soumettreImmediat de [brancher]) —
         * quand `res/` est résolu APRÈS lui, un re-rendu immédiat
         * s'impose (sinon `@color/…` reste non résolu jusqu'à la
         * prochaine frappe).
         */
        private fun suivreProjet(sourceBranchee: SourceApercu) {
            suiviProjet?.cancel()
            val uriProjet = sourceBranchee.uriProjet
            if (uriProjet == null) {
                // Pas de projet connu : repli ressources natives (miroir PE1).
                moteur.definirDossierRes(null)
                moteur.definirThemeName(null)
                moteur.definirLabelActivite(null)
                return
            }
            suiviProjet =
                viewModelScope.launch {
                    val racine = resolveurChemin.resoudreEntree(uriProjet) ?: return@launch
                    val cheminRes = dossierResProjet(racine, sourceBranchee.cheminRelatif)
                    if (cheminRes == null) return@launch
                    moteur.definirDossierRes(cheminRes)
                    val manifeste = lireManifeste(racine)
                    moteur.definirThemeName(manifeste?.let { resoudreThemeActivite(it) })
                    moteur.definirLabelActivite(manifeste?.let { resoudreLabelActivite(it) })
                    // Le premier rendu a pu partir avec res=null (course
                    // soumettreImmediat ↔ résolution FUSE) : re-rendu
                    // (definirDossierRes a réarmé l'anti-boucle).
                    moteur.soumettreImmediat { sourceBranchee.texte() }
                }
        }

        /**
         * Le dossier `res/` du projet, DÉRIVÉ du chemin relatif du
         * document édité (`…/res/<famille>/f.xml` → `…/res`) puis des
         * candidats standards — le MÊME ordre que [trouverManifeste].
         * `null` = aucun dossier réellement présent (repli natif).
         */
        private fun dossierResProjet(
            racineProjet: String,
            cheminRelatif: String,
        ): String? {
            val marqueur = "/res/"
            val debut = cheminRelatif.indexOf(marqueur)
            if (debut > 0) {
                val relatif = cheminRelatif.substring(0, debut + marqueur.length - 1)
                val candidat = racineProjet.trimEnd('/') + "/" + relatif
                if (java.io.File(candidat).isDirectory) return candidat
            }
            val base = racineProjet.trimEnd('/')
            return listOf("$base/app/src/main/res", "$base/src/main/res", "$base/res")
                .firstOrNull { java.io.File(it).isDirectory }
        }

        /** Lit le manifest d'application (chemins standards) — null si absent/illisible. */
        private fun lireManifeste(racineProjet: String): String? {
            val manifeste = trouverManifeste(racineProjet) ?: return null
            return try {
                java.io.File(manifeste).readText()
            } catch (_: Throwable) {
                null
            }
        }

        /** Titre de l'action bar : `android:label` de la PREMIÈRE `<activity>` (à défaut `<application>`). */
        @Suppress("ReturnCount") // Chaque garde = une issue distincte du contrat (miroir PE1).
        private fun resoudreLabelActivite(contenu: String): String? {
            val regexLabel = Regex("""android:label\s*=\s*"([^"]+)"""")
            Regex("""<activity\b[^>]*""").find(contenu)?.let { balise ->
                regexLabel.find(balise.value)?.let { return it.groupValues[1] }
            }
            Regex("""<application\b[^>]*""").find(contenu)?.let { balise ->
                regexLabel.find(balise.value)?.let { return it.groupValues[1] }
            }
            return null
        }

        /** Thème activité : `android:theme` de la balise ouvrante (application). */
        private fun resoudreThemeActivite(contenu: String): String? =
            Regex("""android:theme\s*=\s*"(@style/[^"]+|@android:style/[^"]+)"""")
                .find(contenu)
                ?.groupValues
                ?.get(1)

        /** Cherche AndroidManifest.xml dans les chemins standards du projet. */
        private fun trouverManifeste(racineProjet: String): String? {
            val base = racineProjet.trimEnd('/')
            val candidats =
                listOf(
                    "$base/app/src/main/AndroidManifest.xml",
                    "$base/src/main/AndroidManifest.xml",
                    "$base/AndroidManifest.xml",
                    "$base/../AndroidManifest.xml",
                )
            return candidats.firstOrNull { java.io.File(it).isFile }
        }

        /** Extras d'intention (Publics : l'implémentation du navigateur pose les extras.) */
        public companion object {
            private const val TAG: String = "PreviewViewModel"

            /** Extra d'intention : identifiant du projet (relance de l'éditeur). */
            public const val CLE_PROJET: String = "jo.codeide.preview.PROJECT_ID"

            /** Extra d'intention : URI du document à prévisualiser. */
            public const val CLE_URI: String = "jo.codeide.preview.URI"

            /** Échelle de police MOYENNE du cycle (miroir `S.fs = 1.3`). */
            public const val ECHELLE_POLICE_MOYENNE: Float = 1.3f

            /** Échelle de police GRANDE du cycle (miroir `S.fs = 2`). */
            public const val ECHELLE_POLICE_GRANDE: Float = 2f

            /** Marge haute PAR DÉFAUT d'un placement à la racine (miroir `n.mt = 16`). */
            private const val MARGE_PLACEMENT_HAUT_DP: Int = 16

            /** Marge latérale PAR DÉFAUT à la racine (miroir `ml/mr = 24`). */
            private const val MARGE_PLACEMENT_COTE_DP: Int = 24

            /** Taille de la liste des récents (miroir `slice(0, 4)`). */
            private const val RECENTS_MAX: Int = 4
        }
    }
