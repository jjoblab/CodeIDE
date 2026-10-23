package jo.codeide.feature.settings

import jo.codeide.core.domain.CapaciteLsp
import jo.codeide.core.domain.EtatMoteurLsp
import jo.codeide.core.domain.LangageLsp
import jo.codeide.core.domain.MoteursLsp
import jo.codeide.core.domain.PhaseMoteurLsp
import jo.codeide.core.model.LspSettings

/**
 * Texte localisable produit par [PresentationLsp] (patron
 * [PresentationIndexation] de :feature:editor — ADR 0117 §4.4) : la
 * résolution a lieu au DERNIER moment par l'UI ; le présentateur ne
 * touche ni `Context` ni vue.
 */
sealed interface TexteLsp {
    /** Libellé d'une ressource de chaîne (identifiant + arguments). */
    data class Ressource(val id: Int, val args: List<String> = emptyList()) : TexteLsp

    /** Libellé d'un PLURIEL (identifiant + quantité qui choisit la forme). */
    data class Pluriel(val id: Int, val nombre: Int) : TexteLsp

    /** Libellé brut (message technique déjà formulé, ré-expurgé par l'UI). */
    data class Brut(val texte: String) : TexteLsp
}

/**
 * État d'affichage d'un moteur (L1 rangées, L2 état, L5 résumé) — la
 * FORME de la pastille (IndicateurEtat, core:ui) se déduit SANS la
 * couleur : PRET → ok, EN_COURS/PARTIEL → avertissement, ERREUR →
 * erreur, DEMARRAGE/INACTIF → neutre.
 */
enum class EtatMoteurAffiche {
    INACTIF,
    DEMARRAGE,
    EN_COURS,
    PRET,
    PARTIEL,
    ERREUR,
}

/**
 * Rangée d'un moteur (L1) : état + sous-titre composé (morceaux joints
 * par « · » par l'UI) + horodatage du dernier passage (l'UI formate
 * l'heure — jamais un temps relatif approximatif).
 */
data class LigneMoteur(val etat: EtatMoteurAffiche, val sousTitres: List<TexteLsp>, val dernierPassageMs: Long? = null)

/** Vue d'ensemble (L1) : une ligne par moteur + les sous-titres de l'analyse. */
data class ModeleApercu(val java: LigneMoteur, val kotlin: LigneMoteur, val xml: LigneMoteur)

/** Fonction annoncée (L2) : libellé + effet en une phrase. */
data class FonctionAffichee(val titre: Int, val description: Int)

/** Écran L2 : moteur (nom, description, pack langue), état, fonctions. */
data class ModeleLangage(
    val titre: Int,
    val iconeMoteurRes: Int,
    val nomMoteur: Int,
    val descriptionMoteur: Int,
    val packLangueBientot: Boolean,
    val etat: LigneMoteur,
    val fonctions: List<FonctionAffichee>,
)

/** Écran L3 : sous-titre du cache, valeur du budget, surveillance. */
data class ModeleIndex(
    val sousTitreCache: List<TexteLsp>,
    val valeurBudget: TexteLsp,
    val surveillanceActive: Boolean,
    val valeurFrequence: TexteLsp?,
    /** Indexation en cours : « Vider le cache » indisponible (explication). */
    val vidageIndisponible: Boolean,
    /** Échecs à réessayer : la rangée d'action ne vivrait pas sans eux. */
    val echecsPresents: Boolean,
    /** Aucun moteur : les actions sont posées mais désactivées. */
    val moteurAbsent: Boolean,
)

/** Écran L4 : valeurs lues + échec JDK (action unique « Réparer »). */
data class ModeleEnvironnement(
    val jdkAbsent: Boolean,
    val versionJdk: String?,
    val plateforme: TexteLsp?,
    val modeInstantane: Boolean,
)

/** Erreur récente (L5) : horodatage ABSOLU, message ré-expurgé par l'UI. */
data class ErreurRecente(val horodatageMs: Long?, val message: String)

/** Écran L5 : résumé par moteur + erreurs récentes (≤ 20). */
data class ModeleEtat(
    val java: LigneMoteur,
    val kotlin: LigneMoteur,
    val xml: LigneMoteur,
    val erreursRecentes: List<ErreurRecente>,
)

/**
 * Présentateur PUR des écrans « Intelligence du code » (lot LSP-3, spec
 * `LSP_SPEC_ECRANS.md` § 4) : transforme l'état des moteurs (port
 * `:core:domain`) et les réglages (`LspSettings`, :core:model) en
 * modèles d'écran — testé en JVM SANS Android ; jamais de chaîne
 * comparable (les langages/capacités/phases restent des ÉNUMÉRATIONS).
 *
 * Honnêteté par construction : Kotlin/XML n'ont PAS de surface d'état
 * côté frère — leur ligne dit « Prêt » sur présence de session et
 * « Inactif » sinon ; Java seul porte avancement, comptes et messages.
 */
internal object PresentationLsp {
    /** Erreurs transportées vers les écrans (spec § 3 L5 : « ≤ 20 »). */
    internal const val ERREURS_LSP_AFFICHEES: Int = 20

    /** Ordre FIXE des fonctions (spec § 3 L2) — jamais l'ordre de la table. */
    private val ORDRE_FONCTIONS: List<CapaciteLsp> =
        listOf(
            CapaciteLsp.COMPLETION,
            CapaciteLsp.SURVOL,
            CapaciteLsp.DIAGNOSTICS,
            CapaciteLsp.SIGNATURES,
            CapaciteLsp.DEFINITION,
            CapaciteLsp.REFERENCES,
            CapaciteLsp.RENOMMAGE,
            CapaciteLsp.ACTIONS_RAPIDES,
            CapaciteLsp.FORMATAGE,
            CapaciteLsp.INDICES_EN_LIGNE,
        )

    // ------------------------------------------------------------------
    // L1 — Vue d'ensemble
    // ------------------------------------------------------------------

    /** Trois lignes moteurs : Java détaillé, Kotlin/XML par présence. */
    fun apercu(moteurs: MoteursLsp): ModeleApercu = ModeleApercu(
        java = ligneJava(moteurs.java),
        kotlin = lignePresence(moteurs.kotlin),
        xml = lignePresence(moteurs.xml),
    )

    // ------------------------------------------------------------------
    // L2 — Langage
    // ------------------------------------------------------------------

    /** Modèle du langage : moteur, état, fonctions EN ORDRE FIXE (annoncées ∩ ordre). */
    fun langage(langage: LangageLsp, moteurs: MoteursLsp, capacitesAnnoncees: Set<CapaciteLsp>): ModeleLangage =
        ModeleLangage(
            titre = titreLangage(langage),
            iconeMoteurRes = iconeLangage(langage),
            nomMoteur = nomMoteur(langage),
            descriptionMoteur = descriptionMoteur(langage),
            packLangueBientot = langage != LangageLsp.JAVA,
            etat = etatLangage(langage, moteurs),
            fonctions =
            ORDRE_FONCTIONS
                .filter { it in capacitesAnnoncees }
                .map(::fonctionAffichee),
        )

    // ------------------------------------------------------------------
    // L3 — Index et cache
    // ------------------------------------------------------------------

    /**
     * Modèle de l'index : sous-titre du cache (taille + dernier passage
     * + avertissements), valeur du budget, surveillance, actions.
     */
    fun index(moteurs: MoteursLsp, tailleCacheOctets: Long?, reglages: LspSettings): ModeleIndex {
        val java = moteurs.java
        val sousTitre =
            when {
                java == null -> {
                    listOf(TexteLsp.Ressource(R.string.lsp_etat_inactif))
                }

                else -> {
                    buildList<TexteLsp> {
                        tailleCacheOctets?.let { add(tailleFormatee(it)) }
                        if (java.avertissements > 0) {
                            add(TexteLsp.Pluriel(R.plurals.lsp_avertissements, java.avertissements))
                        }
                        java.dureeDernierPassageMs?.let { add(dureeFormatee(it)) }
                        if (isEmpty()) add(TexteLsp.Ressource(R.string.lsp_etat_pret))
                    }
                }
            }
        return ModeleIndex(
            sousTitreCache = sousTitre,
            valeurBudget = valeurBudget(reglages.budgetCacheOctets),
            surveillanceActive = reglages.surveillanceFichiersMs > 0,
            valeurFrequence =
            reglages.surveillanceFichiersMs
                .takeIf { it > 0 }
                ?.let {
                    TexteLsp.Ressource(
                        R.string.lsp_frequence_secondes,
                        listOf((it / SECONDES_PAR_MS).toString()),
                    )
                },
            vidageIndisponible = java?.constructionActive == true,
            echecsPresents = (java?.avertissements ?: 0) > 0 || (java?.erreurs ?: 0) > 0,
            moteurAbsent = java == null,
        )
    }

    // ------------------------------------------------------------------
    // L4 — Environnement d'analyse (lecture seule)
    // ------------------------------------------------------------------

    /** Modèle de l'environnement : valeurs lues, échec JDK porté en DONNÉE. */
    fun environnement(
        versionJdk: String?,
        niveauApiPlateforme: Int?,
        androidJarPresent: Boolean,
    ): ModeleEnvironnement = ModeleEnvironnement(
        jdkAbsent = versionJdk == null,
        versionJdk = versionJdk,
        plateforme =
        niveauApiPlateforme?.let {
            TexteLsp.Ressource(R.string.lsp_api_niveau, listOf(it.toString()))
        },
        modeInstantane = !androidJarPresent,
    )

    // ------------------------------------------------------------------
    // L5 — État et diagnostic
    // ------------------------------------------------------------------

    /** Résumé par moteur + erreurs récentes du Java (≤ 20, ordre d'arrivée). */
    fun etat(moteurs: MoteursLsp): ModeleEtat = ModeleEtat(
        java = ligneJava(moteurs.java),
        kotlin = lignePresence(moteurs.kotlin),
        xml = lignePresence(moteurs.xml),
        erreursRecentes =
        moteurs.java
            ?.messagesErreur
            .orEmpty()
            .take(ERREURS_LSP_AFFICHEES)
            .map { ErreurRecente(horodatageMs = moteurs.java?.horodatageDernierPassageMs, message = it) },
    )

    // ------------------------------------------------------------------
    // Projections internes
    // ------------------------------------------------------------------

    /** Ligne du moteur JAVA : phase, avancement, fichiers, comptes. */
    private fun ligneJava(etat: EtatMoteurLsp?): LigneMoteur = if (etat == null) {
        LigneMoteur(EtatMoteurAffiche.INACTIF, listOf(TexteLsp.Ressource(R.string.lsp_etat_inactif)))
    } else {
        when (etat.phase) {
            PhaseMoteurLsp.INITIAL -> {
                LigneMoteur(EtatMoteurAffiche.DEMARRAGE, listOf(TexteLsp.Ressource(R.string.lsp_etat_demarrage)))
            }

            PhaseMoteurLsp.CONSTRUCTION -> {
                LigneMoteur(
                    EtatMoteurAffiche.EN_COURS,
                    listOf(
                        TexteLsp.Ressource(
                            R.string.lsp_etat_indexation,
                            listOf((etat.avancementPourCent ?: 0).toString()),
                        ),
                    ),
                )
            }

            PhaseMoteurLsp.PRET -> {
                LigneMoteur(
                    EtatMoteurAffiche.PRET,
                    sousTitresComptes(
                        etat,
                        R.string.lsp_etat_pret,
                        R.plurals.lsp_fichiers_indexes,
                        etat.fichiersIndexes,
                    ),
                    etat.horodatageDernierPassageMs,
                )
            }

            PhaseMoteurLsp.PARTIEL -> {
                LigneMoteur(
                    EtatMoteurAffiche.PARTIEL,
                    sousTitresComptes(
                        etat,
                        R.string.lsp_etat_partiel,
                        R.plurals.lsp_fichiers_indexes,
                        etat.fichiersIndexes,
                    ),
                    etat.horodatageDernierPassageMs,
                )
            }

            PhaseMoteurLsp.ERREUR -> {
                LigneMoteur(
                    EtatMoteurAffiche.ERREUR,
                    sousTitresComptes(
                        etat,
                        R.string.lsp_etat_erreur,
                        R.plurals.lsp_erreurs,
                        etat.erreurs,
                    ),
                    etat.horodatageDernierPassageMs,
                )
            }
        }
    }

    /** Ligne Kotlin/XML : la PRÉSENCE de session est le seul état honnête. */
    private fun lignePresence(active: Boolean): LigneMoteur = if (active) {
        LigneMoteur(EtatMoteurAffiche.PRET, listOf(TexteLsp.Ressource(R.string.lsp_etat_pret)))
    } else {
        LigneMoteur(EtatMoteurAffiche.INACTIF, listOf(TexteLsp.Ressource(R.string.lsp_etat_inactif)))
    }

    /** Sous-titre d'état + pluriel (fichiers ou erreurs) + avertissements. */
    private fun sousTitresComptes(
        etat: EtatMoteurLsp,
        resEtat: Int,
        resPluriel: Int,
        nombrePluriel: Int,
    ): List<TexteLsp> = buildList<TexteLsp> {
        add(TexteLsp.Ressource(resEtat))
        if (nombrePluriel > 0) add(TexteLsp.Pluriel(resPluriel, nombrePluriel))
        if (etat.avertissements > 0) {
            add(TexteLsp.Pluriel(R.plurals.lsp_avertissements, etat.avertissements))
        }
    }

    /** État du langage demandé (L2) : Java complet, Kotlin/XML présence. */
    private fun etatLangage(langage: LangageLsp, moteurs: MoteursLsp): LigneMoteur = when (langage) {
        LangageLsp.JAVA -> ligneJava(moteurs.java)
        LangageLsp.KOTLIN -> lignePresence(moteurs.kotlin)
        LangageLsp.XML -> lignePresence(moteurs.xml)
    }
}

// ------------------------------------------------------------------
// Mappers statiques — fonctions de FICHIER (l'objet ne garde que les
// cinq projections publiques et leurs internautes de projection : le
// seuil detekt TooManyFunctions des objets n'a pas à être levé).
// ------------------------------------------------------------------

private fun titreLangage(langage: LangageLsp): Int = when (langage) {
    LangageLsp.JAVA -> R.string.lsp_langage_java
    LangageLsp.KOTLIN -> R.string.lsp_langage_kotlin
    LangageLsp.XML -> R.string.lsp_langage_xml
}

private fun iconeLangage(langage: LangageLsp): Int = when (langage) {
    LangageLsp.JAVA -> jo.codeide.core.ui.R.drawable.ic_fichier_java
    LangageLsp.KOTLIN -> jo.codeide.core.ui.R.drawable.ic_fichier_kotlin
    LangageLsp.XML -> jo.codeide.core.ui.R.drawable.ic_fichier_xml
}

private fun nomMoteur(langage: LangageLsp): Int = when (langage) {
    LangageLsp.JAVA -> R.string.lsp_moteur_nom_java
    LangageLsp.KOTLIN -> R.string.lsp_moteur_nom_kotlin
    LangageLsp.XML -> R.string.lsp_moteur_nom_xml
}

private fun descriptionMoteur(langage: LangageLsp): Int = when (langage) {
    LangageLsp.JAVA -> R.string.lsp_moteur_desc_java
    LangageLsp.KOTLIN -> R.string.lsp_moteur_desc_kotlin
    LangageLsp.XML -> R.string.lsp_moteur_desc_xml
}

/** Libellé + effet d'une capacité (une ressource par énumération, ADR 0117). */
private fun fonctionAffichee(capacite: CapaciteLsp): FonctionAffichee = when (capacite) {
    CapaciteLsp.COMPLETION -> FonctionAffichee(R.string.lsp_cap_completion, R.string.lsp_cap_completion_desc)
    CapaciteLsp.SURVOL -> FonctionAffichee(R.string.lsp_cap_survol, R.string.lsp_cap_survol_desc)
    CapaciteLsp.DIAGNOSTICS -> FonctionAffichee(R.string.lsp_cap_diagnostics, R.string.lsp_cap_diagnostics_desc)
    CapaciteLsp.SIGNATURES -> FonctionAffichee(R.string.lsp_cap_signatures, R.string.lsp_cap_signatures_desc)
    CapaciteLsp.DEFINITION -> FonctionAffichee(R.string.lsp_cap_definition, R.string.lsp_cap_definition_desc)
    CapaciteLsp.REFERENCES -> FonctionAffichee(R.string.lsp_cap_references, R.string.lsp_cap_references_desc)
    CapaciteLsp.RENOMMAGE -> FonctionAffichee(R.string.lsp_cap_renommage, R.string.lsp_cap_renommage_desc)
    CapaciteLsp.ACTIONS_RAPIDES -> FonctionAffichee(R.string.lsp_cap_actions, R.string.lsp_cap_actions_desc)
    CapaciteLsp.FORMATAGE -> FonctionAffichee(R.string.lsp_cap_formatage, R.string.lsp_cap_formatage_desc)
    CapaciteLsp.INDICES_EN_LIGNE -> FonctionAffichee(R.string.lsp_cap_indices, R.string.lsp_cap_indices_desc)
}

/** Valeur du budget : Auto ou Mo — paliers du modèle, jamais recopiés. */
private fun valeurBudget(budgetOctets: Long?): TexteLsp = budgetOctets?.let {
    TexteLsp.Ressource(R.string.lsp_budget_mio, listOf((it / OCTETS_PAR_MIO).toString()))
} ?: TexteLsp.Ressource(R.string.lsp_budget_auto)

/** Taille sur disque : mêmes formes que l'empreinte de l'historique. */
private fun tailleFormatee(octets: Long): TexteLsp = when {
    octets < OCTETS_PAR_KO -> {
        TexteLsp.Ressource(R.string.settings_historique_octets, listOf(octets.toString()))
    }

    octets < OCTETS_PAR_MIO -> {
        TexteLsp.Ressource(
            R.string.settings_historique_ko,
            listOf((octets / OCTETS_PAR_KO).toString()),
        )
    }

    else -> {
        TexteLsp.Ressource(
            R.string.settings_historique_mo,
            listOf(String.format(java.util.Locale.US, "%.1f", octets / OCTETS_PAR_MIO.toDouble())),
        )
    }
}

/** Durée du dernier passage : « 1 min 12 s » — bornée, lisible. */
private fun dureeFormatee(dureeMs: Long): TexteLsp {
    val secondesTotales = dureeMs / SECONDES_PAR_MS
    val minutes = secondesTotales / SECONDES_PAR_MINUTE
    val secondes = secondesTotales % SECONDES_PAR_MINUTE
    return TexteLsp.Brut(
        if (minutes > 0) {
            "$minutes min $secondes s"
        } else {
            "$secondes s"
        },
    )
}

private const val OCTETS_PAR_KO = 1024L
private const val OCTETS_PAR_MIO = 1024L * 1024L
private const val SECONDES_PAR_MS = 1000L
private const val SECONDES_PAR_MINUTE = 60L
