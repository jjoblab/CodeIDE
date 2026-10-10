package jo.codeide.applog;

/**
 * Format des trames échangées entre le pont (app cible) et l'IDE —
 * ADR 0103 : « tabulaire à message-reste, réécrit ».
 *
 * <p>Une trame est une ligne unique. Les champs sont séparés par la
 * tabulation ({@code \t}) ; le MESSAGE est toujours le DERNIER champ et
 * s'étend jusqu'à la fin de la trame — il peut donc contenir des
 * tabulations, sans échappement (la coupure se fait au premier
 * séparateur qui suit l'étiquette).
 *
 * <p>Deux types de trames :
 *
 * <ul>
 *   <li>{@code L&lt;tab&gt;niveau&lt;tab&gt;epoch_ms&lt;tab&gt;pid&lt;tab&gt;tid&lt;tab&gt;étiquette&lt;tab&gt;message…}
 *       — une ligne de journal ; niveau = un caractère
 *       {@code V D I W E F} (logcat) ;
 *       epoch_ms = horodatage en millisecondes Unix.</li>
 *   <li>{@code X&lt;tab&gt;codeRaison&lt;tab&gt;description…} — information de fin
 *       du processus PRÉCÉDENT (ApplicationExitInfo, API 30+).</li>
 * </ul>
 *
 * <p>Le récepteur (IDE) analyse ce format de façon DÉFENSIVE : toute
 * trame non conforme est ignorée — jamais d'exception sur une donnée
 * externe non fiable.
 */
final class Trames {

    /** Séparateur de champs. */
    static final char SEP = '\t';

    /** Type « ligne de journal ». */
    static final char TYPE_LIGNE = 'L';

    /** Type « information de sortie du processus précédent ». */
    static final char TYPE_SORTIE_PRECEDENTE = 'X';

    /** Longueur maximale d'un message (les messages géants sont tronqués). */
    static final int TAILLE_MESSAGE_MAX = 4000;

    /** Marqueur de troncature apposé aux messages coupés. */
    private static final String MARQUEUR_TRONCATURE = "… [tronqué]";

    private Trames() {
        // Classe utilitaire — jamais instanciée.
    }

    /**
     * Fabrique la trame d'une ligne de journal.
     *
     * @param niveau    caractère de niveau ({@code V D I W E F})
     * @param epochMs   horodatage Unix en millisecondes
     * @param pid       identifiant du processus émetteur
     * @param tid       identifiant du fil émetteur
     * @param etiquette étiquette de journal (vide si absente)
     * @param message   texte (tronqué au-delà de {@link #TAILLE_MESSAGE_MAX})
     * @return la trame prête à expédier
     */
    static String ligne(
            char niveau,
            long epochMs,
            int pid,
            int tid,
            String etiquette,
            String message) {
        // StringBuilder EXPLICITE : en Java, char + char est une ARITHMÉTIQUE
        // d'entiers (« L » + tabulation + « W » = un nombre) — le piège
        // classique que la première String du chaînage ne sauve pas.
        StringBuilder trame = new StringBuilder(64);
        trame.append(TYPE_LIGNE)
                .append(SEP).append(niveau)
                .append(SEP).append(epochMs)
                .append(SEP).append(pid)
                .append(SEP).append(tid)
                .append(SEP).append(etiquette == null ? "" : etiquette)
                .append(SEP).append(message == null ? "" : message);
        return trame.toString();
    }

    /**
     * Fabrique la trame d'une information de fin du processus précédent.
     *
     * @param codeRaison   code numérique de la raison (constantes
     *                     ApplicationExitInfo.REASON_*)
     * @param description  texte court décrivant la raison
     * @return la trame prête à expédier
     */
    static String sortiePrecedente(int codeRaison, String description) {
        // Même raison que [ligne] : jamais d'arithmétique accidentelle.
        StringBuilder trame = new StringBuilder(48);
        trame.append(TYPE_SORTIE_PRECEDENTE)
                .append(SEP).append(codeRaison)
                .append(SEP).append(description == null ? "" : description);
        return trame.toString();
    }

    /**
     * Tronque un message à la taille maximale — la troncature est VISIBLE
     * (marqueur explicite), jamais silencieuse.
     */
    static String tronquer(String message) {
        if (message == null || message.length() <= TAILLE_MESSAGE_MAX) {
            return message == null ? "" : message;
        }
        return message.substring(0, TAILLE_MESSAGE_MAX) + MARQUEUR_TRONCATURE;
    }
}
