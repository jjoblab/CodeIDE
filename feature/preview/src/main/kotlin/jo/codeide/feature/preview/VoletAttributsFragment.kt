package jo.codeide.feature.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.preview.databinding.BarreOutilsAttributsBinding
import jo.codeide.feature.preview.databinding.CarteSectionBinding
import jo.codeide.feature.preview.databinding.ControleContrainteBinding
import jo.codeide.feature.preview.databinding.ControleContraintesBinding
import jo.codeide.feature.preview.databinding.ControleCotesBinding
import jo.codeide.feature.preview.databinding.ControleCouleurBinding
import jo.codeide.feature.preview.databinding.ControleDimensionsBinding
import jo.codeide.feature.preview.databinding.ControleDrapeauxBinding
import jo.codeide.feature.preview.databinding.ControleExtractionBinding
import jo.codeide.feature.preview.databinding.ControleIdentifiantBinding
import jo.codeide.feature.preview.databinding.ControleListeBinding
import jo.codeide.feature.preview.databinding.ControleRessourceBinding
import jo.codeide.feature.preview.databinding.ControleSegmentsBinding
import jo.codeide.feature.preview.databinding.ControleTexteBinding
import jo.codeide.feature.preview.databinding.FragmentVoletAttributsBinding
import jo.codeide.feature.preview.databinding.ItemAttributBinding
import jo.codeide.feature.preview.databinding.ItemAttributGroupeBinding
import jo.codeide.feature.preview.databinding.ItemDimensionBinding
import jo.codeide.feature.preview.databinding.ItemDrapeauBinding
import jo.codeide.feature.preview.databinding.ItemLectureSeuleBinding
import jo.codeide.feature.preview.databinding.ItemMenuCibleBinding
import jo.codeide.feature.preview.databinding.ItemSegmentBinding
import jo.codeide.feature.preview.databinding.PopoverMenuCibleBinding
import jo.codeide.feature.preview.databinding.SaisieNombreBinding
import jo.codeide.feature.preview.databinding.SaisieTexteBinding
import jo.codeide.feature.preview.databinding.SeparateurEditeurBinding
import jo.codeide.preview.xml.CatalogueAttributs
import jo.codeide.preview.xml.CouleursProjetXml
import jo.codeide.preview.xml.EditionAttributsXml
import jo.codeide.preview.xml.ExtractionRessource
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProblemesPreview
import jo.codeide.preview.xml.RenommageIdentifiant
import jo.codeide.preview.xml.ValidationAttribut
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Le volet ATTRIBUTS de la feuille (PE11b, miroir `renderAttrs` +
 * `renderEditor`) : un RECYCLERVIEW dont les items sont les layouts
 * réels de PREVIEW_UI.md § 9 (titre, barre d'outils épinglée, cartes de
 * section repliables — le CORPS n'est gonflé qu'à l'ouverture, état
 * mémorisé par titre — puis les groupes LECTURE SEULE) ; le catalogue
 * pur [CatalogueAttributs] reste la source de vérité, la mécanique
 * (ViewModel, ports, [EditionAttributsXml]) est conservée telle quelle
 * (ADR 0132).
 *
 * UN SEUL CHEMIN d'édition : chaque contrôle passe au ViewModel (UNE
 * écriture au port, rendu immédiat) ; un refus recharge le volet depuis
 * le texte vivant ([editerOuRecharger]). Les `@string` restent en
 * lecture seule (miroir `.ro`).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions + LargeClass —
 * chaque contrôle a SA fonction de liage (un type = un item layout).
 */
@Suppress("TooManyFunctions", "LargeClass")
@AndroidEntryPoint
internal class VoletAttributsFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_ATTRIBUTS

    private lateinit var liaison: FragmentVoletAttributsBinding

    private lateinit var adaptateur: AdaptateurAttributs

    /**
     * L'état de repli des SECTIONS de l'éditeur, par TITRE (miroir `S.so`
     * de la maquette — mémorisé pendant la session du fragment).
     */
    private val etatSections: MutableMap<String, Boolean> = mutableMapOf()

    // ── PE11f : la barre d'outils branchée (recherche + bascule) ──────
    // Miroir `S.attrQ`/`S.attrMode` de la maquette : la requête et le
    // mode SURVIVENT aux reconstructions du volet (l'état vit ici, la
    // barre n'est jamais reconstruite par la frappe).

    /** La requête de recherche courante (miroir `S.attrQ`, minuscule à la comparaison). */
    private val rechercheInterne = MutableStateFlow("")

    /** Le mode Courants (vrai) ou Tous (miroir `S.attrMode` — défaut Courants). */
    private val modeCourantsInterne = MutableStateFlow(true)

    /** La garde anti-boucle : brancher la barre ne re-déclenche pas la recherche. */
    private var branchementBarre: Boolean = false

    /**
     * Le contrat adaptateur : le LIAGE des corps de cartes reste au volet
     * (chaque contrôle a sa fonction, un type = un item layout).
     */
    private val evenementsAdaptateur =
        object : AdaptateurAttributs.Evenements {
            override fun lierCorpsCarte(
                carte: CarteSectionBinding,
                section: CatalogueAttributs.SectionAttributs,
                controles: List<CatalogueAttributs.ControleAttribut>,
                attrs: List<EditionAttributsXml.AttributSource>,
            ) {
                controles.forEach { controle ->
                    carte.corpsSection.addView(controleSection(controle, attrs))
                }
            }

            override fun lierBarre(barre: BarreOutilsAttributsBinding) {
                brancherBarre(barre)
            }

            override fun sectionOuverte(
                section: CatalogueAttributs.SectionAttributs,
                position: Int,
            ): Boolean =
                // La recherche OUVRE les sections qui restent (miroir
                // `d.open = qq ? true : …`).
                rechercheInterne.value.isNotBlank() ||
                    (etatSections[section.titre.name] ?: (position < DEFAUT_OUVERTES))

            override fun basculerSection(
                section: CatalogueAttributs.SectionAttributs,
                ouverte: Boolean,
            ) {
                // La recherche NE MÉMORISE PAS (miroir `if (!qq) S.so[t] = d.open`).
                if (rechercheInterne.value.isBlank()) etatSections[section.titre.name] = ouverte
            }

            override fun auBoutonAutres() {
                modeCourantsInterne.value = false
                majAttributs()
            }

            override fun auCorrectif(action: ActionCorrectif) {
                declencherCorrectif(action)
            }
        }

    private fun gonfleur(): LayoutInflater = LayoutInflater.from(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater,
        conteneur: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        liaison = FragmentVoletAttributsBinding.inflate(inflater, conteneur, false)
        adaptateur = AdaptateurAttributs(evenementsAdaptateur)
        liaison.voletAttributs.layoutManager = LinearLayoutManager(requireContext())
        liaison.voletAttributs.adapter = adaptateur
        liaison.voletAttributs.addItemDecoration(DecorationBarreCollante())
        return liaison.root
    }

    /** La mise en forme du volet (l'onglet est actif — porte de la base). */
    override fun marcher() {
        majAttributs()
    }

    /**
     * Les contrôles FILTRÉS d'une section (miroir `fill` de la
     * maquette) : la recherche cherche dans l'ÉTIQUETTE, le NOM
     * d'attribut et le TITRE de section, et elle PRIME sur le mode ;
     * sans recherche, le mode Courants retient les contrôles
     * `courant`, le mode Tous les garde tous. Les contrôles SANS nom
     * (dimensions, contraintes) suivent le TITRE de leur section.
     */
    private fun controlesFiltres(
        section: CatalogueAttributs.SectionAttributs,
    ): List<CatalogueAttributs.ControleAttribut> {
        val requete = rechercheInterne.value.trim().lowercase()
        val titre = titreSectionRes(section.titre).lowercase()
        if (requete.isEmpty()) {
            return if (modeCourantsInterne.value) {
                section.controles.filter { it.courant() }
            } else {
                section.controles
            }
        }
        return section.controles.filter { controle ->
            val nom = nomDuControle(controle)?.lowercase().orEmpty()
            val etiquette =
                etiquetteDuControle(controle)?.let { etiquetteRes(it).lowercase() }.orEmpty()
            titre.contains(requete) || nom.contains(requete) || etiquette.contains(requete)
        }
    }

    /**
     * Le drapeau Courants d'un contrôle (PE11a du brief PE10 : les
     * contrôles sans paramètres — dimensions, contraintes — sont
     * TOUJOURS courants).
     */
    private fun CatalogueAttributs.ControleAttribut.courant(): Boolean =
        when (this) {
            is CatalogueAttributs.ControleAttribut.Identifiant -> courant

            is CatalogueAttributs.ControleAttribut.Texte -> courant

            is CatalogueAttributs.ControleAttribut.Nombre -> courant

            is CatalogueAttributs.ControleAttribut.Choix -> courant

            is CatalogueAttributs.ControleAttribut.Liste -> courant

            is CatalogueAttributs.ControleAttribut.Drapeaux -> courant

            is CatalogueAttributs.ControleAttribut.TypeSaisie -> courant

            is CatalogueAttributs.ControleAttribut.TriEtat -> courant

            is CatalogueAttributs.ControleAttribut.Couleur -> courant

            is CatalogueAttributs.ControleAttribut.Ressource -> courant

            is CatalogueAttributs.ControleAttribut.Cotes -> courant

            CatalogueAttributs.ControleAttribut.Dimensions,
            CatalogueAttributs.ControleAttribut.Contraintes,
            -> true
        }

    /**
     * L'ÉTIQUETTE locale d'un contrôle (miroir `c.l` de la maquette) :
     * les contrôles SANS étiquette (identifiant, composite de saisie,
     * dimensions, contraintes) ne matchent que par leur NOM ou le
     * TITRE de leur section.
     */
    private fun etiquetteDuControle(controle: CatalogueAttributs.ControleAttribut): CatalogueAttributs.Etiquette? =
        when (controle) {
            is CatalogueAttributs.ControleAttribut.Texte -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.Nombre -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.Choix -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.Liste -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.Drapeaux -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.TriEtat -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.Couleur -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.Ressource -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.Cotes -> controle.etiquette

            is CatalogueAttributs.ControleAttribut.Identifiant,
            is CatalogueAttributs.ControleAttribut.TypeSaisie,
            CatalogueAttributs.ControleAttribut.Dimensions,
            CatalogueAttributs.ControleAttribut.Contraintes,
            -> null
        }

    /**
     * Branche la BARRE d'outils (miroir `q.oninput` + la bascule
     * `aria-pressed` de la maquette, PE11f) : la frappe RECONSTRUIT
     * les items SANS rebrancher la barre (l'item BarreOutils ne change
     * pas — la frappe garde son focus, brief PE11f) et SANS vider le
     * focus du volet (le protocole crash 0.134.30 vise les VUES
     * RECYCLÉES ; la barre reste la même vue).
     */
    private fun brancherBarre(barre: BarreOutilsAttributsBinding) {
        branchementBarre = true
        barre.champRechercheAttribut.isVisible = true
        barre.groupeModeAttributs.isVisible = true
        if (barre.champRechercheAttribut.text?.toString() != rechercheInterne.value) {
            barre.champRechercheAttribut.setText(rechercheInterne.value)
        }
        barre.champRechercheAttribut.doAfterTextChanged { saisie ->
            if (branchementBarre) return@doAfterTextChanged
            rechercheInterne.value = saisie?.toString().orEmpty()
            adaptateur.soumettre(itemsVolet(renduCourant?.vues?.getOrNull(selectionCourante)))
        }
        barre.modeCourants.isChecked = modeCourantsInterne.value
        barre.modeTous.isChecked = !modeCourantsInterne.value
        barre.groupeModeAttributs.setOnCheckedStateChangeListener { groupe, cochees ->
            if (branchementBarre) return@setOnCheckedStateChangeListener
            modeCourantsInterne.value = barre.modeCourants.id in cochees
            adaptateur.soumettre(itemsVolet(renduCourant?.vues?.getOrNull(selectionCourante)))
        }
        branchementBarre = false
    }

    /**
     * L'onglet Attributs (miroir `renderAttrs`) : la liste des items —
     * titre, barre d'outils, cartes repliables, trait, groupes LECTURE
     * SEULE (identité, disposition, mesuré) — soumise à l'adaptateur.
     * Le protocole focus-sûr du crash 0.134.30 (NPE `mViewFlags`, rapport
     * c8f6ffeb) est CONSERVÉ : le focus quitte le volet AVANT la
     * soumission (les vues focusées sont recyclées par la liste).
     */
    private fun majAttributs() {
        val vue = renduCourant?.vues?.getOrNull(selectionCourante)
        liaison.voletAttributs.findFocus()?.clearFocus()
        adaptateur.soumettre(itemsVolet(vue))
    }

    /** La LISTE des items du volet (PREVIEW_UI.md § 8, de haut en bas). */
    private fun itemsVolet(vue: InfoVueRendu?): List<ItemAttributs> =
        buildList {
            if (vue == null) {
                add(ItemAttributs.Vide)
                return@buildList
            }
            val classe = viewModel.classeSelection() ?: vue.classe
            val genreParent =
                viewModel.genreParentSelection() ?: CatalogueAttributs.GenreParent.AUCUN
            add(ItemAttributs.Titre(getString(R.string.preview_modifier_classe, classe)))
            add(ItemAttributs.BarreOutils)
            val attrs = viewModel.attributsSelection()
            if (attrs == null) {
                // Un span périmé retombe en LECTURE SEULE avec explication
                // (miroir piège § 6 du prompt) — les corrections du rendu
                // seraient stalles aussi : elles vivent avec l'éditable.
                add(ItemAttributs.LectureSeule(getString(R.string.preview_edition_impossible_toast)))
            } else {
                // PE11g — LES CORRECTIONS RAPIDES (miroir quickFixes) : une
                // carte par problème de la vue qui a UNE action, AVANT les
                // sections (PREVIEW_UI.md § 8 : item 2 « correctifs »).
                ajouterCorrectifs(this, vue)
                val sections = CatalogueAttributs.sections(classe, genreParent)
                var visibles = 0
                var masques = 0
                sections.forEachIndexed { position, section ->
                    val controles = controlesFiltres(section)
                    if (controles.isEmpty()) return@forEachIndexed
                    masques += section.controles.size - controles.size
                    visibles++
                    add(
                        ItemAttributs.Carte(
                            section = section,
                            controles = controles,
                            attrs = attrs,
                            position = position,
                            titre = titreSectionRes(section.titre),
                            definis = nombreDefinis(section, attrs),
                        ),
                    )
                }
                if (visibles == 0) {
                    // Miroir le `<p class="empty">` de la maquette (la
                    // suggestion « Ajouter … comme attribut libre » arrive
                    // avec la section Autres attributs, PE11h).
                    add(ItemAttributs.Message(getString(R.string.preview_attr_aucun, rechercheInterne.value.trim())))
                }
                // Miroir le `.linkbtn.wide` : visible en mode Courants
                // quand des attributs sont MASQUÉS (toujours 0 dans la
                // 1re vague — tous courants — le bouton s'allumera avec
                // les drapeaux de la 2e vague).
                if (rechercheInterne.value.isBlank() && modeCourantsInterne.value && masques > 0) {
                    add(
                        ItemAttributs.BoutonAutres(
                            resources.getQuantityString(R.plurals.preview_attr_autres, masques, masques, classe),
                        ),
                    )
                }
                add(ItemAttributs.Separateur)
            }
            for (groupe in FeuilleApercu.groupesAttributs(vue, viewModel.moteur.profil.densiteDpi)) {
                add(ItemAttributs.TitreGroupe(groupe.titre))
                groupe.entrees.forEach { entree ->
                    add(ItemAttributs.Attribut(entree.libelle, entree.valeur))
                }
            }
        }

    /** Le titre localisé d'une section (clé sémantique → ressource — table
     * unique, miroir [ETIQUETTES_RES]). */
    private fun titreSectionRes(titre: CatalogueAttributs.TitreSection): String = getString(TITRES_RES.getValue(titre))

    /**
     * Les cartes de CORRECTION RAPIDE (PE11g — miroir `quickFixes` :
     * `S.probs.filter(p => p.rid === v.rid)`) : une carte par problème de
     * la VUE SÉLECTIONNÉE qui a UNE action — le titre de groupe n'apparaît
     * que s'il y a des cartes (miroir le bloc `.fixes` absent à zéro fix).
     */
    private fun ajouterCorrectifs(
        items: MutableList<ItemAttributs>,
        vue: InfoVueRendu,
    ) {
        val vues = renduCourant?.vues ?: return
        val cartes =
            ProblemesPreview
                .evaluer(vues, viewModel.moteur.profil.densiteDpi)
                .filter { it.indiceVue == selectionCourante }
                .mapNotNull { correctifDe(it, vue) }
        if (cartes.isEmpty()) return
        items.add(ItemAttributs.TitreGroupe(getString(R.string.preview_correctifs_titre)))
        items.addAll(cartes)
    }

    /**
     * La CARTE d'un problème ACTIONNABLE (miroir `quickFixes` : titre =
     * `p.title`, corps = `p.msg`, bouton = `fx.b`). `null` = PAS d'action :
     * « Ajouter la dépendance » de la classe introuvable est HORS périmètre
     * (brief : « n'afficher le bouton que si l'action existe »).
     */
    private fun correctifDe(
        probleme: ProblemesPreview.ProblemeApercu,
        vue: InfoVueRendu,
    ): ItemAttributs.Correctif? {
        val action =
            when (probleme.genre) {
                ProblemesPreview.GenreProbleme.CLASSE_ABSENTE -> return null
                ProblemesPreview.GenreProbleme.CIBLE_TACTILE -> ActionCorrectif.ElargirCible
                ProblemesPreview.GenreProbleme.SANS_CONTENT_DESCRIPTION -> ActionCorrectif.OuvrirContentDescription
                ProblemesPreview.GenreProbleme.TEXTE_CODE_DUR -> ActionCorrectif.ExtraireTexte
                ProblemesPreview.GenreProbleme.TEXTE_TRONQUE -> ActionCorrectif.AutoriserDeuxLignes
            }
        return ItemAttributs.Correctif(
            genre = probleme.genre,
            titre = LibellesProblemes.titre(requireContext(), probleme.genre, vue),
            message =
                LibellesProblemes.message(
                    requireContext(),
                    probleme.genre,
                    vue,
                    viewModel.moteur.profil.densiteDpi,
                    viewModel.echellePolice,
                ),
            bouton = getString(boutonDe(action)),
            action = action,
        )
    }

    /** Le LIBELLÉ localisé du bouton d'une action (miroir `fx.b`). */
    private fun boutonDe(action: ActionCorrectif): Int =
        when (action) {
            ActionCorrectif.ElargirCible -> R.string.preview_correctif_cible_bouton
            ActionCorrectif.ExtraireTexte -> R.string.preview_correctif_extraire_bouton
            ActionCorrectif.AutoriserDeuxLignes -> R.string.preview_correctif_tronque_bouton
            ActionCorrectif.OuvrirContentDescription -> R.string.preview_correctif_description_bouton
        }

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

            is CatalogueAttributs.ControleAttribut.Liste -> controle.nom

            is CatalogueAttributs.ControleAttribut.Drapeaux -> controle.nom

            is CatalogueAttributs.ControleAttribut.TypeSaisie -> controle.nom

            is CatalogueAttributs.ControleAttribut.TriEtat -> controle.nom

            is CatalogueAttributs.ControleAttribut.Couleur -> controle.nom

            is CatalogueAttributs.ControleAttribut.Ressource -> controle.nom

            is CatalogueAttributs.ControleAttribut.Cotes,
            CatalogueAttributs.ControleAttribut.Dimensions,
            CatalogueAttributs.ControleAttribut.Contraintes,
            -> null
        }

    /** La DÉRIVATION d'un contrôle (miroir `ctl` : un type = un item layout). */
    private fun controleSection(
        controle: CatalogueAttributs.ControleAttribut,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View =
        when (controle) {
            is CatalogueAttributs.ControleAttribut.Identifiant -> controleIdentifiant(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Texte -> controleTexte(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Nombre -> controleNombre(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Choix -> controleChoix(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Liste -> controleListe(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Drapeaux -> controleDrapeaux(controle, attrs)
            is CatalogueAttributs.ControleAttribut.TypeSaisie -> controleTypeSaisie(controle, attrs)
            is CatalogueAttributs.ControleAttribut.TriEtat -> controleTriEtat(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Couleur -> controleCouleur(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Ressource -> controleRessource(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Cotes -> controleCotes(controle, attrs)
            CatalogueAttributs.ControleAttribut.Dimensions -> controleDimensions()
            CatalogueAttributs.ControleAttribut.Contraintes -> controleContraintes()
        }

    /** La valeur courante d'un contrôle nommé (lecture dans les attributs lus). */
    private fun valeurDe(
        nom: String,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): String? = attrs.firstOrNull { it.nom == nom }?.valeur

    /** Un bloc LECTURE SEULE (item_lecture_seule.xml — mono sur surface 2). */
    private fun blocLectureSeule(
        parent: ViewGroup,
        texte: String,
    ): TextView =
        ItemLectureSeuleBinding
            .inflate(gonfleur(), parent, false)
            .apply {
                valeurLectureSeule.text = texte
            }.valeurLectureSeule

    /** Un contrôle TEXTE (controle_texte.xml) : littéral éditable ou
     * référence en lecture seule (miroir `.ro`), EXTRACTION offerte (PE8c). */
    private fun controleTexte(
        controle: CatalogueAttributs.ControleAttribut.Texte,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleTexteBinding.inflate(gonfleur())
        val libelle = etiquetteRes(controle.etiquette)
        bloc.entete.libelleControle.text = libelle
        val valeur = valeurDe(controle.nom, attrs)
        if (valeur != null && (valeur.startsWith("@") || valeur.startsWith("?"))) {
            // Une référence se MONTRE, jamais écrasée (miroir la ligne `.ro`).
            bloc.conteneurSaisie.addView(
                blocLectureSeule(
                    bloc.conteneurSaisie,
                    getString(R.string.preview_modifier_reference, valeur, ""),
                ),
            )
            return bloc.root
        }
        val saisie = SaisieTexteBinding.inflate(gonfleur(), bloc.conteneurSaisie, false)
        saisie.champSaisie.setText(valeur ?: "")
        saisie.champSaisie.hint = libelle
        saisie.champSaisie.contentDescription = libelle
        // PE11g — le NOM d'attribut en tag : « Renseigner » amène le champ
        // du contentDescription par findViewWithTag (miroir `S.focusAttr`).
        saisie.champSaisie.tag = controle.nom
        val commettre = {
            commiterTexte(saisie.champSaisie) { nouvelle ->
                viewModel.definirAttributSelection(controle.nom, nouvelle)
            }
        }
        saisie.champSaisie.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commettre()
                saisie.champSaisie.clearFocus()
                true
            } else {
                false
            }
        }
        saisie.champSaisie.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        bloc.conteneurSaisie.addView(saisie.champSaisie)
        // L'EXTRACTION (PE8c) : littéral + session strings.xml ouverte.
        if (controle.extrayable && viewModel.extractionPossible(controle.nom)) {
            bloc.root.addView(rangeeExtraction(controle, attrs))
        }
        return bloc.root
    }

    /** Le commit d'un champ TEXTE : vide = rien (miroir les champs PE7e). */
    private fun commiterTexte(
        champ: EditText,
        action: (String) -> Boolean,
    ) {
        val saisie =
            champ.text
                ?.toString()
                .orEmpty()
        if (saisie.isNotEmpty()) editerOuRecharger { action(saisie) }
    }

    /** La rangée d'EXTRACTION (controle_extraction.xml, PE8c) : le bouton
     * révèle le champ du nom (pré-rempli par [ExtractionRessource.nomPropose])
     * et le valider. */
    private fun rangeeExtraction(
        controle: CatalogueAttributs.ControleAttribut.Texte,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val rangee = ControleExtractionBinding.inflate(gonfleur())
        rangee.boutonExtraire.text = getString(R.string.preview_extraire_bouton)
        rangee.boutonValiderExtraction.text = getString(R.string.preview_extraire_valider)
        rangee.champNomRessource.hint = getString(R.string.preview_extraire_nom)
        rangee.champNomRessource.contentDescription = getString(R.string.preview_extraire_nom)
        rangee.boutonExtraire.setOnClickListener {
            val reveler = rangee.champNomRessource.isVisible.not()
            rangee.champNomRessource.isVisible = reveler
            rangee.boutonValiderExtraction.isVisible = reveler
            if (reveler) {
                rangee.champNomRessource.setText(
                    ExtractionRessource.nomPropose(valeurDe(controle.nom, attrs) ?: ""),
                )
            }
        }
        rangee.boutonValiderExtraction.setOnClickListener {
            val nom =
                rangee.champNomRessource.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            if (!viewModel.extraireEnRessource(controle.nom, nom)) {
                editerOuRecharger(R.string.preview_extraire_refuse) { false }
            }
        }
        return rangee.root
    }

    /** Un contrôle IDENTIFIANT (controle_identifiant.xml, PE8a) : valeur
     * courante en mono, champ « nouveau nom » validé à l'IME/à la perte
     * de focus — le renommage couvre la déclaration ET les références du
     * fichier (une édition) ; hors périmètre signalé (code R.id, autres
     * fichiers). */
    private fun controleIdentifiant(
        controle: CatalogueAttributs.ControleAttribut.Identifiant,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleIdentifiantBinding.inflate(gonfleur())
        bloc.libelleControle.text = etiquetteRes(CatalogueAttributs.Etiquette.IDENTIFIANT)
        val brut = valeurDe(controle.nom, attrs)
        bloc.conteneurCourant.addView(
            blocLectureSeule(
                bloc.conteneurCourant,
                brut ?: getString(R.string.preview_identite_absent),
            ),
        )
        bloc.champNouveauNom.hint = getString(R.string.preview_identite_nouveau)
        bloc.champNouveauNom.contentDescription = getString(R.string.preview_identite_nouveau)
        val commettre = { commiterRenommage(bloc.champNouveauNom, controle, attrs) }
        bloc.champNouveauNom.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) bloc.champNouveauNom.clearFocus()
                true
            } else {
                false
            }
        }
        bloc.champNouveauNom.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        return bloc.root
    }

    /** Le nom SANS préfixe d'une valeur d'id (`@+id/x`/`@id/x` → `x`), sinon la valeur brute. */
    private fun nomCourant(valeur: String): String =
        when {
            valeur.startsWith("@+id/") -> valeur.removePrefix("@+id/")
            valeur.startsWith("@id/") -> valeur.removePrefix("@id/")
            else -> valeur
        }

    /** Le commit d'un RENOMMAGE (miroir `commiterNombre`) : vide = rien,
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

    /** Un contrôle NOMBRE (saisie_nombre.xml) avec unité — saisie refusée
     * proprement (repli visuel) si non numérique. */
    private fun controleNombre(
        controle: CatalogueAttributs.ControleAttribut.Nombre,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = SaisieNombreBinding.inflate(gonfleur())
        val libelle = etiquetteRes(controle.etiquette)
        bloc.champNombre.setText(valeurDe(controle.nom, attrs)?.trimEnd('d', 'p', 's'))
        bloc.champNombre.hint = libelle
        bloc.champNombre.contentDescription = libelle
        val commettre = { commiterNombre(bloc.champNombre, controle, attrs) }
        bloc.champNombre.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) bloc.champNombre.clearFocus()
                true
            } else {
                false
            }
        }
        bloc.champNombre.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        return bloc.root
    }

    /** Le commit d'un NOMBRE : vide = rien, non numérique = refus visuel (repli). */
    private fun commiterNombre(
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

    /** Un contrôle CHOIX (controle_segments.xml) — le PREMIER choix est
     * le défaut (retrait de l'attribut). */
    private fun controleChoix(
        controle: CatalogueAttributs.ControleAttribut.Choix,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleSegmentsBinding.inflate(gonfleur())
        bloc.entete.libelleControle.text = etiquetteRes(controle.etiquette)
        val courant = valeurDe(controle.nom, attrs) ?: controle.valeurs.first()
        segmentes(bloc.groupeSegments, controle.valeurs, courant, etiquetteRes(controle.etiquette)) { choix ->
            val valeur = if (choix == controle.valeurs.first()) "" else choix
            editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
        }
        return bloc.root
    }

    /**
     * Un contrôle LISTE (controle_liste.xml — miroir `.fld select`, PE11c,
     * § 9 : tout enum de plus de 4 valeurs). La première entrée « par
     * défaut » retire l'attribut ; une valeur courante hors catalogue est
     * ajoutée à la fin (miroir `opts.push(cur)` de la maquette) ; le champ
     * ne SAISIT pas (inputType none) — il ne fait qu'ouvrir le menu.
     */
    private fun controleListe(
        controle: CatalogueAttributs.ControleAttribut.Liste,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleListeBinding.inflate(gonfleur())
        bloc.entete.libelleControle.text = etiquetteRes(controle.etiquette)
        val courant = valeurDe(controle.nom, attrs)
        // Miroir maquette : la valeur posée hors catalogue reste visible.
        val valeurs =
            if (courant != null && courant !in controle.valeurs) {
                controle.valeurs + courant
            } else {
                controle.valeurs
            }
        val options = listOf(getString(R.string.preview_choix_defaut)) + valeurs
        bloc.listeDeroulante.setText(courant ?: getString(R.string.preview_choix_defaut), false)
        // Garantie de non-saisie (miroir select) : le layout pose déjà
        // focusable=false + inputType none — le keyListener null l'assure
        // aussi sur les appareils où inputType none laisse passer l'IME.
        bloc.listeDeroulante.keyListener = null
        bloc.listeDeroulante.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_option_liste, options),
        )
        bloc.listeDeroulante.setOnItemClickListener { _, _, position, _ ->
            val valeur = if (position == 0) "" else options[position]
            editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
        }
        // Sans frappe (inputType none), le menu ne s'ouvre qu'au TAP —
        // miroir du select natif de la maquette.
        bloc.listeDeroulante.setOnClickListener { vue ->
            (vue as? AppCompatAutoCompleteTextView)?.showDropDown()
        }
        return bloc.root
    }

    /**
     * Un contrôle DRAPEAUX (controle_drapeaux.xml — miroir `.flg`, PE11d) :
     * les drapeaux cochés suivent les parties de la valeur courante, un
     * drapeau posé hors catalogue est AJOUTÉ à la fin (miroir
     * `opts.concat(parts…)`), et la bascule écrit les parties retenues
     * ORDONNÉES selon les options (miroir `opts.filter(…).join('|')`) ;
     * tout décoché RETIRE l'attribut. Le groupe est MULTI-sélection.
     */
    private fun controleDrapeaux(
        controle: CatalogueAttributs.ControleAttribut.Drapeaux,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleDrapeauxBinding.inflate(gonfleur())
        val libelle = etiquetteRes(controle.etiquette)
        bloc.entete.libelleControle.text = libelle
        val valeur = valeurDe(controle.nom, attrs)
        val parties = controle.parties(valeur)
        controle.options(valeur).forEach { option ->
            val drapeau = ItemDrapeauBinding.inflate(gonfleur(), bloc.groupeDrapeaux, false)
            drapeau.drapeau.text = option
            drapeau.drapeau.isChecked = option in parties
            drapeau.drapeau.contentDescription = "$libelle : $option"
            drapeau.drapeau.setOnClickListener {
                editerOuRecharger {
                    viewModel.definirAttributSelection(
                        controle.nom,
                        controle.basculer(valeur, option),
                    )
                }
            }
            bloc.groupeDrapeaux.addView(drapeau.root)
        }
        return bloc.root
    }

    /**
     * Le TYPE DE SAISIE composite (PE11d — miroir `input` de la maquette,
     * § 9 : `controle_liste` + `controle_drapeaux`) : la BASE en liste
     * déroulante (sans « par défaut » — « text » EST le défaut), puis les
     * MODIFICATEURS en drapeaux SOUS le champ, désactivés (alpha .4) quand
     * la base n'est pas textuelle (miroir `b.disabled =
     * !base.startsWith('text')`). Le groupe de drapeaux vient du contrôle
     * de référence : seule sa RANGÉE est greffée, son en-tête ne l'est pas
     * (le composite n'a qu'UNE étiquette — miroir du DOM `f.appendChild`).
     */
    private fun controleTypeSaisie(
        controle: CatalogueAttributs.ControleAttribut.TypeSaisie,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleListeBinding.inflate(gonfleur())
        bloc.entete.libelleControle.text = etiquetteRes(CatalogueAttributs.Etiquette.TYPE_SAISIE)
        val valeur = valeurDe(controle.nom, attrs)
        val base = controle.base(valeur)
        val modificateurs = controle.modificateursPoses(valeur)
        bloc.listeDeroulante.setText(base, false)
        // Garantie de non-saisie (miroir select) — cf. controleListe.
        bloc.listeDeroulante.keyListener = null
        bloc.listeDeroulante.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_option_liste, controle.bases),
        )
        bloc.listeDeroulante.setOnItemClickListener { _, _, position, _ ->
            val nouvelleBase = controle.bases[position]
            editerOuRecharger {
                viewModel.definirAttributSelection(
                    controle.nom,
                    controle.composer(nouvelleBase, modificateurs),
                )
            }
        }
        bloc.listeDeroulante.setOnClickListener { vue ->
            (vue as? AppCompatAutoCompleteTextView)?.showDropDown()
        }
        // Les MODIFICATEURS : la rangée du contrôle drapeaux, greffée sous
        // le champ (l'écart de 4 dp est celui du `.fld`, marginTop du groupe) ;
        // l'en-tête du contrôle gonflé n'est PAS greffé (une seule étiquette).
        val drapeaux = ControleDrapeauxBinding.inflate(gonfleur())
        val groupeModificateurs = drapeaux.groupeDrapeaux
        (groupeModificateurs.parent as? ViewGroup)?.removeView(groupeModificateurs)
        groupeModificateurs.contentDescription = getString(R.string.preview_drapeaux_options_saisie)
        bloc.root.addView(groupeModificateurs)
        val textuelle = base.startsWith("text")
        controle.modificateurs.forEach { modificateur ->
            val drapeau = ItemDrapeauBinding.inflate(gonfleur(), groupeModificateurs, false)
            // Miroir maquette : le glyphe retire le préfixe « text », le
            // nom complet reste la description (miroir `b.title = x`).
            drapeau.drapeau.text = modificateur.removePrefix("text")
            drapeau.drapeau.isChecked = modificateur in modificateurs
            drapeau.drapeau.contentDescription = modificateur
            drapeau.drapeau.isEnabled = textuelle
            drapeau.drapeau.alpha = if (textuelle) 1f else ALPHA_DESACTIVE
            drapeau.drapeau.setOnClickListener {
                val nouvelles =
                    if (modificateur in modificateurs) {
                        modificateurs - modificateur
                    } else {
                        modificateurs + modificateur
                    }
                editerOuRecharger {
                    viewModel.definirAttributSelection(
                        controle.nom,
                        controle.composer(base, nouvelles),
                    )
                }
            }
            groupeModificateurs.addView(drapeau.root)
        }
        return bloc.root
    }

    /** Un contrôle TRI-ÉTAT (controle_segments.xml partagé — défaut =
     * retrait). */
    private fun controleTriEtat(
        controle: CatalogueAttributs.ControleAttribut.TriEtat,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleSegmentsBinding.inflate(gonfleur())
        bloc.entete.libelleControle.text = etiquetteRes(controle.etiquette)
        val valeurs =
            listOf(
                getString(R.string.preview_choix_defaut),
                getString(R.string.preview_choix_vrai),
                getString(R.string.preview_choix_faux),
            )
        val courant =
            when (valeurDe(controle.nom, attrs)) {
                "true" -> getString(R.string.preview_choix_vrai)
                "false" -> getString(R.string.preview_choix_faux)
                else -> getString(R.string.preview_choix_defaut)
            }
        segmentes(bloc.groupeSegments, valeurs, courant, etiquetteRes(controle.etiquette)) { choix ->
            val valeur =
                when (choix) {
                    getString(R.string.preview_choix_vrai) -> "true"
                    getString(R.string.preview_choix_faux) -> "false"
                    else -> ""
                }
            editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
        }
        return bloc.root
    }

    /**
     * La rangée de SEGMENTS (miroir `.seg2` — ChipGroup de item_segment,
     * ADR 0132 § 3) : le tap valide (pas de frappe intermédiaire), le
     * segment coché suit l'état `isChecked` (sélecteurs de couleur).
     */
    private fun segmentes(
        groupe: ChipGroup,
        valeurs: List<String>,
        courant: String,
        description: String,
        onChoix: (String) -> Unit,
    ) {
        valeurs.forEach { valeur ->
            val segment = ItemSegmentBinding.inflate(gonfleur(), groupe, false)
            segment.segment.text = valeur
            segment.segment.isChecked = valeur == courant
            segment.segment.contentDescription = "$description : $valeur"
            segment.segment.setOnClickListener {
                if (valeur != courant) onChoix(valeur)
            }
            groupe.addView(segment.root)
        }
    }

    /**
     * Un contrôle COULEUR (controle_couleur.xml, PE11e — miroir `colr`
     * de la maquette) : pastille à TROIS couches (damier posé en code —
     * [DamierPastille], miroir `DamierDrawable` de la référence — puis
     * teinte TINTÉE, cadre du layout), champ hex VALIDÉ au commit
     * (miroir `vCol` : ERREUR n'écrit rien, AVERTISSEMENT écrit quand
     * même, vide RETIRE l'attribut), bouton « Palette » qui ouvre les
     * `@color` du projet + les entrées fixes (miroir `.cpal`).
     */
    private fun controleCouleur(
        controle: CatalogueAttributs.ControleAttribut.Couleur,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleCouleurBinding.inflate(gonfleur())
        bloc.entete.libelleControle.text = etiquetteRes(controle.etiquette)
        val valeur = valeurDe(controle.nom, attrs)
        bloc.pastilleDamier.background = DamierPastille(bloc.root.resources)
        poserTeintePastille(bloc.pastilleTeinte, valeur)
        bloc.champCouleur.setText(valeur ?: "")
        val libelleHint = getString(R.string.preview_attr_couleur_hint)
        bloc.champCouleur.hint = libelleHint
        bloc.champCouleur.contentDescription = libelleHint
        val commettre = { commiterCouleur(bloc, controle, attrs) }
        bloc.champCouleur.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) bloc.champCouleur.clearFocus()
                true
            } else {
                false
            }
        }
        bloc.champCouleur.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        // L'avertissement de la valeur COURANTE se montre d'emblée
        // (miroir `const m0 = vCol(cur); if (m0.warn) showMsg(f, m0)`).
        when (val verdict = verdictCouleur(valeur)) {
            is ValidationAttribut.Resultat.Avertissement -> {
                montrerMessageCouleur(bloc, R.color.apercu_avert, verdict.genre, verdict.arguments)
            }

            else -> {
                masquerMessageCouleur(bloc)
            }
        }
        bloc.boutonPalette.setOnClickListener {
            basculerPaletteCouleurs(bloc, controle)
        }
        return bloc.root
    }

    /**
     * Le VERDICT de validation d'une valeur du contrôle couleur : les
     * `@color`/`@drawable` du projet nourrissent l'avertissement
     * « introuvable » (miroir `RES` de la maquette — l'état VIVANT du
     * ViewModel, rechargé à chaque suivi de projet).
     */
    private fun verdictCouleur(valeur: String?): ValidationAttribut.Resultat =
        ValidationAttribut.couleur(
            valeur,
            viewModel.couleursProjet.value
                .map { it.nom }
                .toSet(),
            viewModel.drawablesProjet.value,
        )

    /**
     * POSE la TEINTE de la pastille du contrôle couleur (miroir
     * `sw.style.setProperty('--c', colorCss(cur) || 'transparent')`) :
     * résolution par [CouleursProjetXml.resoudre] (hex, `@color/`, trio
     * `@android:color/`), puis le THÈME de l'aperçu pour les six
     * `?attr/` de `ATTRS_THEME` (miroir `ATTR_CSS` — le thème Material
     * de l'activité porte les mêmes noms). Introuvable = SANS teinte :
     * le damier reste seul (miroir `transparent`).
     */
    private fun poserTeintePastille(
        vue: View,
        valeur: String?,
    ) {
        vue.setBackgroundResource(R.drawable.apercu_pastille_teinte)
        val teinte =
            CouleursProjetXml
                .resoudre(valeur, viewModel.couleursProjet.value.associate { it.nom to it.valeur })
                ?.toInt()
                ?: valeur
                    ?.removePrefix("?attr/")
                    ?.takeIf { it in CouleursProjetXml.ATTRS_THEME }
                    ?.let { teinteTheme(it) }
        vue.backgroundTintList =
            teinte?.let { couleur ->
                android.content.res.ColorStateList
                    .valueOf(couleur)
            }
    }

    /**
     * La couleur d'un ATTRIBUT DE THÈME (miroir `ATTR_CSS` de la
     * maquette : `colorPrimary`→`var(--s-pri)`, `colorSurface`→
     * `var(--s-surf)`…) : le thème de l'aperçu porte les MÊMES jetons
     * par ses attrs propres ([StyleVolet] — résolution par
     * `resolveAttribute`, jamais d'identifiant d'attr d'une autre
     * bibliothèque).
     */
    private fun teinteTheme(nom: String): Int? {
        val attr =
            when (nom) {
                "colorPrimary" -> R.attr.apercuPrimaire
                "colorOnPrimary" -> R.attr.apercuSurPrimaire
                "colorSurface" -> R.attr.apercuSurface
                "colorOnSurface" -> R.attr.apercuTexte
                "colorOutline" -> R.attr.apercuTrait
                "colorError" -> R.attr.apercuErreur
                else -> return null
            }
        val valeur = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(attr, valeur, true)) valeur.data else null
    }

    /**
     * Le COMMIT du champ couleur (miroir le `onchange` de la maquette) :
     * ERREUR → RIEN s'écrit, message rouge + fond de champ d'erreur ;
     * AVERTISSEMENT → la valeur s'écrit QUAND MÊME, message ambre ;
     * VALIDE → écrit (le VIDE RETIRE l'attribut, miroir `setEx('')` —
     * un vide sur un attribut ABSENT reste un no-op silencieux).
     */
    private fun commiterCouleur(
        bloc: ControleCouleurBinding,
        controle: CatalogueAttributs.ControleAttribut.Couleur,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): Boolean {
        val saisie =
            bloc.champCouleur.text
                ?.toString()
                ?.trim()
                .orEmpty()
        return when (val verdict = verdictCouleur(saisie)) {
            is ValidationAttribut.Resultat.Erreur -> {
                montrerMessageCouleur(bloc, R.color.apercu_erreur, verdict.genre, verdict.arguments)
                bloc.champCouleur.setBackgroundResource(R.drawable.apercu_champ_fond_erreur)
                false
            }

            is ValidationAttribut.Resultat.Avertissement -> {
                montrerMessageCouleur(bloc, R.color.apercu_avert, verdict.genre, verdict.arguments)
                bloc.champCouleur.setBackgroundResource(R.drawable.apercu_champ_fond)
                if (saisie.isEmpty() && valeurDe(controle.nom, attrs) == null) {
                    true
                } else {
                    editerOuRecharger { viewModel.definirAttributSelection(controle.nom, saisie) }
                }
            }

            ValidationAttribut.Resultat.Valide -> {
                masquerMessageCouleur(bloc)
                bloc.champCouleur.setBackgroundResource(R.drawable.apercu_champ_fond)
                if (saisie.isEmpty() && valeurDe(controle.nom, attrs) == null) {
                    true
                } else {
                    editerOuRecharger { viewModel.definirAttributSelection(controle.nom, saisie) }
                }
            }
        }
    }

    /**
     * Le MESSAGE de validation sous le contrôle (miroir `showMsg` :
     * `.fmsg.err` rouge, `.fmsg.warn` ambre — `role=alert` =
     * `accessibilityLiveRegion` du layout).
     */
    private fun montrerMessageCouleur(
        bloc: ControleCouleurBinding,
        couleur: Int,
        genre: ValidationAttribut.GenreMessage,
        arguments: List<String>,
    ) = montrerMessageControle(bloc.messageControle, couleur, genre, arguments)

    /** Retire le message de validation du contrôle couleur (miroir `showMsg(f, null)`). */
    private fun masquerMessageCouleur(bloc: ControleCouleurBinding) {
        masquerMessageControle(bloc.messageControle)
    }

    /**
     * Le MESSAGE de validation d'un contrôle (miroir `showMsg` :
     * `.fmsg.err` rouge, `.fmsg.warn` ambre — `role=alert` =
     * `accessibilityLiveRegion` du layout). Partagé Couleur/Ressource.
     */
    private fun montrerMessageControle(
        message: TextView,
        couleurJeton: Int,
        genre: ValidationAttribut.GenreMessage,
        arguments: List<String>,
    ) {
        val ressource = messageRessource(genre)
        message.text =
            when (arguments.size) {
                0 -> getString(ressource)
                1 -> getString(ressource, arguments[0])
                else -> getString(ressource, arguments[0], arguments[1])
            }
        message.setTextColor(
            androidx.core.content.res.ResourcesCompat
                .getColor(resources, couleurJeton, null),
        )
        message.isVisible = true
    }

    /** La ressource chaîne d'un genre de message (règle 3 — jamais de chaîne en dur). */
    private fun messageRessource(genre: ValidationAttribut.GenreMessage): Int =
        when (genre) {
            ValidationAttribut.GenreMessage.COULEUR_INVALIDE -> R.string.preview_attr_couleur_invalide
            ValidationAttribut.GenreMessage.COULEUR_INTROUVABLE -> R.string.preview_attr_couleur_introuvable
            ValidationAttribut.GenreMessage.DRAWABLE_INTROUVABLE -> R.string.preview_attr_drawable_introuvable
            ValidationAttribut.GenreMessage.REFERENCE_ATTENDUE -> R.string.preview_attr_reference_attendue
            ValidationAttribut.GenreMessage.TYPE_REFUSE -> R.string.preview_attr_type_refuse
            ValidationAttribut.GenreMessage.RESSOURCE_INTROUVABLE -> R.string.preview_attr_ressource_introuvable
        }

    /** Retire le message de validation (miroir `showMsg(f, null)` retire le bloc). */
    private fun masquerMessageControle(message: TextView) {
        message.isVisible = false
    }

    /**
     * OUVRE/FERME la palette de couleurs (miroir le `onclick` du bouton
     * Palette : la palette se RETIRE au second tap ; `aria-expanded` =
     * `stateDescription`). Les ressources du projet sont RECHARGÉES à
     * l'ouverture (elles ont pu changer dans l'éditeur).
     */
    private fun basculerPaletteCouleurs(
        bloc: ControleCouleurBinding,
        controle: CatalogueAttributs.ControleAttribut.Couleur,
    ) {
        val ouvrir = !bloc.paletteCouleurs.isVisible
        if (ouvrir) viewModel.rechargerRessourcesProjet()
        bloc.paletteCouleurs.isVisible = ouvrir
        // `aria-expanded` de la maquette : stateDescription (API 30+, la
        // garde remplace tout tools:ignore — règle 8).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            bloc.boutonPalette.stateDescription =
                getString(if (ouvrir) R.string.preview_attr_palette_ouverte else R.string.preview_attr_palette_fermee)
        }
        if (!ouvrir) return
        val couleurs = viewModel.couleursProjet.value
        val pastilles =
            couleurs.map { couleur ->
                AdaptateurPastilles.Pastille(
                    nom = "@color/${couleur.nom}",
                    teinte = CouleursProjetXml.resoudre(couleur.valeur, emptyMap())?.toInt(),
                )
            } +
                CouleursProjetXml.PALETTE_FIXE.map { nom ->
                    AdaptateurPastilles.Pastille(nom = nom, teinte = teintePaletteFixe(nom))
                }
        bloc.paletteCouleurs.adapter =
            AdaptateurPastilles(pastilles) { choix ->
                bloc.champCouleur.setText(choix)
                masquerMessageCouleur(bloc)
                editerOuRecharger { viewModel.definirAttributSelection(controle.nom, choix) }
            }
        bloc.paletteCouleurs.post {
            val ressources = bloc.paletteCouleurs.resources
            val cellule =
                ressources.getDimensionPixelSize(R.dimen.apercu_palette_pastille) +
                    2 * ressources.getDimensionPixelSize(R.dimen.apercu_pastille_palette_inset)
            bloc.paletteCouleurs.layoutManager =
                androidx.recyclerview.widget.GridLayoutManager(
                    requireContext(),
                    AdaptateurPastilles.colonnesPastille(bloc.paletteCouleurs.width, cellule),
                )
        }
    }

    /** La teinte d'une entrée FIXE de la palette (trio système + deux `?attr/` de thème). */
    private fun teintePaletteFixe(nom: String): Int? =
        when {
            nom.startsWith("@android:color/") -> {
                CouleursProjetXml.resoudre(nom, emptyMap())?.toInt()
            }

            nom.startsWith("?attr/") -> {
                teinteTheme(nom.removePrefix("?attr/"))
            }

            else -> {
                null
            }
        }

    /**
     * Un contrôle RESSOURCE (controle_ressource.xml, PE11f — miroir
     * `res` de la maquette) : champ à SUGGESTIONS `@type/nom` (miroir
     * le `<datalist>`), un nom NU saisi est COMPLÉTÉ `@premierType/nom`
     * au commit, validation miroir `vRes` (TYPE refusé ou forme cassée
     * = ERREUR sans écriture ; ressource ABSENTE du projet =
     * AVERTISSEMENT qui s'écrit quand même ; vide = RETRAIT).
     */
    private fun controleRessource(
        controle: CatalogueAttributs.ControleAttribut.Ressource,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleRessourceBinding.inflate(gonfleur())
        bloc.entete.libelleControle.text = etiquetteRes(controle.etiquette)
        val valeur = valeurDe(controle.nom, attrs)
        bloc.champRessource.setText(valeur.orEmpty())
        val libelle = etiquetteRes(controle.etiquette)
        bloc.champRessource.hint = libelle
        bloc.champRessource.contentDescription = libelle
        // Miroir le `<datalist>` : une suggestion par ressource des
        // types acceptés (`@type/nom`).
        val suggestions =
            controle.types.flatMap { type ->
                viewModel.indexRessources.value[type]
                    .orEmpty()
                    .map { nom -> "@$type/$nom" }
            }
        bloc.champRessource.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_option_liste, suggestions),
        )
        bloc.champRessource.setOnItemClickListener { _, _, position, _ ->
            val choix = suggestions[position]
            masquerMessageControle(bloc.messageControle)
            bloc.champRessource.setBackgroundResource(R.drawable.apercu_champ_fond)
            editerOuRecharger { viewModel.definirAttributSelection(controle.nom, choix) }
        }
        val commettre = { commiterRessource(bloc, controle, attrs) }
        bloc.champRessource.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) bloc.champRessource.clearFocus()
                true
            } else {
                false
            }
        }
        bloc.champRessource.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        // L'avertissement de la valeur COURANTE se montre d'emblée
        // (même miroir que la couleur : `const m0 = vRes(cur)`).
        when (val verdict = verdictRessource(valeur, controle)) {
            is ValidationAttribut.Resultat.Avertissement -> {
                montrerMessageControle(bloc.messageControle, R.color.apercu_avert, verdict.genre, verdict.arguments)
            }

            else -> {
                masquerMessageControle(bloc.messageControle)
            }
        }
        return bloc.root
    }

    /** Le verdict `vRes` d'une valeur du contrôle ressource (l'index VIVANT du ViewModel). */
    private fun verdictRessource(
        valeur: String?,
        controle: CatalogueAttributs.ControleAttribut.Ressource,
    ): ValidationAttribut.Resultat =
        ValidationAttribut.ressource(valeur, controle.types, viewModel.indexRessources.value)

    /**
     * Le COMMIT du champ ressource (miroir le `inp(...)` de la
     * maquette) : le nom NU est COMPLÉTÉ AVANT la validation, une
     * ERREUR n'écrit RIEN (message + fond d'erreur), un AVERTISSEMENT
     * écrit quand même, le vide RETIRE l'attribut posé (un vide sur un
     * attribut ABSENT reste un no-op silencieux).
     */
    private fun commiterRessource(
        bloc: ControleRessourceBinding,
        controle: CatalogueAttributs.ControleAttribut.Ressource,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): Boolean {
        val saisie =
            bloc.champRessource.text
                ?.toString()
                ?.trim()
                .orEmpty()
        // Le VIDE : no-op silencieux sur un attribut absent, RETRAIT sinon.
        if (saisie.isEmpty() && valeurDe(controle.nom, attrs) == null) {
            masquerMessageControle(bloc.messageControle)
            bloc.champRessource.setBackgroundResource(R.drawable.apercu_champ_fond)
            return true
        }
        val valeur = controle.completer(saisie)
        bloc.champRessource.setText(valeur)
        bloc.champRessource.setBackgroundResource(R.drawable.apercu_champ_fond)
        if (valeur.isEmpty()) masquerMessageControle(bloc.messageControle)
        return when (val verdict = verdictRessource(valeur, controle)) {
            is ValidationAttribut.Resultat.Erreur -> {
                montrerMessageControle(bloc.messageControle, R.color.apercu_erreur, verdict.genre, verdict.arguments)
                bloc.champRessource.setBackgroundResource(R.drawable.apercu_champ_fond_erreur)
                false
            }

            is ValidationAttribut.Resultat.Avertissement -> {
                montrerMessageControle(bloc.messageControle, R.color.apercu_avert, verdict.genre, verdict.arguments)
                editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
            }

            ValidationAttribut.Resultat.Valide -> {
                if (valeur.isNotEmpty()) masquerMessageControle(bloc.messageControle)
                editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
            }
        }
    }

    /** Un contrôle CÔTÉS (controle_cotes.xml — les QUATRE champs nommés,
     * miroir `.mrow`). */
    private fun controleCotes(
        controle: CatalogueAttributs.ControleAttribut.Cotes,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleCotesBinding.inflate(gonfleur())
        bloc.entete.libelleControle.text = etiquetteRes(controle.etiquette)
        val cotes =
            listOf(
                Triple(EditionAttributsXml.Cote.HAUT, R.string.preview_cote_haut, bloc.coteHaut),
                Triple(EditionAttributsXml.Cote.FIN, R.string.preview_cote_droite, bloc.coteDroite),
                Triple(EditionAttributsXml.Cote.BAS, R.string.preview_cote_bas, bloc.coteBas),
                Triple(EditionAttributsXml.Cote.DEBUT, R.string.preview_cote_gauche, bloc.coteGauche),
            )
        cotes.forEach { (cote, libelleRes, champ) ->
            lierCote(controle, cote, getString(libelleRes), champ, attrs)
        }
        return bloc.root
    }

    /** UN champ de côté (commit IME/perte de focus, miroir `.mrow`). */
    private fun lierCote(
        controle: CatalogueAttributs.ControleAttribut.Cotes,
        cote: EditionAttributsXml.Cote,
        libelleCote: String,
        champ: AppCompatEditText,
        attrs: List<EditionAttributsXml.AttributSource>,
    ) {
        champ.setText(EditionAttributsXml.valeurCote(attrs, controle.base, cote)?.removeSuffix("dp"))
        champ.hint = libelleCote
        champ.contentDescription =
            getString(R.string.preview_cote_cd, etiquetteRes(controle.etiquette), libelleCote)
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
                    champ.setText(
                        EditionAttributsXml.valeurCote(attrs, controle.base, cote)?.removeSuffix("dp") ?: "",
                    )
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
    }

    /** Un contrôle DIMENSIONS (controle_dimensions.xml — les DEUX axes
     * Largeur/Hauteur, PE7d repris). */
    private fun controleDimensions(): View {
        val bloc = ControleDimensionsBinding.inflate(gonfleur())
        rangeeDimension(
            ItemDimensionBinding.inflate(gonfleur(), bloc.root, false).also { bloc.root.addView(it.root) },
            EditionAttributsXml.Axe.LARGEUR,
            getString(R.string.preview_modifier_largeur),
        )
        rangeeDimension(
            ItemDimensionBinding.inflate(gonfleur(), bloc.root, false).also { bloc.root.addView(it.root) },
            EditionAttributsXml.Axe.HAUTEUR,
            getString(R.string.preview_modifier_hauteur),
        )
        return bloc.root
    }

    /**
     * Le LIAGE d'une rangée de DIMENSION (item_dimension.xml, miroir
     * `dimrow`) : segments wrap/match/fixe + champ dp révélé quand le
     * mode est FIXE (le champ prend le relais sans valeur).
     */
    private fun rangeeDimension(
        rangee: ItemDimensionBinding,
        axe: EditionAttributsXml.Axe,
        libelle: String,
    ) {
        val nomAttribut = if (axe == EditionAttributsXml.Axe.LARGEUR) ATTR_LARGEUR else ATTR_HAUTEUR
        val valeur = viewModel.valeurAttributSelection(nomAttribut) ?: ""
        val (modeCourant, valeurFixe) = modeDimensionDe(valeur)
        rangee.etiquetteDimension.text = libelle
        rangee.champDimension.isVisible = modeCourant == EditionAttributsXml.ModeDimension.FIXE
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
        segmentes(rangee.groupeDimension, valeurs.map { it.second }, courant, description) { texteChoix ->
            val mode =
                valeurs.firstOrNull { it.second == texteChoix }?.first
                    ?: EditionAttributsXml.ModeDimension.FIXE
            rangee.champDimension.isVisible = mode == EditionAttributsXml.ModeDimension.FIXE
            ecrireDimension(axe, mode, valeurFixe)
        }
        rangee.champDimension.setText(valeurFixe?.toString() ?: "")
        rangee.champDimension.doAfterTextChanged { texte ->
            val nombre = texte?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (nombre > 0 && nombre != valeurFixe) {
                ecrireDimension(axe, EditionAttributsXml.ModeDimension.FIXE, nombre)
            }
        }
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

    /**
     * La section CONTRAINTES (controle_contraintes.xml, PE8b — ÉDITABLE) :
     * les QUATRE ancres du ConstraintLayout parent, chacune un bouton
     * CIBLE (menu Aucune / parent / frères à id) et un champ MARGE en dp
     * — chaque geste = UNE édition au port. Un span périmé retombe en
     * lecture seule (miroir PE7e).
     */
    private fun controleContraintes(): View {
        val bloc = ControleContraintesBinding.inflate(gonfleur())
        val lues = viewModel.lireContraintesSelection()
        if (lues == null) {
            bloc.root.addView(
                blocLectureSeule(bloc.root, getString(R.string.preview_edition_impossible_toast)),
            )
            return bloc.root
        }
        lues.forEach { lue -> bloc.root.addView(rangeeContrainte(lue, bloc.root)) }
        return bloc.root
    }

    /** Le LIAGE d'une rangée d'ancre (controle_contrainte.xml, miroir `.crow`). */
    private fun rangeeContrainte(
        lue: EditionAttributsXml.ContrainteLue,
        parent: ViewGroup,
    ): View {
        val rangee = ControleContrainteBinding.inflate(gonfleur(), parent, false)
        val libelle = libelleAncre(lue.cote)
        rangee.libelleAncre.text = libelle
        rangee.boutonCible.text = lue.cible ?: getString(R.string.preview_contrainte_aucune)
        rangee.boutonCible.contentDescription = getString(R.string.preview_contrainte_cible_cd, libelle)
        rangee.boutonCible.setOnClickListener { menuCible(rangee.boutonCible, lue) }
        rangee.champMarge.setText(lue.margeDp?.toString() ?: "")
        rangee.champMarge.hint = getString(R.string.preview_contrainte_marge)
        rangee.champMarge.contentDescription =
            getString(R.string.preview_contrainte_marge_cd, libelle)
        val commettre = {
            val saisie =
                rangee.champMarge.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            val nombre = saisie.toIntOrNull()
            when {
                saisie.isEmpty() -> {
                    false
                }

                nombre == null || nombre < 0 -> {
                    rangee.champMarge.setText(lue.margeDp?.toString() ?: "")
                    false
                }

                else -> {
                    editerOuRecharger { viewModel.ecrireContrainteSelection(lue.cote, null, nombre) }
                }
            }
        }
        rangee.champMarge.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) rangee.champMarge.clearFocus()
                true
            } else {
                false
            }
        }
        rangee.champMarge.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        return rangee.root
    }

    /** Le popover CIBLE translucide (PE9c) — un seul menu à la fois. */
    private val menuCiblePopover: PopoverMenuApercu by lazy { PopoverMenuApercu(liaison.root) }

    /** Le menu de CIBLE d'une ancre (popover_menu_cible.xml — Aucune,
     * parent, puis les frères à id ; la cible courante est marquée). */
    private fun menuCible(
        ancre: View,
        lue: EditionAttributsXml.ContrainteLue,
    ) {
        if (menuCiblePopover.estOuvert) {
            menuCiblePopover.masquer()
            return
        }
        val cibleCourante = lue.cible
        menuCiblePopover.montrer(R.layout.popover_menu_cible, ancre, LARGEUR_MENU_CIBLE_DP) { vue ->
            val popover = PopoverMenuCibleBinding.bind(vue)
            // Les entrées portent la VALEUR BRUTE (miroir ContrainteLue.cible :
            // "parent", "@id/x" ou null = ancre absente).
            val entrees: List<Pair<String, String?>> =
                listOf(
                    getString(R.string.preview_contrainte_aucune) to null,
                    VALEUR_PARENT to VALEUR_PARENT,
                ) +
                    viewModel.ciblesContrainteSelection().map { identifiant ->
                        "@id/$identifiant" to "@id/$identifiant"
                    }
            entrees.forEach { (libelle, valeur) ->
                val rangee = ItemMenuCibleBinding.inflate(gonfleur(), popover.listeCibles, false)
                rangee.libelleCible.text = libelle
                // PE11j : la cible courante est SÉLECTIONNÉE (miroir `.opt`
                // — fond accent doux), plus de point de coche.
                rangee.rangeeCible.isSelected = cibleCourante == valeur
                rangee.rangeeCible.setOnClickListener {
                    menuCiblePopover.masquer()
                    val cible =
                        when {
                            valeur == null -> EditionAttributsXml.CibleContrainte.Aucune
                            valeur == VALEUR_PARENT -> EditionAttributsXml.CibleContrainte.Parent
                            else -> EditionAttributsXml.CibleContrainte.Vue(valeur.removePrefix("@id/"))
                        }
                    editerOuRecharger { viewModel.ecrireContrainteSelection(lue.cote, cible, null) }
                }
                popover.listeCibles.addView(rangee.root)
            }
        }
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

    /**
     * Déclenche la CORRECTION (PE11g — miroir `b.onclick` de la maquette :
     * `fx.go` NAVIGUE sans édition, `fx.run` écrit UNE édition puis le
     * toast `fx.msg` part — la carte disparaît au re-rendu, critère § 6.7
     * « produit une édition et fait disparaître le problème »).
     */
    private fun declencherCorrectif(action: ActionCorrectif) {
        when (action) {
            ActionCorrectif.ElargirCible -> {
                if (editerOuRecharger { viewModel.elargirCibleSelection() }) {
                    toastCorrectif(R.string.preview_correctif_cible_toast)
                }
            }

            ActionCorrectif.ExtraireTexte -> {
                extraireTexteCorrectif()
            }

            ActionCorrectif.AutoriserDeuxLignes -> {
                if (editerOuRecharger { viewModel.definirAttributSelection(ATTR_MAX_LIGNES, DEUX_LIGNES) }) {
                    toastCorrectif(R.string.preview_correctif_tronque_toast)
                }
            }

            ActionCorrectif.OuvrirContentDescription -> {
                ouvrirContentDescription()
            }
        }
    }

    /** Le toast de SUCCÈS d'une correction (miroir `mutate(fx.run, fx.msg)`). */
    private fun toastCorrectif(message: Int) {
        Toast.makeText(requireContext(), getString(message), Toast.LENGTH_SHORT).show()
    }

    /**
     * « Extraire en @string » (miroir `extractStr`) : le nom proposé vient
     * de la VALEUR (le même [ExtractionRessource.nomPropose] que la rangée
     * d'extraction), l'écriture passe par le flux EXISTANT (entrée FR+EN
     * puis référence) — un refus porte le toast PROPRE à l'extraction.
     */
    private fun extraireTexteCorrectif() {
        val valeur = viewModel.valeurAttributSelection(ATTR_TEXTE).orEmpty()
        if (
            editerOuRecharger(R.string.preview_extraire_refuse) {
                viewModel.extraireEnRessource(ATTR_TEXTE, ExtractionRessource.nomPropose(valeur))
            }
        ) {
            toastCorrectif(R.string.preview_correctif_extraire_toast)
        }
    }

    /**
     * « Renseigner » (miroir `fx.go`) : vide la RECHERCHE, OUVRE la
     * section qui PORTE le champ et pose le FOCUS dessus — SANS édition.
     * La maquette vise la section Accessibilité de la 2e vague ; l'app
     * vise la section qui CONTIENT le contrôle (aujourd'hui Affichage —
     * l'annexe § 7 la déplacera, la correction suivra par construction).
     */
    private fun ouvrirContentDescription() {
        rechercheInterne.value = ""
        val classe = viewModel.classeSelection() ?: return
        val genreParent = viewModel.genreParentSelection() ?: CatalogueAttributs.GenreParent.AUCUN
        val section =
            CatalogueAttributs.sections(classe, genreParent).firstOrNull { parcourue ->
                parcourue.controles.any { controle ->
                    controle is CatalogueAttributs.ControleAttribut.Texte &&
                        controle.nom == ATTR_CONTENT_DESCRIPTION
                }
            } ?: return
        etatSections[section.titre.name] = true
        majAttributs()
        liaison.voletAttributs.post { amenerAuChamp(section) }
    }

    /** Amène la carte à l'écran puis pose le FOCUS sur le champ (miroir `S.focusAttr`). */
    private fun amenerAuChamp(section: CatalogueAttributs.SectionAttributs) {
        val position =
            adaptateur.currentList.indexOfFirst { item ->
                item is ItemAttributs.Carte && item.section.titre == section.titre
            }
        if (position < 0) return
        liaison.voletAttributs.scrollToPosition(position)
        liaison.voletAttributs.post {
            liaison.voletAttributs
                .findViewHolderForAdapterPosition(position)
                ?.itemView
                ?.findViewWithTag<TextView>(ATTR_CONTENT_DESCRIPTION)
                ?.requestFocus()
        }
    }

    private companion object {
        /** Les DEUX PREMIÈRES sections de l'éditeur s'ouvrent par défaut (miroir `i < 2`). */
        private const val DEFAUT_OUVERTES: Int = 2

        /** La valeur de plateforme pour cibler le PARENT (code, pas UI). */
        private const val VALEUR_PARENT: String = "parent"

        /** PE11g — l'attribut du TEXTE (correctif « Extraire en @string »). */
        private const val ATTR_TEXTE: String = "android:text"

        /** PE11g — l'attribut visé par « Renseigner » (focus du champ). */
        private const val ATTR_CONTENT_DESCRIPTION: String = "android:contentDescription"

        /** PE11g — l'attribut écrit par « Autoriser 2 lignes » (miroir setEx). */
        private const val ATTR_MAX_LIGNES: String = "android:maxLines"

        /** PE11g — la valeur de « Autoriser 2 lignes » (miroir la maquette). */
        private const val DEUX_LIGNES: String = "2"

        /** Largeur du popover CIBLE, en dp (les cibles tiennent en courte). */
        private const val LARGEUR_MENU_CIBLE_DP: Int = 232

        /** L'opacité d'un contrôle DÉSACTIVÉ (miroir `.flg button:disabled { opacity: .4 }`). */
        private const val ALPHA_DESACTIVE: Float = 0.4f

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
                CatalogueAttributs.Etiquette.SCROLLBARS to R.string.preview_etiquette_scrollbars,
                CatalogueAttributs.Etiquette.SHOW_DIVIDERS to R.string.preview_etiquette_show_dividers,
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
