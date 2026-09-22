package jo.codeide.feature.newproject

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.TemplateParameterEvaluation
import jo.codeide.core.model.TemplateParameterType
import jo.codeide.core.ui.SimpleTextWatcher

/**
 * Politique de composants du rendu dynamique (étape 10 — section 12.2/12.3).
 *
 * Les champs des étapes Configuration et Informations sont générés depuis
 * les [TemplateParameterEvaluation] du moteur. Cette classe choisit le
 * **composant dédié** (section 12.2 : tuiles segmentées, cartes radio,
 * liste déroulante, interrupteur, champ texte), construit chaque vue et la
 * met à jour — l'apparition/disparition est animée par le conteneur
 * (`animateLayoutChanges`).
 *
 * Le registre [COMPOSANTS_NOMMES] fixe les composants que la spécification
 * nomme explicitement (« Type de projet » en grands boutons segmentés avec
 * icône et sous-titre, « Système de build » en cartes radio) ; tout autre
 * paramètre suit le repli générique par traits. C'est une décision de
 * **présentation** : un modèle futur s'affiche correctement sans
 * modification, et le registre reste modifiable sans toucher au moteur.
 */
@Suppress("TooManyFunctions") // Un constructeur par composant du rendu (règle 16).
class RenduParametres(
    private val conteneur: ViewGroup,
    private val ecouteur: Ecouteur,
) {
    /** Interactions émises par les champs dynamiques. */
    interface Ecouteur {
        /** Saisie d'un paramètre texte (dérivation figée). */
        fun saisirTexte(
            parametreId: String,
            valeur: String,
        )

        /** Choix d'une valeur (choix ou interrupteur). */
        fun choisirValeur(
            parametreId: String,
            valeur: String,
        )

        /** Resynchronisation d'un champ dérivé. */
        fun resynchroniser(parametreId: String)
    }

    /** Composant rendu pour un paramètre. */
    private enum class Composant {
        /** Deux grandes tuiles côte à côte (icône + sous-titre). */
        TUILES_SEGMENTEES,

        /** Cartes radio empilées avec explication. */
        CARTES_RADIO,

        /** Liste déroulante (exposée). */
        LISTE_DEROULANTE,

        /** Interrupteur avec libellé et aide. */
        INTERRUPTEUR,

        /** Champ texte encadré, éventuellement dérivé (resynchronisation). */
        CHAMP_TEXTE,
    }

    /**
     * Rend (ou met à jour) la liste des paramètres visibles d'une section.
     *
     * Les vues sont réutilisées par identifiant de paramètre : seules les
     * vues dont la visibilité change apparaissent ou disparaissent (animé
     * par le conteneur).
     */
    fun rendre(parametres: List<TemplateParameterEvaluation>) {
        // Supprime les vues de paramètres devenus invisibles.
        val visibles = parametres.filter { it.visible }.map { it.parameterId }.toSet()
        val obsoletes =
            (0 until conteneur.childCount)
                .map { conteneur.getChildAt(it) }
                .filter { it.getTag(R.id.tag_parametre_id) !in visibles }
        obsoletes.forEach(conteneur::removeView)

        parametres.filter { it.visible }.forEach { parametre ->
            var vue = vueExistante(parametre.parameterId)
            if (vue == null) {
                vue = construire(parametre)
                vue.setTag(R.id.tag_parametre_id, parametre.parameterId)
                conteneur.addView(vue)
            }
            majVue(vue, parametre)
        }
    }

    /** Retrouve la vue d'un paramètre déjà rendu. */
    private fun vueExistante(parametreId: String): View? {
        for (position in 0 until conteneur.childCount) {
            val vue = conteneur.getChildAt(position)
            if (vue.getTag(R.id.tag_parametre_id) == parametreId) return vue
        }
        return null
    }

    /** Construit la vue du composant choisi pour [parametre]. */
    private fun construire(parametre: TemplateParameterEvaluation): View =
        when (composantPour(parametre)) {
            Composant.TUILES_SEGMENTEES -> construireTuiles(parametre)
            Composant.CARTES_RADIO -> construireCartesRadio(parametre)
            Composant.LISTE_DEROULANTE -> construireListeDeroulante(parametre)
            Composant.INTERRUPTEUR -> construireInterrupteur(parametre)
            Composant.CHAMP_TEXTE -> construireChampTexte(parametre)
        }

    /** Met à jour la vue existante (valeur, erreur, aide, sous-titres). */
    @Suppress("LongMethod") // Un branch par composant, symétrique de construire.
    private fun majVue(
        vue: View,
        parametre: TemplateParameterEvaluation,
    ) {
        when (composantPour(parametre)) {
            Composant.TUILES_SEGMENTEES -> {
                val groupe = vue.findViewById<LinearLayout>(R.id.tuiles_groupe)
                val aide = vue.findViewById<TextView>(R.id.champ_aide)
                for (position in 0 until groupe.childCount) {
                    val tuile = groupe.getChildAt(position)
                    val valeur = tuile.getTag(R.id.tag_choix_valeur) as String
                    tuile.findViewById<MaterialCardView>(R.id.tuile_carte).isChecked =
                        valeur == parametre.effectiveValue
                }
                val erreur = parametre.error
                aide.text =
                    if (erreur != null) {
                        messageErreur(parametre.errorReason, erreur, vue.context)
                    } else {
                        parametre.help
                    }
            }

            Composant.CARTES_RADIO -> {
                val pile = vue.findViewById<LinearLayout>(R.id.pile_cartes)
                for (position in 0 until pile.childCount) {
                    val carte = pile.getChildAt(position)
                    val valeur = carte.getTag(R.id.tag_choix_valeur) as String
                    carte.findViewById<MaterialCardView>(R.id.carte_radio_contour).isChecked =
                        valeur == parametre.effectiveValue
                }
                val aide = vue.findViewById<TextView>(R.id.champ_aide)
                aide.text = aideSousSelection(parametre.parameterId, parametre.effectiveValue, vue.context)
                aide.isVisible = aide.text.isNotBlank()
            }

            Composant.LISTE_DEROULANTE -> {
                val champ = vue.findViewById<TextInputLayout>(R.id.champ_liste)
                val saisie = vue.findViewById<AutoCompleteTextView>(R.id.saisie_liste)
                champ.helperText = parametre.help.ifBlank { null }
                if (saisie.text.toString() != libelleChoix(parametre.effectiveValue)) {
                    saisie.setText(libelleChoix(parametre.effectiveValue), false)
                }
            }

            Composant.INTERRUPTEUR -> {
                val interrupteur = vue.findViewById<MaterialSwitch>(R.id.champ_interrupteur)
                val aide = vue.findViewById<TextView>(R.id.champ_aide)
                interrupteur.text = parametre.label
                interrupteur.isChecked = parametre.effectiveValue == "true"
                aide.text = parametre.help
            }

            Composant.CHAMP_TEXTE -> {
                val champ = vue.findViewById<TextInputLayout>(R.id.champ_texte)
                val saisie = vue.findViewById<TextInputEditText>(R.id.saisie_texte)
                champ.hint = parametre.label
                champ.helperText = parametre.help.ifBlank { null }
                val erreur = parametre.error
                champ.error =
                    if (erreur != null) {
                        messageErreur(parametre.errorReason, erreur, vue.context)
                    } else {
                        null
                    }
                // Champ dérivé : icône de resynchronisation visible dès que
                // la valeur a été figée à la main (section 12.2).
                if (parametre.derived) {
                    champ.endIconMode = TextInputLayout.END_ICON_CUSTOM
                    champ.setEndIconDrawable(R.drawable.ic_resynchroniser)
                    champ.setEndIconContentDescription(R.string.wizard_resynchroniser)
                    champ.setEndIconOnClickListener { ecouteur.resynchroniser(parametre.parameterId) }
                }
                if (saisie.text.toString() != parametre.effectiveValue && !saisie.hasFocus()) {
                    saisie.setText(parametre.effectiveValue)
                }
            }
        }
    }

    // ------------------------------------------------------- constructeurs

    /** Deux grandes tuiles côte à côte, icône et sous-titre (spec 12.3). */
    private fun construireTuiles(parametre: TemplateParameterEvaluation): View {
        val racine = gonfler(R.layout.champ_tuiles_segmentees)
        val groupe = racine.findViewById<LinearLayout>(R.id.tuiles_groupe)
        racine.findViewById<TextView>(R.id.champ_titre).text = parametre.label
        parametre.choices.take(NB_TUILES).forEach { valeur ->
            val tuile = gonfler(R.layout.vue_tuile_choix, groupe)
            tuile.setTag(R.id.tag_choix_valeur, valeur)
            tuile.findViewById<TextView>(R.id.tuile_titre).text = libelleChoix(valeur)
            tuile.findViewById<TextView>(R.id.tuile_sous_titre).text = sousTitreChoix(parametre.parameterId, valeur)
            iconeChoix(parametre.parameterId, valeur)?.let { icone ->
                tuile.findViewById<TextView>(R.id.tuile_icone).isVisible = true
                tuile.findViewById<TextView>(R.id.tuile_icone).text = icone
            }
            tuile.setOnClickListener { ecouteur.choisirValeur(parametre.parameterId, valeur) }
            groupe.addView(tuile)
        }
        return racine
    }

    /** Cartes radio empilées avec explication (spec 12.3). */
    private fun construireCartesRadio(parametre: TemplateParameterEvaluation): View {
        val racine = gonfler(R.layout.champ_cartes_radio)
        val pile = racine.findViewById<LinearLayout>(R.id.pile_cartes)
        racine.findViewById<TextView>(R.id.champ_titre).text = parametre.label
        parametre.choices.forEach { valeur ->
            val carte = gonfler(R.layout.vue_carte_radio, pile)
            carte.setTag(R.id.tag_choix_valeur, valeur)
            carte.findViewById<TextView>(R.id.carte_titre).text = libelleChoix(valeur)
            carte.findViewById<TextView>(R.id.carte_explication).text = explicationChoix(parametre.parameterId, valeur)
            carte.setOnClickListener { ecouteur.choisirValeur(parametre.parameterId, valeur) }
            pile.addView(carte)
        }
        return racine
    }

    /** Liste déroulante exposée (spec 12.3 : « Version du JDK »). */
    private fun construireListeDeroulante(parametre: TemplateParameterEvaluation): View {
        val racine = gonfler(R.layout.champ_liste_deroulante)
        val champ = racine.findViewById<TextInputLayout>(R.id.champ_liste)
        val saisie = racine.findViewById<AutoCompleteTextView>(R.id.saisie_liste)
        champ.hint = parametre.label
        val libelles = parametre.choices.map(::libelleChoix)
        saisie.setAdapter(
            ArrayAdapter(racine.context, android.R.layout.simple_list_item_1, libelles),
        )
        saisie.setOnItemClickListener { _, _, position, _ ->
            val valeur = parametre.choices.getOrNull(position) ?: return@setOnItemClickListener
            ecouteur.choisirValeur(parametre.parameterId, valeur)
        }
        return racine
    }

    /** Interrupteur avec libellé et aide (spec 12.3). */
    private fun construireInterrupteur(parametre: TemplateParameterEvaluation): View {
        val racine = gonfler(R.layout.champ_interrupteur)
        val interrupteur = racine.findViewById<MaterialSwitch>(R.id.champ_interrupteur)
        interrupteur.setOnCheckedChangeListener { _, coche ->
            ecouteur.choisirValeur(parametre.parameterId, coche.toString())
        }
        return racine
    }

    /** Champ texte encadré, avec action IME et resynchronisation. */
    private fun construireChampTexte(parametre: TemplateParameterEvaluation): View {
        val racine = gonfler(R.layout.champ_texte)
        val saisie = racine.findViewById<TextInputEditText>(R.id.saisie_texte)
        saisie.addTextChangedListener(
            object : SimpleTextWatcher() {
                override fun onTextChanged(
                    texte: CharSequence?,
                    debut: Int,
                    avant: Int,
                    nombre: Int,
                ) {
                    ecouteur.saisirTexte(parametre.parameterId, texte?.toString().orEmpty())
                }
            },
        )
        saisie.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                saisie.clearFocus()
            }
            false
        }
        return racine
    }

    /** Gonfle une vue de composant dans le conteneur (sans l'attacher). */
    private fun gonfler(
        layoutRes: Int,
        parent: ViewGroup = conteneur,
    ): View = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)

    // ---------------------------------------------------- registres

    /** Composant d'un paramètre : registre nommé puis repli par traits. */
    private fun composantPour(parametre: TemplateParameterEvaluation): Composant =
        COMPOSANTS_NOMMES[parametre.parameterId]
            ?: when (parametre.type) {
                TemplateParameterType.BOOLEAN -> Composant.INTERRUPTEUR
                TemplateParameterType.CHOICE -> Composant.LISTE_DEROULANTE
                TemplateParameterType.TEXT -> Composant.CHAMP_TEXTE
            }

    /** Libellé affichable d'une valeur de choix (registre, repli brut). */
    private fun libelleChoix(valeur: String): String =
        when (valeur) {
            "application" -> conteneur.context.getString(R.string.wizard_choix_application)
            "library" -> conteneur.context.getString(R.string.wizard_choix_bibliotheque)
            "gradle-kts" -> conteneur.context.getString(R.string.wizard_choix_gradle)
            "maven" -> conteneur.context.getString(R.string.wizard_choix_maven)
            "none" -> conteneur.context.getString(R.string.wizard_choix_sources)
            "17" -> conteneur.context.getString(R.string.wizard_choix_jdk17)
            "21" -> conteneur.context.getString(R.string.wizard_choix_jdk21)
            else -> valeur
        }

    /** Sous-titre d'une tuile (icône et sous-titre, spec 12.3). */
    private fun sousTitreChoix(
        parametreId: String,
        valeur: String,
    ): String =
        when (parametreId to valeur) {
            "projectType" to "application" -> conteneur.context.getString(R.string.wizard_sous_titre_application)
            "projectType" to "library" -> conteneur.context.getString(R.string.wizard_sous_titre_bibliotheque)
            else -> ""
        }

    /** Monogramme d'icône maison d'une tuile (aucun logo officiel). */
    private fun iconeChoix(
        parametreId: String,
        valeur: String,
    ): String? =
        when (parametreId to valeur) {
            "projectType" to "application" -> conteneur.context.getString(R.string.wizard_icone_application)
            "projectType" to "library" -> conteneur.context.getString(R.string.wizard_icone_bibliotheque)
            else -> null
        }

    /** Explication d'une carte radio. */
    private fun explicationChoix(
        parametreId: String,
        valeur: String,
    ): String =
        when (parametreId to valeur) {
            "buildSystem" to "gradle-kts" -> conteneur.context.getString(R.string.wizard_explication_gradle)
            "buildSystem" to "maven" -> conteneur.context.getString(R.string.wizard_explication_maven)
            "buildSystem" to "none" -> conteneur.context.getString(R.string.wizard_explication_sources)
            else -> ""
        }

    /** Ligne d'aide dynamique sous la sélection (spec 12.3). */
    private fun aideSousSelection(
        parametreId: String,
        valeur: String,
        contexte: Context,
    ): String =
        when (parametreId to valeur) {
            "buildSystem" to "none" -> contexte.getString(R.string.wizard_aide_sans_build)
            else -> ""
        }

    /** Message localisé d'une raison de validation (repli : message moteur). */
    @Suppress("CyclomaticComplexMethod") // Un branch par raison typée (règle 16).
    private fun messageErreur(
        raison: RaisonValidation?,
        messageMoteur: String,
        contexte: Context,
    ): String =
        when (raison) {
            is RaisonValidation.LongueurNom -> {
                contexte.getString(R.string.wizard_erreur_nom_longueur)
            }

            is RaisonValidation.CaractereInterditNom -> {
                contexte.getString(R.string.wizard_erreur_nom_caractere, raison.fautif.toString())
            }

            is RaisonValidation.PointsFictifsNom -> {
                contexte.getString(R.string.wizard_erreur_nom_points)
            }

            is RaisonValidation.FinNomInterdite -> {
                contexte.getString(R.string.wizard_erreur_nom_fin)
            }

            is RaisonValidation.NomReserveWindows -> {
                contexte.getString(R.string.wizard_erreur_nom_reserves)
            }

            is RaisonValidation.PackageVideOuSegmentVide -> {
                contexte.getString(R.string.wizard_erreur_package_vide)
            }

            is RaisonValidation.SegmentPackageInvalide -> {
                contexte.getString(R.string.wizard_erreur_package_segment, raison.segment)
            }

            is RaisonValidation.MotClePackage -> {
                contexte.getString(R.string.wizard_erreur_package_mot_cle, raison.segment)
            }

            is RaisonValidation.IdentifiantInvalide -> {
                contexte.getString(R.string.wizard_erreur_identifiant)
            }

            is RaisonValidation.MotCleIdentifiant -> {
                contexte.getString(R.string.wizard_erreur_identifiant_mot_cle)
            }

            is RaisonValidation.VersionInvalide -> {
                contexte.getString(R.string.wizard_erreur_version)
            }

            is RaisonValidation.RegexNonCorrespondance -> {
                if (raison.motif == MOTIF_ARTIFACT) {
                    contexte.getString(R.string.wizard_erreur_artifact)
                } else {
                    contexte.getString(R.string.wizard_erreur_motif, raison.motif)
                }
            }

            is RaisonValidation.ValeurInterdite -> {
                messageMoteur
            }

            null -> {
                messageMoteur
            }
        }

    private companion object {
        /** Nombre de tuiles côte à côte (« deux grands boutons segmentés »). */
        const val NB_TUILES = 2

        /** Motif de validation de l'artifact ID (modèles de l'étape 9). */
        const val MOTIF_ARTIFACT = "^[a-z0-9][a-z0-9._-]*$"

        /** Composants explicitement nommés par la spécification (section 12.3). */
        val COMPOSANTS_NOMMES: Map<String, Composant> =
            mapOf(
                "projectType" to Composant.TUILES_SEGMENTEES,
                "buildSystem" to Composant.CARTES_RADIO,
            )
    }
}
