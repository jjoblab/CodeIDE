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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isEmpty
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.preview.databinding.FragmentVoletAttributsBinding
import jo.codeide.preview.xml.CatalogueAttributs
import jo.codeide.preview.xml.EditionAttributsXml
import jo.codeide.preview.xml.ExtractionRessource
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.RenommageIdentifiant

/**
 * Le volet ATTRIBUTS de la feuille (PE9a, miroir `renderAttrs` +
 * `renderEditor` — ancien `majAttributs` de [FeuilleApercuFragment],
 * migré tel quel) : la section MODIFIER (PE7e — le catalogue pur
 * [CatalogueAttributs] décrit les SECTIONS selon la classe de la vue et
 * le genre de son parent — cartes repliables, état mémorisé par titre)
 * puis les groupes LECTURE SEULE (identité, disposition, mesuré).
 *
 * UN SEUL CHEMIN d'édition : chaque contrôle passe au ViewModel (UNE
 * écriture au port, rendu immédiat) ; un refus recharge le volet depuis
 * le texte vivant ([editerOuRecharger]). Les `@string` restent en
 * lecture seule (miroir `.ro`).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions + MagicNumber +
 * LargeClass — chaque contrôle a SA fonction (miroir `ctl` : un type =
 * un contrôle), la mise en forme des contrôles reste EN CODE jusqu'à
 * PE9b (les item layouts réels par type de contrôle), les dimensions dp
 * y sont littérales (patron render* de la maquette).
 */
@Suppress("TooManyFunctions", "MagicNumber", "LargeClass")
@AndroidEntryPoint
internal class VoletAttributsFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_ATTRIBUTS

    private lateinit var liaison: FragmentVoletAttributsBinding

    /**
     * L'état de repli des SECTIONS de l'éditeur, par TITRE (miroir `S.so`
     * de la maquette — mémorisé pendant la session du fragment).
     */
    private val etatSections: MutableMap<String, Boolean> = mutableMapOf()

    // ── Aides de style (délégation à StyleVolet — un seul endroit) ──
    private val styleMoyen: Int get() = StyleVolet.styleMoyen
    private val stylePetit: Int get() = StyleVolet.stylePetit
    private val styleTitre: Int get() = StyleVolet.styleTitre
    private val styleCorps: Int get() = StyleVolet.styleCorps
    private val couleurPrimaire: Int get() = StyleVolet.couleurPrimaire
    private val couleurContenu: Int get() = StyleVolet.couleurContenu
    private val couleurNeutre: Int get() = StyleVolet.couleurNeutre
    private val couleurSurface: Int get() = StyleVolet.couleurSurface
    private val couleurContour: Int get() = StyleVolet.couleurContour
    private val couleurAccentViolet: Int get() = StyleVolet.couleurAccentViolet
    private val couleurAccentVioletFond: Int get() = StyleVolet.couleurAccentVioletFond

    private fun dp(valeur: Int): Int = StyleVolet.dp(requireContext(), valeur)

    private fun couleur(attribut: Int): Int = StyleVolet.couleur(requireContext(), attribut)

    private fun texteLibelle(
        texte: String,
        apparence: Int,
        couleurValeur: Int,
    ): TextView = StyleVolet.texteLibelle(requireContext(), texte, apparence, couleurValeur)

    override fun onCreateView(
        inflater: LayoutInflater,
        conteneur: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaison = FragmentVoletAttributsBinding.inflate(inflater, conteneur, false)
        return liaison.root
    }

    /** La mise en forme du volet (l'onglet est actif — porte de la base). */
    override fun marcher() {
        majAttributs()
    }

    /** Un TextView simple de volet vide (miroir `texteSimple` de l'ancien fragment). */
    private fun texteSimple(texte: String): TextView =
        StyleVolet.texteLibelle(requireContext(), texte, styleMoyen, couleur(couleurNeutre)).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

    /**
     * Vide le conteneur reconstruit (seul volet encore LinearLayout en
     * PE9a) — le crash du rapport 0.134.30 NPE `mViewFlags` (rapport
     * c8f6ffeb) vivait ICI : `removeAllViews` sur un panneau dont un
     * contrôle éditable (EditText de la section MODIFIER) tenait le
     * focus lance la recherche d'un SUCCESSEUR au focus au MILIEU de la
     * suppression. Le protocole focus-sûr : le focus est RETIRÉ d'abord,
     * la descente de focus est BLOQUÉE pendant la suppression
     * (FOCUS_BLOCK_DESCENDANTS), puis rétablie. (PE9b remplacera la
     * reconstruction par les item layouts réels.)
     */
    private fun viderConteneur(conteneur: ViewGroup) {
        if (conteneur.isEmpty()) return
        conteneur.findFocus()?.clearFocus()
        val descentePrecedente = conteneur.descendantFocusability
        conteneur.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        conteneur.removeAllViews()
        conteneur.descendantFocusability = descentePrecedente
    }

    /**
     * L'onglet Attributs (miroir `renderAttrs`) : la section MODIFIER
     * (PE7d — texte/hint littéraux, dimensions wrap/match/fixe, « les
     * `@string` restent en lecture seule ») puis les groupes LECTURE
     * SEULE (identité, disposition, mesuré — PE6d).
     */
    private fun majAttributs() {
        val vue = renduCourant?.vues?.getOrNull(selectionCourante)
        viderConteneur(liaison.groupesAttributs)
        if (vue == null) {
            liaison.groupesAttributs.addView(texteSimple(getString(R.string.preview_feuille_vide)))
            return
        }
        majSectionModifier(vue)
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
                        addView(libelle, LinearLayout.LayoutParams(dp(160), StyleVolet.WRAP))
                        addView(texteLibelle(entree.valeur, styleCorps, couleur(couleurContenu)))
                    }
                liaison.groupesAttributs.addView(rangee)
            }
        }
    }

    /**
     * La section MODIFIER (PE7e, miroir `renderEditor` + `sectionsFor`) :
     * le catalogue PUR ([CatalogueAttributs]) décrit les SECTIONS selon la
     * classe de la vue et le genre de son parent — cartes repliables
     * (miroir `.sec2` : titre, badge « n définis », flèche), les DEUX
     * PREMIÈRES ouvertes par défaut, l'état de repli mémorisé par TITRE
     * pendant la session. UN SEUL CHEMIN d'édition : chaque contrôle passe
     * au ViewModel (UNE écriture au port, rendu immédiat — miroir
     * `soumettreImmediat`) ; un refus recharge le volet depuis le texte
     * vivant. Les `@string` restent en lecture seule (miroir `.ro`).
     */
    private fun majSectionModifier(vue: InfoVueRendu) {
        val classe = viewModel.classeSelection() ?: vue.classe
        val genreParent =
            viewModel.genreParentSelection() ?: CatalogueAttributs.GenreParent.AUCUN
        val editeur = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        editeur.addView(titreEditeur(classe))
        val attrs = viewModel.attributsSelection()
        if (attrs == null) {
            // Span périmé (include/merge, document changé) : le volet passe
            // en LECTURE SEULE avec explication (miroir piège § 6 du prompt).
            editeur.addView(blocLectureSeule(getString(R.string.preview_edition_impossible_toast)))
            liaison.groupesAttributs.addView(editeur)
            return
        }
        val sections = CatalogueAttributs.sections(classe, genreParent)
        sections.forEachIndexed { position, section ->
            editeur.addView(carteSection(section, attrs, position))
        }
        // Séparateur de fin (miroir le border-bottom de `.ed`).
        val separateur = View(requireContext())
        separateur.setBackgroundColor(couleur(couleurNeutre))
        separateur.alpha = SEPARATEUR_ALPHA
        editeur.addView(
            separateur,
            LinearLayout.LayoutParams(StyleVolet.WRAP, dp(1)).apply { topMargin = dp(10) },
        )
        liaison.groupesAttributs.addView(editeur)
    }

    /** Le titre de l'éditeur (miroir `.ed h5` : « MODIFIER · CLASSE », mono, caps). */
    private fun titreEditeur(classe: String): TextView =
        TextView(requireContext()).apply {
            text = getString(R.string.preview_modifier_classe, classe)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            letterSpacing = 0.08f
            setAllCaps(true)
            setTextColor(couleur(couleurNeutre))
            setPadding(dp(16), dp(12), dp(8), dp(6))
        }

    /** Le titre localisé d'une section (clé sémantique → ressource — table
     * unique, miroir [ETIQUETTES_RES]). */
    private fun titreSectionRes(titre: CatalogueAttributs.TitreSection): String = getString(TITRES_RES.getValue(titre))

    /** L'étiquette localisée d'un contrôle (clé sémantique → ressource). */
    private fun etiquetteRes(etiquette: CatalogueAttributs.Etiquette): String =
        getString(ETIQUETTES_RES.getValue(etiquette))

    /** Le nombre d'attributs DÉFINIS de la section (miroir le badge `n définis`). */
    private fun nombreDefinis(
        section: CatalogueAttributs.SectionAttributs,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): Int =
        section.controles.count { controle ->
            val nom = nomDuControle(controle)
            nom != null && attrs.any { it.nom == nom && it.valeur.isNotEmpty() }
        }

    /** Le nom d'attribut d'un contrôle (Dimensions/Contraintes : rien à compter). */
    private fun nomDuControle(controle: CatalogueAttributs.ControleAttribut): String? =
        when (controle) {
            is CatalogueAttributs.ControleAttribut.Identifiant -> controle.nom

            is CatalogueAttributs.ControleAttribut.Texte -> controle.nom

            is CatalogueAttributs.ControleAttribut.Nombre -> controle.nom

            is CatalogueAttributs.ControleAttribut.Choix -> controle.nom

            is CatalogueAttributs.ControleAttribut.TriEtat -> controle.nom

            is CatalogueAttributs.ControleAttribut.Cotes,
            CatalogueAttributs.ControleAttribut.Dimensions,
            CatalogueAttributs.ControleAttribut.Contraintes,
            -> null
        }

    /**
     * UNE CARTE de section repliable (miroir `.sec2` : contour 1, coins
     * 10, en-tête 9×11 avec flèche, corps 2×11) — le tap sur l'en-tête
     * replie/déploie et MÉMORISE l'état par titre ([etatSections]).
     */
    private fun carteSection(
        section: CatalogueAttributs.SectionAttributs,
        attrs: List<EditionAttributsXml.AttributSource>,
        position: Int,
    ): View {
        val ouverte = etatSections[section.titre.name] ?: (position < DEFAUT_OUVERTES)
        val fleche = TextView(requireContext())
        val corps =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(2), dp(11), dp(11))
                visibility = if (ouverte) View.VISIBLE else View.GONE
            }
        section.controles.forEach { controle ->
            corps.addView(controleSection(controle, attrs))
        }
        val carte =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                background = fondCarte()
                addView(
                    enteteSection(section, attrs, ouverte, fleche) { nouvelleOuverture ->
                        etatSections[section.titre.name] = nouvelleOuverture
                        corps.visibility = if (nouvelleOuverture) View.VISIBLE else View.GONE
                        fleche.text =
                            getString(
                                if (nouvelleOuverture) {
                                    R.string.preview_section_ouverte
                                } else {
                                    R.string.preview_section_fermee
                                },
                            )
                    },
                )
                addView(corps)
            }
        carte.layoutParams =
            LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP).apply {
                topMargin = dp(8)
                marginStart = dp(12)
                marginEnd = dp(12)
            }
        return carte
    }

    /** L'en-tête d'une carte : titre + badge « n définis » + flèche (cible 48 dp). */
    private fun enteteSection(
        section: CatalogueAttributs.SectionAttributs,
        attrs: List<EditionAttributsXml.AttributSource>,
        ouverte: Boolean,
        fleche: TextView,
        basculer: (Boolean) -> Unit,
    ): View {
        val entete =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(9), dp(11), dp(9))
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                background = StyleVolet.fondReactif(requireContext())
            }
        entete.addView(
            texteLibelle(titreSectionRes(section.titre), styleMoyen, couleur(couleurContenu)),
            LinearLayout.LayoutParams(0, StyleVolet.WRAP, 1f),
        )
        val nombre = nombreDefinis(section, attrs)
        if (nombre > 0) {
            entete.addView(
                badgeDefinis(nombre),
                LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP).apply { marginEnd = dp(8) },
            )
        }
        fleche.text = getString(if (ouverte) R.string.preview_section_ouverte else R.string.preview_section_fermee)
        fleche.setTextColor(couleur(couleurNeutre))
        entete.addView(fleche)
        entete.setOnClickListener { basculer(corpsFerme(fleche)) }
        return entete
    }

    /** L'état OPPOSÉ lu sur la flèche (source unique du visuel). */
    private fun corpsFerme(fleche: TextView): Boolean = fleche.text == getString(R.string.preview_section_ouverte)

    /** Le badge « n défini(s) » (miroir `summary small` : violet sur violet pâle). */
    private fun badgeDefinis(nombre: Int): TextView =
        TextView(requireContext()).apply {
            text = resources.getQuantityString(R.plurals.preview_section_definis, nombre, nombre)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(couleurRessource(couleurAccentViolet))
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(couleurRessource(couleurAccentVioletFond))
                }
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }

    /** La couleur d'un JETON du module (ressource `preview_*`). */
    private fun couleurRessource(ressource: Int): Int = StyleVolet.couleurRessource(requireContext(), ressource)

    /** La DÉRIVATION d'un contrôle (miroir `ctl` : un type = un contrôle). */
    private fun controleSection(
        controle: CatalogueAttributs.ControleAttribut,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View =
        when (controle) {
            is CatalogueAttributs.ControleAttribut.Identifiant -> controleIdentifiant(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Texte -> controleTexte(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Nombre -> controleNombre(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Choix -> controleChoix(controle, attrs)
            is CatalogueAttributs.ControleAttribut.TriEtat -> controleTriEtat(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Cotes -> controleCotes(controle, attrs)
            CatalogueAttributs.ControleAttribut.Dimensions -> controleDimensions()
            CatalogueAttributs.ControleAttribut.Contraintes -> controleContraintes()
        }

    /** La valeur courante d'un contrôle nommé (lecture dans les attributs lus). */
    private fun valeurDe(
        nom: String,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): String? = attrs.firstOrNull { it.nom == nom }?.valeur

    /** Un contrôle TEXTE : littéral éditable, référence en lecture seule
     * (miroir `.ro`), EXTRACTION en ressource offerte aux libellés (PE8c). */
    private fun controleTexte(
        controle: CatalogueAttributs.ControleAttribut.Texte,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val libelle = etiquetteRes(controle.etiquette)
        bloc.addView(rangeeLibelle(libelle))
        val valeur = valeurDe(controle.nom, attrs)
        if (valeur != null && (valeur.startsWith("@") || valeur.startsWith("?"))) {
            // Une référence se MONTRE, jamais écrasée (miroir la ligne `.ro`).
            bloc.addView(
                blocLectureSeule(getString(R.string.preview_modifier_reference, valeur, "")),
            )
        } else {
            bloc.addView(
                champTexte(libelle, valeur ?: "") { nouvelle ->
                    editerOuRecharger { viewModel.definirAttributSelection(controle.nom, nouvelle) }
                },
            )
            // L'EXTRACTION (PE8c) : littéral + session strings.xml ouverte.
            if (controle.extrayable && viewModel.extractionPossible(controle.nom)) {
                bloc.addView(rangeeExtraction(controle, attrs))
            }
        }
        return bloc
    }

    /** La rangée d'EXTRACTION (PE8c) : le bouton révèle le champ du nom
     * (pré-rempli par [ExtractionRessource.nomPropose]) et le valider. */
    private fun rangeeExtraction(
        controle: CatalogueAttributs.ControleAttribut.Texte,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val rangee =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(2), dp(16), dp(2))
            }
        val bouton = boutonMini(getString(R.string.preview_extraire_bouton))
        val champ = TextInputEditText(requireContext())
        champ.hint = getString(R.string.preview_extraire_nom)
        champ.setSingleLine(true)
        champ.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        champ.background = fondChampLectureSeule()
        champ.setPadding(dp(8), dp(6), dp(8), dp(6))
        champ.visibility = View.GONE
        champ.contentDescription = getString(R.string.preview_extraire_nom)
        val valider = boutonMini(getString(R.string.preview_extraire_valider))
        valider.visibility = View.GONE
        bouton.setOnClickListener {
            val reveler = champ.visibility != View.VISIBLE
            champ.visibility = if (reveler) View.VISIBLE else View.GONE
            valider.visibility = if (reveler) View.VISIBLE else View.GONE
            if (reveler) {
                champ.setText(ExtractionRessource.nomPropose(valeurDe(controle.nom, attrs) ?: ""))
            }
        }
        valider.setOnClickListener {
            val nom =
                champ.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            if (!viewModel.extraireEnRessource(controle.nom, nom)) {
                editerOuRecharger(R.string.preview_extraire_refuse) { false }
            }
        }
        rangee.addView(bouton, LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP))
        rangee.addView(
            champ,
            LinearLayout.LayoutParams(0, StyleVolet.WRAP, 1f).apply { marginStart = dp(8) },
        )
        rangee.addView(
            valider,
            LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP).apply { marginStart = dp(8) },
        )
        return rangee
    }

    /** Le PETIT bouton outline de la feuille (miroir les `.chips` — PE8b/PE8c). */
    private fun boutonMini(texte: String): com.google.android.material.button.MaterialButton =
        com.google.android.material.button
            .MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                this.text = texte
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                minWidth = 0
                insetLeft = 0
                insetRight = 0
                insetTop = 0
                insetBottom = 0
                minimumHeight = dp(32)
                setPadding(dp(8), 0, dp(8), 0)
                contentDescription = texte
            }

    /** Un contrôle IDENTIFIANT (PE8a) : valeur courante en mono, champ
     * « nouveau nom » validé à l'IME/à la perte de focus — le renommage
     * couvre la déclaration ET les références du fichier (une édition) ;
     * hors périmètre signalé (code R.id, autres fichiers). */
    private fun controleIdentifiant(
        controle: CatalogueAttributs.ControleAttribut.Identifiant,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        bloc.addView(rangeeLibelle(etiquetteRes(CatalogueAttributs.Etiquette.IDENTIFIANT)))
        val brut = valeurDe(controle.nom, attrs)
        bloc.addView(
            blocLectureSeule(brut ?: getString(R.string.preview_identite_absent)),
        )
        val champ = TextInputEditText(requireContext())
        champ.hint = getString(R.string.preview_identite_nouveau)
        champ.setSingleLine(true)
        champ.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        champ.background = fondChampLectureSeule()
        champ.setPadding(dp(10), dp(8), dp(10), dp(8))
        champ.contentDescription = getString(R.string.preview_identite_nouveau)
        champ.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commiterRenommage(champ, controle, attrs)) champ.clearFocus()
                true
            } else {
                false
            }
        }
        champ.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commiterRenommage(champ, controle, attrs)
        }
        bloc.addView(
            champ,
            LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(4)
            },
        )
        // Le périmètre du renommage, signalé (prompt PE7e § 3) : ce fichier
        // seul — le code (R.id.…) et les autres layouts ne sont pas touchés.
        val note = texteLibelle(getString(R.string.preview_identite_hors_perimetre), stylePetit, couleur(couleurNeutre))
        note.setPadding(dp(16), dp(4), dp(16), dp(2))
        bloc.addView(note)
        return bloc
    }

    /** Le nom SANS préfixe d'une valeur d'id (`@+id/x`/`@id/x` → `x`), sinon la valeur brute. */
    private fun nomCourant(valeur: String): String =
        when {
            valeur.startsWith("@+id/") -> valeur.removePrefix("@+id/")
            valeur.startsWith("@id/") -> valeur.removePrefix("@id/")
            else -> valeur
        }

    /** Le commit d'un RENOMMAGE (miroir `committerNombre`) : vide = rien,
     * nom invalide/identique = retour visuel, sinon l'édition — un refus
     * porte le message PROPRE au renommage (nom déjà pris dans le fichier). */
    private fun commiterRenommage(
        champ: TextInputEditText,
        controle: CatalogueAttributs.ControleAttribut.Identifiant,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): Boolean {
        val saisie =
            champ.text
                ?.toString()
                ?.trim()
                .orEmpty()
        return when {
            // Vide = jamais un renommage (miroir les champs PE7e).
            saisie.isEmpty() -> {
                false
            }

            // Nom invalide ou IDENTIQUE au courant : retour immédiat (le
            // champ se vide, pas de toast pour un no-op).
            !RenommageIdentifiant.nomValide(saisie) ||
                saisie == valeurDe(controle.nom, attrs)?.let { nomCourant(it) } -> {
                champ.setText("")
                false
            }

            else -> {
                if (viewModel.renommerIdentifiantSelection(saisie)) {
                    true
                } else {
                    editerOuRecharger(R.string.preview_identite_refuse) { false }
                }
            }
        }
    }

    /** Un contrôle NOMBRE avec unité (saisie refusée proprement si non numérique). */
    private fun controleNombre(
        controle: CatalogueAttributs.ControleAttribut.Nombre,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val libelle = etiquetteRes(controle.etiquette)
        bloc.addView(rangeeLibelle(libelle))
        val champ = EditText(requireContext())
        champ.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        champ.setText(valeurDe(controle.nom, attrs)?.trimEnd('d', 'p', 's'))
        champ.hint = libelle
        champ.setSingleLine(true)
        champ.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        champ.background = fondChampLectureSeule()
        champ.setPadding(dp(10), dp(8), dp(10), dp(8))
        champ.contentDescription = libelle
        champ.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val ecrit = committerNombre(champ, controle, attrs)
                if (ecrit) champ.clearFocus()
                true
            } else {
                false
            }
        }
        champ.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) committerNombre(champ, controle, attrs)
        }
        bloc.addView(
            champ,
            LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(2)
            },
        )
        return bloc
    }

    /** Le commit d'un NOMBRE : vide = rien, non numérique = refus visuel (repli). */
    private fun committerNombre(
        champ: EditText,
        controle: CatalogueAttributs.ControleAttribut.Nombre,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): Boolean {
        val saisie =
            champ.text
                ?.toString()
                ?.trim()
                .orEmpty()
        return when {
            saisie.isEmpty() -> {
                false
            }

            // Retour visuel : la saisie non numérique est REPLACÉE (pas d'édition).
            saisie.toFloatOrNull() == null || saisie.toFloat() < 0f -> {
                champ.setText(valeurDe(controle.nom, attrs)?.trimEnd('d', 'p', 's') ?: "")
                false
            }

            else -> {
                val valeur = formaterNombre(saisie.toFloat()) + controle.unite
                editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
            }
        }
    }

    /** Le format d'un nombre saisi (entier sans décimales, sinon valeur brute). */
    private fun formaterNombre(nombre: Float): String =
        if (nombre == nombre.toLong().toFloat()) nombre.toLong().toString() else nombre.toString()

    /** Un contrôle CHOIX segmenté — le PREMIER choix est le défaut (retrait). */
    private fun controleChoix(
        controle: CatalogueAttributs.ControleAttribut.Choix,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        bloc.addView(rangeeLibelle(etiquetteRes(controle.etiquette)))
        val courant = valeurDe(controle.nom, attrs) ?: controle.valeurs.first()
        bloc.addView(
            segmentes(
                controle.valeurs,
                courant,
                etiquetteRes(controle.etiquette),
            ) { choix ->
                val valeur = if (choix == controle.valeurs.first()) "" else choix
                editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
            },
        )
        return bloc
    }

    /** Un contrôle TRI-ÉTAT (défaut / true / false — défaut = retrait). */
    private fun controleTriEtat(
        controle: CatalogueAttributs.ControleAttribut.TriEtat,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        bloc.addView(rangeeLibelle(etiquetteRes(controle.etiquette)))
        val valeurs =
            listOf(
                getString(R.string.preview_choix_defaut),
                getString(R.string.preview_choix_vrai),
                getString(R.string.preview_choix_faux),
            )
        val brut = valeurDe(controle.nom, attrs)
        val courant =
            when (brut) {
                "true" -> getString(R.string.preview_choix_vrai)
                "false" -> getString(R.string.preview_choix_faux)
                else -> getString(R.string.preview_choix_defaut)
            }
        bloc.addView(
            segmentes(valeurs, courant, etiquetteRes(controle.etiquette)) { choix ->
                val valeur =
                    when (choix) {
                        getString(R.string.preview_choix_vrai) -> "true"
                        getString(R.string.preview_choix_faux) -> "false"
                        else -> ""
                    }
                editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
            },
        )
        return bloc
    }

    /** Les quatre CÔTÉS d'une marge/padding (miroir `.mrow` : grille de 4 champs). */
    private fun controleCotes(
        controle: CatalogueAttributs.ControleAttribut.Cotes,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        bloc.addView(rangeeLibelle(etiquetteRes(controle.etiquette)))
        val rangee = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        val cotes =
            listOf(
                EditionAttributsXml.Cote.HAUT to R.string.preview_cote_haut,
                EditionAttributsXml.Cote.FIN to R.string.preview_cote_droite,
                EditionAttributsXml.Cote.BAS to R.string.preview_cote_bas,
                EditionAttributsXml.Cote.DEBUT to R.string.preview_cote_gauche,
            )
        cotes.forEach { (cote, libelleRes) ->
            val libelleCote = getString(libelleRes)
            rangee.addView(
                champCote(controle, cote, libelleCote, attrs),
                LinearLayout.LayoutParams(0, StyleVolet.WRAP, 1f),
            )
        }
        rangee.setPadding(dp(16), dp(2), dp(16), dp(0))
        bloc.addView(rangee)
        return bloc
    }

    /** UN champ de côté (commit IME/perte de focus — pas à chaque frappe). */
    private fun champCote(
        controle: CatalogueAttributs.ControleAttribut.Cotes,
        cote: EditionAttributsXml.Cote,
        libelleCote: String,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): EditText {
        val champ = EditText(requireContext())
        champ.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        champ.setText(EditionAttributsXml.valeurCote(attrs, controle.base, cote)?.removeSuffix("dp"))
        champ.hint = libelleCote
        champ.setSingleLine(true)
        champ.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        champ.setTextAlignment(View.TEXT_ALIGNMENT_CENTER)
        champ.background = fondChampLectureSeule()
        champ.setPadding(dp(6), dp(8), dp(6), dp(8))
        champ.contentDescription = getString(R.string.preview_cote_cd, etiquetteRes(controle.etiquette), libelleCote)
        val commettre = {
            val saisie =
                champ.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            val nombre = saisie.toIntOrNull()
            when {
                saisie.isEmpty() -> {
                    false
                }

                nombre == null || nombre < 0 -> {
                    champ.setText(EditionAttributsXml.valeurCote(attrs, controle.base, cote)?.removeSuffix("dp") ?: "")
                    false
                }

                else -> {
                    editerOuRecharger {
                        viewModel.ecrireCoteSelection(controle.base, cote, nombre)
                    }
                }
            }
        }
        champ.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) champ.clearFocus()
                true
            } else {
                false
            }
        }
        champ.setOnFocusChangeListener { _, aFocus -> if (!aFocus) commettre() }
        return champ
    }

    /** Les DIMENSIONS (PE7d repris — les deux rangées wrap/match/fixe). */
    private fun controleDimensions(): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        bloc.addView(rangeeDimension(EditionAttributsXml.Axe.LARGEUR, getString(R.string.preview_modifier_largeur)))
        bloc.addView(rangeeDimension(EditionAttributsXml.Axe.HAUTEUR, getString(R.string.preview_modifier_hauteur)))
        return bloc
    }

    /**
     * La section CONTRAINTES (PE8b — ÉDITABLE) : les QUATRE ancres du
     * ConstraintLayout parent, chacune un bouton CIBLE (menu Aucune /
     * parent / frères à id) et un champ MARGE en dp — chaque geste = UNE
     * édition au port. Un span périmé retombe en lecture seule (miroir PE7e).
     */
    private fun controleContraintes(): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val lues = viewModel.lireContraintesSelection()
        if (lues == null) {
            bloc.addView(blocLectureSeule(getString(R.string.preview_edition_impossible_toast)))
            return bloc
        }
        lues.forEach { lue -> bloc.addView(rangeeContrainte(lue)) }
        return bloc
    }

    /** Une RANGÉE d'ancre (miroir `.crow`) : libellé, bouton cible, champ marge. */
    private fun rangeeContrainte(lue: EditionAttributsXml.ContrainteLue): View {
        val rangee =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(2), dp(16), dp(2))
            }
        val libelle = libelleAncre(lue.cote)
        rangee.addView(
            texteLibelle(libelle, stylePetit, couleur(couleurContenu)),
            LinearLayout.LayoutParams(dp(64), StyleVolet.WRAP),
        )
        val bouton =
            com.google.android.material.button.MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
        bouton.text = lue.cible ?: getString(R.string.preview_contrainte_aucune)
        bouton.isAllCaps = false
        bouton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        bouton.isFocusable = true
        bouton.minWidth = 0
        bouton.insetLeft = 0
        bouton.insetRight = 0
        bouton.insetTop = 0
        bouton.insetBottom = 0
        bouton.minimumHeight = dp(32)
        bouton.setPadding(dp(10), 0, dp(10), 0)
        bouton.contentDescription = getString(R.string.preview_contrainte_cible_cd, libelle)
        bouton.setOnClickListener { menuCible(bouton, lue) }
        rangee.addView(bouton, LinearLayout.LayoutParams(0, StyleVolet.WRAP, 1f))
        rangee.addView(champMargeContrainte(lue), LinearLayout.LayoutParams(dp(72), StyleVolet.WRAP))
        return rangee
    }

    /** Le champ MARGE d'une ancre (commit IME/perte de focus — miroir `champCote`). */
    private fun champMargeContrainte(lue: EditionAttributsXml.ContrainteLue): EditText {
        val champ = EditText(requireContext())
        champ.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        champ.setText(lue.margeDp?.toString() ?: "")
        champ.hint = getString(R.string.preview_contrainte_marge)
        champ.setSingleLine(true)
        champ.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        champ.setTextAlignment(View.TEXT_ALIGNMENT_CENTER)
        champ.background = fondChampLectureSeule()
        champ.setPadding(dp(6), dp(8), dp(6), dp(8))
        champ.contentDescription =
            getString(R.string.preview_contrainte_marge_cd, libelleAncre(lue.cote))
        val commettre = {
            val saisie =
                champ.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            val nombre = saisie.toIntOrNull()
            when {
                saisie.isEmpty() -> {
                    false
                }

                nombre == null || nombre < 0 -> {
                    champ.setText(lue.margeDp?.toString() ?: "")
                    false
                }

                else -> {
                    editerOuRecharger { viewModel.ecrireContrainteSelection(lue.cote, null, nombre) }
                }
            }
        }
        champ.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) champ.clearFocus()
                true
            } else {
                false
            }
        }
        champ.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        return champ
    }

    /** Le menu de CIBLE d'une ancre : Aucune, parent, puis les frères à id. */
    private fun menuCible(
        ancre: View,
        lue: EditionAttributsXml.ContrainteLue,
    ) {
        val menu = androidx.appcompat.widget.PopupMenu(requireContext(), ancre)
        val itemAucune = menu.menu.add(getString(R.string.preview_contrainte_aucune))
        val itemParent = menu.menu.add(VALEUR_PARENT)
        viewModel.ciblesContrainteSelection().forEach { identifiant ->
            menu.menu.add("@id/$identifiant")
        }
        menu.setOnMenuItemClickListener { item ->
            val cible =
                when (item.itemId) {
                    itemAucune.itemId -> EditionAttributsXml.CibleContrainte.Aucune
                    itemParent.itemId -> EditionAttributsXml.CibleContrainte.Parent
                    else -> EditionAttributsXml.CibleContrainte.Vue(item.title.toString().removePrefix("@id/"))
                }
            editerOuRecharger { viewModel.ecrireContrainteSelection(lue.cote, cible, null) }
        }
        menu.show()
    }

    /** Le libellé localisé d'une ancre (Début/Fin/Haut/Bas). */
    private fun libelleAncre(cote: EditionAttributsXml.Cote): String =
        getString(
            when (cote) {
                EditionAttributsXml.Cote.DEBUT -> R.string.preview_contrainte_debut
                EditionAttributsXml.Cote.FIN -> R.string.preview_contrainte_fin
                EditionAttributsXml.Cote.HAUT -> R.string.preview_contrainte_haut
                EditionAttributsXml.Cote.BAS -> R.string.preview_contrainte_bas
            },
        )

    /** Un bloc LECTURE SEULE (miroir `.ro` : surface 2 arrondie, mono). */
    private fun blocLectureSeule(texte: String): TextView =
        TextView(requireContext()).apply {
            this.text = texte
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(couleur(couleurContenu))
            background = fondChampLectureSeule()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val params = LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP)
            params.marginStart = dp(16)
            params.marginEnd = dp(16)
            params.topMargin = dp(2)
            layoutParams = params
        }

    /**
     * La rangée de SEGMENTS (miroir `.seg2` : fond surface 2, bouton
     * coché PRIMAIRE) — le tap valide (pas de frappe intermédiaire).
     */
    private fun segmentes(
        valeurs: List<String>,
        courant: String,
        description: String,
        onChoix: (String) -> Unit,
    ): View {
        val rangee =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                background = fondChampLectureSeule()
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
        val paramsRangee = LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP)
        paramsRangee.marginStart = dp(16)
        paramsRangee.marginEnd = dp(16)
        paramsRangee.topMargin = dp(2)
        valeurs.forEach { valeur ->
            val bouton =
                com.google.android.material.button.MaterialButton(
                    requireContext(),
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                )
            bouton.text = valeur
            bouton.isAllCaps = false
            bouton.minWidth = 0
            bouton.insetLeft = 0
            bouton.insetRight = 0
            bouton.insetTop = 0
            bouton.insetBottom = 0
            bouton.setPadding(dp(9), dp(7), dp(9), dp(7))
            bouton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            bouton.isSelected = valeur == courant
            bouton.contentDescription = "$description : $valeur"
            bouton.setOnClickListener {
                if (valeur != courant) onChoix(valeur)
            }
            rangee.addView(bouton)
        }
        rangee.layoutParams = paramsRangee
        return rangee
    }

    /** Le fond d'une CARTE de section (miroir `.sec2` : contour, coins 10). */
    private fun fondCarte(): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(couleur(couleurSurface))
            setStroke(dp(1), couleur(couleurContour))
        }

    /** L'édition ou le RECHARGEMENT : un refus → toast (surchargeable pour
     * un refus spécialisé, ex. renommage) + volet relu depuis le texte vivant. */
    private fun editerOuRecharger(
        message: Int = R.string.preview_edition_perimee_toast,
        action: () -> Boolean,
    ): Boolean {
        if (action()) return true
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        majAttributs()
        return false
    }

    /** Un champ texte avec son libellé (commit sur IME_DONE + perte de focus). */
    private fun champTexte(
        libelle: String,
        valeur: String,
        commit: (String) -> Unit,
    ): View {
        val bloc = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        bloc.addView(rangeeLibelle(libelle))
        val champ = TextInputEditText(requireContext())
        champ.setText(valeur)
        champ.hint = libelle
        champ.setSingleLine(true)
        champ.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        champ.background = fondChampLectureSeule()
        champ.setPadding(dp(10), dp(8), dp(10), dp(8))
        champ.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commit(champ.text?.toString() ?: "")
                champ.clearFocus()
                true
            } else {
                false
            }
        }
        champ.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commit(champ.text?.toString() ?: "")
        }
        bloc.addView(
            champ,
            LinearLayout.LayoutParams(StyleVolet.WRAP, StyleVolet.WRAP).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(2)
            },
        )
        return bloc
    }

    /** La rangée label au-dessus d'un champ (miroir `fld label`). */
    private fun rangeeLibelle(libelle: String): TextView =
        texteLibelle(libelle, stylePetit, couleur(couleurNeutre)).apply {
            setPadding(dp(16), dp(4), dp(8), dp(2))
        }

    /**
     * La rangée d'une DIMENSION (miroir `dimrow`) : label + segment
     * wrap/match/fixe + champ dp quand le mode est FIXE.
     */
    private fun rangeeDimension(
        axe: EditionAttributsXml.Axe,
        libelle: String,
    ): View {
        val nomAttribut = if (axe == EditionAttributsXml.Axe.LARGEUR) ATTR_LARGEUR else ATTR_HAUTEUR
        val valeur = viewModel.valeurAttributSelection(nomAttribut) ?: ""
        val (modeCourant, valeurFixe) = modeDimensionDe(valeur)

        val rangee =
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(4))
            }
        val label = texteLibelle(libelle, stylePetit, couleur(couleurNeutre))
        rangee.addView(label, LinearLayout.LayoutParams(dp(56), StyleVolet.WRAP))

        rangee.addView(segmentsDimension(axe, modeCourant, valeurFixe))

        if (modeCourant == EditionAttributsXml.ModeDimension.FIXE) {
            rangee.addView(
                champDimensionFixe(axe, valeurFixe),
                LinearLayout.LayoutParams(dp(64), StyleVolet.WRAP).apply { marginStart = dp(8) },
            )
        }
        return rangee
    }

    /**
     * La rangée de SEGMENTS wrap/match/fixe d'une dimension (miroir `seg2`
     * — la SEULE implémentation de segments reste [segmentes]) : le tap
     * écrit le mode (FIXE sans valeur → le champ dp prend le relais).
     */
    private fun segmentsDimension(
        axe: EditionAttributsXml.Axe,
        modeCourant: EditionAttributsXml.ModeDimension,
        valeurFixe: Int?,
    ): View {
        val valeurs =
            listOf(
                EditionAttributsXml.ModeDimension.WRAP to getString(R.string.preview_modifier_wrap),
                EditionAttributsXml.ModeDimension.MATCH to getString(R.string.preview_modifier_match),
                EditionAttributsXml.ModeDimension.FIXE to getString(R.string.preview_modifier_fixe),
            )
        val courant =
            valeurs.firstOrNull { it.first == modeCourant }?.second
                ?: getString(R.string.preview_modifier_fixe)
        val description = getString(R.string.preview_modifier_dimension_cd)
        return segmentes(valeurs.map { it.second }, courant, description) { texteChoix ->
            val mode =
                valeurs.firstOrNull { it.second == texteChoix }?.first
                    ?: EditionAttributsXml.ModeDimension.FIXE
            ecrireDimension(axe, mode, valeurFixe)
        }
    }

    /** Le champ dp d'une dimension FIXE (miroir l'input number de `dimrow`). */
    private fun champDimensionFixe(
        axe: EditionAttributsXml.Axe,
        valeurFixe: Int?,
    ): EditText {
        val champ = EditText(requireContext())
        champ.setText(valeurFixe?.toString() ?: "")
        champ.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        champ.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        champ.setTypeface(Typeface.MONOSPACE)
        champ.contentDescription = getString(R.string.preview_modifier_dimension_cd)
        champ.setPadding(dp(8), dp(6), dp(8), dp(6))
        champ.background = fondChampLectureSeule()
        champ.doAfterTextChanged { texte ->
            val nombre = texte?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (nombre > 0 && nombre != valeurFixe) {
                ecrireDimension(axe, EditionAttributsXml.ModeDimension.FIXE, nombre)
            }
        }
        return champ
    }

    /** Le mode + la valeur fixe d'une dimension courante (miroir `mode`). */
    private fun modeDimensionDe(valeur: String): Pair<EditionAttributsXml.ModeDimension, Int?> =
        when {
            valeur == "wrap_content" -> {
                EditionAttributsXml.ModeDimension.WRAP to null
            }

            valeur == "match_parent" -> {
                EditionAttributsXml.ModeDimension.MATCH to null
            }

            valeur.endsWith("dp") -> {
                val nombre = valeur.removeSuffix("dp").toIntOrNull()
                if (nombre != null) {
                    EditionAttributsXml.ModeDimension.FIXE to nombre
                } else {
                    EditionAttributsXml.ModeDimension.FIXE to null
                }
            }

            else -> {
                EditionAttributsXml.ModeDimension.FIXE to null
            }
        }

    /** Écrit une dimension sur la sélection (silencieux — même raison). */
    private fun ecrireDimension(
        axe: EditionAttributsXml.Axe,
        mode: EditionAttributsXml.ModeDimension,
        valeurDp: Int?,
    ) {
        editerOuRecharger { viewModel.ecrireDimensionSelection(axe, mode, valeurDp) }
    }

    /** Le fond doux des champs (miroir `.fld input` : surface 2 arrondie). */
    private fun fondChampLectureSeule(): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(couleur(StyleVolet.couleurFondSecondaire))
        }

    private companion object {
        /** Les DEUX PREMIÈRES sections de l'éditeur s'ouvrent par défaut (miroir `i < 2`). */
        private const val DEFAUT_OUVERTES: Int = 2

        /** La valeur de plateforme pour cibler le PARENT (code, pas UI). */
        private const val VALEUR_PARENT: String = "parent"

        /** Transparence du séparateur du volet Modifier (0-1). */
        private const val SEPARATEUR_ALPHA: Float = 0.25f

        // ── Attributs de dimension du volet Modifier (PE7d) ──
        private const val ATTR_LARGEUR: String = "android:layout_width"
        private const val ATTR_HAUTEUR: String = "android:layout_height"

        /** La table des titres de sections (clé sémantique → ressource — une seule voie). */
        private val TITRES_RES: Map<CatalogueAttributs.TitreSection, Int> =
            mapOf(
                CatalogueAttributs.TitreSection.IDENTITE to R.string.preview_section_identite,
                CatalogueAttributs.TitreSection.TEXTE to R.string.preview_section_texte,
                CatalogueAttributs.TitreSection.SAISIE to R.string.preview_section_saisie,
                CatalogueAttributs.TitreSection.CHAMP to R.string.preview_section_champ,
                CatalogueAttributs.TitreSection.BOUTON to R.string.preview_section_bouton,
                CatalogueAttributs.TitreSection.ETAT to R.string.preview_section_etat,
                CatalogueAttributs.TitreSection.IMAGE to R.string.preview_section_image,
                CatalogueAttributs.TitreSection.CONTENEUR to R.string.preview_section_conteneur,
                CatalogueAttributs.TitreSection.PROGRESSION to R.string.preview_section_progression,
                CatalogueAttributs.TitreSection.LISTE to R.string.preview_section_liste,
                CatalogueAttributs.TitreSection.CONTRAINTES to R.string.preview_section_contraintes,
                CatalogueAttributs.TitreSection.DANS_PARENT to R.string.preview_section_dans_parent,
                CatalogueAttributs.TitreSection.DISPOSITION to R.string.preview_section_disposition,
                CatalogueAttributs.TitreSection.AFFICHAGE to R.string.preview_section_affichage,
            )

        /** La table des étiquettes (clé sémantique → ressource — une seule voie). */
        private val ETIQUETTES_RES: Map<CatalogueAttributs.Etiquette, Int> =
            mapOf(
                CatalogueAttributs.Etiquette.IDENTIFIANT to R.string.preview_etiquette_identifiant,
                CatalogueAttributs.Etiquette.TEXTE to R.string.preview_etiquette_texte,
                CatalogueAttributs.Etiquette.TAILLE_SP to R.string.preview_etiquette_taille,
                CatalogueAttributs.Etiquette.STYLE to R.string.preview_etiquette_style,
                CatalogueAttributs.Etiquette.GRAVITE to R.string.preview_etiquette_gravite,
                CatalogueAttributs.Etiquette.COULEUR to R.string.preview_etiquette_couleur,
                CatalogueAttributs.Etiquette.HINT to R.string.preview_modifier_hint,
                CatalogueAttributs.Etiquette.TYPE_SAISIE to R.string.preview_etiquette_type_saisie,
                CatalogueAttributs.Etiquette.LIGNES_MAX to R.string.preview_etiquette_lignes_max,
                CatalogueAttributs.Etiquette.LIBELLE to R.string.preview_etiquette_libelle,
                CatalogueAttributs.Etiquette.FOND to R.string.preview_etiquette_fond,
                CatalogueAttributs.Etiquette.ICONE_FIN to R.string.preview_etiquette_icone_fin,
                CatalogueAttributs.Etiquette.ARRONDI to R.string.preview_etiquette_arrondi,
                CatalogueAttributs.Etiquette.ELEVATION to R.string.preview_etiquette_elevation,
                CatalogueAttributs.Etiquette.ICONE to R.string.preview_etiquette_icone,
                CatalogueAttributs.Etiquette.COCHE to R.string.preview_etiquette_coche,
                CatalogueAttributs.Etiquette.SOURCE to R.string.preview_etiquette_source,
                CatalogueAttributs.Etiquette.TYPE_ECHELLE to R.string.preview_etiquette_type_echelle,
                CatalogueAttributs.Etiquette.ORIENTATION to R.string.preview_etiquette_orientation,
                CatalogueAttributs.Etiquette.MAXIMUM to R.string.preview_etiquette_maximum,
                CatalogueAttributs.Etiquette.PROGRESSION to R.string.preview_etiquette_progression,
                CatalogueAttributs.Etiquette.INDETERMINE to R.string.preview_etiquette_indetermine,
                CatalogueAttributs.Etiquette.ITEM_LISTE to R.string.preview_etiquette_item_liste,
                CatalogueAttributs.Etiquette.GESTIONNAIRE to R.string.preview_etiquette_gestionnaire,
                CatalogueAttributs.Etiquette.POIDS to R.string.preview_etiquette_poids,
                CatalogueAttributs.Etiquette.GRAVITE_PARENT to R.string.preview_etiquette_gravite_parent,
                CatalogueAttributs.Etiquette.MARGES to R.string.preview_etiquette_marges,
                CatalogueAttributs.Etiquette.PADDING to R.string.preview_etiquette_padding,
                CatalogueAttributs.Etiquette.VISIBILITE to R.string.preview_etiquette_visibilite,
                CatalogueAttributs.Etiquette.ALPHA to R.string.preview_etiquette_alpha,
                CatalogueAttributs.Etiquette.ACTIF to R.string.preview_etiquette_actif,
                CatalogueAttributs.Etiquette.DESCRIPTION to R.string.preview_etiquette_description,
            )
    }
}
