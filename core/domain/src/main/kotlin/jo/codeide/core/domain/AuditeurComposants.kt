package jo.codeide.core.domain

/**
 * Audit en lecture seule des composants installés (E5, § 7 — écran
 * Environnement, ADR 0090) : tailles sur disque, sans aucune décision —
 * l'orchestrateur reste le seul décideur du parcours.
 *
 * Le JDK, installé par `pkg` en phase 3 et non par le manifeste, est
 * audité séparément : sa taille est mesurée depuis le `JAVA_HOME` résolu
 * (règle unique de `LocalisationOutils`), ou `null` s'il est introuvable.
 */
public interface AuditeurComposants {
    /**
     * Taille occupée sur disque par l'`installPath` du composant, en
     * octets — `null` si le dossier est absent (composant désinstallé
     * ou never installé : l'écran l'affiche « absent », jamais 0).
     */
    public suspend fun tailleOctets(composant: InstalledComponent): Long?

    /** Taille du JDK sur disque (`JAVA_HOME`), en octets — `null` s'il est absent. */
    public suspend fun tailleJdkOctets(): Long?
}
