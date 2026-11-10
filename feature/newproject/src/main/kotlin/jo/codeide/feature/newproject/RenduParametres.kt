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
import jo.codeide.core.model.ChoixEvalue
import jo.codeide.core.model.EtatParametre
import jo.codeide.core.model.RaisonValidation
import jo.codeide.core.model.RenduParametre
import jo.codeide.core.model.TemplateParameterEvaluation
import jo.codeide.core.model.TemplateParameterType
import jo.codeide.core.ui.SimpleTextWatcher

/**
 * Politique de composants du rendu dynamique (étape 10 — section 12.2/12.3,
 * enrichie ADR 0154 — lot L5c).
 *
 * Les champs de toute étape de modèle sont générés depuis les
 * [TemplateParameterEvaluation] du moteur. Cette classe choisit le
 * **composant déclaré** (`rendu` du manifeste — l'UI n'invente plus),
 * construit chaque vue et la met à jour : apparition/disparition animée par
 * le conteneur, **titres de groupe** (`groupe` + `groupe.<id>.titre`),
 * **zone « Avancé » repliable** (paramètres `avance`), **cartes** pour les
 * interrupteurs groupés (« Bibliothèques » de la maquette), et **états**
 * actif/désactivé avec leur raison (ADR 0153 §2 — désactivé ≠ masqué :
 * grisé, raison annoncée à TalkBack).
 *
 * Les libellés de choix viennent du **dictionnaire du modèle** ([ChoixEvalue])
 * quand le manifeste les déclare ; à défaut, repli sur le registre de
 * présentation du wizard, puis sur la valeur brute. C'est une décision de
 * **présentation** : un modèle futur s'affiche correctement sans
 * modification, et le registre reste modifiable sans toucher au moteur.
 */
@Suppress("TooManyFunctions") // Un constructeur par composant du rendu (règle 16).
class RenduParametres(private val conteneur: ViewGroup, private val ecouteur: Ecouteur) {
    /** Interactions émises par les champs dynamiques. */
    interface Ecouteur {
        /** Saisie d'un paramètre texte (dérivation figée). */
        fun saisirTexte(parametreId: String, valeur: String)

        /** Choix d'une valeur (choix ou interrupteur). */
        fun choisirValeur(parametreId: String, valeur: String)

        /** Resynchronisation d'un champ dérivé. */
        fun resynchroniser(parametreId: String)
    }

    /** Composant rendu pour un paramètre. */
    private enum class Composant {
        /** Deux grandes tuiles côte à côte (icône + sous-titre). */
        TUILES_SEGMENTEES,

        /** Cartes radio empilées avec explication. */
        CARTES_RADIO,

        /** Liste déroulante (repli historique des `CHOICE`). */
        LISTE_DEROULANTE,

        /** Interrupteur avec libellé et aide. */
        INTERRUPTEUR,

        /** Carte-interrupteur (booléens groupés — « Bibliothèques »). */
        CARTE_INTERRUPTUEUR,

        /** Champ texte encadré, éventuellement dérivé (resynchronisation). */
        CHAMP_TEXTE,
    }

    /**
     * Rend (ou met à jour) la liste des paramètres visibles de l'étape.
     *
     * Les vues sont réutilisées par identifiant de paramètre : seules les
     * vues dont la visibilité change apparaissent ou disparaissent (animé
     * par le conteneur). Les titres de groupe et la zone « Avancé » sont
     * reconstruits à chaque rendu (léger, ordre du manifeste).
     */
    fun rendre(parametres: List<TemplateParameterEvaluation>) {
        // Supprime les vues de paramètres devenus invisibles.
        val visibles = parametres.filter { it.visible }.map { it.parameterId }.toSet()
        val obsoletes =
            (0 until conteneur.childCount)
                .map { conteneur.getChildAt(it) }
                .filter { it.getTag(R.id.tag_parametre_id) !in visibles }
        obsoletes.forEach(conteneur::removeView)

        // Zone « Avancé » et titres de groupe reconstruits : retirés d'abord
        // (leurs enfants les réintègrent si nécessaire au nouveau rendu).
        while (true) {
            val zone = conteneur.findViewWithTag<View>(ETIQUETTE_AVANCEE) ?: break
            conteneur.removeView(zone)
        }
        while (true) {
            val titre = conteneur.findViewWithTag<View>(ETIQUETTE_TITRE_GROUPE) ?: break
            conteneur.removeView(titre)
        }

        val principaux = mutableListOf<TemplateParameterEvaluation>()
        val avances = mutableListOf<TemplateParameterEvaluation>()
        parametres.filter { it.visible }.forEach { parametre ->
            if (parametre.avance) avances += parametre else principaux += parametre
        }

        // Titres de groupe : un titre avant le premier paramètre visible
        // de chaque groupe (ordre du manifeste conservé).
        var dernierGroupe: String? = null
        principaux.forEach { parametre ->
            if (parametre.groupe != null && parametre.groupe != dernierGroupe) {
                conteneur.addView(titreGroupe(parametre))
                dernierGroupe = parametre.groupe
            }
            var vue = vueExistante(parametre.parameterId)
            if (vue == null) {
                vue = construire(parametre)
                vue.setTag(R.id.tag_parametre_id, parametre.parameterId)
                conteneur.addView(vue)
            }
            majVue(vue, parametre)
        }

        // Zone « Avancé » : repliée par défaut (ce qui change rarement).
        if (avances.isNotEmpty()) {
            conteneur.addView(zoneAvancee(avances))
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

    /** Titre résolu d'un groupe d'affichage (ADR 0154 §3). */
    private fun titreGroupe(parametre: TemplateParameterEvaluation): View {
        val titre = gonfler(R.layout.vue_groupe_titre, conteneur)
        titre.setTag(ETIQUETTE_TITRE_GROUPE)
        titre.findViewById<TextView>(R.id.titre_groupe).text =
            parametre.groupeTitre.ifBlank { parametre.groupe.orEmpty() }
        return titre
    }

    /** Zone « Avancé » repliable : en-tête + conteneur des paramètres rares. */
    private fun zoneAvancee(avances: List<TemplateParameterEvaluation>): View {
        val zone = gonfler(R.layout.vue_zone_avancee, conteneur)
        zone.setTag(ETIQUETTE_AVANCEE)
        val conteneurAvance = zone.findViewById<LinearLayout>(R.id.conteneur_avance)
        val chevron = zone.findViewById<View>(R.id.chevron_avance)
        val entete = zone.findViewById<View>(R.id.entete_avance)
        entete.setOnClickListener {
            val nouveau = !conteneurAvance.isVisible
            chevron.rotation = if (nouveau) ROTATION_OUVERT else ROTATION_FERME
            conteneurAvance.isVisible = nouveau
            entete.contentDescription =
                conteneur.context.getString(
                    if (nouveau) R.string.wizard_avance_replier else R.string.wizard_avance_deplier,
                )
        }
        var dernierGroupe: String? = null
        avances.forEach { parametre ->
            if (parametre.groupe != null && parametre.groupe != dernierGroupe) {
                conteneurAvance.addView(titreGroupe(parametre))
                dernierGroupe = parametre.groupe
            }
            var vue = vueExistante(parametre.parameterId)
            if (vue == null) {
                vue = construire(parametre)
                vue.setTag(R.id.tag_parametre_id, parametre.parameterId)
            } else {
                (vue.parent as? ViewGroup)?.removeView(vue)
            }
            conteneurAvance.addView(vue)
            majVue(vue, parametre)
        }
        return zone
    }

    /** Construit la vue du composant choisi pour [parametre]. */
    private fun construire(parametre: TemplateParameterEvaluation): View = when (composantPour(parametre)) {
        Composant.TUILES_SEGMENTEES -> construireTuiles(parametre)
        Composant.CARTES_RADIO -> construireCartesRadio(parametre)
        Composant.LISTE_DEROULANTE -> construireListeDeroulante(parametre)
        Composant.INTERRUPTEUR -> construireInterrupteur(parametre)
        Composant.CARTE_INTERRUPTUEUR -> construireCarteInterrupteur(parametre)
        Composant.CHAMP_TEXTE -> construireChampTexte(parametre)
    }

    /** Met à jour la vue existante (valeur, erreur, aide, état, sous-titres). */
    @Suppress("LongMethod", "CyclomaticComplexMethod") // Un branch par composant, symétrique de construire (règle 16).
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
                    tuile.isEnabled = parametre.etat is EtatParametre.Visible
                    tuile.alpha = alphaEtat(parametre)
                }
                aide.text = aideDynamique(parametre, vue.context)
            }

            Composant.CARTES_RADIO -> {
                val pile = vue.findViewById<LinearLayout>(R.id.pile_cartes)
                for (position in 0 until pile.childCount) {
                    val carte = pile.getChildAt(position)
                    val valeur = carte.getTag(R.id.tag_choix_valeur) as String
                    carte.findViewById<MaterialCardView>(R.id.carte_radio_contour).isChecked =
                        valeur == parametre.effectiveValue
                    carte.isEnabled = parametre.etat is EtatParametre.Visible
                    carte.alpha = alphaEtat(parametre)
                }
                val aide = vue.findViewById<TextView>(R.id.champ_aide)
                aide.text = aideDynamique(parametre, vue.context)
                aide.isVisible = aide.text.isNotBlank()
            }

            Composant.LISTE_DEROULANTE -> {
                val champ = vue.findViewById<TextInputLayout>(R.id.champ_liste)
                val saisie = vue.findViewById<AutoCompleteTextView>(R.id.saisie_liste)
                champ.hint = parametre.label
                champ.helperText =
                    parametre.help.ifBlank { null }
                champ.error =
                    (parametre.etat as? EtatParametre.Desactive)?.raison
                champ.isEnabled = parametre.etat is EtatParametre.Visible
                if (saisie.text.toString() != libelleChoix(parametre, parametre.effectiveValue)) {
                    saisie.setText(libelleChoix(parametre, parametre.effectiveValue), false)
                }
            }

            Composant.INTERRUPTEUR -> {
                val interrupteur = vue.findViewById<MaterialSwitch>(R.id.champ_interrupteur)
                val aide = vue.findViewById<TextView>(R.id.champ_aide)
                interrupteur.text = parametre.label
                interrupteur.isChecked = parametre.effectiveValue == "true"
                interrupteur.isEnabled = parametre.etat is EtatParametre.Visible
                interrupteur.alpha = alphaEtat(parametre)
                aide.text = aideDynamique(parametre, vue.context)
            }

            Composant.CARTE_INTERRUPTUEUR -> {
                val carte = vue.findViewById<MaterialCardView>(R.id.carte_interruption)
                val interrupteur = vue.findViewById<MaterialSwitch>(R.id.interrupteur_carte)
                val titre = vue.findViewById<TextView>(R.id.titre_carte)
                val aide = vue.findViewById<TextView>(R.id.aide_carte)
                titre.text = parametre.label
                aide.text = aideDynamique(parametre, vue.context)
                aide.isVisible = aide.text.isNotBlank()
                interrupteur.isChecked = parametre.effectiveValue == "true"
                interrupteur.isEnabled = parametre.etat is EtatParametre.Visible
                carte.alpha = alphaEtat(parametre)
            }

            Composant.CHAMP_TEXTE -> {
                val champ = vue.findViewById<TextInputLayout>(R.id.champ_texte)
                val saisie = vue.findViewById<TextInputEditText>(R.id.saisie_texte)
                champ.hint = parametre.label
                champ.helperText =
                    parametre.help.ifBlank { null }
                champ.error =
                    (parametre.etat as? EtatParametre.Desactive)?.raison
                        ?: parametre.error?.let { messageErreur(parametre.errorReason, it, vue.context) }
                champ.isEnabled = parametre.etat is EtatParametre.Visible
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

    /** Opacité d'un champ selon son état (désactivé = grisé, lisible). */
    private fun alphaEtat(parametre: TemplateParameterEvaluation): Float =
        if (parametre.etat is EtatParametre.Desactive) ALPHA_DESACTIVE else ALPHA_ACTIVE

    /**
     * Ligne d'aide d'un champ : **la raison de désactivation prime** (ADR
     * 0153 §2), sinon l'erreur de validation, sinon l'aide déclarée, sinon
     * l'aide sous sélection du registre.
     */
    private fun aideDynamique(parametre: TemplateParameterEvaluation, contexte: Context): String {
        val raison = (parametre.etat as? EtatParametre.Desactive)?.raison
        val erreur = parametre.error
        return when {
            raison != null -> raison.ifBlank { contexte.getString(R.string.wizard_parametre_desactive) }
            erreur != null -> messageErreur(parametre.errorReason, erreur, contexte)
            parametre.help.isNotBlank() -> parametre.help
            else -> aideSousSelection(parametre, contexte)
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
            tuile.findViewById<TextView>(R.id.tuile_titre).text = libelleChoix(parametre, valeur)
            tuile.findViewById<TextView>(R.id.tuile_sous_titre).text = sousTitreChoix(parametre, valeur)
            iconeChoix(parametre, valeur)?.let { icone ->
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
            carte.findViewById<TextView>(R.id.carte_titre).text = libelleChoix(parametre, valeur)
            carte.findViewById<TextView>(R.id.carte_explication).text = explicationChoix(parametre, valeur)
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
        val libelles = parametre.choices.map { libelleChoix(parametre, it) }
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

    /**
     * Carte-interrupteur pour les booléens **groupés** (ADR 0155 §5 —
     * « cartes de bibliothèques » de la maquette) : libellé, aide décrite
     * (effet dans le projet généré), interrupteur en bout de carte.
     */
    private fun construireCarteInterrupteur(parametre: TemplateParameterEvaluation): View {
        val racine = gonfler(R.layout.vue_carte_interruption)
        val interrupteur = racine.findViewById<MaterialSwitch>(R.id.interrupteur_carte)
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
                override fun onTextChanged(texte: CharSequence?, debut: Int, avant: Int, nombre: Int) {
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
    private fun gonfler(layoutRes: Int, parent: ViewGroup = conteneur): View =
        LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)

    // ---------------------------------------------------- registres

    /** Composant d'un paramètre : déclaration du manifeste, puis registre nommé, puis repli par traits.
     *
     * Un booléen **groupé** devient une carte (maquette « Bibliothèques ») ;
     * un booléen hors groupe reste un interrupteur. Tuiles segmentées :
     * deux valeurs côte à côte — au-delà, les cartes radio empilées
     * prennent le relais : chaque valeur garde son explication.
     */
    private fun composantPour(parametre: TemplateParameterEvaluation): Composant {
        val parTraits =
            when (parametre.type) {
                TemplateParameterType.BOOLEAN -> Composant.INTERRUPTEUR
                TemplateParameterType.CHOICE -> Composant.LISTE_DEROULANTE
                TemplateParameterType.TEXT -> Composant.CHAMP_TEXTE
            }
        val nomme = COMPOSANTS_NOMMES[parametre.parameterId]
        return when {
            parametre.rendu != null -> declaree(parametre.rendu!!) ?: parTraits

            parametre.type == TemplateParameterType.BOOLEAN && parametre.groupe != null ->
                Composant.CARTE_INTERRUPTUEUR

            nomme == Composant.TUILES_SEGMENTEES && parametre.choices.size > NB_TUILES ->
                Composant.CARTES_RADIO

            else -> nomme ?: parTraits
        }
    }

    /** Traduction de l'indice déclaré (ADR 0154 §2) en composant du wizard. */
    private fun declaree(rendu: RenduParametre): Composant? = when (rendu) {
        RenduParametre.TUILES_SEGMENTEES -> Composant.TUILES_SEGMENTEES
        RenduParametre.CARTES_RADIO -> Composant.CARTES_RADIO
        RenduParametre.LISTE_DEROULANTE -> Composant.LISTE_DEROULANTE
        RenduParametre.INTERRUPTEUR -> Composant.INTERRUPTEUR
        RenduParametre.CHAMP_TEXTE -> Composant.CHAMP_TEXTE
    }

    /**
     * Libellé affichable d'une valeur de choix : dictionnaire du modèle
     * quand le manifeste déclare la clé, puis registre du wizard, puis
     * valeur brute.
     */
    private fun libelleChoix(parametre: TemplateParameterEvaluation, valeur: String): String {
        parametre.choix.firstOrNull { it.valeur == valeur }?.let { choix ->
            if (choix.libelle != valeur) return choix.libelle
        }
        return registreChoix(valeur)
    }

    /** Sous-titre d'une tuile : déclaré, sinon registre (spec 12.3). */
    private fun sousTitreChoix(parametre: TemplateParameterEvaluation, valeur: String): String =
        parametre.choix.firstOrNull { it.valeur == valeur }?.sousTitre
            ?: registreSousTitre(parametre.parameterId, valeur)

    /** Monogramme d'icône maison d'une tuile (aucun logo officiel). */
    private fun iconeChoix(parametre: TemplateParameterEvaluation, valeur: String): String? =
        parametre.choix.firstOrNull { it.valeur == valeur }?.icone?.takeIf { it.isNotBlank() }
            ?: registreIcone(parametre.parameterId, valeur)

    /** Explication d'une carte radio : déclarée, sinon registre. */
    private fun explicationChoix(parametre: TemplateParameterEvaluation, valeur: String): String =
        parametre.choix.firstOrNull { it.valeur == valeur }?.description?.takeIf { it.isNotBlank() }
            ?: registreExplication(parametre.parameterId, valeur)

    /** Registre de présentation : libellés des valeurs connues du wizard. */
    @Suppress("CyclomaticComplexMethod") // Un branch par valeur connue du wizard.
    private fun registreChoix(valeur: String): String =
        when (valeur) {
            "application" -> conteneur.context.getString(R.string.wizard_choix_application)
            "library" -> conteneur.context.getString(R.string.wizard_choix_bibliotheque)
            "gradle-kts" -> conteneur.context.getString(R.string.wizard_choix_gradle)
            "maven" -> conteneur.context.getString(R.string.wizard_choix_maven)
            "none" -> conteneur.context.getString(R.string.wizard_choix_sources)
            "17" -> conteneur.context.getString(R.string.wizard_choix_jdk17)
            "21" -> conteneur.context.getString(R.string.wizard_choix_jdk21)
            "empty-activity" -> conteneur.context.getString(R.string.wizard_choix_activite_vide)
            "no-activity" -> conteneur.context.getString(R.string.wizard_choix_sans_activite)
            "basic-activity" -> conteneur.context.getString(R.string.wizard_choix_activite_tiroir)
            "kotlin" -> conteneur.context.getString(R.string.wizard_choix_kotlin)
            "java" -> conteneur.context.getString(R.string.wizard_choix_java)
            else -> valeur
        }

    /** Registre : sous-titres de tuiles (icône et sous-titre, spec 12.3). */
    private fun registreSousTitre(parametreId: String, valeur: String): String = when (parametreId to valeur) {
        "projectType" to "application" -> conteneur.context.getString(R.string.wizard_sous_titre_application)
        "projectType" to "library" -> conteneur.context.getString(R.string.wizard_sous_titre_bibliotheque)
        "language" to "kotlin" -> conteneur.context.getString(R.string.wizard_sous_titre_kotlin)
        "language" to "java" -> conteneur.context.getString(R.string.wizard_sous_titre_java)
        else -> ""
    }

    /** Registre : monogrammes des tuiles. */
    private fun registreIcone(parametreId: String, valeur: String): String? = when (parametreId to valeur) {
        "projectType" to "application" -> conteneur.context.getString(R.string.wizard_icone_application)
        "projectType" to "library" -> conteneur.context.getString(R.string.wizard_icone_bibliotheque)
        "language" to "kotlin" -> conteneur.context.getString(R.string.wizard_icone_kotlin)
        "language" to "java" -> conteneur.context.getString(R.string.wizard_icone_java)
        else -> null
    }

    /** Registre : explications des cartes radio. */
    private fun registreExplication(parametreId: String, valeur: String): String = when (parametreId to valeur) {
        "buildSystem" to "gradle-kts" -> conteneur.context.getString(R.string.wizard_explication_gradle)

        "buildSystem" to "maven" -> conteneur.context.getString(R.string.wizard_explication_maven)

        "buildSystem" to "none" -> conteneur.context.getString(R.string.wizard_explication_sources)

        "projectType" to "empty-activity" -> conteneur.context.getString(R.string.wizard_explication_activite_vide)

        "projectType" to "no-activity" -> conteneur.context.getString(R.string.wizard_explication_sans_activite)

        "projectType" to "basic-activity" -> {
            conteneur.context.getString(R.string.wizard_explication_activite_tiroir)
        }

        else -> {
            ""
        }
    }

    /** Ligne d'aide dynamique sous la sélection (spec 12.3). */
    private fun aideSousSelection(parametre: TemplateParameterEvaluation, contexte: Context): String =
        if (parametre.parameterId == "buildSystem" && parametre.effectiveValue == "none") {
            contexte.getString(R.string.wizard_aide_sans_build)
        } else {
            ""
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

        /** Opacité d'un champ désactivé (grisé, lisible — ADR 0153 §2). */
        const val ALPHA_DESACTIVE = 0.5f

        /** Opacité d'un champ actif. */
        const val ALPHA_ACTIVE = 1f

        /** Rotation du chevron « Avancé » ouvert (fermé = 0). */
        const val ROTATION_OUVERT = 180f

        /** Rotation du chevron « Avancé » fermé. */
        const val ROTATION_FERME = 0f

        /** Motif de validation de l'artifact ID (modèles de l'étape 9). */
        const val MOTIF_ARTIFACT = "^[a-z0-9][a-z0-9._-]*$"

        /** Étiquette de la zone « Avancé » dans le conteneur. */
        const val ETIQUETTE_AVANCEE = "rendu.zone_avancee"

        /** Étiquette d'un titre de groupe dans le conteneur. */
        const val ETIQUETTE_TITRE_GROUPE = "rendu.titre_groupe"

        /** Composants explicitement nommés par la spécification (section 12.3). */
        val COMPOSANTS_NOMMES: Map<String, Composant> =
            mapOf(
                "projectType" to Composant.TUILES_SEGMENTEES,
                "buildSystem" to Composant.CARTES_RADIO,
                "language" to Composant.TUILES_SEGMENTEES,
            )
    }
}
