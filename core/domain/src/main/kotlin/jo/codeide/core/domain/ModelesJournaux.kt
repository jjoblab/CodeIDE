package jo.codeide.core.domain

/**
 * Niveau de journal d'une ligne captée par le pont de l'application
 * exécutée (mission « Exécuter », ADR 0103) — miroir des niveaux de
 * logcat : un caractère par niveau ({@code V D I W E F}).
 *
 * L'ordre des valeurs EST l'ordre de sévérité croissante : le filtre de
 * niveau de l'onglet Logcat s'appuie dessus (« masquer les niveaux
 * inférieurs », façon Android Studio).
 */
public enum class NiveauJournal {
    /** Verbose ({@code V}). */
    VERBEUX,

    /** Débogage ({@code D}). */
    DEBOGAGE,

    /** Information ({@code I}). */
    INFO,

    /** Avertissement ({@code W}). */
    AVERTISSEMENT,

    /** Erreur ({@code E}). */
    ERREUR,

    /** Fatal / assertion ({@code F}) — le process est en train de mourir. */
    ASSERT,
}

/**
 * Une ligne de journal d'une application exécutée — produite par
 * [AnalyseurTramesJournal] depuis les trames du pont, JAMAIS construite
 * à la main côté transport.
 *
 * @property horodatageMs horodatage Unix reconstruit par le pont
 * @property pid identifiant du processus émetteur
 * @property tid identifiant du fil émetteur
 * @property niveau sévérité
 * @property etiquette étiquette de journal (tag) — vide si absente
 * @property message texte de la ligne (tronqué côté pont à 4 000)
 */
public data class LigneJournal(
    public val horodatageMs: Long,
    public val pid: Int,
    public val tid: Int,
    public val niveau: NiveauJournal,
    public val etiquette: String,
    public val message: String,
)

/**
 * Identité d'une session de journaux : UN PONT PAR PROCESSUS de
 * l'application (ADR 0103 §6 — clé (paquet, pid, nom de processus)).
 *
 * @property paquet nom de paquet de l'application émettrice
 * @property pid identifiant du processus
 * @property nomProcessus nom du processus (« principal », « :service »…)
 */
public data class IdentiteSessionJournal(
    public val paquet: String,
    public val pid: Int,
    public val nomProcessus: String,
)

/**
 * Raison de fin d'une session de journaux — affichée telle quelle dans
 * le bandeau (« Le processus s'est arrêté : … »).
 */
public data class FinSessionJournal(
    public val raison: String,
    public val horodatageMs: Long,
)
