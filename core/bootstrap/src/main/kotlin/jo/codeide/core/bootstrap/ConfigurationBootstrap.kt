package jo.codeide.core.bootstrap

/**
 * Configuration de l'installation du bootstrap (prompt compagnon
 * Terminal-1, sections 1.1 et 3.4).
 *
 * Valeurs **figées et vérifiées** le 2026-09-23 contre le dépôt
 * `jjoblab/codeide-packages` :
 * - release `bootstrap-2026.08.14-r3` (la plus récente publiée) ;
 * - seul asset publié : `bootstrap-aarch64.zip` — aucune autre
 *   architecture n'existe à ce jour (l'installateur le vérifie côté
 *   appareil avant de télécharger, voir [CapaciteArchitecture]) ;
 * - empreinte SHA-256 publiée dans les notes de release ;
 * - dépôt APT `stable main` (fichier `Release` vérifié), ligne
 *   `sources.list` avec `[trusted=yes]` conformément à la section 3.4 ;
 * - paquets d'outils : `openjdk-17` (17.0.20) et `git` sont présents
 *   dans le dépôt ; **aucun paquet `gradle` ni `android-sdk` n'y figure
 *   à ce jour** — signalé côté `codeide-packages`, non contourné ici
 *   (chaque paquet est installé individuellement, un absent ne bloque
 *   pas les autres et est rapporté non installé).
 *
 * Injectée (doublable en test) : l'installateur ne connaît jamais ces
 * constantes en dur ailleurs.
 */
public data class ConfigurationBootstrap(
    /** URL complète de l'archive du bootstrap à télécharger. */
    public val urlArchive: String = ConstantesBootstrap.URL_ARCHIVE,
    /** Empreinte SHA-256 attendue de l'archive (hexadécimal minuscule). */
    public val empreinteAttendue: String = ConstantesBootstrap.EMPREINTE_ARCHIVE,
    /** Ligne de dépôt APT inscrite dans `sources.list` (URL + suite + composante). */
    public val ligneDepotApt: String = ConstantesBootstrap.LIGNE_DEPOT_APT,
    /** Paquets d'outils installés après le second stage, un par un. */
    public val paquets: List<String> = Constantes.PAQUETS_OUTILS,
    /**
     * Espace disque libre minimal exigé avant le téléchargement
     * (octets) : archive + extraction transitoire + paquets (le JDK
     * seul dépasse 200 Mio installé).
     */
    public val seuilEspaceDisque: Long = Constantes.SEUIL_ESPACE_DISQUE,
) {
    private object Constantes {
        val PAQUETS_OUTILS = listOf("openjdk-17", "git")

        /** 1 Gio — marge conservatrice pour JDK (200+ Mio) et dépendances. */
        const val SEUIL_ESPACE_DISQUE = 1024L * 1024 * 1024
    }

    private object ConstantesBootstrap {
        const val VERSION_RELEASE = "bootstrap-2026.08.14-r3"
        const val URL_ARCHIVE =
            "https://github.com/jjoblab/codeide-packages/releases/download/" +
                "$VERSION_RELEASE/bootstrap-aarch64.zip"
        const val EMPREINTE_ARCHIVE =
            "58ac56a69737dba8754b6c7b773eae63e5f8e23a929f8b5d05f09621cba0b5d5"
        const val LIGNE_DEPOT_APT =
            "deb [trusted=yes] https://jjoblab.github.io/codeide-packages/apt/codeide-main stable main"
    }

    internal companion object {
        /** Instance de production (fournie à Hilt par le module du dépôt). */
        internal val PAR_DEFAUT: ConfigurationBootstrap = ConfigurationBootstrap()
    }
}
