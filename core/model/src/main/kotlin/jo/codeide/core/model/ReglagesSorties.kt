package jo.codeide.core.model

/**
 * Le mode d'HORODATAGE initial du lecteur à l'ouverture (spec SORTIES
 * § 5.10 « horodatage par défaut » — les réglages « Sorties » du lot L6,
 * M13, chacun avec UN effet réel, aucun réglage décoratif) — la valeur
 * de départ du bouton trois états de la barre du lecteur (masqué →
 * relatif → absolu, lot L2) ; l'utilisateur garde le dernier mot dans
 * la session.
 */
public enum class HorodatageSorties {
    /** Pas d'horodatage affiché à l'ouverture (défaut historique). */
    MASQUE,

    /** Horodatage RELATIF à la première ligne de la fenêtre. */
    RELATIF,

    /** Horodatage ABSOLU (l'instant d'émission ou de réception). */
    ABSOLU,
    ;

    public companion object {
        /**
         * Reconstitue le mode depuis son nom persisté.
         *
         * @param nom nom de l'entrée, ou `null`/inconnu.
         * @return l'entrée correspondante, ou `null` si inconnue (le
         * consommateur retombe sur [MASQUE]).
         */
        public fun depuisNom(nom: String?): HorodatageSorties? = entries.firstOrNull { it.name == nom }
    }
}

/**
 * Le FILTRE par défaut du lecteur Logcat à l'ouverture (spec SORTIES
 * § 5.10 « filtres par défaut ») — un niveau minimal appliqué une fois
 * à la construction du lecteur ; les lignes masquées restent comptées
 * et annoncées (honnêteté règle 14), jamais perdues. Les autres
 * familles gardent leur filtre « tout » (un build Gradle filtré par
 * défaut cacherait la moitié de la vérité).
 */
public enum class NiveauDefautLogcat {
    /** Toutes les lignes (aucun filtre initial). */
    TOUT,

    /** Avertissements et erreurs seuls ([NiveauSortie.AVERTISSEMENT] et plus). */
    AVERTISSEMENT,

    /** Erreurs seules ([NiveauSortie.ERREUR]). */
    ERREUR,
    ;

    public companion object {
        /**
         * Reconstitue le niveau depuis son nom persisté.
         *
         * @param nom nom de l'entrée, ou `null`/inconnu.
         * @return l'entrée correspondante, ou `null` si inconnue (le
         * consommateur retombe sur [TOUT]).
         */
        public fun depuisNom(nom: String?): NiveauDefautLogcat? = entries.firstOrNull { it.name == nom }
    }
}
