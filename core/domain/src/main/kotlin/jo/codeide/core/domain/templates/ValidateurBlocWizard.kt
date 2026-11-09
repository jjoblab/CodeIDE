package jo.codeide.core.domain.templates

/**
 * Validateur du bloc `wizard` du manifeste (ADR 0155 §1 — schéma lu dès le
 * lot L3) : au plus 12 étapes de modèle et 12 préréglages, identifiants
 * uniques hors socle, expressions `when` analysables, chaque paramètre cité
 * est un `CONFIGURATION` existant cité au plus une fois (un paramètre non
 * rangé rejoindra l'étape par défaut « Configuration » au calcul du
 * parcours, L5a) ; préréglages à valeurs valides pour leur paramètre cible.
 *
 * Extrait de [TemplateManifestParser] pour rester sous les bornes detekt
 * (TooManyFunctions / CyclomaticComplexMethod) — le parseur délègue.
 */
internal object ValidateurBlocWizard {
    /** Analyse et valide le bloc `wizard` ; `null` si le manifeste est sain. */
    @Suppress("ReturnCount") // Clauses de garde de validation (règle 16).
    fun valide(bloc: BlocWizardJson, parametres: List<ParametreTemplateJson>): String? {
        if (bloc.etapes.size > ETAPES_MAX) {
            return "plus de $ETAPES_MAX étapes de modèle"
        }
        if (bloc.presets.size > PRESETS_MAX) return "plus de $PRESETS_MAX préréglages"
        val idsEtapes = mutableSetOf<String>()
        val parametresRanges = mutableSetOf<String>()
        for (etape in bloc.etapes) {
            if (!idsEtapes.add(etape.id)) return "étape wizard en double : « ${etape.id} »"
            val probleme = validerEtape(etape, parametres, parametresRanges)
            if (probleme != null) return probleme
        }
        val idsPresets = mutableSetOf<String>()
        for (preset in bloc.presets) {
            val probleme = validerPreset(preset, parametres, idsPresets)
            if (probleme != null) return probleme
        }
        return null
    }

    /** Règles d'une étape de modèle (ADR 0155 §1) : identité, i18n, `when`, paramètres. */
    @Suppress("ReturnCount") // Clauses de garde de validation (règle 16).
    private fun validerEtape(
        etape: EtapeWizardJson,
        parametres: List<ParametreTemplateJson>,
        parametresRanges: MutableSet<String>,
    ): String? {
        if (!MOTIF_ID_ETAPE.matches(etape.id)) {
            return "identifiant d'étape wizard invalide : « ${etape.id} »"
        }
        if (etape.id in IDS_SOCLE_RESERVES) {
            return "identifiant d'étape wizard « ${etape.id} » est réservé au socle de parcours"
        }
        if (!MOTIF_CLE_I18N.matches(etape.titreKey)) {
            return "titreKey invalide pour l'étape « ${etape.id} » : « ${etape.titreKey} »"
        }
        if (etape.iconeKey != null && !MOTIF_CLE_I18N.matches(etape.iconeKey)) {
            return "iconeKey invalide pour l'étape « ${etape.id} » : « ${etape.iconeKey} »"
        }
        etape.quand?.let { quand ->
            try {
                ExpressionParser.analyser(quand)
            } catch (erreur: ExpressionException) {
                return "when de l'étape « ${etape.id} » invalide : ${erreur.message}"
            }
        }
        if (etape.parametres.isEmpty()) {
            return "étape « ${etape.id} » sans paramètre (jamais d'écran vide)"
        }
        for (id in etape.parametres) {
            val parametre = parametres.firstOrNull { it.id == id }
                ?: return "étape « ${etape.id} » cite « $id » : paramètre inexistant"
            if (parametre.section != "CONFIGURATION") {
                return "étape « ${etape.id} » cite « $id » : " +
                    "seul un paramètre CONFIGURATION se range dans une étape"
            }
            if (!parametresRanges.add(id)) {
                return "paramètre « $id » cité dans plusieurs étapes wizard"
            }
        }
        return null
    }

    /** Règles d'un préréglage (ADR 0155 §1, B-8) : identité, i18n, valeurs valides. */
    @Suppress("ReturnCount") // Clauses de garde de validation (règle 16).
    private fun validerPreset(
        preset: PresetWizardJson,
        parametres: List<ParametreTemplateJson>,
        idsPresets: MutableSet<String>,
    ): String? {
        if (!MOTIF_ID_ETAPE.matches(preset.id)) {
            return "identifiant de préréglage invalide : « ${preset.id} »"
        }
        if (!idsPresets.add(preset.id)) return "préréglage en double : « ${preset.id} »"
        if (!MOTIF_CLE_I18N.matches(preset.nomKey)) {
            return "nomKey invalide pour le préréglage « ${preset.id} » : « ${preset.nomKey} »"
        }
        if (preset.descriptionKey != null && !MOTIF_CLE_I18N.matches(preset.descriptionKey)) {
            return "descriptionKey invalide pour le préréglage « ${preset.id} » : « ${preset.descriptionKey} »"
        }
        if (preset.valeurs.isEmpty()) {
            return "préréglage « ${preset.id} » sans valeurs"
        }
        for ((id, valeur) in preset.valeurs) {
            val parametre = parametres.firstOrNull { it.id == id }
                ?: return "préréglage « ${preset.id} » vise « $id » : paramètre inexistant"
            val texte = valeur.content
            when (parametre.type) {
                "BOOLEAN" ->
                    if (texte.lowercase() !in VALEURS_BOOLEENNES) {
                        return "préréglage « ${preset.id} » : « $texte » n'est pas booléen pour « $id »"
                    }

                "CHOICE" ->
                    if (texte !in parametre.choices) {
                        return "préréglage « ${preset.id} » : « $texte » hors des choix de « $id »"
                    }
            }
        }
        return null
    }

    private const val ETAPES_MAX = 12
    private const val PRESETS_MAX = 12
    private val VALEURS_BOOLEENNES = setOf("true", "false")

    /** Identifiants du socle de parcours réservés par l'application (ADR 0155 §2). */
    private val IDS_SOCLE_RESERVES = setOf("modele", "identite", "fichiers", "recapitulatif")

    /** Motif d'un identifiant d'étape ou de préréglage wizard (ADR 0155 §1). */
    private val MOTIF_ID_ETAPE = Regex("^[a-z][a-z0-9-]*$")

    /** Motif d'une clé i18n (partagé avec le parseur). */
    private val MOTIF_CLE_I18N = Regex("^[a-zA-Z0-9_.-]+$")
}
