package jo.codeide.core.domain

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.inject.Inject

/**
 * Cas d'usage « exécuter l'application du projet » (mission « Exécuter »
 * R1, ADR 0102) : localise l'APK produit par le build debug, lit son
 * identifiant d'application, l'installe, puis lance l'application.
 *
 * La COMPILATION (`:app:assembleDebug`) reste en dehors : elle voyage
 * par la chaîne Gradle existante ([ExecuterTachesUseCase]) — ce cas
 * d'usage prend le relais une fois le build réussi (l'appelant attend
 * [EtatBuild] terminal), comme le bouton « Run » d'Android Studio
 * enchaîne les étapes du runner.
 *
 * Localisation : le chemin de l'APK debug est **déterministe**
 * (`app/build/outputs/apk/debug/app-debug.apk` — même contrat que
 * `scripts/verify-templates.sh`), et l'`applicationId` se lit dans
 * `output-metadata.json` du même dossier (écrit par AGP à chaque
 * assemblage) : **aucun changement du protocole tooling** n'est
 * nécessaire en R1.
 *
 * Contexte d'exécution : suspendante (lecture disque, installation,
 * lancement), hors fil principal ; le lancement d'une activité exige
 * que l'appelant soit l'application au PREMIER PLAN (le bouton
 * Exécuter est pressé dans l'espace d'édition — contrat respecté par
 * construction ; un service en arrière-plan n'a pas ce droit,
 * Android 10+).
 */
public class ExecuterApplicationUseCase
    @Inject
    constructor(
        private val installateur: ApkInstaller,
        private val repartiteurs: DispatcherProvider,
    ) {
        /**
         * Localise, installe et lance l'application du projet [dossierProjet].
         *
         * @param dossierProjet dossier FUSE réel du projet (racine).
         * @param progression récepteur optionnel des étapes franchies —
         *        intentions typées pour la console et les snackbars.
         * @return le résultat typé, jamais d'exception vers l'appelant.
         */
        @Suppress("ReturnCount") // Gardes typées : APK absent, métadonnées illisibles — une issue par étape.
        public suspend operator fun invoke(
            dossierProjet: File,
            progression: ((EtapeExecutionApplication) -> Unit)? = null,
        ): ResultatExecutionApplication {
            val apk =
                withContext(repartiteurs.io) { localiserApkDebug(dossierProjet) }
                    ?: return ResultatExecutionApplication.ApkAbsent
            val nomPaquet =
                withContext(repartiteurs.io) { lireNomPaquet(dossierProjet) }
                    ?: return ResultatExecutionApplication.MetadonneesIllisibles

            return when (
                val installation =
                    installateur.installer(apk) { etape ->
                        progression?.invoke(projectionEtape(etape))
                    }
            ) {
                is ResultatInstallationApk.Succes -> {
                    when (installateur.lancer(nomPaquet)) {
                        is ResultatLancementApk.Succes -> {
                            progression?.invoke(EtapeExecutionApplication.ApplicationLancee(nomPaquet))
                            ResultatExecutionApplication.Succes(nomPaquet)
                        }

                        is ResultatLancementApk.Introuvable -> {
                            ResultatExecutionApplication.LancementIntrouvable(nomPaquet)
                        }
                    }
                }

                is ResultatInstallationApk.Echec -> {
                    ResultatExecutionApplication.Installation(installation, nomPaquet)
                }
            }
        }

        /**
         * Projette une étape du port d'installation vers l'étape du cycle
         * complet — l'interface ne connaît que le vocabulaire du runner.
         */
        private fun projectionEtape(etape: EtapeInstallationApk): EtapeExecutionApplication =
            when (etape) {
                EtapeInstallationApk.AutorisationSourcesInconnuesRequise -> {
                    EtapeExecutionApplication.AutorisationSourcesInconnuesRequise
                }

                EtapeInstallationApk.CopieApk -> {
                    EtapeExecutionApplication.CopieApk
                }

                EtapeInstallationApk.ConfirmationSysteme -> {
                    EtapeExecutionApplication.ConfirmationSysteme
                }
            }

        /**
         * APK debug du projet, ou `null` (le build n'en a produit aucun —
         * chemin déterministe, contrat `verify-templates.sh`).
         */
        private fun localiserApkDebug(dossierProjet: File): File? =
            File(dossierProjet, CHEMIN_APK_DEBUG).takeIf { it.isFile }

        /**
         * Le projet possède-t-il un module application Android
         * (`app/build.gradle` ou `app/build.gradle.kts`) ? Décide du
         * comportement du bouton Exécuter : « Run » d'Android Studio
         * (compile → installe → lance) contre `gradle run` (JVM).
         * Lecture disque sur le dispatcheur E/S.
         */
        public suspend fun estModuleApplication(dossierProjet: File): Boolean =
            withContext(repartiteurs.io) {
                File(dossierProjet, SCRIPT_BUILD_GRADLE).isFile ||
                    File(dossierProjet, SCRIPT_BUILD_GRADLE_KTS).isFile
            }

        /** Nom de paquet depuis `output-metadata.json`, ou `null`. */
        private fun lireNomPaquet(dossierProjet: File): String? =
            File(dossierProjet, CHEMIN_METADONNEES)
                .takeIf { it.isFile }
                ?.let { fichier -> lireApplicationId(fichier.readText()) }

        private companion object {
            /** APK debug : chemin relatif à la racine du projet. */
            const val CHEMIN_APK_DEBUG = "app/build/outputs/apk/debug/app-debug.apk"

            /** Métadonnées AGP (applicationId, variante) du même dossier. */
            const val CHEMIN_METADONNEES = "app/build/outputs/apk/debug/output-metadata.json"

            /** Script de build Groovy du module application. */
            const val SCRIPT_BUILD_GRADLE = "app/build.gradle"

            /** Script de build Kotlin DSL du module application. */
            const val SCRIPT_BUILD_GRADLE_KTS = "app/build.gradle.kts"
        }
    }

/**
 * Étape du cycle « exécuter l'application » (progression typée).
 *
 * Les étapes d'installation reprises de [EtapeInstallationApk]
 * (autorisation, copie, confirmation système) y sont projetées par le
 * cas d'usage, plus le lancement.
 */
public sealed interface EtapeExecutionApplication {
    /**
     * L'autorisation « sources inconnues » manque : l'écran système
     * s'ouvre, l'installation reprendra automatiquement à son retour.
     */
    public data object AutorisationSourcesInconnuesRequise : EtapeExecutionApplication

    /** Copie de l'APK dans la session d'installation. */
    public data object CopieApk : EtapeExecutionApplication

    /** La boîte de confirmation du système est affichée. */
    public data object ConfirmationSysteme : EtapeExecutionApplication

    /** L'application vient d'être lancée ([nomPaquet]). */
    public data class ApplicationLancee(
        public val nomPaquet: String,
    ) : EtapeExecutionApplication
}

/**
 * Résultat du cycle « exécuter l'application » — chaque échec porte
 * son ACTION CORRECTIVE (ADR 0102 : jamais d'échec muet).
 */
public sealed interface ResultatExecutionApplication {
    /** Application installée ET lancée. */
    public data class Succes(
        public val nomPaquet: String,
    ) : ResultatExecutionApplication

    /** Échec typé (voir chaque issue). */
    public sealed interface Echec : ResultatExecutionApplication

    /** Le build n'a produit aucun APK (chemin déterministe absent). */
    public data object ApkAbsent : Echec

    /** `output-metadata.json` illisible : pas d'identifiant de paquet. */
    public data object MetadonneesIllisibles : Echec

    /** L'installation a échoué — issue d'origine typée + paquet visé. */
    public data class Installation(
        public val cause: ResultatInstallationApk.Echec,
        public val nomPaquet: String,
    ) : Echec

    /** Installée mais introuvable au lancement (après les relances). */
    public data class LancementIntrouvable(
        public val nomPaquet: String,
    ) : Echec
}

/**
 * Extrait l'`applicationId` des `output-metadata.json` d'AGP (analyseur
 * PUR — testé en JVM, aucun `Context` Android).
 *
 * Tolérant : document absent, JSON invalide, clé absente, ou valeur non
 * textuelle ⇒ `null` — l'appelant refuse honnêtement au lieu d'inventer
 * un identifiant.
 */
internal fun lireApplicationId(texte: String): String? =
    runCatching {
        (Json.parseToJsonElement(texte).jsonObject["applicationId"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
    }.getOrNull()
