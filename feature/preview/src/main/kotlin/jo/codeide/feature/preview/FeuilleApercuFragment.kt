package jo.codeide.feature.preview

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.core.ui.AppNavigator
import jo.codeide.feature.preview.databinding.FragmentFeuilleApercuBinding
import jo.codeide.preview.xml.CalquesApercu
import jo.codeide.preview.xml.EtatPreviewXml
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProblemesPreview
import jo.codeide.preview.xml.ProblemesPreview.GenreProbleme
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.google.android.material.R as RMaterial

/**
 * FEUILLE inférieure de l'écran d'aperçu XML (PE6d, maquette
 * `docs/mockups/apercu-xml.html` § « Feuille inférieure : arbre,
 * attributs, problèmes, source ») : quatre onglets ALIMENTÉS PAR LE
 * MÊME ÉTAT que la vue (« alimentée par le même état que la vue ») —
 * le [MoteurPreviewXml] du [PreviewViewModel] partagé
 * (`activityViewModels`) et la sélection S'ALIGNENT DEUX VOIES (une
 * rangée d'arbre touchée, une ligne de source touchée →
 * [PreviewViewModel.selectionner] → la vue se surligne) : miroir
 * `renderTree` / `renderAttrs` / `renderProbs` / `renderSrc`.
 *
 * - **Arbre** : le pré-ordre de l'arbre rendu, indentation par
 *   profondeur, badge « ! n » (problèmes) sinon dimensions en dp
 *   ([FeuilleApercu.entreesArbre]) ;
 * - **Attributs** : LECTURE SEULE en PE6d (l'édition d'attributs est
 *   PE7d) — groupes d'attributs mesurés ([FeuilleApercu.groupesAttributs]) ;
 * - **Problèmes** : les règles pures ([ProblemesPreview.evaluer],
 *   évaluées à chaque rendu valide), pastille ambre/rouge, badge
 *   compteur sur l'onglet (miroir `nProbs`) ;
 * - **Source** : le TEXTE VIVANT de la session, lignes numérotées,
 *   surlignage des lignes du span de la vue sélectionnée (miroir
 *   `renderSrc`), clic sur une ligne → la vue propriétaire (miroir
 *   `ownerOf`, [FeuilleApercu.vueDeLigne]), et le bouton « Ouvrir dans
 *   l'éditeur ↗ » (la sélection est DÉJÀ posée dans la session —
 *   l'éditeur la retrouve à son retour).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque onglet
 * a SA fonction de mise en forme (miroir render* de la maquette, même
 * patron que les menus de [PreviewActivity]) ; MagicNumber — la mise en
 * forme des volets se fait EN CODE, les dimensions dp y sont littérales
 * (patron render*).
 */
@Suppress("TooManyFunctions", "MagicNumber")
@AndroidEntryPoint
public class FeuilleApercuFragment : Fragment() {
    /** Navigation : « Ouvrir dans l'éditeur ↗ » (l'éditeur est en dessous dans la pile). */
    @Inject
    lateinit var navigateur: AppNavigator

    private val viewModel: PreviewViewModel by activityViewModels()

    private lateinit var liaison: FragmentFeuilleApercuBinding

    /** L'onglet affiché (miroir `S.tab`). */
    private var ongletActif: Int = ONGLET_ARBRE

    /** Le dernier rendu valide (les volets s'en alimentent). */
    private var renduCourant: EtatPreviewXml.RenduValide? = null

    /** La sélection partagée (indice dans l'arbre, -1 aucune). */
    private var selectionCourante: Int = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        conteneur: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaison = FragmentFeuilleApercuBinding.inflate(inflater, conteneur, false)
        construireOnglets()
        liaison.grabFeuille.setOnClickListener { basculerFeuille() }
        liaison.boutonOuvrirEditeur.setOnClickListener { navigateur.goBack() }
        return liaison.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val comportement = BottomSheetBehavior.from(liaison.root)
        comportement.peekHeight = dp(PEEK_DP)
        // Un onglet touché DÉPLOYE la feuille (miroir maquette : « if
        // (!S.open) S.open = true »).
        liaison.ongletsFeuille.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(onglet: TabLayout.Tab) {
                    ongletActif = onglet.position
                    deploierFeuille()
                    majVolets()
                }

                override fun onTabUnselected(onglet: TabLayout.Tab) = Unit

                override fun onTabReselected(onglet: TabLayout.Tab) = Unit
            },
        )
        // La collecte vit sur le viewLifecycleOwner (la feuille se
        // rebranche à chaque vue recréée — patron repeatOnLifecycle).
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.indiceSelectionne.collect { indice ->
                        selectionCourante = indice
                        majFilAriane()
                        majVolets()
                    }
                }
                launch {
                    viewModel.moteur.etat.collect { etat ->
                        if (etat is EtatPreviewXml.RenduValide) renduCourant = etat
                        majFilAriane()
                        majVolets()
                    }
                }
            }
        }
    }

    /** La feuille déployée ↔ repliée (miroir `grab.onclick : S.open = !S.open`). */
    private fun basculerFeuille() {
        val comportement = BottomSheetBehavior.from(liaison.root)
        if (comportement.state == BottomSheetBehavior.STATE_EXPANDED) {
            comportement.state = BottomSheetBehavior.STATE_COLLAPSED
        } else {
            comportement.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun deploierFeuille() {
        val comportement = BottomSheetBehavior.from(liaison.root)
        if (comportement.state != BottomSheetBehavior.STATE_EXPANDED) {
            comportement.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /** Les quatre onglets (miroir `.tabs` de la maquette). */
    private fun construireOnglets() {
        val titres =
            listOf(
                R.string.preview_feuille_onglet_arbre,
                R.string.preview_feuille_onglet_attributs,
                R.string.preview_feuille_onglet_problemes,
                R.string.preview_feuille_onglet_source,
            )
        titres.forEachIndexed { indice, titre ->
            val onglet = liaison.ongletsFeuille.newTab()
            onglet.text = getString(titre)
            liaison.ongletsFeuille.addTab(onglet, indice, indice == ONGLET_ARBRE)
        }
    }

    /** Le fil d'Ariane de la poignée (miroir `renderCrumb`). */
    private fun majFilAriane() {
        val vues = renduCourant?.vues
        val vue = vues?.getOrNull(selectionCourante)
        liaison.filAriane.text =
            when {
                vue == null -> {
                    getString(R.string.preview_feuille_sans_selection)
                }

                vue.indiceParent < 0 || selectionCourante == 0 -> {
                    libelle(vue)
                }

                else -> {
                    val parent = vues.getOrNull(vue.indiceParent)
                    getString(
                        R.string.preview_feuille_croisement,
                        parent?.let { libelle(it) } ?: "",
                        libelle(vue),
                    )
                }
            }
    }

    /** Le libellé d'une vue (« Classe · @+id/x », sans id : la classe seule). */
    private fun libelle(vue: InfoVueRendu): String {
        val idNom = vue.idRes.substringAfterLast('/')
        return if (idNom.isEmpty()) vue.classe else getString(R.string.preview_feuille_libelle, vue.classe, idNom)
    }

    /** Re-marche le volet ACTIF (les autres attendent leur onglet). */
    private fun majVolets() {
        when (ongletActif) {
            ONGLET_ARBRE -> majArbre()
            ONGLET_ATTRIBUTS -> majAttributs()
            ONGLET_PROBLEMES -> majProblemes()
            ONGLET_SOURCE -> majSource()
        }
    }

    /** L'onglet Arbre (miroir `renderTree`). */
    private fun majArbre() {
        montrerVolet(liaison.voletArbre)
        val vues = renduCourant?.vues
        liaison.lignesArbre.removeAllViews()
        if (vues.isNullOrEmpty()) {
            liaison.lignesArbre.addView(texteSimple(getString(R.string.preview_feuille_vide)))
            return
        }
        val densite = viewModel.moteur.profil.densiteDpi
        val problemes = ProblemesPreview.evaluer(vues, densite)
        majBadgeProblemes(problemes)
        for (entree in FeuilleApercu.entreesArbre(vues, problemes, densite)) {
            val rangee =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(entree.indentation), dp(8), dp(8), dp(8))
                    background = fondReactif()
                    val mono = pillMonogramme(entree.monogramme)
                    addView(mono, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
                    val classeVue = texteLibelle(entree.classe, styleMoyen, couleur(couleurContenu))
                    addView(classeVue, LinearLayout.LayoutParams(WRAP, WRAP))
                    if (entree.idNom.isNotEmpty()) {
                        val idVue = texteLibelle("@+id/${entree.idNom}", stylePetit, couleur(couleurNeutre))
                        addView(idVue, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
                    }
                    val badge = TextView(requireContext())
                    badge.text = entree.badge
                    badge.setTextAppearance(stylePetit)
                    badge.setTextColor(
                        ContextCompat.getColor(
                            requireContext(),
                            if (entree.aProblemes) R.color.preview_probleme_erreur else R.color.preview_probleme_neutre,
                        ),
                    )
                    val paramsBadge = LinearLayout.LayoutParams(0, WRAP, 1f)
                    paramsBadge.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    addView(badge, paramsBadge)
                    setOnClickListener { viewModel.selectionner(entree.indice) }
                }
            liaison.lignesArbre.addView(rangee)
        }
    }

    /** L'onglet Attributs (miroir `renderAttrs` ; lecture seule — PE7d). */
    private fun majAttributs() {
        montrerVolet(liaison.voletAttributs)
        val vue = renduCourant?.vues?.getOrNull(selectionCourante)
        liaison.groupesAttributs.removeAllViews()
        if (vue == null) {
            liaison.groupesAttributs.addView(texteSimple(getString(R.string.preview_feuille_vide)))
            return
        }
        for (groupe in FeuilleApercu.groupesAttributs(vue, viewModel.moteur.profil.densiteDpi)) {
            val titre =
                texteLibelle(groupe.titre, styleTitre, couleur(couleurPrimaire)).apply {
                    setPadding(dp(16), dp(12), dp(8), dp(4))
                }
            liaison.groupesAttributs.addView(titre)
            for (entree in groupe.entrees) {
                val rangee =
                    LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(16), dp(2), dp(8), dp(2))
                        val libelle = texteLibelle(entree.libelle, stylePetit, couleur(couleurNeutre))
                        addView(libelle, LinearLayout.LayoutParams(dp(160), WRAP))
                        addView(texteLibelle(entree.valeur, styleCorps, couleur(couleurContenu)))
                    }
                liaison.groupesAttributs.addView(rangee)
            }
        }
    }

    /** L'onglet Problèmes (miroir `renderProbs`, badge compteur sur l'onglet). */
    private fun majProblemes() {
        montrerVolet(liaison.voletProblemes)
        val vues = renduCourant?.vues
        liaison.lignesProblemes.removeAllViews()
        if (vues == null) return
        val problemes = ProblemesPreview.evaluer(vues, viewModel.moteur.profil.densiteDpi)
        majBadgeProblemes(problemes)
        if (problemes.isEmpty()) {
            liaison.lignesProblemes.addView(texteSimple(getString(R.string.preview_feuille_aucun_probleme)))
            return
        }
        for (entree in FeuilleApercu.entreesProblemes(problemes)) {
            val vueFautive = vues.getOrNull(entree.indiceVue)
            val couleurPastille =
                ContextCompat.getColor(
                    requireContext(),
                    if (entree.erreur) R.color.preview_probleme_erreur else R.color.preview_probleme_avertissement,
                )
            val pastille =
                View(requireContext()).apply {
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(couleurPastille)
                        }
                    val paramsPastille = LinearLayout.LayoutParams(dp(10), dp(10))
                    paramsPastille.marginEnd = dp(8)
                    layoutParams = paramsPastille
                }
            val titre = texteLibelle(titreProbleme(entree.genre, vueFautive), styleMoyen, couleur(couleurContenu))
            val ligne =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(pastille)
                    addView(titre)
                }
            val detailLibelle =
                (vueFautive?.let { libelle(it) } ?: "") + "\n" + messageProbleme(entree.genre, vueFautive)
            val detail = texteLibelle(detailLibelle, stylePetit, couleur(couleurNeutre))
            detail.setPadding(dp(18), dp(2), 0, 0)
            val rangee =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(8), dp(8))
                    background = fondReactif()
                    addView(ligne)
                    addView(detail)
                    setOnClickListener { viewModel.selectionner(entree.indiceVue) }
                }
            liaison.lignesProblemes.addView(rangee)
        }
    }

    /** L'onglet Source (miroir `renderSrc` : lignes numérotées, surlignage du span). */
    private fun majSource() {
        montrerVolet(liaison.voletSource)
        val texte = viewModel.texteCourant()
        val vues = renduCourant?.vues
        val vue = vues?.getOrNull(selectionCourante)
        val plage = FeuilleApercu.plageSource(texte, vue)
        liaison.plageSource.text =
            if (plage != null) {
                getString(R.string.preview_feuille_source_lignes, plage.first, plage.last)
            } else {
                getString(R.string.preview_feuille_source_entier)
            }
        liaison.lignesSource.removeAllViews()
        val lignes = texte.split('\n')
        lignes.forEachIndexed { indiceLigne, contenuLigne ->
            val numero = indiceLigne + 1
            val surlignee = plage != null && numero >= plage.first && numero <= plage.last
            val rangee =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val fond =
                        if (surlignee) {
                            couleur(couleurFondSecondaire)
                        } else {
                            ContextCompat.getColor(requireContext(), android.R.color.transparent)
                        }
                    setBackgroundColor(fond)
                    setOnClickListener {
                        val indice = FeuilleApercu.vueDeLigne(vues ?: emptyList(), texte, numero)
                        if (indice >= 0) viewModel.selectionner(indice)
                    }
                }
            val numeroVue = TextView(requireContext())
            numeroVue.text = getString(R.string.preview_feuille_ligne_numero, numero)
            numeroVue.typeface = Typeface.MONOSPACE
            numeroVue.textSize = 10f
            numeroVue.gravity = Gravity.END
            numeroVue.setTextColor(couleur(couleurNeutre))
            rangee.addView(numeroVue, LinearLayout.LayoutParams(dp(36), WRAP))
            val texteVue = TextView(requireContext())
            texteVue.text = if (contenuLigne.isEmpty()) " " else contenuLigne
            texteVue.typeface = Typeface.MONOSPACE
            texteVue.textSize = 11f
            texteVue.setTextColor(couleur(couleurContenu))
            rangee.addView(texteVue, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            liaison.lignesSource.addView(rangee)
        }
        // Défilement vers la plage (miroir `pane.scrollTop = …offsetTop - 70`).
        if (plage != null) {
            liaison.defileSource.post {
                val hauteurLigne =
                    liaison.lignesSource.height
                        .coerceAtLeast(1)
                        .toFloat() / lignes.size.coerceAtLeast(1)
                liaison.defileSource.scrollTo(0, (((plage.first - 1) * hauteurLigne - dp(70)).toInt()).coerceAtLeast(0))
            }
        }
    }

    /** Le titre localisé d'un problème (miroir `p.title` de `computeProblems`). */
    private fun titreProbleme(
        genre: GenreProbleme,
        vueFautive: InfoVueRendu?,
    ): String =
        when (genre) {
            GenreProbleme.CLASSE_ABSENTE -> {
                getString(R.string.preview_probleme_classe_absente_titre, vueFautive?.classe ?: "")
            }

            GenreProbleme.CIBLE_TACTILE -> {
                getString(R.string.preview_probleme_cible_tactile_titre)
            }
        }

    /** Le message localisé d'un problème (miroir `p.msg`). */
    private fun messageProbleme(
        genre: GenreProbleme,
        vueFautive: InfoVueRendu?,
    ): String =
        when (genre) {
            GenreProbleme.CLASSE_ABSENTE -> {
                getString(R.string.preview_probleme_classe_absente_message)
            }

            GenreProbleme.CIBLE_TACTILE -> {
                val densite = viewModel.moteur.profil.densiteDpi
                if (vueFautive != null && densite > 0) {
                    getString(
                        R.string.preview_probleme_cible_tactile_message,
                        CalquesApercu.pxVersDp(vueFautive.largeurPx, densite),
                        CalquesApercu.pxVersDp(vueFautive.hauteurPx, densite),
                    )
                } else {
                    getString(R.string.preview_probleme_cible_tactile_message_sans_mesure)
                }
            }
        }

    /** Le badge compteur de l'onglet Problèmes (miroir `nProbs`, caché à zéro). */
    private fun majBadgeProblemes(problemes: List<ProblemesPreview.ProblemeApercu>) {
        val onglet = liaison.ongletsFeuille.getTabAt(ONGLET_PROBLEMES) ?: return
        val badge = onglet.orCreateBadge
        badge.isVisible = problemes.isNotEmpty()
        badge.number = problemes.size
    }

    private fun montrerVolet(volet: View) {
        listOf(liaison.voletArbre, liaison.voletAttributs, liaison.voletProblemes, liaison.voletSource).forEach {
            it.visibility = if (it.id == volet.id) View.VISIBLE else View.GONE
        }
    }

    /** Un TextView simple de volet vide. */
    private fun texteSimple(texte: String): TextView =
        TextView(requireContext()).apply {
            this.text = texte
            setTextAppearance(styleMoyen)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

    /** Un TextView stylé (apparence Material 3 + couleur du thème). */
    private fun texteLibelle(
        texte: String,
        apparence: Int,
        couleurValeur: Int,
    ): TextView =
        TextView(requireContext()).apply {
            this.text = texte
            setTextAppearance(apparence)
            setTextColor(couleurValeur)
        }

    /** Le monogramme de classe (miroir `k` : initiale posée sur une pastille). */
    private fun pillMonogramme(mono: String): TextView =
        TextView(requireContext()).apply {
            text = mono
            gravity = Gravity.CENTER
            setTextAppearance(styleMoyen)
            setTextColor(couleur(couleurPrimaire))
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(couleur(couleurFondPrimaire))
                }
        }

    /** Le fond réactif des rangées cliquables (ondulation du thème). */
    private fun fondReactif(): Drawable? {
        val valeur = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.selectableItemBackground, valeur, true)
        return ContextCompat.getDrawable(requireContext(), valeur.resourceId)
    }

    // ── Aides de style (lecture des attributs du thème — une seule voie) ──

    private fun dp(valeur: Int): Int = (valeur * resources.displayMetrics.density).toInt()

    private fun couleur(attribut: Int): Int {
        val valeur = TypedValue()
        requireContext().theme.resolveAttribute(attribut, valeur, true)
        return valeur.data
    }

    private companion object {
        // ── Styles et couleurs (noms courts : les appels restent sous 120) ──
        private val styleMoyen: Int = RMaterial.style.TextAppearance_Material3_LabelMedium
        private val stylePetit: Int = RMaterial.style.TextAppearance_Material3_LabelSmall
        private val styleTitre: Int = RMaterial.style.TextAppearance_Material3_TitleSmall
        private val styleCorps: Int = RMaterial.style.TextAppearance_Material3_BodySmall
        private val couleurPrimaire: Int = androidx.appcompat.R.attr.colorPrimary
        private val couleurContenu: Int = RMaterial.attr.colorOnSurface
        private val couleurNeutre: Int = RMaterial.attr.colorOnSurfaceVariant
        private val couleurFondPrimaire: Int = RMaterial.attr.colorPrimaryContainer
        private val couleurFondSecondaire: Int = RMaterial.attr.colorSecondaryContainer

        private const val ONGLET_ARBRE: Int = 0
        private const val ONGLET_ATTRIBUTS: Int = 1
        private const val ONGLET_PROBLEMES: Int = 2
        private const val ONGLET_SOURCE: Int = 3

        /** Hauteur repliée de la feuille : poignée + onglets (miroir 72 px). */
        private const val PEEK_DP: Int = 76

        /** Épaisseur : la constante WRAP lisible. */
        private const val WRAP: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
