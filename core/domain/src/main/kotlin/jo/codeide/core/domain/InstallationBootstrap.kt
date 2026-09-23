package jo.codeide.core.domain

import jo.codeide.core.model.EtatInstallationBootstrap
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream

/**
 * Installateur du bootstrap natif, exposé en **état partagé** (prompt
 * compagnon Terminal-1, section 3.4 : la progression est « consommée à
 * la fois par l'étape d'onboarding et par un déclenchement à la
 * demande »).
 *
 * Une seule installation peut courir à la fois : `demarrer()` pendant
 * un état `EnCours` est sans effet (l'observateur suit la progression
 * en cours) ; après `Terminee`, il est sans effet également — le
 * bootstrap est en place. Les reprises ne se font qu'après `Echouee`
 * ou `Annulee` (et repartent de zéro : staging nettoyé).
 *
 * L'implémentation de référence vit dans `core:bootstrap` : pipeline
 * coroutine (téléchargement, extraction, liens symboliques, second
 * stage, `sources.list`, `apt`), erreurs typées
 * `AppError.Bootstrap`, annulation propre.
 */
public interface BootstrapInstaller {
    /**
     * État partagé de l'installation, mis à jour à chaque étape et à
     * chaque transition terminale.
     */
    public val etat: StateFlow<EtatInstallationBootstrap>

    /**
     * Démarre l'installation si aucune n'est en cours (ni déjà
     * terminée avec succès) ; sans effet sinon.
     *
     * Le pipeline tourne dans une portée interne à l'implémentation :
     * survivre à la rotation, au changement d'écran et aux deux points
     * d'entrée — l'appelant n'a **pas** de coroutine à conserver.
     */
    public fun demarrer()

    /**
     * Annule l'installation en cours (sans effet sinon) : le
     * téléchargement est interrompu, les répertoires de préparation
     * sont supprimés, l'état devient `Annulee`.
     */
    public fun annuler()
}

/**
 * Accès aux binaires d'outils embarqués dans les assets de
 * l'application (prompt compagnon Terminal-1, section 3.5 —
 * `Aapt2Deployer`).
 *
 * Port volontairement minimal (une ouverture de flux par nom) : le
 * `aapt2` cross-compilé est un **asset de l'application**, pas un
 * document utilisateur — l'accès direct `File`/SAF de l'ADR 0003 ne
 * s'applique pas. Implémenté par l'`AssetManager` dans `app`,
 * doublé par un faux en test.
 */
public interface BootstrapAssetsSource {
    /**
     * Ouvre un binaire embarqué en lecture.
     *
     * @param nom chemin relatif de l'asset (ex. `outils/aapt2`).
     * @return le flux de lecture, ou `null` si l'asset n'existe pas —
     * l'appelant traduit lui-même l'absence en `AppError.Bootstrap`
     * ([jo.codeide.core.model.AppError.BootstrapReason.AssetAbsent]).
     */
    public fun ouvrir(nom: String): InputStream?
}
