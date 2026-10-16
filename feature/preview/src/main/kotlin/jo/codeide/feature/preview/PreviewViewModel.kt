package jo.codeide.feature.preview

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.RegistreSourcesApercu
import jo.codeide.core.domain.ResoudreRepertoireProjet
import jo.codeide.core.domain.SourceApercu
import jo.codeide.preview.xml.ClassificateurPreview
import jo.codeide.preview.xml.MoteurPreviewXml
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de l'écran d'aperçu XML (PE6-0, maquette
 * `docs/mockups/apercu-xml.html` § « Activité dédiée ») : le MOTEUR et
 * son dernier rendu vivent ICI (piège « rotation » de la maquette —
 * chaque rotation ne relance PAS une inflation LayoutLib), la session
 * d'édition reste celle du `EditorViewModel` de `:feature:editor` —
 * consommée par le port [SourceApercu] du registre singleton.
 *
 * Pièges traités (maquette § « Pièges à traiter ») :
 * - **mort du processus** : le registre est VIDE au retour (Android ne
 *   recrée que l'activité du dessus) → [finir] passe à `true`, l'écran
 *   se termine et relance l'éditeur — jamais de panneau vide ;
 * - **rotation** : moteur + état ici, l'activité se rebranche ;
 * - **changement de fichier** : le titre devient un sélecteur des
 *   layouts ouverts — [sourcesPreviewables] (registre) + [ouvrirFichier].
 *
 * Le sens inverse de la sélection (éditeur → preview) DISPARAÎT avec
 * l'overlay : l'éditeur n'est plus visible — la feuille source viendra
 * en PE6d.
 *
 * Exemptions detekt ciblées (règle 16) : TooManyFunctions — ViewModel de
 * COUTURE (brancher la source, suivre le projet, sélecteur de fichier,
 * réglages du moteur, relance éditeur : chaque face a SA méthode,
 * précédent CoordonnateurLsp) ; ReturnCount de [resoudreLabelActivite] —
 * chaque garde (activité → application) est une issue DISTINCTE du
 * contrat (reprise telle quelle du contrôleur PE1).
 */
@Suppress("TooManyFunctions")
@HiltViewModel
public class PreviewViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val contexte: Context,
        private val dispatchers: DispatcherProvider,
        private val journal: AppLogger,
        private val registre: RegistreSourcesApercu,
        private val resolveurChemin: ResoudreRepertoireProjet,
        sauvegarde: SavedStateHandle,
    ) : ViewModel() {
        /** Identifiant du projet (piège « mort du processus » : relance de l'éditeur). */
        public val projectId: String = sauvegarde.get<String>(CLE_PROJET) ?: ""

        /** URI du document demandé (extra de [jo.codeide.core.ui.AppNavigator.openPreview]). */
        public val uriInitial: String = sauvegarde.get<String>(CLE_URI) ?: ""

        /** Le moteur de rendu — vit HORS de l'activité (rotation sûre). */
        val moteur =
            MoteurPreviewXml(
                contexte = contexte,
                dispatchers = dispatchers,
                journal = journal,
                scope = viewModelScope,
            )

        /** La source branchée (null = aucune — panneau sans session). */
        public var source: SourceApercu? = null
            private set

        /** Effet « se terminer et relancer l'éditeur » (piège mort du processus). */
        private val finirInterne = MutableStateFlow(false)

        /** L'écran doit se terminer (source disparue) — collecté une fois par l'activité. */
        public val finir: StateFlow<Boolean> = finirInterne.asStateFlow()

        /** URI du fichier affiché (titre + sélecteur). */
        public var uriCourante: String? = null
            private set

        /** Écouteur de mutation branché à la source courante (détaché au changement). */
        private var ecouteur: SourceApercu.EcouteurMutation? = null

        /** Job du suivi projet (résolution FUSE suspendante — annulé au changement). */
        private var suiviProjet: kotlinx.coroutines.Job? = null

        init {
            brancher(uriInitial)
        }

        /**
         * Branche la source d'un document (initial ou sélecteur) :
         * délie l'ancienne, suit le projet, classifie, rend immédiatement.
         */
        public fun ouvrirFichier(uri: String) {
            if (uri == uriCourante && source != null) return
            brancher(uri)
        }

        /** Les documents OUVERTS (registre) qui se laissent prévisualiser — le sélecteur de titre. */
        public fun sourcesPreviewables(): List<SourceApercu> =
            registre
                .sources()
                .filter { source -> ClassificateurPreview.genreDe(source.cheminRelatif, source.texte()) != null }

        /** Le nom affichable du fichier courant (dernier segment du chemin relatif). */
        public fun nomFichierCourant(): String = source?.cheminRelatif?.substringAfterLast('/') ?: ""

        /** Tap sur le rendu → le SPAN source est posé DANS la session (retour éditeur : sélection active). */
        public fun definirSelection(
            debut: Int,
            fin: Int,
        ) {
            source?.definirSelection(debut, fin)
        }

        override fun onCleared() {
            delierSource()
            suiviProjet?.cancel()
            moteur.close()
        }

        /** Branche la source [uri] : déliaison, écoute de mutation, projet, rendu immédiat. */
        private fun brancher(uri: String) {
            val nouvelleSource = registre.sourceDe(uri)
            if (nouvelleSource == null) {
                // Piège « mort du processus » : Android ne recrée que
                // l'activité du dessus — le registre est vide. L'écran se
                // termine et relance l'éditeur (jamais de panneau vide).
                journal.w(TAG) { "source absente du registre (processus mort ?) — fin d'écran : $uri" }
                finirInterne.value = true
                return
            }
            delierSource()
            source = nouvelleSource
            uriCourante = uri
            val ecouteurNouveau =
                SourceApercu.EcouteurMutation {
                    // Fournisseur paresseux : le texte n'est tiré qu'après debounce.
                    moteur.soumettre { nouvelleSource.texte() }
                }
            ecouteur = ecouteurNouveau
            nouvelleSource.ajouterEcouteur(ecouteurNouveau)
            suivreProjet(nouvelleSource)
            moteur.definirGenre(ClassificateurPreview.genreDe(nouvelleSource.cheminRelatif, nouvelleSource.texte()))
            moteur.soumettreImmediat { nouvelleSource.texte() }
        }

        /** Délie l'écouteur de la source courante (changement de fichier, onCleared). */
        private fun delierSource() {
            val ancienne = source
            val ancienEcouteur = ecouteur
            if (ancienne != null && ancienEcouteur != null) {
                ancienne.retirerEcouteur(ancienEcouteur)
            }
            ecouteur = null
        }

        /**
         * Suit le projet porteur (PE5/G2/G5, repris du contrôleur PE1) :
         * `res/` résolu FUSE, thème activité et label du manifest — le
         * rendu reprend le chrome du vrai appareil.
         */
        private fun suivreProjet(sourceBranchee: SourceApercu) {
            suiviProjet?.cancel()
            val uriProjet = sourceBranchee.uriProjet
            if (uriProjet == null) {
                // Pas de projet connu : repli ressources natives (miroir PE1).
                moteur.definirDossierRes(null)
                moteur.definirThemeName(null)
                moteur.definirLabelActivite(null)
                return
            }
            suiviProjet =
                viewModelScope.launch {
                    val racine = resolveurChemin.resoudreEntree(uriProjet)
                    val cheminRes = racine?.let { "${it.trimEnd('/')}/res" }
                    moteur.definirDossierRes(cheminRes)
                    val manifeste = racine?.let { lireManifeste(it) }
                    moteur.definirThemeName(manifeste?.let { resoudreThemeActivite(it) })
                    moteur.definirLabelActivite(manifeste?.let { resoudreLabelActivite(it) })
                }
        }

        /** Lit le manifest d'application (chemins standards) — null si absent/illisible. */
        private fun lireManifeste(racineProjet: String): String? {
            val manifeste = trouverManifeste(racineProjet) ?: return null
            return try {
                java.io.File(manifeste).readText()
            } catch (_: Throwable) {
                null
            }
        }

        /** Titre de l'action bar : `android:label` de la PREMIÈRE `<activity>` (à défaut `<application>`). */
        @Suppress("ReturnCount") // Chaque garde = une issue distincte du contrat (miroir PE1).
        private fun resoudreLabelActivite(contenu: String): String? {
            val regexLabel = Regex("""android:label\s*=\s*"([^"]+)"""")
            Regex("""<activity\b[^>]*""").find(contenu)?.let { balise ->
                regexLabel.find(balise.value)?.let { return it.groupValues[1] }
            }
            Regex("""<application\b[^>]*""").find(contenu)?.let { balise ->
                regexLabel.find(balise.value)?.let { return it.groupValues[1] }
            }
            return null
        }

        /** Thème activité : `android:theme` de la balise ouvrante (application). */
        private fun resoudreThemeActivite(contenu: String): String? =
            Regex("""android:theme\s*=\s*"(@style/[^"]+|@android:style/[^"]+)"""")
                .find(contenu)
                ?.groupValues
                ?.get(1)

        /** Cherche AndroidManifest.xml dans les chemins standards du projet. */
        private fun trouverManifeste(racineProjet: String): String? {
            val base = racineProjet.trimEnd('/')
            val candidats =
                listOf(
                    "$base/app/src/main/AndroidManifest.xml",
                    "$base/src/main/AndroidManifest.xml",
                    "$base/AndroidManifest.xml",
                    "$base/../AndroidManifest.xml",
                )
            return candidats.firstOrNull { java.io.File(it).isFile }
        }

        /** Extras d'intention (Publics : l'implémentation du navigateur pose les extras.) */
        public companion object {
            private const val TAG: String = "PreviewViewModel"

            /** Extra d'intention : identifiant du projet (relance de l'éditeur). */
            public const val CLE_PROJET: String = "jo.codeide.preview.PROJECT_ID"

            /** Extra d'intention : URI du document à prévisualiser. */
            public const val CLE_URI: String = "jo.codeide.preview.URI"
        }
    }
