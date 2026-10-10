package jo.codeide.core.model

/**
 * Rétention de l'historique local (mission « Historique local » H6, ADR
 * 0105) : combien de jours calendaires le filet de sécurité conserve
 * les révisions — la purge d'entretien (à l'ouverture du projet) lit
 * ce réglage À CHAQUE exécution.
 *
 * Choisis fermes (borne le risque de stockage privé, se lisent d'un
 * coup d'œil dans les Paramètres) : 1, 5 (défaut, aligné Android
 * Studio), 15 ou 30 jours. Le défaut vit dans
 * [PolitiqueHistorique][jo.codeide.core.domain.PolitiqueHistorique]
 * (constante documentée) — l'enum ne fait que le citer.
 *
 * Persistance DataStore par nom (comme [License]) ; la lecture est
 * tolérante : un nom inconnu retombe sur le défaut, jamais d'écran de
 * paramètres cassé.
 */
@Suppress("MagicNumber") // Les valeurs de l'enum SONT la donnée métier (jours cités par l'ADR 0105).
public enum class RetentionHistorique(
    /** Nombre de jours calendaires conservés. */
    public val jours: Int,
) {
    /** Un jour — filet court, stockage minimal. */
    JOURS_1(1),

    /** Cinq jours — défaut, aligné sur Android Studio (ADR 0105). */
    JOURS_5(5),

    /** Quinze jours — travail étalé sur deux semaines. */
    JOURS_15(15),

    /** Trente jours — filet long, quota de 256 Mo toujours actif. */
    JOURS_30(30),

    ;

    public companion object {
        /**
         * Lit une valeur persistée par nom — tolérante : un nom inconnu
         * (version antérieure, corruption) retourne `null` et l'appelant
         * retombe sur le défaut.
         */
        public fun fromPersistedName(nomPersiste: String): RetentionHistorique? =
            entries.firstOrNull { it.name == nomPersiste }
    }
}
