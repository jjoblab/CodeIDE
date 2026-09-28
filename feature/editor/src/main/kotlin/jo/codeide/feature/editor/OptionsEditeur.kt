package jo.codeide.feature.editor

import jo.codeeditor.view.chrome.EditorTheme
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ObserveSettingsUseCase
import jo.codeide.core.model.AppSettings
import jo.codeide.core.model.TaillePoliceEditeur
import jo.codeide.core.model.ThemeEditeur
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Détenteur des réglages de l'éditeur consommés par l'espace de travail
 * (v0.37.0) — même patron que `OptionsTooling` (ADR 0059, ADR 0057) : une
 * seule collecte des paramètres applicatifs en tâche de fond, la dernière
 * valeur connue servie à la demande, sans coupler l'espace à un flux de
 * réglages. L'activité y collecte [reglages] pour appliquer chaque bascule
 * à `EditorView` **au fil de l'eau** (thème, zoom, retour à la ligne,
 * minimap, caractères non imprimables, ligatures, badges de diagnostic) ;
 * le ViewModel y lit la garde de la sauvegarde automatique.
 *
 * Classe pure testable : la collecte vit dans une portée interne bornée au
 * dispatcher injecté (règle 5), l'état exposé est un instantané immuable.
 * Les thèmes cel sont mis en cache par valeur (dix instances au plus —
 * `EditorTheme.dark()` & co allouent à chaque appel).
 *
 * @param observerReglages source des paramètres applicatifs.
 * @param dispatchers répartition des fils (collecte hors principal).
 */
@Singleton
class OptionsEditeur
    @Inject
    constructor(
        observerReglages: ObserveSettingsUseCase,
        dispatchers: DispatcherProvider,
    ) {
        /** Derniers réglages connus — émis à chaque changement persisté. */
        private val reglagesInterne = MutableStateFlow(AppSettings())

        /** Réglages observables — l'activité y applique les changements en direct. */
        val reglages: StateFlow<AppSettings> = reglagesInterne.asStateFlow()

        /**
         * Derniers réglages connus — lecture ponctuelle pour le ViewModel
         * (garde de la sauvegarde automatique) : un aperçu cohérent sans
         * collecte supplémentaire.
         */
        internal val courants: AppSettings
            get() = reglagesInterne.value

        /** Thèmes cel mis en cache — un par thème forcé, deux pour l'automatique. */
        private val themesCel = HashMap<ThemeEditeur, EditorTheme>()

        private val portee = CoroutineScope(SupervisorJob() + dispatchers.default)

        init {
            portee.launch {
                observerReglages().collect { reglagesInterne.value = it }
            }
        }

        /**
         * Facteur d'échelle de police pour [EditorView.setFontScale] (base
         * cel : 14 sp, bornes [0.6, 2.6]) — les trois tailles miroir de
         * celles du terminal.
         */
        internal fun facteurPolice(): Float =
            when (courants.editorTaillePolice) {
                TaillePoliceEditeur.PETITE -> ECHELLE_PETITE
                TaillePoliceEditeur.MOYENNE -> ECHELLE_MOYENNE
                TaillePoliceEditeur.GRANDE -> ECHELLE_GRANDE
            }

        /**
         * Thème cel correspondant au réglage : les neuf thèmes embarqués de
         * la bibliothèque, ou le couple clair/sombre de l'application en
         * mode automatique (le comportement d'avant v0.37.0).
         *
         * @param nuit l'application est-elle en mode sombre ?
         * @return le thème à appliquer (instance mise en cache).
         */
        internal fun themePour(nuit: Boolean): EditorTheme {
            val demande = courants.editorThemeEditeur
            if (demande == ThemeEditeur.AUTO) {
                return themeCel(if (nuit) ThemeEditeur.VSCODE_SOMBRE else ThemeEditeur.VSCODE_CLAIR)
            }
            return themeCel(demande)
        }

        /** Instance cel d'un thème, créée puis mise en cache. */
        private fun themeCel(theme: ThemeEditeur): EditorTheme =
            themesCel.getOrPut(theme) {
                when (theme) {
                    ThemeEditeur.AUTO, ThemeEditeur.VSCODE_SOMBRE -> EditorTheme.dark()
                    ThemeEditeur.VSCODE_CLAIR -> EditorTheme.light()
                    ThemeEditeur.DRACULA -> EditorTheme.dracula()
                    ThemeEditeur.ONE_DARK -> EditorTheme.oneDark()
                    ThemeEditeur.MONOKAI -> EditorTheme.monokai()
                    ThemeEditeur.SOLARIZED_SOMBRE -> EditorTheme.solarizedDark()
                    ThemeEditeur.GITHUB_CLAIR -> EditorTheme.gitHubLight()
                    ThemeEditeur.GITHUB_SOMBRE -> EditorTheme.gitHubDark()
                    ThemeEditeur.NORD -> EditorTheme.nord()
                }
            }

        private companion object {
            /** Échelles de police (base cel 14 sp : ~12, 14 et ~17 sp). */
            const val ECHELLE_PETITE = 0.85f
            const val ECHELLE_MOYENNE = 1.0f
            const val ECHELLE_GRANDE = 1.2f
        }
    }
