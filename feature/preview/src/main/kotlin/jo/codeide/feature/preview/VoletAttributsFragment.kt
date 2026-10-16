package jo.codeide.feature.preview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import jo.codeide.feature.preview.databinding.CarteSectionBinding
import jo.codeide.feature.preview.databinding.ControleContrainteBinding
import jo.codeide.feature.preview.databinding.ControleContraintesBinding
import jo.codeide.feature.preview.databinding.ControleCotesBinding
import jo.codeide.feature.preview.databinding.ControleDimensionsBinding
import jo.codeide.feature.preview.databinding.ControleExtractionBinding
import jo.codeide.feature.preview.databinding.ControleIdentifiantBinding
import jo.codeide.feature.preview.databinding.ControleSegmentsBinding
import jo.codeide.feature.preview.databinding.ControleTexteBinding
import jo.codeide.feature.preview.databinding.EnteteEditeurBinding
import jo.codeide.feature.preview.databinding.FragmentVoletAttributsBinding
import jo.codeide.feature.preview.databinding.ItemAttributBinding
import jo.codeide.feature.preview.databinding.ItemCoteBinding
import jo.codeide.feature.preview.databinding.ItemDimensionBinding
import jo.codeide.feature.preview.databinding.ItemLectureSeuleBinding
import jo.codeide.feature.preview.databinding.ItemLibelleBinding
import jo.codeide.feature.preview.databinding.ItemSegmentBinding
import jo.codeide.feature.preview.databinding.ItemTitreGroupeBinding
import jo.codeide.feature.preview.databinding.SaisieNombreBinding
import jo.codeide.feature.preview.databinding.SaisieTexteBinding
import jo.codeide.feature.preview.databinding.SeparateurEditeurBinding
import jo.codeide.preview.xml.CatalogueAttributs
import jo.codeide.preview.xml.EditionAttributsXml
import jo.codeide.preview.xml.ExtractionRessource
import jo.codeide.preview.xml.FeuilleApercu
import jo.codeide.preview.xml.InfoVueRendu
import jo.codeide.preview.xml.RenommageIdentifiant

/**
 * Le volet ATTRIBUTS de la feuille (PE9b, miroir `renderAttrs` +
 * `renderEditor`) : la section MODIFIER (le catalogue pur
 * [CatalogueAttributs] décrit les SECTIONS selon la classe de la vue et
 * le genre de son parent — cartes repliables `carte_section.xml`, état
 * mémorisé par titre) puis les groupes LECTURE SEULE — TOUTES les vues
 * viennent d'ITEM LAYOUTS RÉELS (un par type de contrôle, miroir `ctl` :
 * texte, identifiant, nombre, segments, cotes, dimensions, contraintes)
 * gonflés au liage ; le catalogue reste la source de vérité.
 *
 * UN SEUL CHEMIN d'édition : chaque contrôle passe au ViewModel (UNE
 * écriture au port, rendu immédiat) ; un refus recharge le volet depuis
 * le texte vivant ([editerOuRecharger]). Les `@string` restent en
 * lecture seule (miroir `.ro`).
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions + LargeClass —
 * chaque contrôle a SA fonction de liage (un type = un item layout) ;
 * les MagicNumber de PE9a ont disparu avec les dimensions en code
 * (elles vivent dans les layouts).
 */
@Suppress("TooManyFunctions", "LargeClass")
@AndroidEntryPoint
internal class VoletAttributsFragment : VoletFeuilleFragment() {
    override val onglet: Int = FeuilleApercuFragment.ONGLET_ATTRIBUTS

    private lateinit var liaison: FragmentVoletAttributsBinding

    /**
     * L'état de repli des SECTIONS de l'éditeur, par TITRE (miroir `S.so`
     * de la maquette — mémorisé pendant la session du fragment).
     */
    private val etatSections: MutableMap<String, Boolean> = mutableMapOf()

    private fun gonfleur(): LayoutInflater = LayoutInflater.from(requireContext())

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

    /**
     * Vide le conteneur reconstruit (seul volet en LinearLayout) — le
     * crash du rapport 0.134.30 NPE `mViewFlags` (rapport c8f6ffeb)
     * vivait ICI : le protocole focus-sûr RETIRE le focus d'abord, BLOQUE
     * la descente de focus pendant la suppression (FOCUS_BLOCK_DESCENDANTS),
     * puis rétablit. (Un passage RecyclerView de ce volet reste possible
     * mais les cartes repliables avec état éclatent le diff pour rien.)
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
     * (cartes repliables) puis les groupes LECTURE SEULE (identité,
     * disposition, mesuré — item_titre_groupe + item_attribut).
     */
    private fun majAttributs() {
        val vue = renduCourant?.vues?.getOrNull(selectionCourante)
        viderConteneur(liaison.groupesAttributs)
        if (vue == null) {
            liaison.groupesAttributs.addView(texteVide(getString(R.string.preview_feuille_vide)))
            return
        }
        majSectionModifier(vue)
        for (groupe in FeuilleApercu.groupesAttributs(vue, viewModel.moteur.profil.densiteDpi)) {
            val titre = ItemTitreGroupeBinding.inflate(gonfleur(), liaison.groupesAttributs, false)
            titre.titreGroupe.text = groupe.titre
            liaison.groupesAttributs.addView(titre.root)
            for (entree in groupe.entrees) {
                val rangee = ItemAttributBinding.inflate(gonfleur(), liaison.groupesAttributs, false)
                rangee.libelleAttribut.text = entree.libelle
                rangee.valeurAttribut.text = entree.valeur
                liaison.groupesAttributs.addView(rangee.root)
            }
        }
    }

    /** Un TextView simple de volet vide (miroir `texteSimple` des volets). */
    private fun texteVide(texte: String): TextView = StyleVolet.texteVide(requireContext(), texte)

    /**
     * La section MODIFIER (PE7e, miroir `renderEditor` + `sectionsFor`) :
     * l'en-tête (« MODIFIER · CLASSE »), les cartes repliables des
     * sections, le séparateur de fin — un span périmé retombe en LECTURE
     * SEULE avec explication (miroir piège § 6 du prompt).
     */
    private fun majSectionModifier(vue: InfoVueRendu) {
        val classe = viewModel.classeSelection() ?: vue.classe
        val genreParent =
            viewModel.genreParentSelection() ?: CatalogueAttributs.GenreParent.AUCUN
        val entete = EnteteEditeurBinding.inflate(gonfleur(), liaison.groupesAttributs, false)
        entete.titreEditeur.text = getString(R.string.preview_modifier_classe, classe)
        liaison.groupesAttributs.addView(entete.root)
        val attrs = viewModel.attributsSelection()
        if (attrs == null) {
            liaison.groupesAttributs.addView(
                blocLectureSeule(liaison.groupesAttributs, getString(R.string.preview_edition_impossible_toast)),
            )
            return
        }
        val sections = CatalogueAttributs.sections(classe, genreParent)
        sections.forEachIndexed { position, section ->
            liaison.groupesAttributs.addView(carteSection(section, attrs, position))
        }
        val separateur = SeparateurEditeurBinding.inflate(gonfleur(), liaison.groupesAttributs, false)
        separateur.separateurEditeur.alpha = SEPARATEUR_ALPHA
        liaison.groupesAttributs.addView(separateur.root)
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
     * UNE CARTE de section repliable (`carte_section.xml`) — le tap sur
     * l'en-tête replie/déploie et MÉMORISE l'état par titre
     * ([etatSections]) ; la flèche suit l'état de la fermeture locale.
     */
    private fun carteSection(
        section: CatalogueAttributs.SectionAttributs,
        attrs: List<EditionAttributsXml.AttributSource>,
        position: Int,
    ): View {
        var ouverte = etatSections[section.titre.name] ?: (position < DEFAUT_OUVERTES)
        val carte = CarteSectionBinding.inflate(gonfleur(), liaison.groupesAttributs, false)
        carte.titreSection.text = titreSectionRes(section.titre)
        majBadgeDefinis(carte, nombreDefinis(section, attrs))
        majFleche(carte.flecheSection, ouverte)
        carte.corpsSection.visibility = visibiliteOuverte(ouverte)
        section.controles.forEach { controle ->
            carte.corpsSection.addView(controleSection(controle, attrs))
        }
        carte.enteteSection.setOnClickListener {
            ouverte = !ouverte
            etatSections[section.titre.name] = ouverte
            majFleche(carte.flecheSection, ouverte)
            carte.corpsSection.visibility = visibiliteOuverte(ouverte)
        }
        return carte.root
    }

    /** Le badge « n défini(s) » (violet sur violet pâle), caché à zéro. */
    private fun majBadgeDefinis(
        carte: CarteSectionBinding,
        nombre: Int,
    ) {
        carte.badgeDefinis.isVisible = nombre > 0
        carte.badgeDefinis.text =
            resources.getQuantityString(R.plurals.preview_section_definis, nombre, nombre)
    }

    /** Le libellé de flèche d'une carte (« ▴ ouverte » / « ▾ fermée »). */
    private fun majFleche(
        fleche: TextView,
        ouverte: Boolean,
    ) {
        fleche.text =
            getString(if (ouverte) R.string.preview_section_ouverte else R.string.preview_section_fermee)
    }

    /** La visibilité d'un corps replié. */
    private fun visibiliteOuverte(ouverte: Boolean): Int = if (ouverte) View.VISIBLE else View.GONE

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

    /** Un bloc LECTURE SEULE (item_lecture_seule.xml — mono sur surface 2). */
    private fun blocLectureSeule(
        parent: ViewGroup,
        texte: String,
    ): TextView =
        ItemLectureSeuleBinding
            .inflate(gonfleur(), parent, false)
            .apply {
                texteLectureSeule.text = texte
            }.texteLectureSeule

    /** Un contrôle TEXTE (controle_texte.xml) : littéral éditable ou
     * référence en lecture seule (miroir `.ro`), EXTRACTION offerte (PE8c). */
    private fun controleTexte(
        controle: CatalogueAttributs.ControleAttribut.Texte,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleTexteBinding.inflate(gonfleur())
        val libelle = etiquetteRes(controle.etiquette)
        bloc.libelleControle.text = libelle
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
        bloc.libelleControle.text = etiquetteRes(controle.etiquette)
        val courant = valeurDe(controle.nom, attrs) ?: controle.valeurs.first()
        segmentes(bloc.rangeeSegments, controle.valeurs, courant, etiquetteRes(controle.etiquette)) { choix ->
            val valeur = if (choix == controle.valeurs.first()) "" else choix
            editerOuRecharger { viewModel.definirAttributSelection(controle.nom, valeur) }
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
        bloc.libelleControle.text = etiquetteRes(controle.etiquette)
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
        segmentes(bloc.rangeeSegments, valeurs, courant, etiquetteRes(controle.etiquette)) { choix ->
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
     * La rangée de SEGMENTS (miroir `.seg2` — item_segment.xml gonflé
     * dans la rangée donnée) : le tap valide (pas de frappe
     * intermédiaire), le bouton coché suit `isSelected`.
     */
    private fun segmentes(
        rangee: LinearLayout,
        valeurs: List<String>,
        courant: String,
        description: String,
        onChoix: (String) -> Unit,
    ) {
        valeurs.forEach { valeur ->
            val segment = ItemSegmentBinding.inflate(gonfleur(), rangee, false)
            segment.segment.text = valeur
            segment.segment.isSelected = valeur == courant
            segment.segment.contentDescription = "$description : $valeur"
            segment.segment.setOnClickListener {
                if (valeur != courant) onChoix(valeur)
            }
            rangee.addView(segment.root)
        }
    }

    /** Un contrôle CÔTÉS (controle_cotes.xml — les QUATRE champs
     * item_cote gonflés, miroir `.mrow`). */
    private fun controleCotes(
        controle: CatalogueAttributs.ControleAttribut.Cotes,
        attrs: List<EditionAttributsXml.AttributSource>,
    ): View {
        val bloc = ControleCotesBinding.inflate(gonfleur())
        bloc.libelleControle.text = etiquetteRes(controle.etiquette)
        val cotes =
            listOf(
                EditionAttributsXml.Cote.HAUT to R.string.preview_cote_haut,
                EditionAttributsXml.Cote.FIN to R.string.preview_cote_droite,
                EditionAttributsXml.Cote.BAS to R.string.preview_cote_bas,
                EditionAttributsXml.Cote.DEBUT to R.string.preview_cote_gauche,
            )
        cotes.forEach { (cote, libelleRes) ->
            bloc.rangeeCotes.addView(champCote(controle, cote, getString(libelleRes), attrs, bloc.rangeeCotes))
        }
        return bloc.root
    }

    /** UN champ de côté (item_cote.xml — commit IME/perte de focus). */
    private fun champCote(
        controle: CatalogueAttributs.ControleAttribut.Cotes,
        cote: EditionAttributsXml.Cote,
        libelleCote: String,
        attrs: List<EditionAttributsXml.AttributSource>,
        parent: ViewGroup,
    ): View {
        val champ = ItemCoteBinding.inflate(gonfleur(), parent, false)
        champ.champCote.setText(EditionAttributsXml.valeurCote(attrs, controle.base, cote)?.removeSuffix("dp"))
        champ.champCote.hint = libelleCote
        champ.champCote.contentDescription =
            getString(R.string.preview_cote_cd, etiquetteRes(controle.etiquette), libelleCote)
        val commettre = {
            val saisie =
                champ.champCote.text
                    ?.toString()
                    ?.trim()
                    .orEmpty()
            val nombre = saisie.toIntOrNull()
            when {
                saisie.isEmpty() -> {
                    false
                }

                nombre == null || nombre < 0 -> {
                    champ.champCote.setText(
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
        champ.champCote.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (commettre()) champ.champCote.clearFocus()
                true
            } else {
                false
            }
        }
        champ.champCote.setOnFocusChangeListener { _, aFocus -> if (!aFocus) commettre() }
        return champ.root
    }

    /** Un contrôle DIMENSIONS (controle_dimensions.xml — les DEUX axes
     * Largeur/Hauteur, PE7d repris). */
    private fun controleDimensions(): View {
        val bloc = ControleDimensionsBinding.inflate(gonfleur())
        rangeeDimension(
            bloc.largeur,
            EditionAttributsXml.Axe.LARGEUR,
            getString(R.string.preview_modifier_largeur),
        )
        rangeeDimension(
            bloc.hauteur,
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
        rangee.libelleAxe.text = libelle
        rangee.champFixe.isVisible = modeCourant == EditionAttributsXml.ModeDimension.FIXE
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
        segmentes(rangee.rangeeSegmentsDimension, valeurs.map { it.second }, courant, description) { texteChoix ->
            val mode =
                valeurs.firstOrNull { it.second == texteChoix }?.first
                    ?: EditionAttributsXml.ModeDimension.FIXE
            rangee.champFixe.isVisible = mode == EditionAttributsXml.ModeDimension.FIXE
            ecrireDimension(axe, mode, valeurFixe)
        }
        rangee.champFixe.setText(valeurFixe?.toString() ?: "")
        rangee.champFixe.doAfterTextChanged { texte ->
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
