package jo.codeide.feature.editor

import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import jo.codeide.core.ui.IconesFichiers
import jo.codeide.feature.editor.databinding.LigneNoeudArborescenceBinding

/**
 * Adaptateur de l'arborescence v2 (étape 31, § 6-7 et § 11,
 * `docs/EXPLORATEUR_V2.md`) : liste aplatie des nœuds visibles avec
 * guides dessinés ([VueGuides]), chevron des dossiers (rotation 90°
 * animée 0,16 s), point d'état des fichiers ([VuePointEtat] — 4 états,
 * précédence § 7), icônes par type, compteur d'enfants, badge de chemin
 * de la racine, ligne coupée (50 % + nom barré), flash de mutation
 * (1,1 s) et **éditeur inline** (création sous le dossier cible,
 * renommage à la place de la ligne — § 11).
 *
 * Mutations par nœud : le `ListAdapter` re-lie uniquement les lignes
 * changées (DiffUtil) — critère d'acceptation § 20.7, aucun re-chargement
 * d'arbre entier.
 *
 * Interactions (§ 17) : tap = sélection (+ dépli/repli ou ouverture) ;
 * appui long = popover d'actions **ancré à la position exacte du doigt**
 * — le contact est capturé au `ACTION_DOWN` et retransmis au fragment
 * en coordonnées écran. Enter = valider l'édition, Échap = annuler.
 *
 * @property surClic tap d'une ligne de nœud.
 * @property surClicLong appui long : (nœud, x écran, y écran).
 * @property surValiderEdition validation de l'éditeur inline (nom saisi).
 * @property surAnnulerEdition abandon de l'édition (Échap, annuler).
 */
@Suppress("TooManyFunctions") // Sections de liaison de la ligne v2 (§ 6-7), une par groupe de vues.
internal class ExplorateurAdapter(
    private val surClic: (NoeudExplorateur) -> Unit,
    private val surClicLong: (NoeudExplorateur, Int, Int) -> Boolean,
    private val surValiderEdition: (String) -> Unit,
    private val surAnnulerEdition: () -> Unit,
) : ListAdapter<ExplorateurAdapter.Element, ExplorateurAdapter.VueNoeud>(Differences) {
    /** Élément de liste : un nœud, ou l'éditeur inline courant (§ 11). */
    internal sealed interface Element {
        /** Ligne de nœud standard. */
        data class Noeud(
            val noeud: NoeudExplorateur,
        ) : Element

        /** Éditeur inline : création ou renommage (§ 11). */
        data class Editeur(
            val edition: EditionInline,
            val profondeur: Int,
            val dernierEnfant: Boolean,
            val masqueAncetres: Int,
        ) : Element
    }

    /** Densité d'écran, lue au premier gonflage. */
    private var dp = 0f

    /** Chemin affiché dans le badge de la racine (posé par le fragment). */
    internal var cheminRacine: String = ""

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): VueNoeud {
        val liaison =
            LigneNoeudArborescenceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
        if (dp == 0f) dp = liaison.root.resources.displayMetrics.density
        return VueNoeud(liaison)
    }

    override fun onBindViewHolder(
        holder: VueNoeud,
        position: Int,
    ) {
        when (val element = getItem(position)) {
            is Element.Noeud -> lierNoeud(holder, element.noeud)
            is Element.Editeur -> lierEditeur(holder, element)
        }
    }

    // ------------------------------------------------------------------
    // Ligne de nœud standard
    // ------------------------------------------------------------------

    private fun lierNoeud(
        holder: VueNoeud,
        noeud: NoeudExplorateur,
    ) {
        val liaison = holder.liaison
        val racine = liaison.racineLigne

        // Hauteur : 38 dp racine, 34 dp sinon (§ 6.1).
        racine.layoutParams.height = ((if (noeud.estRacine) HAUTEUR_RACINE_DP else HAUTEUR_LIGNE_DP) * dp).toInt()

        // Guides + indentation du contenu (§ 6.2).
        liaison.guidesLigne.programmer(noeud.profondeur, noeud.dernierEnfant, noeud.masqueAncetresDerniers)
        liaison.contenuLigne.setPadding((INDENTATION * noeud.profondeur * dp).toInt(), 0, 0, 0)

        lierFondEtCoupe(racine, liaison, noeud)
        lierChevronOuPoint(liaison, noeud)
        lierIconeEtNom(liaison, noeud)
        lierCompteursEtEtats(liaison, noeud)

        // Éditeur inline masqué sur une ligne standard.
        liaison.editionNoeud.isVisible = false
        liaison.contenuLigne.isVisible = true

        // Description accessible : nom, type, état de pli.
        racine.contentDescription = decrire(liaison, noeud)

        // Interactions : tap = sélection (+ pli/ouverture, § 17) ;
        // appui long = popover ancré au contact exact (capture du
        // ACTION_DOWN, coordonnées écran retransmises).
        racine.setOnClickListener { surClic(noeud) }
        // Exemption ClickableViewAccessibility : simple capture du contact
        // (retour false — le clic lui-même vit dans le listener ci-dessus).
        @Suppress("ClickableViewAccessibility")
        racine.setOnTouchListener { vue, evenement ->
            if (evenement.actionMasked == MotionEvent.ACTION_DOWN) {
                vue.tag = floatArrayOf(evenement.rawX, evenement.rawY)
            }
            false
        }
        racine.setOnLongClickListener { vue ->
            val contact = vue.tag as? FloatArray
            val x = contact?.get(0)?.toInt() ?: centreX(vue)
            val y = contact?.get(1)?.toInt() ?: centreY(vue)
            surClicLong(noeud, x, y)
        }
    }

    /** Fond (sélection/flash § 6.1) et rendu de la coupe (50 %, barré). */
    private fun lierFondEtCoupe(
        racine: View,
        liaison: LigneNoeudArborescenceBinding,
        noeud: NoeudExplorateur,
    ) {
        val contexte = racine.context
        racine.background = null
        if (noeud.selectionne || noeud.flasher) {
            racine.background = ContextCompat.getDrawable(contexte, R.drawable.fond_ligne_selectionnee)
        }
        liaison.nomNoeud.paint.isStrikeThruText = noeud.coupe
        val alphaLigne = if (noeud.coupe) ALPHA_COUPE else 1f
        liaison.nomNoeud.alpha = alphaLigne
        liaison.iconeNoeud.alpha = alphaLigne
    }

    /** Chevron des dossiers (rotation 0,16 s) ou point d'état (§ 7). */
    private fun lierChevronOuPoint(
        liaison: LigneNoeudArborescenceBinding,
        noeud: NoeudExplorateur,
    ) {
        val contexte = liaison.root.context
        liaison.chevronNoeud.isVisible = noeud.estDossier
        liaison.pointEtatNoeud.isVisible = !noeud.estDossier
        if (noeud.estDossier) {
            val rotationCible = if (noeud.deplie) ROTATION_DEPLIE else 0f
            if (liaison.chevronNoeud.rotation != rotationCible) {
                liaison.chevronNoeud
                    .animate()
                    .rotation(rotationCible)
                    .setDuration(DUREE_ROTATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            liaison.chevronNoeud.setColorFilter(
                ContextCompat.getColor(
                    contexte,
                    if (noeud.deplie) {
                        jo.codeide.core.ui.R.color.codeide_explorateur_accent
                    } else {
                        jo.codeide.core.ui.R.color.codeide_explorateur_texte_3
                    },
                ),
            )
        } else {
            liaison.pointEtatNoeud.programmer(pointDEtatDe(noeud))
        }
    }

    /** État du point d'un fichier (précédence § 7). */
    private fun pointDEtatDe(noeud: NoeudExplorateur): VuePointEtat.EtatPoint =
        when {
            noeud.selectionne && noeud.ongletActif -> VuePointEtat.EtatPoint.SELECTION_ACTIF
            noeud.selectionne -> VuePointEtat.EtatPoint.SELECTIONNE
            noeud.ongletActif -> VuePointEtat.EtatPoint.ACTIF
            noeud.ongletOuvert -> VuePointEtat.EtatPoint.OUVERT
            else -> VuePointEtat.EtatPoint.DEFAUT
        }

    /** Icône (§ 8) et nom (teintes privées/violettes, racine localisée). */
    private fun lierIconeEtNom(
        liaison: LigneNoeudArborescenceBinding,
        noeud: NoeudExplorateur,
    ) {
        val contexte = liaison.root.context
        liaison.iconeNoeud.setImageResource(
            if (noeud.estDossier) IconesFichiers.pourDossier(noeud.prive) else IconesFichiers.pourNom(noeud.nom),
        )
        liaison.iconeNoeud.setColorFilter(ContextCompat.getColor(contexte, teinteDossier(noeud)))

        // La racine privée porte son libellé localisé (« Stockage
        // privé », § 9) — le ViewModel ne détient pas de ressources.
        liaison.nomNoeud.text =
            if (noeud.estRacine && noeud.prive) {
                contexte.getString(R.string.explorateur_racine_privee)
            } else {
                noeud.nom
            }
        liaison.nomNoeud.setTextColor(
            ContextCompat.getColor(
                contexte,
                when {
                    noeud.prive -> jo.codeide.core.ui.R.color.codeide_explorateur_nom_prive
                    noeud.estDossier -> jo.codeide.core.ui.R.color.codeide_explorateur_nom_dossier
                    else -> jo.codeide.core.ui.R.color.codeide_explorateur_nom_fichier
                },
            ),
        )
    }

    /** Compteur d'enfants, badge de racine, chargement/erreur. */
    private fun lierCompteursEtEtats(
        liaison: LigneNoeudArborescenceBinding,
        noeud: NoeudExplorateur,
    ) {
        liaison.compteNoeud.isVisible = noeud.estDossier && !noeud.estRacine && noeud.nbEnfants >= 0
        if (liaison.compteNoeud.isVisible) {
            liaison.compteNoeud.text = String.format(java.util.Locale.getDefault(), "%d", noeud.nbEnfants)
        }

        liaison.badgeRacine.isVisible = noeud.estRacine
        if (noeud.estRacine) liaison.badgeRacine.text = cheminRacine

        liaison.chargementNoeud.isVisible = noeud.chargementEnfants
        liaison.erreurNoeud.isVisible = noeud.erreurChargement
    }

    /** Abcisse écran de repli (centre de la ligne). */
    private fun centreX(vue: View): Int {
        val position = IntArray(2)
        vue.getLocationOnScreen(position)
        return position[0] + vue.width / 2
    }

    /** Ordonnée écran de repli (centre de la ligne). */
    private fun centreY(vue: View): Int {
        val position = IntArray(2)
        vue.getLocationOnScreen(position)
        return position[1] + vue.height / 2
    }

    /** Teinte du dossier selon le contexte (§ 8). */
    private fun teinteDossier(noeud: NoeudExplorateur): Int =
        when {
            noeud.estRacine && noeud.prive -> jo.codeide.core.ui.R.color.codeide_explorateur_dossier_racine_prive
            noeud.estRacine -> jo.codeide.core.ui.R.color.codeide_explorateur_dossier_racine_projet
            noeud.prive -> jo.codeide.core.ui.R.color.codeide_explorateur_dossier_prive
            else -> jo.codeide.core.ui.R.color.codeide_explorateur_dossier
        }

    // ------------------------------------------------------------------
    // Éditeur inline (§ 11)
    // ------------------------------------------------------------------

    private fun lierEditeur(
        holder: VueNoeud,
        element: Element.Editeur,
    ) {
        val liaison = holder.liaison
        val edition = element.edition
        val racine = liaison.racineLigne
        racine.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        racine.background = null
        racine.contentDescription = liaison.root.context.getString(R.string.edition_champ_cd)

        liaison.guidesLigne.programmer(element.profondeur, element.dernierEnfant, element.masqueAncetres)
        liaison.contenuLigne.isVisible = false
        liaison.editionNoeud.isVisible = true
        liaison.editionNoeud.setPadding((INDENTATION * element.profondeur * dp).toInt(), 0, 0, 0)

        // Icône du type (§ 11 : repère selon le type).
        liaison.iconeEdition.setImageResource(
            if (edition.estDossier) {
                IconesFichiers.pourDossier(false)
            } else {
                IconesFichiers.pourNom(edition.nomInitial.ifBlank { "exemple.kt" })
            },
        )

        val champ = liaison.champEdition
        champ.setRawInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        champ.imeOptions = EditorInfo.IME_ACTION_DONE
        champ.setText(edition.nomInitial)
        champ.hint =
            liaison.root.context.getString(
                if (edition.estDossier) R.string.edition_indication_dossier else R.string.edition_indication_fichier,
            )
        champ.setOnEditorActionListener { _, actionId, evenement ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (evenement?.action == KeyEvent.ACTION_DOWN && evenement.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                surValiderEdition(champ.text.toString())
                true
            } else {
                false
            }
        }
        champ.setOnKeyListener { _, code, evenement ->
            if (code == KeyEvent.KEYCODE_ESCAPE && evenement.action == KeyEvent.ACTION_DOWN) {
                surAnnulerEdition()
                true
            } else {
                false
            }
        }
        liaison.boutonValiderEdition.setOnClickListener { surValiderEdition(champ.text.toString()) }
        liaison.boutonAnnulerEdition.setOnClickListener { surAnnulerEdition() }

        // Renommage : valeur pré-remplie sélectionnée (§ 11) ; création :
        // focus direct sur le champ.
        champ.requestFocus()
        champ.post { champ.selectAll() }
    }

    /** Description accessible complète de la ligne (nom + type + état). */
    private fun decrire(
        liaison: LigneNoeudArborescenceBinding,
        noeud: NoeudExplorateur,
    ): String {
        val contexte = liaison.root.context
        return when {
            noeud.erreurChargement -> {
                contexte.getString(R.string.editor_noeud_erreur_cd, noeud.nom)
            }

            noeud.estDossier -> {
                contexte.getString(
                    R.string.editor_noeud_dossier_cd,
                    noeud.nom,
                    noeud.profondeur,
                    contexte.getString(
                        if (noeud.deplie) R.string.editor_noeud_deplie else R.string.editor_noeud_replie,
                    ),
                )
            }

            else -> {
                contexte.getString(R.string.editor_noeud_fichier_cd, noeud.nom, noeud.profondeur)
            }
        }
    }

    /** Vue d'une ligne : la liaison seule, aucune logique. */
    internal class VueNoeud(
        val liaison: LigneNoeudArborescenceBinding,
    ) : RecyclerView.ViewHolder(liaison.root)

    private object Differences : DiffUtil.ItemCallback<Element>() {
        override fun areItemsTheSame(
            ancien: Element,
            nouveau: Element,
        ): Boolean =
            when {
                ancien is Element.Noeud && nouveau is Element.Noeud -> {
                    ancien.noeud.uri == nouveau.noeud.uri
                }

                ancien is Element.Editeur && nouveau is Element.Editeur -> {
                    ancien.edition.renommage == nouveau.edition.renommage &&
                        ancien.edition.uriParent == nouveau.edition.uriParent
                }

                else -> {
                    false
                }
            }

        override fun areContentsTheSame(
            ancien: Element,
            nouveau: Element,
        ): Boolean = ancien == nouveau
    }

    private companion object {
        /** Indentation par niveau (22 dp, § 6.2). */
        const val INDENTATION = 22f

        /** Hauteur d'une ligne standard (§ 6.1 : 34 dp). */
        const val HAUTEUR_LIGNE_DP = 34

        /** Hauteur de la ligne racine (§ 6.1 : 38 dp). */
        const val HAUTEUR_RACINE_DP = 38

        /** Opacité d'une ligne coupée (§ 6.1 : 50 %). */
        const val ALPHA_COUPE = 0.5f

        /** Rotation du chevron déplié (§ 6.1 : 90°). */
        const val ROTATION_DEPLIE = 90f

        /** Durée de la rotation du chevron (§ 16 : 0,16 s). */
        const val DUREE_ROTATION_MS = 160L
    }
}
