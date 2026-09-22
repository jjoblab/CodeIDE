package jo.codeide.core.domain

import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.Project
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Résultat de la validation d'un dossier de travail proposé par le
 * sélecteur SAF (étapes 5 et 6).
 *
 * Machine explicite : chaque échec garde sa raison (dossier refusé par
 * Android 11+, erreur typée) pour que l'appelant affiche un message
 * **clair et actionnable** au lieu d'un crash ou d'un silence.
 */
public sealed interface ValidationDossier {
    /** Dossier inscriptible et validé : prêt à être persisté. */
    public data class Valide(
        val emplacement: StorageLocation,
    ) : ValidationDossier

    /** Dossier refusé par Android 11+ (racine, Download, Android/data…). */
    public data class Refuse(
        val raison: ForbiddenFolders.Reason,
    ) : ValidationDossier

    /** Échec typé de la permission, du test d'écriture ou de l'URI. */
    public data class Erreur(
        val erreur: AppError,
    ) : ValidationDossier
}

/**
 * Cas d'usage « valider un dossier de travail » (étape 6, partagé avec
 * l'assistant de l'étape 5).
 *
 * Détection des dossiers refusés par Android 11+ **avant** toute prise
 * de permission, prise de la permission persistante, **test
 * d'écriture** (création d'un fichier témoin, écriture puis
 * suppression — prouver que le dossier est réellement utilisable, pas
 * seulement sélectionnable), résolution du libellé lisible.
 *
 * Tout échec **relâche la permission prise** : le système plafonne les
 * permissions persistantes (512 sur Android 11+, section 5.6), on n'en
 * garde pas une inutile. En cas de succès, la permission reste tenue :
 * la persistance du réglage revient à l'appelant
 * (`SetWorkspaceUseCase`) qui relâchera lui-même en cas d'échec
 * d'écriture.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 *
 * Exemption detekt ciblée (règle 16 du prompt maître) : ReturnCount —
 * chaque clause de garde est une **issue** du parcours de validation
 * (URI illisible, dossier refusé, permission refusée, test d'écriture
 * échoué, succès) ; les imbriquer en expressions ferait perdre
 * l'évidence d'un code de sécurité. Même motif pour les cas d'usage
 * voisins de ce fichier (changer, effacer, réinitialiser).
 */
@Suppress("ReturnCount")
public class ValidateWorkspaceUseCase
    @Inject
    constructor(
        private val fichiers: FileSystem,
        private val arborescences: ArborescencesSaf,
        private val horloge: TimeProvider,
    ) {
        /** Valide [grantUri] sans rien persister. */
        public suspend operator fun invoke(grantUri: String): ValidationDossier {
            val idDocument =
                arborescences.idDocument(grantUri)
                    ?: return ValidationDossier.Erreur(
                        AppError.Storage(AppError.StorageReason.Io, "URI d'arborescence illisible"),
                    )

            // Refus plateforme d'abord : jamais de permission prise sur
            // un dossier interdit (message clair, section 5.6).
            val refus = ForbiddenFolders.reasonFor(idDocument)
            if (refus != null) return ValidationDossier.Refuse(refus)

            when (val permission = fichiers.takePersistablePermission(grantUri)) {
                is AppResult.Failure -> {
                    return ValidationDossier.Erreur(permission.error)
                }

                is AppResult.Success -> {
                    Unit
                }
            }

            val uriDocument =
                arborescences.uriDocument(grantUri)
                    ?: return illisibleApresPermission(grantUri)

            val echec = testerEcriture(fichiers, uriDocument, horloge, PREFIXE_TEMOIN)
            return when (echec) {
                null -> {
                    ValidationDossier.Valide(
                        StorageLocation(
                            grantUri = grantUri,
                            documentUri = uriDocument,
                            displayPath = libelleLisible(fichiers, uriDocument, idDocument),
                        ),
                    )
                }

                is AppError -> {
                    echecApresPermission(grantUri, echec)
                }
            }
        }

        /** Relâche la permission puis retourne l'erreur d'URI. */
        private suspend fun illisibleApresPermission(grantUri: String): ValidationDossier {
            fichiers.releasePersistablePermission(grantUri)
            return ValidationDossier.Erreur(
                AppError.Storage(AppError.StorageReason.Io, "URI de document illisible"),
            )
        }

        /** Relâche la permission puis retourne l'erreur du test d'écriture. */
        private suspend fun echecApresPermission(
            grantUri: String,
            erreur: AppError,
        ): ValidationDossier {
            fichiers.releasePersistablePermission(grantUri)
            return ValidationDossier.Erreur(erreur)
        }

        private companion object {
            /** Préfixe du fichier témoin du test d'écriture. */
            const val PREFIXE_TEMOIN = "codeide-temoin"
        }
    }

/**
 * Résultat de l'effacement du dossier de travail (étape 6).
 *
 * @property permissionLiberee l'ancienne permission persistante a pu
 * être relâchée — faux quand des projets en dépendent encore (elle
 * reste alors détenue, volontairement).
 */
public sealed interface EffacementDossier {
    /** Dossier effacé du réglage. */
    public data class Efface(
        val permissionLiberee: Boolean,
    ) : EffacementDossier

    /** Échec d'écriture typé : le réglage est inchangé. */
    public data class Erreur(
        val erreur: AppError,
    ) : EffacementDossier
}

/**
 * Cas d'usage « changer le dossier de travail » (étape 6) : valide le
 * nouveau dossier (via [ValidateWorkspaceUseCase]), le persiste, puis
 * **ne libère l'ancienne permission que si aucun projet n'en dépend**
 * (règle de l'étape 6 : un projet du registre référence encore l'arbre
 * SAF de l'ancien dossier).
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
@Suppress("ReturnCount")
public class ChangeWorkspaceUseCase
    @Inject
    constructor(
        private val parametres: SettingsRepository,
        private val projets: ProjectRepository,
        private val fichiers: FileSystem,
        private val validation: ValidateWorkspaceUseCase,
    ) {
        /** Change le dossier de travail vers [grantUri]. */
        public suspend operator fun invoke(grantUri: String): ValidationDossier {
            when (val valide = validation(grantUri)) {
                is ValidationDossier.Refuse -> return valide
                is ValidationDossier.Erreur -> return valide
                is ValidationDossier.Valide -> return enregistrer(valide.emplacement)
            }
        }

        /** Persiste le nouveau dossier puis conditionne la libération de l'ancien. */
        private suspend fun enregistrer(nouveau: StorageLocation): ValidationDossier {
            // Ancien lu AVANT la persistance : écraser d'abord le
            // réglage ferait disparaître la référence à libérer.
            val ancien = parametres.getSettings().getOrNull()?.workspace
            when (val resultat = parametres.setWorkspace(nouveau)) {
                is AppResult.Failure -> {
                    // Écriture impossible : le dossier redevient
                    // « invérifié », on ne gaspille pas la permission.
                    fichiers.releasePersistablePermission(nouveau.grantUri)
                    return ValidationDossier.Erreur(resultat.error)
                }

                is AppResult.Success -> {
                    Unit
                }
            }
            libererAncienSiInutilise(nouveau, ancien)
            return ValidationDossier.Valide(nouveau)
        }

        /**
         * Libère l'ancienne permission si un autre dossier était actif,
         * si le nouveau est différent, et si aucun projet du registre
         * ne vit dans son arbre.
         */
        private suspend fun libererAncienSiInutilise(
            nouveau: StorageLocation,
            ancien: StorageLocation?,
        ) {
            if (ancien == null || ancien.grantUri == nouveau.grantUri) return
            if (projetsSousDossier(projets, ancien)) return
            fichiers.releasePersistablePermission(ancien.grantUri)
        }
    }

/**
 * Cas d'usage « effacer le dossier de travail » (étape 6) : retire le
 * réglage puis, comme pour un changement, **ne libère la permission que
 * si aucun projet n'en dépend**.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
@Suppress("ReturnCount")
public class ClearWorkspaceUseCase
    @Inject
    constructor(
        private val parametres: SettingsRepository,
        private val projets: ProjectRepository,
        private val fichiers: FileSystem,
    ) {
        /** Efface le dossier de travail du réglage (aucun effet s'il n'y en a pas). */
        public suspend operator fun invoke(): EffacementDossier {
            val ancien = parametres.getSettings().getOrNull()?.workspace ?: return EffacementDossier.Efface(false)

            when (val resultat = parametres.setWorkspace(null)) {
                is AppResult.Failure -> return EffacementDossier.Erreur(resultat.error)
                is AppResult.Success -> Unit
            }

            // Réglage effacé : la permission ne sert plus qu'aux projets
            // qui vivent encore dans l'arbre — on la garde sinon à rien.
            if (projetsSousDossier(projets, ancien)) return EffacementDossier.Efface(false)
            val liberee = fichiers.releasePersistablePermission(ancien.grantUri) is AppResult.Success
            return EffacementDossier.Efface(liberee)
        }
    }

/**
 * Cas d'usage « réinitialiser les préférences » (étape 6).
 *
 * Remet **tous** les paramètres applicatifs à leurs valeurs par défaut
 * (thème, couleurs dynamiques, langue, dossier de travail, nom
 * d'auteur, licence, verbosité) — à deux exceptions près, motivées par
 * le fait qu'elles décrivent des **états applicatifs** et non des
 * préférences :
 * - `isSetupCompleted` reste vrai : « Relancer l'assistant » est une
 *   action distincte, explicite ;
 * - le registre des projets (Room) n'est **pas** touché : il décrit
 *   des dossiers réels sur le stockage, pas des préférences.
 *
 * Le dossier de travail effacé suit la même règle que l'étape 6 : sa
 * permission persistante n'est libérée que si aucun projet n'en dépend.
 *
 * Contexte d'exécution attendu : suspendante, hors thread principal.
 */
@Suppress("ReturnCount")
public class ResetPreferencesUseCase
    @Inject
    constructor(
        private val parametres: SettingsRepository,
        private val projets: ProjectRepository,
        private val fichiers: FileSystem,
    ) {
        /** Réinitialise les préférences applicatives. */
        public suspend operator fun invoke(): AppResult<Unit> {
            val ancienDossier = parametres.getSettings().getOrNull()?.workspace

            val resultat =
                parametres.updateSettings {
                    AppSettings().copy(isSetupCompleted = true)
                }
            when (resultat) {
                is AppResult.Failure -> return resultat
                is AppResult.Success -> Unit
            }

            if (ancienDossier != null && !projetsSousDossier(projets, ancienDossier)) {
                fichiers.releasePersistablePermission(ancienDossier.grantUri)
            }
            return AppResult.Success(Unit)
        }
    }

/**
 * Un projet du registre vit-il dans l'arbre du dossier donné ?
 *
 * La frontière utilise l'URI de document (identifiant canonique du
 * dossier, encodé en pourcentages dans les URI SAF) : un projet dépend
 * du dossier si son URI est celle du dossier **ou** commence par elle
 * suivie d'un séparateur (`%2F` encodé, `/` brut toléré pour les
 * fournisseurs qui ne percent-encodent pas).
 */
internal suspend fun projetsSousDossier(
    projets: ProjectRepository,
    dossier: StorageLocation,
): Boolean = projets.observeProjects().first().any { it.estSousDossier(dossier) }

/** Le projet vit-il dans l'arbre du dossier (voir [projetsSousDossier]) ? */
private fun Project.estSousDossier(dossier: StorageLocation): Boolean =
    estDansArbre(dossier.documentUri, location.documentUri)
