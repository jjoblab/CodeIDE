package jo.codeide.core.domain

import jo.codeide.core.model.FlattenedException
import jo.codeide.core.model.LogEntry
import jo.codeide.core.model.LogLevel

/*
 * Les adaptateurs purs vers [LigneSortie] (ADR 0160 § Décision 2, lot
 * L1) — UNE fonction par source existante, aucune logique : projeter le
 * modèle d'origine sur l'échelle unique du Centre de sorties, sans
 * perdre ni inventer une information. Tous testés JVM, aucun Android.
 *
 * Les événements de la console actuelle (`EvenementConsoleTexte`,
 * internal à `feature:editor`) possèdent leur propre adaptateur dans CE
 * module-là — c'est le seul qui ne peut pas vivre ici : la direction des
 * dépendances interdit à `core:domain` de connaître `feature:editor`.
 */

/**
 * Projette le niveau logcat ([NiveauJournal], pont sans adb — ADR 0103)
 * sur l'échelle unique : miroir un à un (V/D/I/W/E/F).
 */
public fun NiveauJournal.versNiveauSortie(): NiveauSortie = when (this) {
    NiveauJournal.VERBEUX -> NiveauSortie.VERBEUX
    NiveauJournal.DEBOGAGE -> NiveauSortie.DEBOGAGE
    NiveauJournal.INFO -> NiveauSortie.INFO
    NiveauJournal.AVERTISSEMENT -> NiveauSortie.AVERTISSEMENT
    NiveauJournal.ERREUR -> NiveauSortie.ERREUR
    NiveauJournal.ASSERT -> NiveauSortie.ASSERT
}

/**
 * Projette le niveau du journal applicatif ([LogLevel]) sur l'échelle
 * unique : DEBUG→D, INFO→I, WARN→W, ERROR→E (ADR 0160 — la projection
 * implicite de l'échelle à quatre valeurs).
 */
public fun LogLevel.versNiveauSortie(): NiveauSortie = when (this) {
    LogLevel.DEBUG -> NiveauSortie.DEBOGAGE
    LogLevel.INFO -> NiveauSortie.INFO
    LogLevel.WARN -> NiveauSortie.AVERTISSEMENT
    LogLevel.ERROR -> NiveauSortie.ERREUR
}

/**
 * Projette le flux d'origine d'une sortie Gradle sur le niveau : le
 * stdout EST la sortie de l'outil (non graduable — INDETERMINE), le
 * stderr porte les diagnostics (ERREUR) — même projection que le style
 * de la console actuelle (`GradleService`, ADR 0078).
 */
public fun FluxSortieBuild.versNiveauSortie(): NiveauSortie = when (this) {
    FluxSortieBuild.STDOUT -> NiveauSortie.INDETERMINE
    FluxSortieBuild.STDERR -> NiveauSortie.ERREUR
}

/**
 * Clé de session stable d'une identité de session de journaux (ADR 0103)
 * — la chaîne que [LigneSortie.identiteSession] porte et que
 * [FiltreSorties.sessions] compare : `paquet/pid/processus`. Pure et
 * canonique (même identité → même clé), sans donnée de plus que
 * l'identité elle-même.
 */
public fun IdentiteSessionJournal.cleSession(): String = "$paquet/$pid/$nomProcessus"

/**
 * Projette UNE ligne de sortie de build (port tooling, canal Build)
 * vers le modèle unique.
 *
 * @param idExecution identifiant de l'exécution Gradle émettrice (le
 *        `buildId` de la session, ADR 0161) — obligatoire : une ligne
 *        de build appartient toujours à une exécution.
 */
public fun LigneSortieBuild.versLigneSortie(idExecution: String): LigneSortie = LigneSortie(
    source = SourceSortie.BUILD,
    message = ligne,
    horodatageMs = horodatageMs,
    idExecution = idExecution,
    niveau = flux.versNiveauSortie(),
)

/**
 * Projette UNE ligne de sortie de synchronisation (port tooling, canal
 * Sync — ADR 0079) vers le modèle unique.
 *
 * @param idExecution identifiant de la synchronisation si une exécution
 *        la porte (les sync survivantes à l'écran n'en ont pas — `null`
 *        accepté, l'identité reste honnête).
 */
public fun LigneSortieSync.versLigneSortie(idExecution: String? = null): LigneSortie = LigneSortie(
    source = SourceSortie.SYNC,
    message = ligne,
    horodatageMs = horodatageMs,
    idExecution = idExecution,
    niveau = flux.versNiveauSortie(),
)

/**
 * Projette UNE ligne du Logcat (pont `AnalyseurTramesJournal`, ADR 0103)
 * vers le modèle unique — pid/tid ne montent PAS : ils restent sur le
 * modèle d'origine pour les regroupements « ×N » (§ 5.4), le Centre de
 * sorties les affiche via l'étiquette et le filtre de session.
 *
 * @param identiteSession la clé de session produite par
 *        [IdentiteSessionJournal.cleSession] — obligatoire : une ligne
 *        logcat appartient toujours à une session du pont.
 */
public fun LigneJournal.versLigneSortie(identiteSession: String): LigneSortie = LigneSortie(
    source = SourceSortie.LOGCAT,
    message = message,
    horodatageMs = horodatageMs,
    identiteSession = identiteSession,
    niveau = niveau.versNiveauSortie(),
    etiquette = etiquette,
)

/**
 * Projette UNE entrée du journal applicatif (`LogEntry`, core:model)
 * vers le modèle unique. Le nom du fil émetteur se perd sciemment
 * (consultable sur l'entrée d'origine exportée) ; l'EXCEPTION aplatie,
 * elle, PART DANS LE MESSAGE (lot L4) : l'écran Diagnostics consomme le
 * lecteur — il n'y a plus de « journal complet » qui ré-afficherait le
 * détail à côté (pas de second afficheur, ADR 0162) ; le lecteur plie
 * les messages multi-lignes (repli honnête) et la recherche y retrouve
 * le texte de la pile. Bornes du détail : les mêmes que le modèle
 * ([FlattenedException.DEFAULT_MAX_CAUSES] causes, 5 tranches par
 * cause — l'affichage d'origine, règle 14 : aucune perte silencieuse).
 */
public fun LogEntry.versLigneSortie(): LigneSortie = LigneSortie(
    source = SourceSortie.JOURNAL,
    message = exception?.let { exception -> message + "\n" + exception.aplatie() } ?: message,
    horodatageMs = timestampMillis,
    identiteSession = sessionId,
    niveau = level.versNiveauSortie(),
    etiquette = tag,
)

/** Le détail aplanie d'une exception (classe, message, tranches bornées,
 *  causes enchaînées « Caused by: » — la mise en forme du détail
 *  d'origine de `feature:diagnostics`, portée au domaine pur). */
private fun FlattenedException.aplatie(): String = buildString {
    append(className)
    message?.let { detail -> append(": ").append(detail) }
    frames.take(TRANCHES_PAR_CAUSE).forEach { tranche -> append("\n    ").append(tranche) }
    var courante = cause
    var profondeur = 1
    while (courante != null && profondeur <= CAUSES_MAX) {
        append("\nCaused by: ").append(courante.className)
        courante.message?.let { detail -> append(": ").append(detail) }
        courante.frames.take(TRANCHES_PAR_CAUSE).forEach { tranche -> append("\n    ").append(tranche) }
        courante = courante.cause
        profondeur++
    }
}

/** Tranches affichées par cause (le détail d'origine). */
private const val TRANCHES_PAR_CAUSE = 5

/** Causes enchaînées affichées (le détail d'origine, 10 niveaux). */
private const val CAUSES_MAX = 10

/**
 * Projette UN téléchargement (port tooling v4, arbitrage Q12) vers SA
 * ligne du Centre de sorties : la ligne est OUVERTE au premier
 * événement de l'artefact, mise à jour EN PLACE à chaque tick (≤ 5/s
 * côté serveur) et FIGÉE à sa fin — jamais une ligne par tick (spec
 * SORTIES § 4.7). Le rendu µ-jauge/compteurs part des champs
 * structurés ([LigneSortie.octetsRecus], [LigneSortie.octetsTotal],
 * [LigneSortie.dureeMs]) : le message reste vide — l'UI et l'export
 * composent le texte AU RENDU depuis les mêmes champs (aucun libellé
 * résolu dans le modèle, discipline `PresentationTooling`).
 *
 * @param source canal de l'action porteuse (SYNC ou BUILD — les
 *        téléchargements se voient pour toute action, v4 § 6).
 * @param idExecution identifiant de l'exécution émettrice.
 */
public fun TelechargementBuild.versLigneSortie(source: SourceSortie, idExecution: String): LigneSortie = LigneSortie(
    source = source,
    message = "",
    horodatageMs = 0,
    idExecution = idExecution,
    niveau = NiveauSortie.INDETERMINE,
    etiquette = element,
    dureeMs = if (dureeMs > 0) dureeMs else null,
    octetsRecus = octetsRecus,
    octetsTotal = octetsTotal,
)
