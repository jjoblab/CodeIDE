package jo.codeide.core.model

/**
 * Mode de thème de l'interface (réglage utilisateur, section 5.7 du plan
 * d'étapes : `AppSettings.themeMode`).
 *
 * La valeur n'est **pas** persistée sous cette forme dans les préférences :
 * le nom de la constante sert de clé stable de sérialisation ; l'UI la
 * traduit en `AppCompatDelegate.setDefaultNightMode` (étapes 5-6).
 */
public enum class ThemeMode {
    /** Suit le réglage système (clair ou sombre selon l'heure/batterie). */
    SYSTEM,

    /** Toujours clair, quelle que soit la configuration du système. */
    LIGHT,

    /** Toujours sombre, quelle que soit la configuration du système. */
    DARK,
}
