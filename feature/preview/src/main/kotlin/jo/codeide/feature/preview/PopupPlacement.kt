package jo.codeide.feature.preview

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.style.TextAppearanceSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.view.isVisible
import jo.codeide.feature.preview.databinding.ItemCelluleGraviteBinding
import jo.codeide.feature.preview.databinding.ItemPositionBoutonBinding
import jo.codeide.feature.preview.databinding.ItemRangeePlacementBinding
import jo.codeide.feature.preview.databinding.PopoverPlacementParentsBinding
import jo.codeide.preview.xml.FamilleParent
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.PlacementPopup
import jo.codeide.preview.xml.PlanInsertionFamille
import jo.codeide.preview.xml.ZonesDepot
import kotlin.math.min

/**
 * Le POPUP de placement (PE7c, réécrit PE12d — maquette
 * `docs/mockups/apercu-xml.html` § « Bulle, palette et popup de placement »,
 * miroir `showPlace`/`drawPlace`/`posList`, spec `PREVIEW_PARENTS.md` § 7,
 * ADR 0135 D4/D6/D9) : « le popup affiche l'arbre des vues rendues, le jeu
 * de positions selon la famille du parent résultant, la grille de gravité
 * ou la rangée de marges, le résumé, et dessine le repère en direct ».
 *
 * Contenu (miroir fidèle de `popover_placement_parents.xml`, qui REMPLACE
 * `popover_placement.xml`) :
 * - l'en-tête « Où placer X ? » + fermer ;
 * - l'ARBRE des vues rendues (référence surlignée — miroir `.mini`,
 *   [FeuilleApercu.entreesArbre]) ;
 * - le JEU de positions ([PlacementPopup.jeuPositions]) : Avant · Dedans ·
 *   Après (SEQUENCE), Derrière · Dedans · Devant (PILE), Dessous · Dessus ·
 *   Droite · Gauche (+ Dedans si conteneur — COTES_RELATIFS) ou Dedans
 *   seul (racine) — boutons [ItemPositionBoutonBinding], radios (D9) ;
 * - la GRILLE de gravité 3 × 3 (pile, ou ancrage au parent `RelativeLayout`)
 *   — cellules [ItemCelluleGraviteBinding], glyphes de la maquette,
 *   `contentDescription` localisé ;
 * - la RANGÉE de marges 0/8/16/24 dp (`RelativeLayout` — D6) ;
 * - le résumé (trois formes, § 7) et le bouton violet « Placer ici ».
 *
 * Hauteurs (§ 7) : 340 dp max pour les parents à barre ;
 * `min(580 dp, hauteur disponible − 134 dp)` quand la grille ou la rangée
 * de marges est visible (« tall ») — le mini-arbre passe alors à 100 dp
 * (bornes posées par le code, le ScrollView n'a pas de maxHeight).
 *
 * Le REPÈRE EN DIRECT reste au propriétaire : chaque changement de
 * référence/position/cellule/marge notifie [auReference]/[auPosition]/
 * [auCellule]/[auMarge], l'activité pose la cible sur la vue d'aperçu
 * (zones de dépôt PE7b + repère de plan PE12d — un seul calque).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — le popup dérive
 * UN CONTRÔLE par fonction (règle 8 : arbre, positions, grille, marges,
 * résumé, radios, bornes) ; la structure EST la règle, pas une
 * accumulation (précédent [VoletAttributsFragment] PE12c).
 */
@Suppress("TooManyFunctions")
public class PopupPlacement(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val liaison: PopoverPlacementParentsBinding =
        PopoverPlacementParentsBinding.inflate(LayoutInflater.from(context), this, true)

    /** La référence a changé (tap sur une rangée d'arbre — miroir `setPlaceRef`). */
    public var auReference: ((indice: Int) -> Unit)? = null

    /** La position a changé (miroir `p.pos = m`). */
    public var auPosition: ((position: PlacementPopup.Position) -> Unit)? = null

    /** La cellule de gravité a changé (miroir `p.cell = {r, c}`). */
    public var auCellule: ((cellule: PlanInsertionFamille.Cellule) -> Unit)? = null

    /** La marge a changé (miroir `p.mg = +m` — D6 : 0, 8, 16, 24 dp). */
    public var auMarge: ((margeDp: Int) -> Unit)? = null

    /** « Placer ici » (miroir `ok.onclick` → `insertAt`). */
    public var auConfirmer: (() -> Unit)? = null

    /** Fermeture (miroir `hidePop`). */
    public var auFermer: (() -> Unit)? = null

    init {
        liaison.boutonFermerPlacement.setOnClickListener { auFermer?.invoke() }
        liaison.boutonPlacer.setOnClickListener { auConfirmer?.invoke() }
    }

    /** Le titre « Où placer X ? » (miroir `hd b`). */
    public fun definirTitre(nomWidget: String) {
        liaison.titrePlacement.text = context.getString(R.string.preview_placement_titre, nomWidget)
    }

    /**
     * Le CHOIX COURANT du popup (miroir `S.place` : `pos`, `cell`, `mg`)
     * — posé par l'activité, source de vérité unique.
     */
    public data class ChoixPlacement(
        /** La position demandée (miroir `p.pos`). */
        public val position: PlacementPopup.Position,
        /** La cellule de gravité courante (miroir `p.cell`). */
        public val cellule: PlanInsertionFamille.Cellule = PlanInsertionFamille.Cellule(ligne = 0, colonne = 0),
        /** La marge courante, en dp (miroir `p.mg` — D6 : 16 par défaut). */
        public val margeDp: Int = PlanInsertionFamille.MARGE_DEFAUT_DP,
    )

    /**
     * Re-marche TOUT le popup (miroir `drawPlace`) : l'arbre, le jeu de
     * positions, les sous-contrôles visibles selon la famille du parent
     * RÉSULTANT, le résumé. Les vues + les choix courants viennent du
     * propriétaire (source de vérité unique) ; les bornes de hauteur
     * suivent (« tall » quand la grille ou les marges sont visibles).
     */
    public fun afficher(
        vues: List<InfoVueRendu>,
        indiceReference: Int,
        choix: ChoixPlacement,
        cible: ZonesDepot.CibleDepot?,
    ) {
        dernieresVues = vues
        val familleResultante =
            PlacementPopup.familleParentResultant(vues, indiceReference, choix.position)
        val grilleVisible =
            familleResultante == FamilleParent.FRAME ||
                familleResultante == FamilleParent.COORDINATOR ||
                (familleResultante == FamilleParent.RELATIVE && choix.position == PlacementPopup.Position.DEDANS)
        val margesVisibles = familleResultante == FamilleParent.RELATIVE
        majArbre(vues, indiceReference)
        majPositions(vues, indiceReference, choix.position)
        majGrille(choix.cellule, grilleVisible, familleResultante)
        majMarges(choix.margeDp, margesVisibles)
        majResume(cible)
        bornerHauteurs(grilleVisible || margesVisibles)
    }

    /** L'arbre des vues rendues (miroir `.mini` — rangées d'arbre PE6d). */
    private fun majArbre(
        vues: List<InfoVueRendu>,
        indiceReference: Int,
    ) {
        liaison.lignesArbrePlacement.removeAllViews()
        if (vues.isEmpty()) return
        val entrees = FeuilleApercu.entreesArbre(vues, problemes = emptyList(), densiteDpi = 0)
        val retraitBase = resources.getDimensionPixelSize(R.dimen.apercu_rangee_pad_h)
        val retraitNiveau = resources.getDimensionPixelSize(R.dimen.apercu_rangee_indent)
        for (entree in entrees) {
            // PE11j : la rangée vient de l'item layout réel (miroir `.mini`,
            // MÊMES rangées que l'arbre — monogramme, classe, id).
            val rangee =
                ItemRangeePlacementBinding.inflate(
                    LayoutInflater.from(context),
                    liaison.lignesArbrePlacement,
                    false,
                )
            // La référence surlignée = isSelected → accent DOUX du sélecteur
            // (miroir `.row.on` — PE11j retire le violet posé en code).
            rangee.rangeePlacement.isSelected = entree.indice == indiceReference
            // L'indentation de profondeur suit l'arbre (8 + profondeur × 16 —
            // miroir `style.paddingLeft`, le reste du padding vient du style).
            rangee.rangeePlacement.setPadding(
                retraitBase + entree.indentation * retraitNiveau,
                rangee.rangeePlacement.paddingTop,
                rangee.rangeePlacement.paddingEnd,
                rangee.rangeePlacement.paddingBottom,
            )
            rangee.monogrammePlacement.text = entree.monogramme
            rangee.libelleClassePlacement.text = entree.classe
            rangee.libelleIdPlacement.isVisible = entree.idNom.isNotEmpty()
            rangee.libelleIdPlacement.text = context.getString(R.string.preview_prefixe_id, entree.idNom)
            rangee.rangeePlacement.setOnClickListener { auReference?.invoke(entree.indice) }
            liaison.lignesArbrePlacement.addView(rangee.rangeePlacement)
        }
        // Le défilement vers la rangée sélectionnée (miroir `mini.scrollTop`).
        val total = entrees.size
        liaison.defileArbrePlacement.post {
            val selectionnee =
                liaison.lignesArbrePlacement.indexOfChild(
                    liaison.lignesArbrePlacement.getChildAt(indiceReference.coerceAtMost(total - 1)),
                )
            if (selectionnee >= 0) {
                val vue = liaison.lignesArbrePlacement.getChildAt(selectionnee)
                liaison.defileArbrePlacement.scrollTo(0, vue.top - liaison.defileArbrePlacement.height / 2)
            }
        }
    }

    /**
     * Le JEU de positions (miroir `.pos` rempli par le code) : 1 à 4
     * boutons égaux, libellés selon [PlacementPopup.jeuPositions], la
     * sélection courant, l'espacement de 4 dp en marge (jamais sur le
     * premier), radios cochables (D9).
     */
    private fun majPositions(
        vues: List<InfoVueRendu>,
        indiceReference: Int,
        courante: PlacementPopup.Position,
    ) {
        val conteneur = liaison.groupePosition
        conteneur.removeAllViews()
        val autorisees = PlacementPopup.positionsAutorisees(vues, indiceReference)
        val jeu = PlacementPopup.jeuPositions(vues, indiceReference)
        var premier = true
        for ((position, ressource) in positionsDuJeu(jeu).filter { it.first in autorisees }) {
            val libelle = context.getString(ressource)
            val bouton =
                ItemPositionBoutonBinding.inflate(LayoutInflater.from(context), conteneur, false)
            bouton.positionBouton.text = libelle
            bouton.positionBouton.isSelected = position == courante
            if (!premier) {
                val ecart = resources.getDimensionPixelSize(R.dimen.apercu_position_gap)
                (bouton.positionBouton.layoutParams as MarginLayoutParams).marginStart = ecart
            }
            premier = false
            poserRadio(bouton.positionBouton, position == courante)
            bouton.positionBouton.setOnClickListener { auPosition?.invoke(position) }
            conteneur.addView(bouton.positionBouton)
        }
    }

    /**
     * Les positions de chaque jeu, dans l'ordre, avec leurs RESSOURCES de
     * libellé (miroir `POS_SEQ`/`POS_Z`/`POS_REL` — une donnée du popup,
     * pas une fonction).
     */
    private fun positionsDuJeu(jeu: PlacementPopup.JeuPositions): List<Pair<PlacementPopup.Position, Int>> =
        when (jeu) {
            PlacementPopup.JeuPositions.SEQUENCE -> {
                listOf(
                    PlacementPopup.Position.AVANT to R.string.preview_placement_avant,
                    PlacementPopup.Position.DEDANS to R.string.preview_placement_dedans,
                    PlacementPopup.Position.APRES to R.string.preview_placement_apres,
                )
            }

            PlacementPopup.JeuPositions.PILE -> {
                listOf(
                    PlacementPopup.Position.AVANT to R.string.preview_placement_derriere,
                    PlacementPopup.Position.DEDANS to R.string.preview_placement_dedans,
                    PlacementPopup.Position.APRES to R.string.preview_placement_devant,
                )
            }

            PlacementPopup.JeuPositions.COTES_RELATIFS -> {
                listOf(
                    PlacementPopup.Position.DESSOUS to R.string.preview_placement_dessous,
                    PlacementPopup.Position.DESSUS to R.string.preview_placement_dessus,
                    PlacementPopup.Position.DROITE to R.string.preview_placement_droite,
                    PlacementPopup.Position.GAUCHE to R.string.preview_placement_gauche,
                    PlacementPopup.Position.DEDANS to R.string.preview_placement_dedans,
                )
            }

            PlacementPopup.JeuPositions.DEDANS_SEUL -> {
                listOf(PlacementPopup.Position.DEDANS to R.string.preview_placement_dedans)
            }
        }

    /**
     * La GRILLE de gravité 3 × 3 (miroir `.pos.g3`) : neuf cellules aux
     * glyphes de la maquette, `contentDescription` localisé (D9), la
     * cellule courante cochée. Le TITRE suit le contexte : « Position dans
     * le conteneur » (pile) ou « Ancrage dans le parent » (`RelativeLayout`
     * — miroir `h4.textContent`).
     */
    private fun majGrille(
        cellule: PlanInsertionFamille.Cellule,
        visible: Boolean,
        famille: FamilleParent,
    ) {
        liaison.grilleGravite.isVisible = visible
        liaison.titreGrille.isVisible = visible
        if (!visible) return
        val titreAncrage = famille == FamilleParent.RELATIVE
        val titre =
            if (titreAncrage) {
                R.string.preview_placement_groupe_ancrage
            } else {
                R.string.preview_placement_groupe_conteneur
            }
        liaison.titreGrille.setText(titre)
        val conteneur = liaison.grilleGravite
        conteneur.removeAllViews()
        val ecart = resources.getDimensionPixelSize(R.dimen.apercu_position_gap)
        val libelles = LIBELLES_CELLULES
        for (ligne in INDICES_GRILLE) {
            for (colonne in INDICES_GRILLE) {
                val bouton =
                    ItemCelluleGraviteBinding.inflate(LayoutInflater.from(context), conteneur, false)
                val indice = ligne * TAILLE_GRILLE + colonne
                bouton.celluleGravite.text = GLYPHES_CELLULES[indice]
                bouton.celluleGravite.contentDescription = context.getString(libelles[indice])
                val cochee = ligne == cellule.ligne && colonne == cellule.colonne
                bouton.celluleGravite.isSelected = cochee
                poserRadio(bouton.celluleGravite, cochee)
                bouton.celluleGravite.setOnClickListener {
                    auCellule?.invoke(PlanInsertionFamille.Cellule(ligne = ligne, colonne = colonne))
                }
                // L'ESPACEMENT de 4 dp entre cellules est posé en marge PAR
                // LE CODE (jamais sur la première ligne/colonne — miroir les
                // mesures `g3` : 118,7 dp de large, 4 dp d'écart, § 7).
                val parametres = bouton.celluleGravite.layoutParams as GridLayout.LayoutParams
                if (colonne > 0) parametres.marginStart = ecart
                if (ligne > 0) parametres.topMargin = ecart
                conteneur.addView(bouton.celluleGravite)
            }
        }
    }

    /** La RANGÉE de marges 0/8/16/24 dp (miroir `MGS` — D6 confirmée). */
    private fun majMarges(
        margeDp: Int,
        visible: Boolean,
    ) {
        liaison.titreMarge.isVisible = visible
        liaison.groupeMarge.isVisible = visible
        if (!visible) return
        val conteneur = liaison.groupeMarge
        conteneur.removeAllViews()
        var premier = true
        for (marge in MARGES_POSSIBLES) {
            val bouton =
                ItemPositionBoutonBinding.inflate(LayoutInflater.from(context), conteneur, false)
            bouton.positionBouton.text = context.getString(R.string.preview_placement_marge_dp, marge)
            bouton.positionBouton.isSelected = marge == margeDp
            if (!premier) {
                val ecart = resources.getDimensionPixelSize(R.dimen.apercu_position_gap)
                (bouton.positionBouton.layoutParams as MarginLayoutParams).marginStart = ecart
            }
            premier = false
            poserRadio(bouton.positionBouton, marge == margeDp)
            bouton.positionBouton.setOnClickListener { auMarge?.invoke(marge) }
            conteneur.addView(bouton.positionBouton)
        }
    }

    /**
     * Le RÉSUMÉ, trois formes (§ 7, miroir `res.innerHTML`) : « Dans X,
     * position n sur total » (sans plan), « {plan}, dans X » (règle ou
     * ancrage `RelativeLayout`) ou « Dans X, {plan}, plan n sur total »
     * (pile) — les valeurs en ResumeFort (violet, miroir `<b>`).
     */
    private fun majResume(cible: ZonesDepot.CibleDepot?) {
        val resume = PlacementPopup.resume(dernieresVues, cible)
        if (resume == null) {
            liaison.resumePlacement.text = ""
            liaison.boutonPlacer.isEnabled = false
            return
        }
        val plan = cible?.plan
        when (plan?.genre) {
            PlanInsertionFamille.GenrePlan.REGLE, PlanInsertionFamille.GenrePlan.PARENT_RELATIF -> {
                // « {libellé du plan}, dans {parent} » — miroir h.kind 'rel'.
                val corps = SpannableStringBuilder()
                majFort(
                    corps,
                    context.getString(
                        R.string.preview_placement_resume_regle,
                        resume.libellePlan.orEmpty(),
                        resume.libelleParent,
                    ),
                    listOf(resume.libellePlan.orEmpty(), resume.libelleParent),
                )
                liaison.resumePlacement.text = corps
            }

            PlanInsertionFamille.GenrePlan.PILE -> {
                // « Dans {parent}, {libellé du plan}, plan n sur total » —
                // miroir kind 'frame'.
                val corps = SpannableStringBuilder()
                majFort(
                    corps,
                    context.getString(
                        R.string.preview_placement_resume_pile,
                        resume.libelleParent,
                        resume.libellePlan.orEmpty(),
                        resume.position,
                        resume.total,
                    ),
                    listOf(resume.libelleParent, resume.libellePlan.orEmpty()),
                )
                liaison.resumePlacement.text = corps
            }

            null -> {
                val corps = SpannableStringBuilder()
                corps.append(context.getString(R.string.preview_placement_resume_dans))
                majFort(corps, "", listOf(resume.libelleParent))
                corps.append(context.getString(R.string.preview_placement_resume_position))
                majFort(corps, "", listOf(resume.position.toString()))
                corps.append(context.getString(R.string.preview_placement_resume_sur))
                corps.append(resume.total.toString())
                liaison.resumePlacement.text = corps
            }
        }
        liaison.boutonPlacer.isEnabled = true
    }

    /** Les vues du dernier affichage (résumé recalculé à chaque re-marche). */
    private var dernieresVues: List<InfoVueRendu> = emptyList()

    /**
     * Pose le morceau FORT (ResumeFort — miroir `<b>` de `.res`) : à la fin
     * de [corps] quand [valeurs] est vide, ou aux occurrences des
     * [valeurs] dans le [texte] ajouté (les gabarits de la référence
     * n'intercalent jamais deux fois la même valeur).
     */
    private fun majFort(
        corps: SpannableStringBuilder,
        texte: String,
        valeurs: List<String> = emptyList(),
    ) {
        val avecTexte = valeurs.isEmpty()
        if (avecTexte) {
            corps.append(texte)
        } else {
            corps.append(texte)
            for (valeur in valeurs.filter { it.isNotEmpty() }) {
                val debut = corps.indexOf(valeur)
                if (debut >= 0) {
                    corps.setSpan(
                        TextAppearanceSpan(context, R.style.TextAppearance_CodeIDE_Apercu_ResumeFort),
                        debut,
                        debut + valeur.length,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            return
        }
        if (avecTexte && texte.isNotEmpty()) {
            val debut = corps.length - texte.length
            corps.setSpan(
                TextAppearanceSpan(context, R.style.TextAppearance_CodeIDE_Apercu_ResumeFort),
                debut,
                corps.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /**
     * Le rôle RADIO d'un bouton (D9, miroir `role="radio"` +
     * `aria-checked`) : cochable et coché pour les lecteurs d'écran —
     * les boutons restent des `TextView` à sélecteur d'état.
     *
     * Exemption de dépréciation ciblée : `AccessibilityNodeInfo.isChecked`
     * (déprécié API 34 au profit des actions d'accessibilité) reste LA
     * voie du rôle coché pour les niveaux 26-33 que l'aperçu couvre.
     */
    @Suppress("DEPRECATION")
    private fun poserRadio(
        bouton: TextView,
        coche: Boolean,
    ) {
        bouton.isClickable = true
        bouton.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    vue: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(vue, info)
                    info.isCheckable = true
                    info.isChecked = coche
                }
            }
    }

    /**
     * Les BORNES de hauteur (§ 7, miroir `max-height`) : 340 dp max pour
     * un parent à barre ; `min(580 dp, hauteur disponible − 134 dp)` quand
     * la grille ou la rangée de marges est visible (« tall ») — et le
     * mini-arbre passe alors à 100 dp. Le code les impose après la passe
     * de mesure (le ScrollView n'a pas de maxHeight).
     */
    private fun bornerHauteurs(tall: Boolean) {
        liaison.defileArbrePlacement.post {
            val miniMax =
                resources.getDimensionPixelSize(
                    if (tall) R.dimen.apercu_mini_hauteur_max_haut else R.dimen.apercu_mini_hauteur_max,
                )
            val hauteurMini = liaison.lignesArbrePlacement.measuredHeight
            if (hauteurMini > 0) {
                liaison.defileArbrePlacement.layoutParams.height = min(miniMax, hauteurMini)
            }
            val hauteurDisponible = (parent as? ViewGroup)?.height ?: 0
            val coqueMax =
                if (tall && hauteurDisponible > 0) {
                    min(
                        resources.getDimensionPixelSize(R.dimen.apercu_placement_haut_max),
                        hauteurDisponible - RETRAIT_HAUTEUR_DP,
                    )
                } else {
                    resources.getDimensionPixelSize(R.dimen.apercu_placement_hauteur_max)
                }
            val hauteurCoque = measuredHeight
            if (hauteurCoque > 0 && hauteurCoque > coqueMax) {
                layoutParams.height = coqueMax
            }
        }
    }

    private companion object {
        /** Retrait de sécurité de la coque « tall » (miroir `hauteur disponible − 134 dp`, § 7). */
        private const val RETRAIT_HAUTEUR_DP: Int = 134

        /** Les marges proposées par le popup (D6 confirmée — miroir `MGS`). */
        private val MARGES_POSSIBLES: List<Int> = listOf(0, 8, 16, 24)

        /** Les indices de ligne/colonne de la grille (0, 1, 2). */
        private val INDICES_GRILLE: IntRange = 0..2

        /** Le côté d'une grille 3 × 3. */
        private const val TAILLE_GRILLE: Int = 3

        /** Les glyphes des neuf cellules (miroir `G9GLY` — caractères, non traduisibles). */
        private val GLYPHES_CELLULES: List<String> =
            listOf("↖", "↑", "↗", "←", "●", "→", "↙", "↓", "↘")

        /** Les libellés localisés des neuf cellules (strings_parents — preview_cellule_*). */
        private val LIBELLES_CELLULES: List<Int> =
            listOf(
                R.string.preview_cellule_haut_debut,
                R.string.preview_cellule_haut_centre,
                R.string.preview_cellule_haut_fin,
                R.string.preview_cellule_centre_debut,
                R.string.preview_cellule_centre,
                R.string.preview_cellule_centre_fin,
                R.string.preview_cellule_bas_debut,
                R.string.preview_cellule_bas_centre,
                R.string.preview_cellule_bas_fin,
            )
    }
}
