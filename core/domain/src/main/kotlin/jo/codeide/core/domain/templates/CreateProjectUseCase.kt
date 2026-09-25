package jo.codeide.core.domain.templates

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.FileSystem
import jo.codeide.core.domain.ProjectRepository
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppResult
import jo.codeide.core.model.CreateProjectRequest
import jo.codeide.core.model.CreationProgress
import jo.codeide.core.model.PlannedContent
import jo.codeide.core.model.StorageLocation
import jo.codeide.core.model.TemplatePlan
import jo.codeide.core.model.getOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Cas d'usage « créer un projet » (étape 8 — section 12.4).
 *
 * Algorithme :
 * 1. **Revalider toutes les entrées** côté domaine (jamais confiance à l'UI) ;
 * 2. plan = moteur.plan(…) — dry-run, rien n'est écrit ;
 * 3. créer le dossier racine `<emplacement>/<nom>`, **échouer s'il existe
 *    déjà** (jamais d'écrasement, jamais de fusion) ;
 * 4. écrire les fichiers du plan un à un (texte ou binaire) en émettant la
 *    progression, y compris `.codeide/project.json` — chaque échec remonte
 *    avec son erreur **réelle** (jamais masquée en collision : v0.31.1) ;
 * 5. à tout échec **ou annulation** : rollback — suppression du dossier
 *    racine créé en `NonCancellable`, résidus éventuels signalés ;
 * 6. ne persister le `Project` en base **qu'après** le succès des écritures ;
 *    si l'insertion échoue → rollback ;
 * 7. l'événement terminal porte l'`AppResult<Project>` typé.
 *
 * Contexte d'exécution attendu : flot froid collecté hors thread principal ;
 * l'annulation de la collecte déclenche le rollback puis relaie
 * l'annulation (jamais avalée).
 */
public class CreateProjectUseCase
    @Inject
    constructor(
        private val planificateur: TemplateProjectPlanner,
        private val fichiers: FileSystem,
        private val projets: ProjectRepository,
        private val journal: AppLogger,
    ) {
        /** Échec d'une écriture : porte l'erreur typée jusqu'au terminal. */
        private class EchecCreation(
            val erreur: AppError,
        ) : Exception(erreur.toString())

        /**
         * Crée le projet demandé.
         *
         * @param requete demande complète (modèle, saisies, options, parent).
         * @return le flot de progression ; se termine toujours par
         * [CreationProgress.Termine] — sauf annulation de la collecte, où le
         * rollback est quand même exécuté avant de relancer l'annulation.
         */
        @Suppress("LongMethod") // Pipeline séquentielle de création, documentée en 7 points ci-dessus.
        public fun create(requete: CreateProjectRequest): Flow<CreationProgress> =
            flow {
                emit(CreationProgress.Preparation)

                // 1 et 2 — revalidation + plan (dry-run, rien n'est écrit).
                val planification = planificateur.planifier(requete)
                val plan =
                    planification.getOrNull()
                        ?: run {
                            // L'erreur typée (Validation, NotFound, Template)
                            // est relayée telle quelle : jamais d'effacement
                            // de contexte par un message générique.
                            emit(
                                CreationProgress.Termine(
                                    result = AppResult.Failure((planification as AppResult.Failure).error),
                                    rolledBack = false,
                                    residues = emptyList(),
                                ),
                            )
                            return@flow
                        }

                // 3 — dossier racine : échec s'il existe déjà.
                emit(CreationProgress.CreationDossierRacine(requete.name))
                val creationRacine = fichiers.createDirectory(requete.parentLocation.documentUri, requete.name)
                val racine =
                    creationRacine.getOrNull()
                        ?: run {
                            emit(
                                CreationProgress.Termine(
                                    result =
                                        AppResult.Failure(
                                            (creationRacine as AppResult.Failure).error,
                                        ),
                                    rolledBack = false,
                                    residues = emptyList(),
                                ),
                            )
                            return@flow
                        }

                // 4 — écritures ; tout échec bascule dans le rollback.
                val dossiers = LinkedHashMap<String, String>()
                val fichiersCrees = mutableListOf<Pair<String, String>>()
                try {
                    ecrirePlan(plan, racine, dossiers, fichiersCrees)
                    emit(CreationProgress.Enregistrement)

                    // 6 — registre en dernier ; un échec déclenche aussi le rollback.
                    // v0.31.5 : l'erreur RÉELLE de l'insertion remonte (elle
                    // est typée — dossier déjà référencé = AlreadyExists,
                    // base verrouillée = Io) ; l'ancien remplacement par un
                    // Io générique masquait la raison exactement comme le
                    // masque de collision de v0.31.1 (même principe honnête).
                    val insertion =
                        projets.addProject(
                            name = requete.name,
                            description = requete.description,
                            location =
                                StorageLocation(
                                    grantUri = requete.parentLocation.grantUri,
                                    documentUri = racine,
                                    displayPath = requete.parentLocation.displayPath + "/" + requete.name,
                                ),
                            templateId = requete.templateId,
                        )
                    val projet =
                        when (insertion) {
                            is AppResult.Success -> insertion.value
                            is AppResult.Failure -> throw EchecCreation(insertion.error)
                        }
                    emit(
                        CreationProgress.Termine(
                            AppResult.Success(projet),
                            rolledBack = false,
                            residues = emptyList(),
                        ),
                    )
                } catch (annulation: CancellationException) {
                    // Le collecteur est parti : le rollback s'exécute quand
                    // même (NonCancellable), puis l'annulation est relayée.
                    nettoyer(racine, dossiers, fichiersCrees)
                    throw annulation
                } catch (echec: EchecCreation) {
                    val residues = nettoyer(racine, dossiers, fichiersCrees)
                    journal.w(TAG) {
                        "création échouée (modèle ${requete.templateId.value}, ${residues.size} résidus)"
                    }
                    emit(
                        CreationProgress.Termine(
                            result = AppResult.Failure(echec.erreur),
                            rolledBack = true,
                            residues = residues,
                        ),
                    )
                } catch (imprevu: Exception) {
                    val residues = nettoyer(racine, dossiers, fichiersCrees)
                    journal.w(TAG, imprevu) { "création interrompue (modèle ${requete.templateId.value})" }
                    emit(
                        CreationProgress.Termine(
                            result = AppResult.Failure(AppError.Unknown(imprevu.message ?: "erreur inattendue")),
                            rolledBack = true,
                            residues = residues,
                        ),
                    )
                }
            }

        /** Émet la progression tout en écrivant chaque fichier du plan. */
        @Suppress("ThrowsCount") // Un throw par échec d'écriture, immédiatement rattrapé pour le rollback.
        private suspend fun FlowCollector<CreationProgress>.ecrirePlan(
            plan: TemplatePlan,
            racine: String,
            dossiers: LinkedHashMap<String, String>,
            fichiersCrees: MutableList<Pair<String, String>>,
        ) {
            plan.fichiers.forEachIndexed { index, fichier ->
                val parent =
                    assurerDossiers(racine, fichier.chemin, dossiers)
                        ?: throw EchecCreation(
                            AppError.Storage(AppError.StorageReason.Io, "dossiers parents de « ${fichier.chemin} »"),
                        )
                val nom = fichier.chemin.substringAfterLast('/')
                val uri =
                    when (
                        val creation =
                            fichiers.createFile(parent, nom, mimePour(fichier.contenu, fichier.chemin))
                    ) {
                        is AppResult.Success -> {
                            creation.value
                        }

                        is AppResult.Failure -> {
                            // L'échec RÉEL remonte tel quel (v0.31.1 : toute
                            // défaillance de création — permission perdue,
                            // E/S, emplacement parti — était ici masquée en
                            // collision, et l'écran affichait « un dossier
                            // porte déjà ce nom » pour une raison sans
                            // rapport ; retour d'appareil réel 7842f130).
                            throw EchecCreation(creation.error)
                        }
                    }
                fichiersCrees += fichier.chemin to uri
                val ecriture =
                    when (val contenu = fichier.contenu) {
                        is PlannedContent.Texte -> fichiers.writeText(uri, contenu.texte)
                        is PlannedContent.Binaire -> fichiers.writeBytes(uri, contenu.octets)
                    }
                if (ecriture is AppResult.Failure) {
                    throw EchecCreation(ecriture.error)
                }
                emit(CreationProgress.GenerationFichier(index + 1, plan.fichiers.size, fichier.chemin))
            }
        }

        /**
         * Crée (et mémoïse) les dossiers parents du chemin ; retourne l'URI
         * du dossier parent direct du fichier.
         */
        private suspend fun assurerDossiers(
            racine: String,
            chemin: String,
            dossiers: LinkedHashMap<String, String>,
        ): String? {
            val segments = chemin.split('/').dropLast(1)
            var courant = racine
            var prefix = ""
            for (segment in segments) {
                prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
                val existant = dossiers[prefix]
                if (existant != null) {
                    courant = existant
                    continue
                }
                val cree =
                    fichiers.createDirectory(courant, segment).getOrNull()
                        ?: return null
                dossiers[prefix] = cree
                courant = cree
            }
            return courant
        }

        /**
         * Rollback : fichiers en ordre inverse, puis dossiers, puis racine —
         * en `NonCancellable` (section 12.4, point 5). Retourne les chemins
         * relatifs impossibles à supprimer.
         */
        private suspend fun nettoyer(
            racine: String,
            dossiers: LinkedHashMap<String, String>,
            fichiersCrees: List<Pair<String, String>>,
        ): List<String> =
            withContext(NonCancellable) {
                val residues = mutableListOf<String>()
                fichiersCrees.asReversed().forEach { (cheminRelatif, uri) ->
                    if (fichiers.delete(uri) is AppResult.Failure) residues += cheminRelatif
                }
                dossiers.entries.toList().asReversed().forEach { (cheminRelatif, uri) ->
                    if (fichiers.delete(uri) is AppResult.Failure) residues += cheminRelatif
                }
                if (fichiers.delete(racine) is AppResult.Failure) residues += "/"
                residues.toList()
            }

        /**
         * Type MIME conseillé à SAF pour la création (indicatif).
         *
         * Piège SAF (constaté sur appareil réel) : un fournisseur honnête
         * complète un nom **sans point** par l'extension canonique du type
         * demandé — « gradlew » ou « LICENSE » avec `text/plain` deviendraient
         * « gradlew.txt », « LICENSE.txt », faux fichiers dans le projet
         * généré. Un type privé inconnu de la table système ne déclenche
         * aucune complétion : le nom demandé est préservé tel quel.
         */
        private fun mimePour(
            contenu: PlannedContent,
            chemin: String,
        ): String =
            if (contenu is PlannedContent.Texte) {
                if (chemin.substringAfterLast('/').contains('.')) "text/plain" else MIME_TEXTE_SANS_EXTENSION
            } else {
                when (chemin.substringAfterLast('.', "").lowercase()) {
                    "png" -> "image/png"
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    else -> "application/octet-stream"
                }
            }

        private companion object {
            /** Étiquette de journal (identifiant, règle 15). */
            const val TAG = "CreateProject"

            /** Type privé pour fichier texte sans extension (voir [mimePour]). */
            const val MIME_TEXTE_SANS_EXTENSION = "text/x-codeide"
        }
    }
