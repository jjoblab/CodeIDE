package jo.codeide.feature.preview

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatAutoCompleteTextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.preview.databinding.BarreOutilsAttributsBinding
import jo.codeide.feature.preview.databinding.CarteSectionBinding
import jo.codeide.feature.preview.databinding.ControleActionBinding
import jo.codeide.feature.preview.databinding.ControleAttributLibreBinding
import jo.codeide.feature.preview.databinding.ControleContrainteBinding
import jo.codeide.feature.preview.databinding.ControleContraintesBinding
import jo.codeide.feature.preview.databinding.ControleContraintesLectureBinding
import jo.codeide.feature.preview.databinding.ControleCotesBinding
import jo.codeide.feature.preview.databinding.ControleCouleurBinding
import jo.codeide.feature.preview.databinding.ControleCurseurBinding
import jo.codeide.feature.preview.databinding.ControleDimensionsBinding
import jo.codeide.feature.preview.databinding.ControleDrapeauxBinding
import jo.codeide.feature.preview.databinding.ControleExtractionBinding
import jo.codeide.feature.preview.databinding.ControleIdentifiantBinding
import jo.codeide.feature.preview.databinding.ControleListeBinding
import jo.codeide.feature.preview.databinding.ControleRatioBinding
import jo.codeide.feature.preview.databinding.ControleReferenceIdBinding
import jo.codeide.feature.preview.databinding.ControleRessourceBinding
import jo.codeide.feature.preview.databinding.ControleSegmentsBinding
import jo.codeide.feature.preview.databinding.ControleTexteBinding
import jo.codeide.feature.preview.databinding.EnteteChampBinding
import jo.codeide.feature.preview.databinding.FragmentVoletAttributsBinding
import jo.codeide.feature.preview.databinding.ItemActionBinding
import jo.codeide.feature.preview.databinding.ItemAttributBinding
import jo.codeide.feature.preview.databinding.ItemAttributGroupeBinding
import jo.codeide.feature.preview.databinding.ItemAttributLibreBinding
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
import jo.codeide.preview.xml.FamilleParent
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.ProblemesFamilles
import jo.codeide.preview.xml.ProblemesPreview
import jo.codeide.preview.xml.ReferencesId
import jo.codeide.preview.xml.RenommageIdentifiant
import jo.codeide.preview.xml.ValidationAttribut
import jo.codeide.preview.xml.formaterNombre
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

    /**
     * Les POSITIONS des sections ouvertes PAR DÉFAUT (PE11 § 7 — miroir
     * `sc.t !== 'Identité' && ++opened <= 2` de la maquette) : les DEUX
     * premières sections VISIBLES hors Identité, recomptées à chaque
     * [itemsVolet] (les positions comptent les sections FILTRÉES, pas la
     * liste complète).
     */
    private var ouvertesParDefaut: Set<Int> = emptySet()

    /** Le compteur d'ouvertes par défaut de la passe [itemsVolet] en cours. */
    private var ouvertesComptees: Int = 0

    /** Le mode Courants (vrai) ou Tous (miroir `S.attrMode` — défaut Courants). */
    private val modeCourantsInterne = MutableStateFlow(true)

    /** La garde anti-boucle : brancher la barre ne re-déclenche pas la recherche. */
    private var branchementBarre: Boolean = false

    /**
     * Le NOM à PRÉREMPLIR dans la rangée d'ajout libre (PE11h — miroir le
     * `n.value = S.attrQ` de la suggestion de la maquette) : posé par le
     * tap sur « Ajouter … comme attribut libre », consommé au liage de la
     * section Autres attributs puis effacé (une seule consommation).
     */
    private var suggestionLibreEnAttente: String? = null

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
                // La section AUTRES (PE11h — miroir `{other:true}`) ne
                // reçoit AUCUN contrôle du catalogue : son corps est le
                // composite attribut libre (rangées du document + ajout).
                if (section.autres) {
                    carte.corpsSection.addView(controleAttributLibre(attrs))
                    return
                }
                // La NOTE de section (PE12c — miroir `sc.note` de la
                // maquette) se rend EN TÊTE du corps, AVANT les contrôles.
                section.note?.let { carte.corpsSection.addView(vueNoteSection(it)) }
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
                when {
                    // La recherche OUVRE les sections qui restent (miroir
                    // `d.open = qq ? true : …`).
                    rechercheInterne.value.isNotBlank() -> {
                        true
                    }

                    // La section AUTRES s'ouvre par défaut dès qu'elle
                    // PORTE des rangées (miroir `rows.length > 0`).
                    section.autres -> {
                        viewModel
                            .attributsSelection()
                            ?.let { attrs -> nomsInconnus(attrs).isNotEmpty() }
                            ?: false
                    }

                    // L'état MÉMORISÉ d'abord (miroir `S.so[t] ?? …`), sinon
                    // l'ouverture par défaut — le tap ÉCRIT dans la carte
                    // (basculerSection), c'est ici qu'on la RELIT.
                    else -> {
                        etatSections[section.titre.name] ?: ouvertureParDefaut(section, position)
                    }
                }

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

            override fun auSuggestionLibre(nom: String) {
                this@VoletAttributsFragment.auSuggestionLibre(nom)
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
        // Correctif du lot parents : les 10 dp de `.secs` entre les cartes
        // (et les 6 dp de `.fixes` entre correctifs) — la décoration
        // connaît les voisins, les marges d'items non.
        liaison.voletAttributs.addItemDecoration(DecorationEspaceSections(requireContext()))
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
                section.controles.filter { it.courant }
            } else {
                section.controles
            }
        }
        return section.controles.filter { controle ->
            val noms = controle.nomsAttributs.joinToString(" ").lowercase()
            val etiquette =
                controle.etiquette?.let { etiquetteRes(it).lowercase() }.orEmpty()
            titre.contains(requete) || noms.contains(requete) || etiquette.contains(requete)
        }
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
            // PE11 § 7 — les ouvertures PAR DÉFAUT se recomptent sur la
            // liste FILTRÉE de CETTE passe (miroir `++opened` de `fill`).
            ouvertesParDefaut = emptySet()
            ouvertesComptees = 0
            if (vue == null) {
                add(ItemAttributs.Vide)
                return@buildList
            }
            val classe = viewModel.classeSelection() ?: vue.classe
            val familleParent = viewModel.familleParentSelection() ?: FamilleParent.AUCUN
            val familleVue = viewModel.familleVueSelection() ?: FamilleParent.deBalise(classe)
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
                val sections = CatalogueAttributs.sections(classe, familleParent, familleVue)
                var visibles = 0
                var masques = 0
                sections.forEachIndexed { position, section ->
                    // La section AUTRES (PE11h — miroir `sc.other`) : les
                    // rangées viennent du DOCUMENT, pas du catalogue.
                    if (section.autres) {
                        if (ajouterSectionAutres(this, attrs, section, position)) {
                            visibles++
                            noterOuvertureParDefaut(section, position)
                        }
                        return@forEachIndexed
                    }
                    val controles = controlesFiltres(section)
                    if (controles.isEmpty()) return@forEachIndexed
                    masques += section.controles.size - controles.size
                    visibles++
                    noterOuvertureParDefaut(section, position)
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
                ajouterPiedSections(this, classe, visibles, masques)
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
     * La carte de la section AUTRES (PE11h — miroir le `sc.other` de
     * `fill`) : la recherche filtre les NOMS des rangées, le mode Tous
     * seul révèle la rangée d'ajout, et la carte se montre dès qu'il y a
     * une rangée (ou en mode Tous sans recherche) — le badge compte TOUTES
     * les rangées hors catalogue, filtrées ou pas. `true` = la carte est
     * visible (elle compte dans l'état vide).
     */
    private fun ajouterSectionAutres(
        items: MutableList<ItemAttributs>,
        attrs: List<EditionAttributsXml.AttributSource>,
        section: CatalogueAttributs.SectionAttributs,
        position: Int,
    ): Boolean {
        val inconnus = nomsInconnus(attrs)
        val requete = rechercheInterne.value.trim().lowercase()
        val ranges =
            if (requete.isEmpty()) {
                inconnus
            } else {
                inconnus.filter { it.nom.lowercase().contains(requete) }
            }
        val visible = ranges.isNotEmpty() || (requete.isEmpty() && !modeCourantsInterne.value)
        if (visible) {
            items.add(
                ItemAttributs.Carte(
                    section = section,
                    controles = emptyList(),
                    attrs = attrs,
                    position = position,
                    titre = titreSectionRes(section.titre),
                    definis = inconnus.size,
                ),
            )
        }
        return visible
    }

    /**
     * L'ouverture PAR DÉFAUT d'une section (PE11 § 7 — miroir
     * `sc.t !== 'Identité' && ++opened <= 2`) : les DEUX premières
     * sections VISIBLES hors Identité s'ouvrent, comptées sur la liste
     * FILTRÉE par [itemsVolet] (l'ordre de rendu, pas le catalogue nu).
     */
    private fun ouvertureParDefaut(
        section: CatalogueAttributs.SectionAttributs,
        position: Int,
    ): Boolean = position in ouvertesParDefaut && section.titre != CatalogueAttributs.TitreSection.IDENTITE

    /**
     * NOTE une section VISIBLE pour l'ouverture par défaut (appelé par
     * [itemsVolet] dans l'ordre de rendu — l'Identité ne compte jamais).
     */
    private fun noterOuvertureParDefaut(
        section: CatalogueAttributs.SectionAttributs,
        position: Int,
    ) {
        if (section.titre != CatalogueAttributs.TitreSection.IDENTITE) {
            ouvertesComptees++
            if (ouvertesComptees <= OUVERTES_DEFAUT) {
                ouvertesParDefaut = ouvertesParDefaut + position
            }
        }
    }

    /**
     * Le PIED des sections (PE11h — miroir la fin de `fill`) : l'état VIDE
     * (le message « Aucun attribut ne correspond » puis la SUGGESTION
     * « Ajouter … comme attribut libre » quand la recherche RESSEMBLE à
     * un nom d'attribut), puis le bouton large « Afficher les N autres »
     * (mode Courants, attributs masqués — miroir le `.linkbtn.wide`).
     */
    private fun ajouterPiedSections(
        items: MutableList<ItemAttributs>,
        classe: String,
        visibles: Int,
        masques: Int,
    ) {
        if (visibles == 0) {
            items.add(ItemAttributs.Message(getString(R.string.preview_attr_aucun, rechercheInterne.value.trim())))
            rechercheInterne.value.trim().takeIf { NOM_SUGGESTION.matches(it) }?.let { nom ->
                items.add(ItemAttributs.SuggestionLibre(nom))
            }
        }
        if (rechercheInterne.value.isBlank() && modeCourantsInterne.value && masques > 0) {
            items.add(
                ItemAttributs.BoutonAutres(
                    resources.getQuantityString(R.plurals.preview_attr_autres, masques, masques, classe),
                ),
            )
        }
    }

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
        // PE12f — les problèmes de FAMILLE, lus dans le TEXTE VIVANT :
        // ils se rattachent à la sélection par le DÉBUT de l'élément (le
        // span du rendu peut dater d'avant la frappe, mais le début d'un
        // élément ne bouge pas quand l'édit se fait À L'INTÉRIEUR — le
        // cas d'emploi de la boucle : une VALEUR de règle éditée à la
        // main, § 12). Le rendu peut être en ERREUR : les cartes restent.
        val familles =
            viewModel
                .problemesFamilles()
                .filter { it.debut == vue.debutElement }
                .map { correctifFamilleDe(it) }
        val cartes =
            ProblemesPreview
                .evaluer(vues, viewModel.moteur.profil.densiteDpi)
                .filter { it.indiceVue == selectionCourante }
                .mapNotNull { correctifDe(it, vue) } + familles
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
        val action = actionDe(probleme.genre) ?: return null
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

    /**
     * L'ACTION d'un problème de RENDU (PE11g — miroir `quickFixesBase`) :
     * `null` = PAS d'action — la classe introuvable (« Ajouter la
     * dépendance » est HORS périmètre) et les genres de FAMILLE (PE12f :
     * ils passent par [correctifFamilleDe], leur correctif vit sur le
     * SPAN du texte, pas sur la sélection du rendu).
     */
    private fun actionDe(genre: ProblemesPreview.GenreProbleme): ActionCorrectif? =
        when (genre) {
            ProblemesPreview.GenreProbleme.CLASSE_ABSENTE -> null

            ProblemesPreview.GenreProbleme.CIBLE_TACTILE -> ActionCorrectif.ElargirCible

            ProblemesPreview.GenreProbleme.SANS_CONTENT_DESCRIPTION -> ActionCorrectif.OuvrirContentDescription

            ProblemesPreview.GenreProbleme.TEXTE_CODE_DUR -> ActionCorrectif.ExtraireTexte

            ProblemesPreview.GenreProbleme.TEXTE_TRONQUE -> ActionCorrectif.AutoriserDeuxLignes

            ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN,
            ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE,
            ProblemesPreview.GenreProbleme.ANCRE_INVALIDE,
            ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT,
            ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE,
            ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS,
            ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR,
            ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE,
            ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
            -> null
        }

    /**
     * La CARTE d'un problème de FAMILLE (PE12f — miroir les `fixer` de la
     * moitié famille de `computeProblems`) : titre et message composés
     * du problème TEXTE, bouton par genre (l'orphelin distingue le
     * singulier), l'action PORTE le problème — son correctif s'écrit sur
     * son span du texte vivant.
     */
    private fun correctifFamilleDe(probleme: ProblemesFamilles.Probleme): ItemAttributs.Correctif =
        ItemAttributs.Correctif(
            genre = probleme.genre,
            titre = LibellesProblemes.titreFamille(requireContext(), probleme),
            message = LibellesProblemes.messageFamille(requireContext(), probleme),
            bouton = getString(boutonFamille(probleme)),
            action = ActionCorrectif.CorrigerFamille(probleme),
        )

    /** Le LIBELLÉ localisé du bouton d'une action (miroir `fx.b`). */
    private fun boutonDe(action: ActionCorrectif): Int =
        when (action) {
            ActionCorrectif.ElargirCible -> R.string.preview_correctif_cible_bouton
            ActionCorrectif.ExtraireTexte -> R.string.preview_correctif_extraire_bouton
            ActionCorrectif.AutoriserDeuxLignes -> R.string.preview_correctif_tronque_bouton
            ActionCorrectif.OuvrirContentDescription -> R.string.preview_correctif_description_bouton
            is ActionCorrectif.CorrigerFamille -> boutonFamille(action.probleme)
        }

    /**
     * Le LIBELLÉ du bouton d'un problème de FAMILLE (PE12f, spec § 9) :
     * « Retirer cet attribut » au singulier, « ces attributs » sinon
     * (miroir `orph.length > 1`).
     */
    private fun boutonFamille(probleme: ProblemesFamilles.Probleme): Int =
        when (probleme.genre) {
            ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN -> {
                if (probleme.attributs.size == 1) {
                    R.string.preview_correctif_retirer_attribut
                } else {
                    R.string.preview_correctif_retirer_attributs
                }
            }

            ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE -> {
                R.string.preview_correctif_retirer_regle
            }

            ProblemesPreview.GenreProbleme.ANCRE_INVALIDE -> {
                R.string.preview_correctif_retirer_ancre
            }

            ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT -> {
                R.string.preview_correctif_ajouter_comportement
            }

            ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE -> {
                R.string.preview_correctif_casser_boucle
            }

            // v0.170.0 — contenance : l'enfant unique dépassé
            // s'ENVELOPPE (miroir la résolution canonique du lint
            // ScrollViewChildren) ; les trois autres genres RETIRENT
            // les enfants fautifs.
            ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE -> {
                R.string.preview_correctif_envelopper
            }

            ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS,
            ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR,
            ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
            -> {
                R.string.preview_correctif_retirer_enfants
            }

            else -> {
                error("genre de rendu : pas de bouton de famille — $probleme")
            }
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
            controle.nomsAttributs.any { nom ->
                attrs.any { it.nom == nom && it.valeur.isNotEmpty() }
            }
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
            is CatalogueAttributs.ControleAttribut.Curseur -> controleCurseur(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Ratio -> controleRatio(controle, attrs)
            else -> controleComposite(controle, attrs)
        }

    /**
     * Les contrôles COMPOSITES (miroir de la seconde moitié du `when` de
     * la maquette) : côtés, référence d'id, bascules, dimensions et
     * contraintes — chaque type a SA fonction de liage.
     */
    private fun controleComposite(
        controle: CatalogueAttributs.ControleAttribut,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View =
        when (controle) {
            is CatalogueAttributs.ControleAttribut.Cotes -> controleCotes(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Coins -> controleCoins(controle, attrs)
            is CatalogueAttributs.ControleAttribut.ReferenceId -> controleReferenceId(controle, attrs)
            is CatalogueAttributs.ControleAttribut.Bascules -> controleBascules(controle, attrs)
            is CatalogueAttributs.ControleAttribut.ActionRapide -> controleActionRapide(controle)
            is CatalogueAttributs.ControleAttribut.ContraintesLecture -> controleContraintesLecture(controle, attrs)
            CatalogueAttributs.ControleAttribut.Dimensions -> controleDimensions()
            CatalogueAttributs.ControleAttribut.Contraintes -> controleContraintes()
            else -> error("contrôle à champ routé par controleSection : $controle")
        }

    /** La valeur courante d'un contrôle nommé (lecture dans les attributs lus). */
    private fun valeurDe(
        nom: String,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): String? = attrs.firstOrNull { it.nom == nom }?.valeur

    /**
     * L'EN-TÊTE commun des contrôles (PE12c — miroir `fieldBox` de la
     * maquette) : l'ÉTIQUETTE, la PUCHE d'espace de noms (« app » ambrée
     * / « tools » violette sur l'attribut, cachée pour `android:` —
     * miroir `.ns`) et le bouton ↺ — visible dès qu'un attribut du
     * groupe est DÉFINI, il rétablit les défauts de TOUS les attributs
     * du groupe en UNE SEULE édition (miroir `.rst`).
     */
    private fun brancherEntete(
        entete: EnteteChampBinding,
        libelle: String,
        noms: List<String>,
        attrs: List<EditionAttributsXml.AttributSource>,
    ) {
        entete.libelleControle.text = libelle
        when (noms.firstOrNull()?.substringBefore(':')) {
            "app" -> {
                entete.pastilleEspaceNoms.text = getString(R.string.preview_pastille_app)
                entete.pastilleEspaceNoms.contentDescription = getString(R.string.preview_pastille_app_desc)
                entete.pastilleEspaceNoms.setBackgroundResource(R.drawable.apercu_pastille_app_fond)
                poserTeinteTexte(entete.pastilleEspaceNoms, R.attr.apercuAvert)
                entete.pastilleEspaceNoms.visibility = View.VISIBLE
            }

            "tools" -> {
                entete.pastilleEspaceNoms.text = getString(R.string.preview_pastille_tools)
                entete.pastilleEspaceNoms.contentDescription = getString(R.string.preview_pastille_tools_desc)
                entete.pastilleEspaceNoms.setBackgroundResource(R.drawable.apercu_pastille_tools_fond)
                poserTeinteTexte(entete.pastilleEspaceNoms, R.attr.apercuNouveau)
                entete.pastilleEspaceNoms.visibility = View.VISIBLE
            }

            else -> {
                entete.pastilleEspaceNoms.visibility = View.GONE
            }
        }
        if (noms.any { nom -> valeurDe(nom, attrs) != null }) {
            entete.boutonReinitialiser.visibility = View.VISIBLE
            entete.boutonReinitialiser.setOnClickListener {
                editerOuRecharger { viewModel.reinitialiserAttributsSelection(noms) }
            }
        } else {
            entete.boutonReinitialiser.visibility = View.GONE
        }
    }

    /** Résout une couleur THÈME (?attr) et la pose sur le TEXTE (miroir le `textColor` de Pastille.App/.Tools). */
    private fun poserTeinteTexte(
        vue: TextView,
        attribut: Int,
    ) {
        val tape = TypedValue()
        if (!vue.context.theme.resolveAttribute(attribut, tape, true)) return
        vue.setTextColor(
            if (tape.resourceId != 0) vue.context.getColor(tape.resourceId) else tape.data,
        )
    }

    /** La NOTE de section (PE12c — miroir `sc.note`) : une ligne .note2 en tête du corps. */
    private fun vueNoteSection(note: CatalogueAttributs.NoteSection): TextView =
        when (note) {
            CatalogueAttributs.NoteSection.REGLES_SOEURS -> {
                noteSousChamp(getString(R.string.preview_note_regles_soeurs))
            }

            CatalogueAttributs.NoteSection.OUTILS_APERCU -> {
                noteSousChamp(getString(R.string.preview_note_outils_apercu))
            }
        }

    /** La note SOUS un champ (miroir `.note2`) : le style Note de la référence, écart d'étiquette en marge haute. */
    private fun noteSousChamp(texte: String): TextView =
        TextView(requireContext(), null, 0, R.style.Widget_CodeIDE_Apercu_Note).apply {
            this.text = texte
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.apercu_gap_etiquette) }
        }

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
        brancherEntete(bloc.entete, libelle, listOf(controle.nom), attrs)
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
                // PE11 § 7 — [Texte.validation] (miroir les `pat`/`msg` du
                // `C.str`) : une ERREUR n'écrit RIEN, le message s'affiche
                // DESSOUS (setError — le champ garde sa saisie).
                when (val verdict = verdictTexte(controle.validation, nouvelle)) {
                    is ValidationAttribut.Resultat.Erreur -> {
                        saisie.champSaisie.error = messageValidation(verdict.genre, verdict.arguments)
                        false
                    }

                    else -> {
                        viewModel.definirAttributSelection(controle.nom, nouvelle)
                    }
                }
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

    /**
     * Le VERDICT de validation d'un champ TEXTE (PE11 § 7) : `null` = AUCUNE
     * validation posée (tout s'écrit) ; sinon [ValidationAttribut.nomActivite]
     * ou [ValidationAttribut.nomMethodeJava] selon le genre.
     */
    private fun verdictTexte(
        genre: CatalogueAttributs.GenreValidationTexte?,
        valeur: String,
    ): ValidationAttribut.Resultat? =
        when (genre) {
            CatalogueAttributs.GenreValidationTexte.NOM_ACTIVITE -> ValidationAttribut.nomActivite(valeur)
            CatalogueAttributs.GenreValidationTexte.NOM_METHODE_JAVA -> ValidationAttribut.nomMethodeJava(valeur)
            null -> null
        }

    /** Le TEXTE localisé d'un message de validation (règle 3 — jamais de chaîne en dur). */
    private fun messageValidation(
        genre: ValidationAttribut.GenreMessage,
        arguments: List<String>,
    ): String =
        when (arguments.size) {
            0 -> getString(messageRessource(genre))
            1 -> getString(messageRessource(genre), arguments[0])
            else -> getString(messageRessource(genre), arguments[0], arguments[1])
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
                val references = viewModel.renommerIdentifiantSelection(saisie)
                if (references != null) {
                    annoncerRenommage(saisie, references)
                    true
                } else {
                    editerOuRecharger(R.string.preview_identite_refuse) { false }
                }
            }
        }
    }

    /**
     * Le toast du RENOMMAGE (PE12g, spec § 10 — miroir le `toast('Renommé
     * en …')` de la maquette) : « Renommé en @+id/x · N références mises
     * à jour » au pluriel, le message simple sans référence.
     */
    private fun annoncerRenommage(
        nouveau: String,
        references: Int,
    ) {
        val texte =
            if (references == 0) {
                getString(R.string.preview_renomme_simple, nouveau)
            } else {
                resources.getQuantityString(
                    R.plurals.preview_renomme_references,
                    references,
                    nouveau,
                    references,
                )
            }
        (activity as? HoteVoletsApercu)?.annoncerTexte(texte)
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

    /** Le commit d'un NOMBRE : vide = rien, non numérique ou HORS BORNES = refus visuel (repli). */
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
        if (saisie.isEmpty()) return false
        val nombre = saisie.toFloatOrNull()
        // PE11 § 7 — [Nombre.bornes] (miroir `min`/`max` du `C.num`) : hors
        // bornes = ERREUR qui n'écrit RIEN ; sans bornes, la 1re vague
        // rejette les NÉGATIFS (comportement historique conservé).
        val horsBornes =
            when (val bornes = controle.bornes) {
                null -> {
                    nombre != null && nombre < 0f
                }

                else -> {
                    val minimum = bornes.minimum
                    val maximum = bornes.maximum
                    nombre == null ||
                        (minimum != null && nombre < minimum) ||
                        (maximum != null && nombre > maximum)
                }
            }
        return if (nombre == null || horsBornes) {
            // Retour visuel : la saisie refusée est REPLACÉE (pas d'édition).
            champ.setText(valeurDe(controle.nom, attrs)?.trimEnd('d', 'p', 's') ?: "")
            false
        } else {
            val valeur = formaterNombre(nombre) + controle.unite
            editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
        }
    }

    /** Un contrôle CHOIX (controle_segments.xml) — le PREMIER choix est
     * le défaut (retrait de l'attribut). */
    private fun controleChoix(
        controle: CatalogueAttributs.ControleAttribut.Choix,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleSegmentsBinding.inflate(gonfleur())
        brancherEntete(bloc.entete, etiquetteRes(controle.etiquette), listOf(controle.nom), attrs)
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
        brancherEntete(bloc.entete, etiquetteRes(controle.etiquette), listOf(controle.nom), attrs)
        val courant = valeurDe(controle.nom, attrs)
        // Miroir maquette : la valeur posée hors catalogue reste visible.
        val valeurs =
            if (courant != null && courant !in controle.valeurs) {
                controle.valeurs + courant
            } else {
                controle.valeurs
            }
        // PE11 § 7 — le LIBELLÉ d'une option retient le DERNIER SEGMENT
        // (miroir `x.replace(/^.*[./]/, '')`) : les styles `@style/…` et les
        // gestionnaires pleinement qualifiés s'affichent courts, la VALEUR
        // écrite reste la forme pleine.
        val libelles = listOf(getString(R.string.preview_choix_defaut)) + valeurs.map(::dernierSegment)
        bloc.listeDeroulante.setText(courant?.let(::dernierSegment) ?: getString(R.string.preview_choix_defaut), false)
        // Garantie de non-saisie (miroir select) : le layout pose déjà
        // focusable=false + inputType none — le keyListener null l'assure
        // aussi sur les appareils où inputType none laisse passer l'IME.
        bloc.listeDeroulante.keyListener = null
        bloc.listeDeroulante.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_option_liste, libelles),
        )
        bloc.listeDeroulante.setOnItemClickListener { _, _, position, _ ->
            val valeur = if (position == 0) "" else valeurs[position - 1]
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
     * Le DERNIER SEGMENT d'une valeur de LISTE (PE11 § 7 — miroir
     * `x.replace(/^.*[./]/, '')`) : « `@style/Widget.Material3.Button` » →
     * « `Button` », « `androidx.…LinearLayoutManager` » → « `LinearLayoutManager` »,
     * une valeur SANS point ni barre passe TELLE QUELLE.
     */
    private fun dernierSegment(valeur: String): String = valeur.replace(Regex("^.*[./]"), "")

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
        brancherEntete(bloc.entete, libelle, listOf(controle.nom), attrs)
        val valeur = valeurDe(controle.nom, attrs)
        val parties = controle.parties(valeur)
        // PE12c — [Drapeaux.requiert] (spec § 5.2) : la gravité de l'ANCRE
        // est désactivée tant qu'aucune ancre n'est posée (miroir
        // `en`/`why` du contrôle de la maquette).
        val raisonInactive =
            controle.requiert
                ?.takeIf { requis -> valeurDe(requis, attrs) == null }
                ?.let { getString(R.string.preview_ref_ancre_requise) }
        controle.options(valeur).forEach { option ->
            val drapeau = ItemDrapeauBinding.inflate(gonfleur(), bloc.groupeDrapeaux, false)
            drapeau.drapeau.text = option
            drapeau.drapeau.isChecked = option in parties
            drapeau.drapeau.isEnabled = raisonInactive == null
            drapeau.drapeau.alpha = if (raisonInactive == null) 1f else ALPHA_DESACTIVE
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
        if (raisonInactive != null) bloc.root.addView(noteSousChamp(raisonInactive))
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
        brancherEntete(bloc.entete, etiquetteRes(CatalogueAttributs.Etiquette.TYPE_SAISIE), listOf(controle.nom), attrs)
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
        brancherEntete(bloc.entete, etiquetteRes(controle.etiquette), listOf(controle.nom), attrs)
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
        brancherEntete(bloc.entete, etiquetteRes(controle.etiquette), listOf(controle.nom), attrs)
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
            ValidationAttribut.GenreMessage.VALEUR_BORNEE -> R.string.preview_attr_curseur_bornes
            ValidationAttribut.GenreMessage.RATIO_INVALIDE -> R.string.preview_attr_ratio_invalide
            ValidationAttribut.GenreMessage.NOM_ACTIVITE_ATTENDU -> R.string.preview_attr_activite_attendue
            ValidationAttribut.GenreMessage.NOM_METHODE_ATTENDU -> R.string.preview_attr_methode_attendue
            ValidationAttribut.GenreMessage.NOM_ATTRIBUT_LIBRE -> R.string.preview_attr_libre_nom_invalide
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
        brancherEntete(bloc.entete, etiquetteRes(controle.etiquette), listOf(controle.nom), attrs)
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

    /**
     * Un contrôle CURSEUR (controle_curseur.xml, PE11g-contrôles — miroir
     * `sld` de la maquette) : glissière violette + champ numérique LIÉS ;
     * le GLISSEMENT suit le champ sans écrire (miroir `r.oninput`), l'écriture
     * ne vient qu'au RELÂCHEMENT (miroir `r.onchange`) ou au commit du champ
     * (action faite / perte de focus, miroir `n.onchange`). La borne HAUTE
     * peut suivre un attribut du document (miroir
     * `Math.max(1, dpv(ex(v, 'android:max')) || 100)` — la progression se
     * borne au max du ProgressBar) et une valeur du document HORS bornes se
     * CLAMPE pour la glissière (le champ garde la vérité du document à
     * l'écriture près).
     */
    private fun controleCurseur(
        controle: CatalogueAttributs.ControleAttribut.Curseur,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleCurseurBinding.inflate(gonfleur())
        val libelle = etiquetteRes(controle.etiquette)
        brancherEntete(bloc.entete, libelle, listOf(controle.nom), attrs)
        val maximum = controle.borneMax(controle.maxDe?.let { nom -> valeurDe(nom, attrs) })
        val affichee = controle.valeurAffichee(valeurDe(controle.nom, attrs)).coerceIn(controle.min, maximum)
        bloc.curseur.valueFrom = controle.min
        bloc.curseur.valueTo = maximum
        bloc.curseur.stepSize = controle.pas
        bloc.curseur.value = affichee
        bloc.curseur.contentDescription = libelle
        bloc.champCurseur.setText(formaterNombre(affichee))
        val libelleValeur = getString(R.string.preview_attr_curseur_valeur, libelle)
        bloc.champCurseur.contentDescription = libelleValeur
        // PE11 § 7 — [Curseur.requiertContraintesVerticales] (miroir
        // `en:!both` + `why` du `Vertical_bias`) : le biais vertical se
        // DÉSACTIVE, explication sous le champ, tant que la vue n'a pas
        // une contrainte en haut ET en bas.
        if (controle.requiertContraintesVerticales) {
            raisonContraintesVerticales()?.let { raison ->
                bloc.curseur.isEnabled = false
                bloc.champCurseur.isEnabled = false
                bloc.root.addView(noteSousChamp(raison))
            }
        }
        // Le glissement met le champ à jour SANS écrire (miroir `r.oninput`).
        bloc.curseur.addOnChangeListener { _, valeurCurseur, _ ->
            bloc.champCurseur.setText(formaterNombre(valeurCurseur))
        }
        // L'écriture au RELÂCHEMENT seulement (miroir `r.onchange` — le
        // champ a suivi le glissement, le commit lit sa valeur).
        bloc.curseur.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit

                override fun onStopTrackingTouch(slider: Slider) {
                    commiterCurseur(bloc, controle, maximum)
                }
            },
        )
        val commettre = { commiterCurseur(bloc, controle, maximum) }
        bloc.champCurseur.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) bloc.champCurseur.clearFocus()
                true
            } else {
                false
            }
        }
        bloc.champCurseur.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        return bloc.root
    }

    /**
     * La RAISON d'inactivité du BIAIS VERTICAL (PE11 § 7) : `null` quand
     * la vue porte une contrainte en HAUT et en BAS
     * ([viewModel.lireContraintesSelection] — les deux côtés ont une
     * CIBLE), quand la lecture est impossible (span périmé — le contrôle
     * reste utilisable, l'écriture tranchera), sinon le libellé de la
     * note (miroir le `why` du `Vertical_bias`).
     */
    private fun raisonContraintesVerticales(): String? {
        val lues = viewModel.lireContraintesSelection() ?: return null
        val haut = lues.firstOrNull { it.cote == EditionAttributsXml.Cote.HAUT }?.cible
        val bas = lues.firstOrNull { it.cote == EditionAttributsXml.Cote.BAS }?.cible
        return if (haut != null && bas != null) null else getString(R.string.preview_note_biais_vertical)
    }

    /**
     * Le COMMIT du curseur (miroir `commit` du `sld`) : hors bornes ou non
     * numérique → ERREUR sans écriture (message rouge + fond de champ) ;
     * sinon la valeur `nombre + unité` s'écrit, et une valeur ÉGALE au
     * DÉFAUT RETIRE l'attribut. Le VIDE ne réécrit RIEN (l'artefact
     * `+'' → 0` de la maquette est une coercion JS, pas un comportement
     * voulu — le champ revient à la valeur de la glissière).
     */
    private fun commiterCurseur(
        bloc: ControleCurseurBinding,
        controle: CatalogueAttributs.ControleAttribut.Curseur,
        maximum: Float,
    ): Boolean {
        val saisie =
            bloc.champCurseur.text
                ?.toString()
                ?.trim()
                .orEmpty()
        if (saisie.isEmpty()) {
            bloc.champCurseur.setText(formaterNombre(bloc.curseur.value))
            masquerMessageControle(bloc.messageControle)
            bloc.champCurseur.setBackgroundResource(R.drawable.apercu_champ_fond)
            return true
        }
        return when (val verdict = ValidationAttribut.curseur(saisie, controle.min, maximum)) {
            is ValidationAttribut.Resultat.Erreur -> {
                montrerMessageControle(bloc.messageControle, R.color.apercu_erreur, verdict.genre, verdict.arguments)
                bloc.champCurseur.setBackgroundResource(R.drawable.apercu_champ_fond_erreur)
                false
            }

            // Jamais produit par la validation du curseur (rien à écrire).
            is ValidationAttribut.Resultat.Avertissement -> {
                true
            }

            ValidationAttribut.Resultat.Valide -> {
                masquerMessageControle(bloc.messageControle)
                bloc.champCurseur.setBackgroundResource(R.drawable.apercu_champ_fond)
                val nombre = saisie.toFloat()
                editerOuRecharger {
                    viewModel.definirAttributSelection(controle.nom, controle.ecrire(nombre))
                }
            }
        }
    }

    /**
     * Un contrôle RATIO (controle_ratio.xml, PE11g-contrôles — miroir
     * `ratio` de la maquette) : deux nombres « a : b », PRÉRÉGLAGES 1:1,
     * 4:3, 16:9, 3:4 (boutons pressés quand ils matchent, miroir
     * `aria-pressed`), VERROU aucun/W/H (segments — miroir
     * `segBtns(['aucun','W','H'])`, qui ne réécrit que si les deux nombres
     * existent) et la note « dimension à 0dp ». Les deux champs VIDES
     * retirent l'attribut ; une valeur sans deux nombres POSITIFS ne
     * s'écrit pas ([poserRatio]).
     */
    private fun controleRatio(
        controle: CatalogueAttributs.ControleAttribut.Ratio,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleRatioBinding.inflate(gonfleur())
        val libelle = etiquetteRes(controle.etiquette)
        brancherEntete(bloc.entete, libelle, listOf(controle.nom), attrs)
        val lue = controle.lire(valeurDe(controle.nom, attrs))
        val verrou = lue?.verrou
        val a = lue?.a.orEmpty()
        val b = lue?.b.orEmpty()
        bloc.champRatioA.setText(a)
        bloc.champRatioB.setText(b)
        bloc.champRatioA.contentDescription = getString(R.string.preview_attr_ratio_largeur)
        bloc.champRatioB.contentDescription = getString(R.string.preview_attr_ratio_hauteur)
        brancherPrereglesRatios(bloc, controle, verrou, a, b)
        brancherVerrouRatio(bloc, controle, verrou, a, b)
        // Le commit d'un champ, au nom du couple (miroir `A.onchange = B.onchange`).
        val commettre = {
            poserRatio(
                bloc,
                controle,
                verrou,
                bloc.champRatioA.text
                    ?.toString()
                    .orEmpty(),
                bloc.champRatioB.text
                    ?.toString()
                    .orEmpty(),
            )
        }
        bloc.champRatioA.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) bloc.champRatioA.clearFocus()
                true
            } else {
                false
            }
        }
        bloc.champRatioB.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) bloc.champRatioB.clearFocus()
                true
            } else {
                false
            }
        }
        bloc.champRatioA.setOnFocusChangeListener { _, aFocus -> if (!aFocus) commettre() }
        bloc.champRatioB.setOnFocusChangeListener { _, aFocus -> if (!aFocus) commettre() }
        // La note « dimension à 0dp » vit dans le LAYOUT (patron du
        // message vide PE11k — toujours visible, miroir note2 du ratio).
        return bloc.root
    }

    /**
     * Les PRÉRÉGLAGES du ratio (miroir le `.flg` du `ratio` : « 1:1 »,
     * « 4:3 », « 16:9 », « 3:4 » pressés quand la valeur matche, l'appui
     * écrit le couple en gardant le VERROU courant).
     */
    private fun brancherPrereglesRatios(
        bloc: ControleRatioBinding,
        controle: CatalogueAttributs.ControleAttribut.Ratio,
        verrou: String?,
        a: String,
        b: String,
    ) {
        CatalogueAttributs.ControleAttribut.Ratio.PREDEFINIS.forEach { prereglage ->
            val drapeau = ItemDrapeauBinding.inflate(gonfleur(), bloc.groupeRatios, false)
            drapeau.drapeau.text = prereglage.libelle
            drapeau.drapeau.isChecked = a == prereglage.a && b == prereglage.b
            drapeau.drapeau.contentDescription = "${etiquetteRes(controle.etiquette)} : ${prereglage.libelle}"
            drapeau.drapeau.setOnClickListener {
                bloc.champRatioA.setText(prereglage.a)
                bloc.champRatioB.setText(prereglage.b)
                poserRatio(bloc, controle, verrou, prereglage.a, prereglage.b)
            }
            bloc.groupeRatios.addView(drapeau.root)
        }
    }

    /**
     * Le VERROU du ratio (miroir `segBtns(f, ['aucun','W','H'], …)`) : le
     * côté CALCULÉ `W`/`H` ou AUCUN ; le choix ne réécrit que si les deux
     * nombres existent (miroir `if (a && b)`).
     */
    private fun brancherVerrouRatio(
        bloc: ControleRatioBinding,
        controle: CatalogueAttributs.ControleAttribut.Ratio,
        verrou: String?,
        a: String,
        b: String,
    ) {
        val aucun = getString(R.string.preview_attr_verrou_aucun)
        val libelles = listOf(aucun, "W", "H")
        segmentes(bloc.groupeVerrouRatio, libelles, verrou ?: aucun, etiquetteRes(controle.etiquette)) { choix ->
            if (a.isNotEmpty() && b.isNotEmpty()) {
                editerOuRecharger {
                    viewModel.definirAttributSelection(
                        controle.nom,
                        controle.ecrire(choix.takeIf { it != aucun }, a, b),
                    )
                }
            }
        }
    }

    /**
     * Le COMMIT du ratio (miroir `put` de la maquette) : les deux champs
     * VIDES retirent l'attribut ; deux nombres POSITIFS s'écrivent
     * `[W|H,]a:b` (le verrou devant, miroir `lk + ','`) ; toute autre
     * saisie est une ERREUR sans écriture (message rouge + fond du champ
     * de gauche, miroir `A.classList.add('bad')`).
     */
    private fun poserRatio(
        bloc: ControleRatioBinding,
        controle: CatalogueAttributs.ControleAttribut.Ratio,
        verrou: String?,
        x: String,
        y: String,
    ): Boolean {
        if (x.isBlank() && y.isBlank()) {
            editerOuRecharger { viewModel.definirAttributSelection(controle.nom, "") }
            return true
        }
        return when (val verdict = ValidationAttribut.ratio(x, y)) {
            is ValidationAttribut.Resultat.Erreur -> {
                montrerMessageControle(bloc.messageControle, R.color.apercu_erreur, verdict.genre, verdict.arguments)
                bloc.champRatioA.setBackgroundResource(R.drawable.apercu_champ_fond_erreur)
                false
            }

            // Jamais produit par la validation du ratio (rien à écrire).
            is ValidationAttribut.Resultat.Avertissement -> {
                true
            }

            ValidationAttribut.Resultat.Valide -> {
                masquerMessageControle(bloc.messageControle)
                bloc.champRatioA.setBackgroundResource(R.drawable.apercu_champ_fond)
                editerOuRecharger {
                    viewModel.definirAttributSelection(controle.nom, controle.ecrire(verrou, x, y))
                }
            }
        }
    }

    /**
     * Le contrôle RÉFÉRENCE D'ID (PE12c — miroir `idref` de la maquette,
     * spec `PREVIEW_PARENTS.md` § 5.3, layout `controle_reference_id.xml`) :
     * liste déroulante « aucun » (retrait) + candidats `@id/…` par
     * PORTÉE ; une option qui FERMERAIT une boucle est désactivée et
     * suffixée « (boucle) » — SAUF si elle est la valeur COURANTE, qui
     * reste choisissable (miroir `o.disabled = bad && cu !== o.value`) ;
     * une valeur absente de l'arbre s'ajoute en dernier « (introuvable) ».
     * La note sous le champ ne vient que des portées FRÈRES et ANCRE.
     */
    private fun controleReferenceId(
        controle: CatalogueAttributs.ControleAttribut.ReferenceId,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleReferenceIdBinding.inflate(gonfleur())
        brancherEntete(bloc.entete, etiquetteRes(controle.etiquette), listOf(controle.nom), attrs)
        val valeur = valeurDe(controle.nom, attrs)
        val options = viewModel.optionsReferenceSelection(controle, valeur)
        val courante = valeur?.replace("@+id/", "@id/").orEmpty()
        // « aucun » (retrait) puis les candidats, puis l'introuvable.
        val libelles = mutableListOf(getString(R.string.preview_ref_aucun))
        val valeurs = mutableListOf("")
        val grisees = mutableListOf(false)
        options.entrees.forEach { entree ->
            val libelle = "@id/${entree.id}"
            valeurs += libelle
            libelles +=
                if (entree.boucle) {
                    getString(R.string.preview_ref_boucle, libelle)
                } else {
                    libelle
                }
            grisees += entree.boucle && courante != libelle
        }
        options.introuvable?.let {
            valeurs += it
            libelles += getString(R.string.preview_ref_introuvable, it)
            grisees += false
        }
        bloc.listeReferences.keyListener = null
        bloc.listeReferences.setAdapter(AdaptateurReferences(libelles, grisees))
        bloc.listeReferences.setText(
            valeurs
                .indexOf(courante)
                .takeIf { it >= 0 }
                ?.let { indice -> libelles.getOrNull(indice) }
                ?: getString(R.string.preview_ref_aucun),
            false,
        )
        bloc.listeReferences.setOnItemClickListener { _, _, position, _ ->
            editerOuRecharger {
                viewModel.definirAttributSelection(controle.nom, valeurs[position])
            }
        }
        // Sans frappe (inputType none), le menu ne s'ouvre qu'au TAP —
        // miroir du select natif de la maquette (cf. controleListe).
        bloc.listeReferences.setOnClickListener { vue ->
            (vue as? AppCompatAutoCompleteTextView)?.showDropDown()
        }
        when (options.note) {
            ReferencesId.Note.FRERES -> {
                bloc.noteReference.text = getString(R.string.preview_ref_note_freres)
                bloc.noteReference.visibility = View.VISIBLE
            }

            ReferencesId.Note.ANCRE -> {
                bloc.noteReference.text = getString(R.string.preview_ref_note_ancre)
                bloc.noteReference.visibility = View.VISIBLE
            }

            ReferencesId.Note.SANS -> {
                bloc.noteReference.visibility = View.GONE
            }
        }
        return bloc.root
    }

    /**
     * L'adaptateur des options d'une référence : les entrées « (boucle) »
     * sont DÉSACTIVÉES (miroir `o.disabled`), les autres choisissables.
     */
    private inner class AdaptateurReferences(
        libelles: List<String>,
        private val grisees: List<Boolean>,
    ) : ArrayAdapter<String>(requireContext(), R.layout.item_option_liste, libelles) {
        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean = grisees.getOrNull(position) != true
    }

    /**
     * Le groupe de BASCULES (PE12c — miroir `tgl` de la maquette, spec
     * § 5.2, habillage du contrôle drapeaux) : chaque bouton porte SON
     * PROPRE attribut — appuyé écrit `="true"`, un second appui RETIRE
     * l'attribut ; le bouton ↺ de l'en-tête réinitialise TOUT le groupe.
     */
    private fun controleBascules(
        controle: CatalogueAttributs.ControleAttribut.Bascules,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleDrapeauxBinding.inflate(gonfleur())
        val libelle = etiquetteRes(controle.etiquette)
        brancherEntete(bloc.entete, libelle, controle.entrees.map { entree -> entree.nom }, attrs)
        bloc.groupeDrapeaux.contentDescription = libelle
        controle.entrees.forEach { entree ->
            val bascule = ItemDrapeauBinding.inflate(gonfleur(), bloc.groupeDrapeaux, false)
            val libelleBascule = etiquetteRes(entree.libelle)
            val posee = valeurDe(entree.nom, attrs) == "true"
            bascule.drapeau.text = libelleBascule
            bascule.drapeau.isChecked = posee
            bascule.drapeau.contentDescription = "$libelle : $libelleBascule"
            bascule.drapeau.setOnClickListener {
                editerOuRecharger {
                    viewModel.definirAttributSelection(entree.nom, if (posee) "" else "true")
                }
            }
            bloc.groupeDrapeaux.addView(bascule.root)
        }
        return bloc.root
    }

    /** Un contrôle CÔTÉS (controle_cotes.xml — les QUATRE champs nommés,
     * miroir `.mrow`). */
    private fun controleCotes(
        controle: CatalogueAttributs.ControleAttribut.Cotes,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleCotesBinding.inflate(gonfleur())
        // Le ↺ retire la BASE et les QUATRE côtés (miroir le `also` du
        // `sides` de la maquette — mêmes suffixes que nomCote).
        brancherEntete(
            bloc.entete,
            etiquetteRes(controle.etiquette),
            listOf(controle.base) + listOf("Start", "End", "Top", "Bottom").map { controle.base + it },
            attrs,
        )
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

    /**
     * Le contrôle COINS (PE11 § 7 — miroir `corners` de la maquette, layout
     * `controle_cotes` réutilisé, brief PE11f) : UN champ par coin
     * (`boxCornerRadiusTopStart` puis fin haut, fin bas, début bas — l'ordre
     * de la rangée), chacun s'écrit SEUL ; VIDE ou `0` RETIRE l'attribut,
     * sinon `Ndp` (miroir `i.value === '' || +i.value <= 0 ? '' :
     * Math.max(0, +i.value) + 'dp'`) ; le ↺ réinitialise les QUATRE coins.
     */
    private fun controleCoins(
        controle: CatalogueAttributs.ControleAttribut.Coins,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleCotesBinding.inflate(gonfleur())
        val libelle = etiquetteRes(controle.etiquette)
        brancherEntete(bloc.entete, libelle, controle.nomsAttributs, attrs)
        val coins =
            listOf(
                bloc.coteHaut to R.string.preview_coin_haut_gauche,
                bloc.coteDroite to R.string.preview_coin_haut_droite,
                bloc.coteBas to R.string.preview_coin_bas_droite,
                bloc.coteGauche to R.string.preview_coin_bas_gauche,
            )
        coins.forEachIndexed { indice, (champ, libelleRes) ->
            val nom = controle.nomsAttributs[indice]
            val nomCoin = getString(libelleRes)
            champ.setText(nombreEnTete(valeurDe(nom, attrs)))
            champ.hint = nomCoin
            champ.contentDescription = getString(R.string.preview_coin_cd, libelle, nomCoin)
            val commettre = {
                val saisie =
                    champ.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                val nombre = saisie.toFloatOrNull()
                when {
                    // Vide = rien (miroir les champs de côtés — le ↺ du
                    // groupe retire les quatre coins d'une édition).
                    saisie.isEmpty() -> {
                        false
                    }

                    // Non numérique ou négatif : repli visuel (pas d'édition).
                    nombre == null || nombre < 0f -> {
                        champ.setText(nombreEnTete(valeurDe(nom, attrs)))
                        false
                    }

                    // 0 RETIRE l'attribut (brief § 4 : « 0 ou vide retire » —
                    // miroir `+i.value <= 0 ? ''`).
                    nombre == 0f -> {
                        editerOuRecharger { viewModel.definirAttributSelection(nom, "") }
                    }

                    // Sinon le nombre et son unité (miroir `Math.max(0, +i.value) + 'dp'`).
                    else -> {
                        val valeur = formaterNombre(nombre) + "dp"
                        editerOuRecharger { viewModel.definirAttributSelection(nom, valeur) }
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
        }
        return bloc.root
    }

    /** Le NOMBRE en tête d'une valeur de coin (« 8dp » → « 8 », miroir `parseFloat(ex(…)) || ''`). */
    private fun nombreEnTete(valeur: String?): String {
        val nombre = Regex("^[-+]?(?:\\d+\\.?\\d*|\\.\\d+)").find(valeur.orEmpty())?.value ?: return ""
        return formaterNombre(nombre.toFloat())
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

    // ── PE11h : action rapide, contraintes en lecture, attribut libre ────

    /**
     * Le contrôle ACTIONS RAPIDES (controle_action.xml — miroir `.qa`) :
     * une Chip « lien » par action, SANS en-tête ni champ (une action ne
     * compte jamais dans le badge « n définis ») ; chaque geste = UNE
     * ÉDITION GROUPÉE + le retour maison du libellé (miroir
     * `mutate(fn, lab)`).
     */
    private fun controleActionRapide(controle: CatalogueAttributs.ControleAttribut.ActionRapide): View {
        val bloc = ControleActionBinding.inflate(gonfleur())
        controle.actions.forEach { action ->
            val puce = ItemActionBinding.inflate(gonfleur(), bloc.root, false)
            puce.action.text = etiquetteRes(action.libelle)
            puce.action.setOnClickListener {
                when (action.ecriture) {
                    CatalogueAttributs.ControleAttribut.ActionRapide.Ecriture.RemplirEspaceRestant -> {
                        if (editerOuRecharger { viewModel.remplirEspaceRestantSelection() }) {
                            annoncerCorrectif(R.string.preview_action_remplir_toast)
                        }
                    }
                }
            }
            bloc.root.addView(puce.root)
        }
        return bloc.root
    }

    /**
     * Les contraintes EN LECTURE (controle_contraintes_lecture.xml —
     * miroir `con`) : TOUTES les ancres `app:layout_constraint*` du
     * document, une ligne « ancre = cible » par contrainte (le préfixe
     * commun se retire, miroir `x[0].replace('app:layout_constraint',
     * '')`), puis la NOTE — l'édition par côtés suit dans la même section
     * (contrôle Contraintes, PE8b). Le bloc ne porte NI puce d'espace de
     * noms NI ↺ (rien à rétablir : il ne détient pas les attributs).
     */
    private fun controleContraintesLecture(
        controle: CatalogueAttributs.ControleAttribut.ContraintesLecture,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleContraintesLectureBinding.inflate(gonfleur())
        brancherEntete(bloc.entete, etiquetteRes(controle.etiquette), emptyList(), attrs)
        bloc.valeurLectureSeule.text =
            attrs
                .filter { it.nom.startsWith(PREFIXE_ANCRE) }
                .joinToString("\n") { attr ->
                    attr.nom.removePrefix(PREFIXE_ANCRE) + " = " + attr.valeur
                }
        bloc.root.addView(noteSousChamp(getString(R.string.preview_note_contraintes_lues)))
        return bloc.root
    }

    /**
     * La section AUTRES ATTRIBUTS (controle_attribut_libre.xml — miroir
     * `.other`) : une rangée éditable par attribut du document HORS
     * catalogue ([nomsInconnus] — la recherche filtre les NOMS), puis la
     * rangée d'AJOUT qui ne se montre qu'en mode TOUS (miroir
     * `if (S.attrMode === 't')`). Chaque geste = UNE édition au port.
     */
    private fun controleAttributLibre(attrs: List<EditionAttributsXml.AttributSource>): View {
        val bloc = ControleAttributLibreBinding.inflate(gonfleur())
        val requete = rechercheInterne.value.trim().lowercase()
        val ranges =
            nomsInconnus(attrs).let { inconnus ->
                if (requete.isEmpty()) {
                    inconnus
                } else {
                    inconnus.filter { it.nom.lowercase().contains(requete) }
                }
            }
        ranges.forEach { attr ->
            bloc.listeAttributsLibres.addView(rangeeAttributLibre(attr, bloc.listeAttributsLibres))
        }
        bloc.rangeeAjoutAttribut.isVisible = !modeCourantsInterne.value
        if (bloc.rangeeAjoutAttribut.isVisible) brancherAjoutLibre(bloc, attrs)
        return bloc.root
    }

    /**
     * Le LIAGE d'une rangée d'attribut libre EXISTANT
     * (item_attribut_libre.xml — miroir `.other` sans l'ajout) : nom en
     * mono tronqué, valeur brute éditable (commit au DONE ou à la perte de
     * focus, seulement si elle CHANGE), ✕ qui RETIRE l'attribut (une
     * édition, retour maison du nom — miroir `mutate(() => setEx(v, n,
     * ''), n + ' supprimé')`).
     */
    private fun rangeeAttributLibre(
        attr: EditionAttributsXml.AttributSource,
        parent: ViewGroup,
    ): View {
        val rangee = ItemAttributLibreBinding.inflate(gonfleur(), parent, false)
        rangee.nomAttributLibre.text = attr.nom
        rangee.valeurAttributLibre.setText(attr.valeur)
        val commettre = {
            val saisie =
                rangee.valeurAttributLibre.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            if (saisie != attr.valeur) {
                editerOuRecharger { viewModel.definirAttributSelection(attr.nom, saisie) }
            }
        }
        rangee.valeurAttributLibre.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commettre()
                rangee.valeurAttributLibre.clearFocus()
                true
            } else {
                false
            }
        }
        rangee.valeurAttributLibre.setOnFocusChangeListener { _, aFocus ->
            if (!aFocus) commettre()
        }
        rangee.boutonRetirerAttributLibre.setOnClickListener {
            if (editerOuRecharger { viewModel.definirAttributSelection(attr.nom, "") }) {
                annoncerTexte(getString(R.string.preview_attr_libre_supprime, attr.nom))
            }
        }
        return rangee.root
    }

    /**
     * La RANGÉE d'ajout libre (miroir `.other.add`) : nom à COMPLÉTION
     * (les suggestions de la maquette + les noms du catalogue PAS encore
     * posés sur la vue — miroir `[...FREE_NAMES, ...[...known]].filter(x
     * => !ex(v, x))`), valeur, « Ajouter » ; un nom qui ne ressemble pas
     * à `android:…`/`app:…`/`tools:…` ou une valeur VIDE ne s'écrit PAS
     * (toasts de la maquette, jamais d'écriture hasardeuse).
     */
    private fun brancherAjoutLibre(
        bloc: ControleAttributLibreBinding,
        attrs: List<EditionAttributsXml.AttributSource>,
    ) {
        val poses = attrs.map { it.nom }.toSet()
        val suggestions =
            (CatalogueAttributs.NOMS_LIBRES_SUGGERES + nomsConnusSelection().filter { it !in poses }).distinct()
        bloc.champNouveauNom.setAdapter(
            ArrayAdapter(requireContext(), R.layout.item_option_liste, suggestions),
        )
        // La PRÉREMPLISSAGE de la suggestion (PE11h) : le nom cherché se
        // pose une seule fois, le curseur en fin de champ, le focus part
        // dès que la carte est liée (miroir le `n.focus()` de la maquette).
        suggestionLibreEnAttente?.let { nom ->
            bloc.champNouveauNom.setText(nom)
            bloc.champNouveauNom.setSelection(nom.length)
            suggestionLibreEnAttente = null
            bloc.champNouveauNom.post { bloc.champNouveauNom.requestFocus() }
        }
        bloc.boutonAjouterAttribut.setOnClickListener {
            val nom =
                bloc.champNouveauNom.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            val valeur =
                bloc.champNouvelleValeur.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            when {
                ValidationAttribut.nomAttributLibre(nom) is ValidationAttribut.Resultat.Erreur -> {
                    annoncerTexte(getString(R.string.preview_attr_libre_nom_invalide))
                }

                valeur.isEmpty() -> {
                    annoncerTexte(getString(R.string.preview_attr_libre_valeur_manquante))
                }

                else -> {
                    if (editerOuRecharger { viewModel.definirAttributSelection(nom, valeur) }) {
                        annoncerTexte(getString(R.string.preview_attr_libre_ajoute, nom))
                    }
                }
            }
        }
    }

    /**
     * Les noms CONNUS du catalogue pour la SÉLECTION (PE11h — la
     * complétion de l'ajout libre y ajoute les absents du document).
     */
    private fun nomsConnusSelection(): Set<String> {
        val classe = viewModel.classeSelection() ?: return emptySet()
        return CatalogueAttributs.nomsConnus(
            classe,
            viewModel.familleParentSelection() ?: FamilleParent.AUCUN,
            viewModel.familleVueSelection() ?: FamilleParent.deBalise(classe),
        )
    }

    /**
     * Les attributs du document HORS catalogue (PE11h — miroir
     * `(v.extra || []).filter(x => !known.has(x[0]))`) : l'opposition du
     * [nomsConnusSelection] aux attributs lus de la sélection — la
     * matière première de la section Autres attributs.
     */
    private fun nomsInconnus(
        attrs: List<EditionAttributsXml.AttributSource>,
    ): List<EditionAttributsXml.AttributSource> {
        if (viewModel.classeSelection() == null) return emptyList()
        val connus = nomsConnusSelection()
        return attrs.filter { it.nom !in connus }
    }

    /**
     * Le tap sur la SUGGESTION « Ajouter … comme attribut libre » (PE11h
     * — miroir le `b.onclick` de la maquette, ADAPTÉ) : passe en mode
     * TOUS, VIDE la recherche (la maquette la garde — mais sa propre
     * rangée d'ajout reste alors cachée par le filtre et son `focus()`
     * n'aboutit jamais ; vider la recherche révèle la section, écart
     * voulu consigné), ouvre la section Autres attributs et PRÉREMPLIT
     * le champ de nom avec le nom cherché — SANS édition.
     */
    private fun auSuggestionLibre(nom: String) {
        rechercheInterne.value = ""
        modeCourantsInterne.value = false
        etatSections[CatalogueAttributs.TitreSection.AUTRES.name] = true
        suggestionLibreEnAttente = nom
        majAttributs()
        liaison.voletAttributs.post { amenerAuChampLibre() }
    }

    /**
     * Amène la section AUTRES à l'écran (miroir le `scrollIntoView` de la
     * maquette) : le FOCUS part au liage de la rangée d'ajout, posé par
     * la suggestion en attente — jamais deux fois.
     */
    private fun amenerAuChampLibre() {
        val position =
            adaptateur.currentList.indexOfFirst { item ->
                item is ItemAttributs.Carte && item.section.autres
            }
        if (position >= 0) liaison.voletAttributs.scrollToPosition(position)
    }

    /** Le retour maison PARAMÉTRÉ (miroir les toasts `mutate(…, msg)` de la maquette). */
    private fun annoncerTexte(texte: String) {
        (activity as? HoteVoletsApercu)?.annoncerTexte(texte)
    }

    /** L'édition ou le RECHARGEMENT : un refus → retour maison via
     * l'HÔTE (surchargeable pour un refus spécialisé, ex. renommage)
     * + volet relu depuis le texte vivant. */
    private fun editerOuRecharger(
        message: Int = R.string.preview_edition_perimee_toast,
        action: () -> Boolean,
    ): Boolean {
        if (action()) return true
        (activity as? HoteVoletsApercu)?.annoncer(message)
        majAttributs()
        return false
    }

    /**
     * Déclenche la CORRECTION (PE11g — miroir `b.onclick` de la maquette :
     * `fx.go` NAVIGUE sans édition, `fx.run` écrit UNE édition puis le
     * retour `fx.msg` part — la carte disparaît au re-rendu, critère § 6.7
     * « produit une édition et fait disparaître le problème »).
     */
    private fun declencherCorrectif(action: ActionCorrectif) {
        when (action) {
            ActionCorrectif.ElargirCible -> {
                if (editerOuRecharger { viewModel.elargirCibleSelection() }) {
                    annoncerCorrectif(R.string.preview_correctif_cible_toast)
                }
            }

            ActionCorrectif.ExtraireTexte -> {
                extraireTexteCorrectif()
            }

            ActionCorrectif.AutoriserDeuxLignes -> {
                if (editerOuRecharger { viewModel.definirAttributSelection(ATTR_MAX_LIGNES, DEUX_LIGNES) }) {
                    annoncerCorrectif(R.string.preview_correctif_tronque_toast)
                }
            }

            ActionCorrectif.OuvrirContentDescription -> {
                ouvrirContentDescription()
            }

            is ActionCorrectif.CorrigerFamille -> {
                // PE12f — l'édition se pose sur le SPAN du problème (le
                // texte vivant) : un refus = problème périmé, le volet se
                // re-marche et la carte disparaît (même retour que les
                // autres correctifs).
                if (editerOuRecharger { viewModel.corrigerProblemeFamille(action.probleme) }) {
                    annoncerCorrectifFamille(action.probleme)
                }
            }
        }
    }

    /** Le retour maison de SUCCÈS d'une correction (miroir `mutate(fx.run, fx.msg)`). */
    private fun annoncerCorrectif(message: Int) {
        (activity as? HoteVoletsApercu)?.annoncer(message)
    }

    /**
     * Le retour maison PARAMÉTRÉ d'une correction de FAMILLE (PE12f —
     * miroir les `fx.msg` : « 2 attributs retirés », « layout_below
     * retiré »…). Les noms d'attributs ne se traduisent pas (spec § 13).
     */
    private fun annoncerCorrectifFamille(probleme: ProblemesFamilles.Probleme) {
        val texte =
            when (probleme.genre) {
                ProblemesPreview.GenreProbleme.ATTRIBUT_ORPHELIN -> {
                    resources.getQuantityString(
                        R.plurals.preview_correctif_orphelin_toast,
                        probleme.attributs.size,
                        probleme.attributs.size,
                    )
                }

                ProblemesPreview.GenreProbleme.REFERENCE_INTROUVABLE -> {
                    getString(
                        R.string.preview_correctif_regle_toast,
                        probleme.attributs.first().substringAfter(':'),
                    )
                }

                ProblemesPreview.GenreProbleme.ANCRE_INVALIDE -> {
                    getString(R.string.preview_correctif_ancre_toast)
                }

                ProblemesPreview.GenreProbleme.COMPORTEMENT_MANQUANT -> {
                    getString(R.string.preview_correctif_comportement_toast)
                }

                ProblemesPreview.GenreProbleme.DEPENDANCE_CIRCULAIRE -> {
                    getString(R.string.preview_correctif_boucle_toast)
                }

                ProblemesPreview.GenreProbleme.ENFANT_UNIQUE_DEPASSE -> {
                    getString(R.string.preview_correctif_envelopper_toast)
                }

                ProblemesPreview.GenreProbleme.ENFANTS_INTERDITS,
                ProblemesPreview.GenreProbleme.ENFANTS_ADAPTATEUR,
                ProblemesPreview.GenreProbleme.ENFANT_INATTENDU,
                -> {
                    getString(R.string.preview_correctif_enfants_toast)
                }

                else -> {
                    error("genre de rendu : pas de retour de famille — $probleme")
                }
            }
        (activity as? HoteVoletsApercu)?.annoncerTexte(texte)
    }

    /**
     * « Extraire en @string » (miroir `extractStr`) : le nom proposé vient
     * de la VALEUR (le même [ExtractionRessource.nomPropose] que la rangée
     * d'extraction), l'écriture passe par le flux EXISTANT (entrée FR+EN
     * puis référence) — un refus porte le retour PROPRE à l'extraction.
     */
    private fun extraireTexteCorrectif() {
        val valeur = viewModel.valeurAttributSelection(ATTR_TEXTE).orEmpty()
        if (
            editerOuRecharger(R.string.preview_extraire_refuse) {
                viewModel.extraireEnRessource(ATTR_TEXTE, ExtractionRessource.nomPropose(valeur))
            }
        ) {
            annoncerCorrectif(R.string.preview_correctif_extraire_toast)
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
        val familleParent = viewModel.familleParentSelection() ?: FamilleParent.AUCUN
        val section =
            CatalogueAttributs.sections(classe, familleParent).firstOrNull { parcourue ->
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
        /** Le NOMBRE de sections ouvertes par défaut (miroir `++opened <= 2` de la maquette). */
        private const val OUVERTES_DEFAUT: Int = 2

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

        /** PE11h — une recherche qui RESSEMBLE à un nom d'attribut
         * (miroir `/^(android|app|tools):\w+$/` de la suggestion). */
        private val NOM_SUGGESTION: Regex = Regex("""^(android|app|tools):\w+$""")

        /** PE11h — le préfixe commun des ancres de contrainte
         * (miroir `/layout_constraint/` du contrôle `con`). */
        private const val PREFIXE_ANCRE: String = "app:layout_constraint"

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
                CatalogueAttributs.TitreSection.BARRE_APPLICATION to R.string.preview_section_barre_application,
                CatalogueAttributs.TitreSection.POSITIONS_RELATIVES to R.string.preview_section_positions_relatives,
                CatalogueAttributs.TitreSection.BARRE_OUTILS to R.string.preview_section_barre_outils,
                CatalogueAttributs.TitreSection.BOUTON_ACTION to R.string.preview_section_bouton_action,
                CatalogueAttributs.TitreSection.CARTE to R.string.preview_section_carte,
                CatalogueAttributs.TitreSection.TYPOGRAPHIE to R.string.preview_section_typographie,
                CatalogueAttributs.TitreSection.LIGNES to R.string.preview_section_lignes,
                CatalogueAttributs.TitreSection.ICONES_TEXTE to R.string.preview_section_icones_texte,
                CatalogueAttributs.TitreSection.AIDES_CHAMP to R.string.preview_section_aides_champ,
                CatalogueAttributs.TitreSection.ICONES to R.string.preview_section_icones,
                CatalogueAttributs.TitreSection.CONTOUR to R.string.preview_section_contour,
                CatalogueAttributs.TitreSection.APPARENCE to R.string.preview_section_apparence,
                CatalogueAttributs.TitreSection.TRANSFORMATION to R.string.preview_section_transformation,
                CatalogueAttributs.TitreSection.INTERACTION to R.string.preview_section_interaction,
                CatalogueAttributs.TitreSection.ACCESSIBILITE to R.string.preview_section_accessibilite,
                CatalogueAttributs.TitreSection.APERCU to R.string.preview_section_apercu,
                CatalogueAttributs.TitreSection.RACINE to R.string.preview_section_racine,
                CatalogueAttributs.TitreSection.AUTRES to R.string.preview_section_autres,
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
                // ── PE12c — § 5.1 : sections du conteneur par famille ──────
                CatalogueAttributs.Etiquette.BASELINE_ALIGNE to R.string.preview_etiquette_baseline_aligne,
                CatalogueAttributs.Etiquette.SOMME_POIDS to R.string.preview_etiquette_somme_poids,
                CatalogueAttributs.Etiquette.SEPARATEUR to R.string.preview_etiquette_separateur,
                CatalogueAttributs.Etiquette.SEPARATEUR_PADDING to R.string.preview_etiquette_separateur_padding,
                CatalogueAttributs.Etiquette.MESURER_PLUS_GRAND to R.string.preview_etiquette_mesurer_plus_grand,
                CatalogueAttributs.Etiquette.CLIP_ENFANTS to R.string.preview_etiquette_clip_enfants,
                CatalogueAttributs.Etiquette.CLIP_PADDING to R.string.preview_etiquette_clip_padding,
                CatalogueAttributs.Etiquette.ANIMER_MISE_EN_PAGE to R.string.preview_etiquette_animer_mise_en_page,
                CatalogueAttributs.Etiquette.GRAVITE_AVANT_PLAN to R.string.preview_etiquette_gravite_avant_plan,
                CatalogueAttributs.Etiquette.MESURER_TOUS_ENFANTS to R.string.preview_etiquette_mesurer_tous_enfants,
                CatalogueAttributs.Etiquette.IGNORER_GRAVITE to R.string.preview_etiquette_ignorer_gravite,
                CatalogueAttributs.Etiquette.AJUSTER_SYSTEME to R.string.preview_etiquette_ajuster_systeme,
                CatalogueAttributs.Etiquette.FOND_BARRE_ETAT to R.string.preview_etiquette_fond_barre_etat,
                CatalogueAttributs.Etiquette.ELEVER_AU_DEFILEMENT to R.string.preview_etiquette_elever_au_defilement,
                CatalogueAttributs.Etiquette.CIBLE_ELEVEE to R.string.preview_etiquette_cible_elevee,
                CatalogueAttributs.Etiquette.ETENDU to R.string.preview_etiquette_etendu,
                // ── PE12c — § 5.2 : dans le parent / positions relatives ──
                CatalogueAttributs.Etiquette.ANCRAGE_PARENT to R.string.preview_etiquette_ancrage_parent,
                CatalogueAttributs.Etiquette.ANCRAGE_DEBUT to R.string.preview_etiquette_ancrage_debut,
                CatalogueAttributs.Etiquette.ANCRAGE_FIN to R.string.preview_etiquette_ancrage_fin,
                CatalogueAttributs.Etiquette.ANCRAGE_HAUT to R.string.preview_etiquette_ancrage_haut,
                CatalogueAttributs.Etiquette.ANCRAGE_BAS to R.string.preview_etiquette_ancrage_bas,
                CatalogueAttributs.Etiquette.ANCRAGE_CENTRE_H to R.string.preview_etiquette_ancrage_centre_h,
                CatalogueAttributs.Etiquette.ANCRAGE_CENTRE_V to R.string.preview_etiquette_ancrage_centre_v,
                CatalogueAttributs.Etiquette.ANCRAGE_CENTRE to R.string.preview_etiquette_ancrage_centre,
                CatalogueAttributs.Etiquette.ALIGNER_SI_ABSENT to R.string.preview_etiquette_aligner_si_absent,
                CatalogueAttributs.Etiquette.DESSOUS_DE to R.string.preview_etiquette_dessous_de,
                CatalogueAttributs.Etiquette.DESSUS_DE to R.string.preview_etiquette_dessus_de,
                CatalogueAttributs.Etiquette.ALIGNE_HAUT_AVEC to R.string.preview_etiquette_aligne_haut_avec,
                CatalogueAttributs.Etiquette.ALIGNE_BAS_AVEC to R.string.preview_etiquette_aligne_bas_avec,
                CatalogueAttributs.Etiquette.LIGNE_BASE_AVEC to R.string.preview_etiquette_ligne_base_avec,
                CatalogueAttributs.Etiquette.A_DROITE_DE to R.string.preview_etiquette_a_droite_de,
                CatalogueAttributs.Etiquette.A_GAUCHE_DE to R.string.preview_etiquette_a_gauche_de,
                CatalogueAttributs.Etiquette.ALIGNE_DEBUT_AVEC to R.string.preview_etiquette_aligne_debut_avec,
                CatalogueAttributs.Etiquette.ALIGNE_FIN_AVEC to R.string.preview_etiquette_aligne_fin_avec,
                CatalogueAttributs.Etiquette.ANCRE to R.string.preview_etiquette_ancre,
                CatalogueAttributs.Etiquette.GRAVITE_ANCRE to R.string.preview_etiquette_gravite_ancre,
                CatalogueAttributs.Etiquette.COMPORTEMENT to R.string.preview_etiquette_comportement,
                CatalogueAttributs.Etiquette.BORD_INSET to R.string.preview_etiquette_bord_inset,
                CatalogueAttributs.Etiquette.ESQUIVER_BORDS to R.string.preview_etiquette_esquiver_bords,
                CatalogueAttributs.Etiquette.KEYLINE to R.string.preview_etiquette_keyline,
                CatalogueAttributs.Etiquette.DRAPEAUX_DEFILEMENT to R.string.preview_etiquette_drapeaux_defilement,
                CatalogueAttributs.Etiquette.INTERPOLATEUR_DEFILEMENT to
                    R.string.preview_etiquette_interpolateur_defilement,
                // ── PE12c — § 5.4 : barre d'outils / bouton d'action ──────
                CatalogueAttributs.Etiquette.TITRE to R.string.preview_etiquette_titre,
                CatalogueAttributs.Etiquette.SOUS_TITRE to R.string.preview_etiquette_sous_titre,
                CatalogueAttributs.Etiquette.ICONE_NAVIGATION to R.string.preview_etiquette_icone_navigation,
                CatalogueAttributs.Etiquette.TITRE_CENTRE to R.string.preview_etiquette_titre_centre,
                CatalogueAttributs.Etiquette.COULEUR_TITRE to R.string.preview_etiquette_couleur_titre,
                CatalogueAttributs.Etiquette.MENU to R.string.preview_etiquette_menu,
                CatalogueAttributs.Etiquette.SRC_COMPAT to R.string.preview_etiquette_src_compat,
                CatalogueAttributs.Etiquette.TEINTE_FOND to R.string.preview_etiquette_teinte_fond,
                CatalogueAttributs.Etiquette.TAILLE_FAB to R.string.preview_etiquette_taille_fab,
                CatalogueAttributs.Etiquette.TEINTE to R.string.preview_etiquette_teinte,
                CatalogueAttributs.Etiquette.LARGEUR_BORDURE to R.string.preview_etiquette_largeur_bordure,
                CatalogueAttributs.Etiquette.COULEUR_RIPPLE to R.string.preview_etiquette_couleur_ripple,
                CatalogueAttributs.Etiquette.PADDING_COMPAT to R.string.preview_etiquette_padding_compat,
                // ── PE11h — action rapide, contraintes en lecture, libre ──
                CatalogueAttributs.Etiquette.ACTIONS to R.string.preview_etiquette_actions,
                CatalogueAttributs.Etiquette.CONTRAINTES_LUES to R.string.preview_etiquette_contraintes_lues,
                CatalogueAttributs.Etiquette.REMPLIR_ESPACE to R.string.preview_etiquette_remplir_espace,
                // ── PE11 § 7 — Identité, Texte, Typographie, Lignes ────
                CatalogueAttributs.Etiquette.TAG to R.string.preview_etiquette_tag,
                CatalogueAttributs.Etiquette.ALIGNEMENT_TEXTE to R.string.preview_etiquette_alignement_texte,
                CatalogueAttributs.Etiquette.APPARENCE_TEXTE to R.string.preview_etiquette_apparence_texte,
                CatalogueAttributs.Etiquette.FAMILLE_POLICE to R.string.preview_etiquette_famille_police,
                CatalogueAttributs.Etiquette.GRAISSE_POLICE to R.string.preview_etiquette_graisse_police,
                CatalogueAttributs.Etiquette.MAJUSCULES to R.string.preview_etiquette_majuscules,
                CatalogueAttributs.Etiquette.ESPACEMENT_LETTRES to R.string.preview_etiquette_espacement_lettres,
                CatalogueAttributs.Etiquette.INTERLIGNE to R.string.preview_etiquette_interligne,
                CatalogueAttributs.Etiquette.INTERLIGNE_MULT to R.string.preview_etiquette_interligne_mult,
                CatalogueAttributs.Etiquette.PADDING_POLICE to R.string.preview_etiquette_padding_police,
                CatalogueAttributs.Etiquette.LIGNES_MIN to R.string.preview_etiquette_lignes_min,
                CatalogueAttributs.Etiquette.ELLIPSIZE to R.string.preview_etiquette_ellipsize,
                CatalogueAttributs.Etiquette.LIENS_AUTO to R.string.preview_etiquette_liens_auto,
                CatalogueAttributs.Etiquette.TEXTE_SELECTIONNABLE to R.string.preview_etiquette_texte_selectionnable,
                CatalogueAttributs.Etiquette.TAILLE_AUTO to R.string.preview_etiquette_taille_auto,
                CatalogueAttributs.Etiquette.TAILLE_AUTO_MIN to R.string.preview_etiquette_taille_auto_min,
                CatalogueAttributs.Etiquette.TAILLE_AUTO_MAX to R.string.preview_etiquette_taille_auto_max,
                // ── PE11 § 7 — Icônes du texte, Saisie ────────────────
                CatalogueAttributs.Etiquette.DRAWABLE_DEBUT to R.string.preview_etiquette_drawable_debut,
                CatalogueAttributs.Etiquette.DRAWABLE_FIN to R.string.preview_etiquette_drawable_fin,
                CatalogueAttributs.Etiquette.DRAWABLE_HAUT to R.string.preview_etiquette_drawable_haut,
                CatalogueAttributs.Etiquette.DRAWABLE_BAS to R.string.preview_etiquette_drawable_bas,
                CatalogueAttributs.Etiquette.DRAWABLE_PADDING to R.string.preview_etiquette_drawable_padding,
                CatalogueAttributs.Etiquette.DRAWABLE_TEINTE to R.string.preview_etiquette_drawable_teinte,
                CatalogueAttributs.Etiquette.OPTIONS_IME to R.string.preview_etiquette_options_ime,
                CatalogueAttributs.Etiquette.LONGUEUR_MAX to R.string.preview_etiquette_longueur_max,
                CatalogueAttributs.Etiquette.CHIFFRES to R.string.preview_etiquette_chiffres,
                CatalogueAttributs.Etiquette.SUGGESTIONS_AUTOFILL to R.string.preview_etiquette_suggestions_autofill,
                CatalogueAttributs.Etiquette.IMPORTANCE_AUTOFILL to R.string.preview_etiquette_importance_autofill,
                CatalogueAttributs.Etiquette.TOUT_SELECTIONNER to R.string.preview_etiquette_tout_selectionner,
                CatalogueAttributs.Etiquette.CURSEUR_VISIBLE to R.string.preview_etiquette_curseur_visible,
                CatalogueAttributs.Etiquette.COULEUR_INDICATION to R.string.preview_etiquette_couleur_indication,
                // ── PE11 § 7 — Champ, Aides et compteur, Icônes, Contour ─
                CatalogueAttributs.Etiquette.INDICATION_ACTIVEE to R.string.preview_etiquette_indication_activee,
                CatalogueAttributs.Etiquette.TEXTE_AIDE to R.string.preview_etiquette_texte_aide,
                CatalogueAttributs.Etiquette.ERREUR_ACTIVEE to R.string.preview_etiquette_erreur_activee,
                CatalogueAttributs.Etiquette.COMPTEUR_ACTIVE to R.string.preview_etiquette_compteur_active,
                CatalogueAttributs.Etiquette.COMPTEUR_LONGUEUR to R.string.preview_etiquette_compteur_longueur,
                CatalogueAttributs.Etiquette.TEXTE_SUBSTITUTION to R.string.preview_etiquette_texte_substitution,
                CatalogueAttributs.Etiquette.PREFIXE to R.string.preview_etiquette_prefixe,
                CatalogueAttributs.Etiquette.SUFFIXE to R.string.preview_etiquette_suffixe,
                CatalogueAttributs.Etiquette.ICONE_DEBUT to R.string.preview_etiquette_icone_debut,
                CatalogueAttributs.Etiquette.TEINTE_ICONE_FIN to R.string.preview_etiquette_teinte_icone_fin,
                CatalogueAttributs.Etiquette.TRAIT_FOND to R.string.preview_etiquette_trait_fond,
                CatalogueAttributs.Etiquette.EPAISSEUR_TRAIT to R.string.preview_etiquette_epaisseur_trait,
                CatalogueAttributs.Etiquette.FOND_BOITE to R.string.preview_etiquette_fond_boite,
                CatalogueAttributs.Etiquette.COINS to R.string.preview_etiquette_coins,
                // ── PE11 § 7 — Bouton, État, Image, Carte ─────────────
                CatalogueAttributs.Etiquette.VARIANTE to R.string.preview_etiquette_variante,
                CatalogueAttributs.Etiquette.GRAVITE_ICONE to R.string.preview_etiquette_gravite_icone,
                CatalogueAttributs.Etiquette.PADDING_ICONE to R.string.preview_etiquette_padding_icone,
                CatalogueAttributs.Etiquette.TEINTE_ICONE to R.string.preview_etiquette_teinte_icone,
                CatalogueAttributs.Etiquette.TAILLE_ICONE to R.string.preview_etiquette_taille_icone,
                CatalogueAttributs.Etiquette.COULEUR_BORDURE to R.string.preview_etiquette_couleur_bordure,
                CatalogueAttributs.Etiquette.EPAISSEUR_BORDURE to R.string.preview_etiquette_epaisseur_bordure,
                CatalogueAttributs.Etiquette.INSET_HAUT to R.string.preview_etiquette_inset_haut,
                CatalogueAttributs.Etiquette.INSET_BAS to R.string.preview_etiquette_inset_bas,
                CatalogueAttributs.Etiquette.TEINTE_CASE to R.string.preview_etiquette_teinte_case,
                CatalogueAttributs.Etiquette.CASE to R.string.preview_etiquette_case,
                CatalogueAttributs.Etiquette.TEINTE_POUCE to R.string.preview_etiquette_teinte_pouce,
                CatalogueAttributs.Etiquette.TEINTE_PISTE to R.string.preview_etiquette_teinte_piste,
                CatalogueAttributs.Etiquette.AFFICHER_TEXTE to R.string.preview_etiquette_afficher_texte,
                CatalogueAttributs.Etiquette.TEXTE_ACTIF to R.string.preview_etiquette_texte_actif,
                CatalogueAttributs.Etiquette.TEXTE_INACTIF to R.string.preview_etiquette_texte_inactif,
                CatalogueAttributs.Etiquette.AJUSTER_BORNES to R.string.preview_etiquette_ajuster_bornes,
                CatalogueAttributs.Etiquette.MODE_TEINTE to R.string.preview_etiquette_mode_teinte,
                CatalogueAttributs.Etiquette.ROGNER_PADDING to R.string.preview_etiquette_rogner_padding,
                CatalogueAttributs.Etiquette.LARGEUR_MAX to R.string.preview_etiquette_largeur_max,
                CatalogueAttributs.Etiquette.HAUTEUR_MAX to R.string.preview_etiquette_hauteur_max,
                CatalogueAttributs.Etiquette.FOND_CARTE to R.string.preview_etiquette_fond_carte,
                CatalogueAttributs.Etiquette.PADDING_CONTENU to R.string.preview_etiquette_padding_contenu,
                CatalogueAttributs.Etiquette.EMPECHER_CHEVAUCHEMENT to
                    R.string.preview_etiquette_empecher_chevauchement,
                CatalogueAttributs.Etiquette.COCHABLE to R.string.preview_etiquette_cochable,
                // ── PE11 § 7 — Progression, Liste, Contraintes ──────
                CatalogueAttributs.Etiquette.MINIMUM to R.string.preview_etiquette_minimum,
                CatalogueAttributs.Etiquette.PROGRESSION_SECONDAIRE to
                    R.string.preview_etiquette_progression_secondaire,
                CatalogueAttributs.Etiquette.TEINTE_PROGRESSION to R.string.preview_etiquette_teinte_progression,
                CatalogueAttributs.Etiquette.TEINTE_FOND_PROGRESSION to
                    R.string.preview_etiquette_teinte_fond_progression,
                CatalogueAttributs.Etiquette.TEINTE_INDETERMINEE to R.string.preview_etiquette_teinte_indeterminee,
                CatalogueAttributs.Etiquette.POUCE to R.string.preview_etiquette_pouce,
                CatalogueAttributs.Etiquette.PISTE_FENDUE to R.string.preview_etiquette_piste_fendue,
                CatalogueAttributs.Etiquette.REPERE to R.string.preview_etiquette_repere,
                CatalogueAttributs.Etiquette.NOMBRE_COLONNES to R.string.preview_etiquette_nombre_colonnes,
                CatalogueAttributs.Etiquette.INVERSER to R.string.preview_etiquette_inverser,
                CatalogueAttributs.Etiquette.EMPILER_FIN to R.string.preview_etiquette_empiler_fin,
                CatalogueAttributs.Etiquette.DEBORDEMENT to R.string.preview_etiquette_debordement,
                CatalogueAttributs.Etiquette.DEFILEMENT_IMBRIQUE to R.string.preview_etiquette_defilement_imbrique,
                CatalogueAttributs.Etiquette.NOMBRE_ITEMS to R.string.preview_etiquette_nombre_items,
                CatalogueAttributs.Etiquette.BIAIS_HORIZONTAL to R.string.preview_etiquette_biais_horizontal,
                CatalogueAttributs.Etiquette.BIAIS_VERTICAL to R.string.preview_etiquette_biais_vertical,
                CatalogueAttributs.Etiquette.POURCENTAGE_LARGEUR to R.string.preview_etiquette_pourcentage_largeur,
                CatalogueAttributs.Etiquette.RATIO to R.string.preview_etiquette_ratio,
                CatalogueAttributs.Etiquette.LARGEUR_MIN_CONTRAINTE to
                    R.string.preview_etiquette_largeur_min_contrainte,
                CatalogueAttributs.Etiquette.LARGEUR_MAX_CONTRAINTE to
                    R.string.preview_etiquette_largeur_max_contrainte,
                // ── PE11 § 7 — Apparence, Transformation, Interaction,
                //    Accessibilité, Aperçu, Racine, Disposition ─────
                CatalogueAttributs.Etiquette.HAUTEUR_MIN to R.string.preview_etiquette_hauteur_min,
                CatalogueAttributs.Etiquette.LARGEUR_MIN to R.string.preview_etiquette_largeur_min,
                CatalogueAttributs.Etiquette.FOND_BRUT to R.string.preview_etiquette_fond_brut,
                CatalogueAttributs.Etiquette.AVANT_PLAN to R.string.preview_etiquette_avant_plan,
                CatalogueAttributs.Etiquette.ELEVATION_BRUTE to R.string.preview_etiquette_elevation_brute,
                CatalogueAttributs.Etiquette.DECOUPE_CONTOUR to R.string.preview_etiquette_decoupe_contour,
                CatalogueAttributs.Etiquette.THEME to R.string.preview_etiquette_theme,
                CatalogueAttributs.Etiquette.ROTATION to R.string.preview_etiquette_rotation,
                CatalogueAttributs.Etiquette.ECHELLE_X to R.string.preview_etiquette_echelle_x,
                CatalogueAttributs.Etiquette.ECHELLE_Y to R.string.preview_etiquette_echelle_y,
                CatalogueAttributs.Etiquette.TRANSLATION_X to R.string.preview_etiquette_translation_x,
                CatalogueAttributs.Etiquette.TRANSLATION_Y to R.string.preview_etiquette_translation_y,
                CatalogueAttributs.Etiquette.TRANSLATION_Z to R.string.preview_etiquette_translation_z,
                CatalogueAttributs.Etiquette.CLIQUABLE to R.string.preview_etiquette_cliquable,
                CatalogueAttributs.Etiquette.FOCUSABLE to R.string.preview_etiquette_focusable,
                CatalogueAttributs.Etiquette.FOCUSABLE_TACTILE to R.string.preview_etiquette_focusable_tactile,
                CatalogueAttributs.Etiquette.CLIQUABLE_LONG to R.string.preview_etiquette_cliquable_long,
                CatalogueAttributs.Etiquette.AU_CLIC to R.string.preview_etiquette_au_clic,
                CatalogueAttributs.Etiquette.IMPORTANCE_ACCESSIBILITE to
                    R.string.preview_etiquette_importance_accessibilite,
                CatalogueAttributs.Etiquette.TITRE_ACCESSIBILITE to R.string.preview_etiquette_titre_accessibilite,
                CatalogueAttributs.Etiquette.LIBELLE_POUR to R.string.preview_etiquette_libelle_pour,
                CatalogueAttributs.Etiquette.INFOBULLE to R.string.preview_etiquette_infobulle,
                CatalogueAttributs.Etiquette.LECTEUR_ECRAN to R.string.preview_etiquette_lecteur_ecran,
                CatalogueAttributs.Etiquette.TOOLS_TEXTE to R.string.preview_etiquette_tools_texte,
                CatalogueAttributs.Etiquette.TOOLS_SRC to R.string.preview_etiquette_tools_src,
                CatalogueAttributs.Etiquette.TOOLS_VISIBILITE to R.string.preview_etiquette_tools_visibilite,
                CatalogueAttributs.Etiquette.TOOLS_IGNORE to R.string.preview_etiquette_tools_ignore,
                CatalogueAttributs.Etiquette.CONTEXTE to R.string.preview_etiquette_contexte,
            )
    }
}
