package jo.codeide.preview.xml

import android.content.Context
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Moteur de preview XML — PE1 → PE2 → PE3 (PREVIEW.md §3, P1–P6).
 *
 * Orchestration : debounce 400 ms (défaut — « RenderService adapté »),
 * single-flight par mutex, états publiés ([etat]) — le RENDU lui-même
 * est DÉLÉGUÉ à un [TransportRendu] (PE2, P3) :
 *  - par défaut [TransportIsolation] : processus `:preview` isolé
 *    (AIDL, DeathRecipient, UNE reprise puis repli local honnête —
 *    §6) ;
 *  - les tests injectent le transport local (le pipeline PE1 inchangé).
 *
 * PE3 : la demande est PARAMÉTRÉE ([RequeteRendu]) — genre (layout /
 * drawable / values), profil d'appareil, régime jour/nuit — chaque
 * changement de paramètre relance un rendu IMMÉDIAT (pas d'attente de
 * debounce : l'utilisateur a agi, la réponse doit suivre).
 *
 * Comportements repris de LayoutLib/Android Studio :
 * - XML malformé (frappe en cours) → rendu annulé **silencieusement**,
 *   le dernier rendu valide reste affiché ([EtatPreviewXml.EnEdition]) ;
 * - erreur sémantique (XML valide, tag inconnu…) → [EtatPreviewXml.ErreurRendu] ;
 * - texte vide/blanc → ignoré silencieusement.
 *
 * Ressources du projet (P5) : `res/` résolu par l'hôte (FUSE) via
 * [definirDossierRes], poussé au transport à CHAQUE rendu (le processus
 * :preview reconfigure ses résolveurs quand le dossier change).
 *
 * Cycle de vie : [close] coupe debounce, worker ET transport
 * (déliaison → le processus :preview meurt seul — idle-shutdown/I7).
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Suppress("TooManyFunctions") // Orchestration nommée ; l'éclater disperserait le single-flight.
public class MoteurPreviewXml(
    private val contexte: Context,
    private val dispatchers: DispatcherProvider,
    private val journal: AppLogger?,
    private val scope: CoroutineScope,
    transport: TransportRendu? = null,
) : AutoCloseable {
    private val etatInterne = MutableStateFlow<EtatPreviewXml>(EtatPreviewXml.Inactif)

    /** État courant du panneau (rendu, erreur, édition). */
    public val etat: StateFlow<EtatPreviewXml> = etatInterne.asStateFlow()

    /**
     * Transport de rendu — défaut : politique d'isolation PE2 (processus
     * :preview, repli local) ; les tests injectent le local.
     */
    private val transport: TransportRendu =
        transport ?: TransportIsolation(contexte = contexte, journal = journal, scope = scope)

    /** Régime de rendu courant (processus isolé / repli local honnête). */
    public val regime: StateFlow<RegimeRendu>
        get() = regimeTransport

    /**
     * RÉACTIVE le processus `:preview` (PE11i — bouton « Réessayer » du
     * bandeau, ADR 0134 D6) : la politique d'isolation repart du régime
     * nominal et le rendu courant est RELANCÉ (le prochain texte rendu
     * retente le processus isolé).
     */
    public fun reactiverIsolation() {
        (transport as? TransportIsolation)?.reactiver()
        relancerSiTexte()
    }

    private val regimeTransport: StateFlow<RegimeRendu> =
        (transport as? TransportIsolation)?.regime
            ?: MutableStateFlow(RegimeRendu.LOCAL_DEGRADE).asStateFlow()

    /** Soumissions debouncées — le texte est tiré À LA DEMANDE (jamais par frappe). */
    private val soumissions =
        MutableSharedFlow<() -> String>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var travailleur: Job? = null
    private val verrouRendu = Mutex()
    private var dossierRes: String? = null

    /** v0.133.1 — G2/G5 : thème activité (null = pas de chrome). */
    private var themeNameCourant: String? = null

    /**
     * v0.134.4 — label d'activité pour le TITRE de l'action bar du chrome
     * (`android:label` du manifest — activité, à défaut application).
     * Null = repli sur le themeName (comportement d'avant v0.134.4).
     */
    private var labelActiviteCourant: String? = null

    /** v0.133.1 — G1 : classpath projet pour vues custom (vide = repli). */
    private var classpathCourant: List<EntreeClasspath> = emptyList()
    private var ferme = false

    /** Genre du document actif (PE3 — dispatch du pipeline). */
    private var genreCourant: GenrePreview = GenrePreview.LAYOUT

    /** Profil d'appareil cible (PE3 — sélecteur de l'UI). */
    private var profilCourant: ProfilPeripherique = ProfilPeripherique.PAR_DEFAUT

    /** Régime nuit courant (PE3). */
    private var nuitCourant: Boolean = false

    /** Langue courante de l'aperçu (PE5a — null = défaut système). */
    private var langueCourante: String? = null

    /** Orientation paysage courante (PE6e-f — dimensions permutées + `values-land`). */
    private var paysageCourant: Boolean = false

    /** Échelle de police courante (PE6e-f — ×1,0 / ×1,3 / ×2,0 de la maquette). */
    private var echellePoliceCourante: Float = RequeteRendu.ECHELLE_POLICE_DEFAUT

    /** Barre de navigation du chrome système courante (PE11j — miroir `S.nav`, défaut ON). */
    private var barreNavigationCourante: Boolean = true

    /** Mode gestuel de la barre de navigation courant (PE14-S1 — pilule, défaut OFF). */
    private var modeGestuelCourant: Boolean = false

    /** Fournisseur de texte courant (dernier onglet branché). */
    private var fournisseurTexte: (() -> String)? = null

    init {
        travailleur =
            scope.launch {
                soumissions
                    .debounce(DEBOUNCE_MS)
                    .collect { fournisseur -> traiter(fournisseur) }
            }
    }

    /**
     * Soumet un texte à rendre (le fournisseur est invoqué APRÈS le
     * debounce, sur le fil du moteur : une frappe ne coûte aucune copie).
     */
    public fun soumettre(fournisseur: () -> String) {
        if (ferme) return
        fournisseurTexte = fournisseur
        soumissions.tryEmit(fournisseur)
    }

    /**
     * Soumet un rendu immédiat (ouverture du panneau, bascule d'onglet) :
     * PAS de debounce — mais TOUJOURS le mutex de rendu (single-flight).
     * v0.133.5 — NE PAS reset dernierTexteRendu : un changement d'onglet
     * peut fournir un nouveau fournisseur avec le même texte. L'anti-boucle
     * reste active. Le seul bypass légitime est parametresChanges (changement
     * de profil/nuit/etc.) géré par relancerSiTexte.
     */
    public fun soumettreImmediat(fournisseur: () -> String) {
        if (ferme) return
        fournisseurTexte = fournisseur
        scope.launch { traiter(fournisseur) }
    }

    /**
     * Définit le dossier `res/` du projet (P5 — chemin POSIX résolu par
     * l'hôte via FUSE). Null = plus de projet : repli ressources natives.
     */
    public fun definirDossierRes(chemin: String?) {
        dossierRes = chemin
        // v0.133.3 — reset du cache anti-boucle quand le contexte change
        // (nouveau projet → nouveau res/ → le même texte peut rendre différemment).
        dernierTexteRendu = null
    }

    /**
     * v0.133.1 — G2/G5 : Définit le thème activité pour la preview.
     * Null = pas de chrome système (status bar + action bar).
     * Un changement relance un rendu immédiat.
     */
    public fun definirThemeName(themeName: String?) {
        if (ferme || themeName == themeNameCourant) return
        themeNameCourant = themeName
        relancerSiTexte()
    }

    /**
     * v0.134.4 — Définit le label d'activité (titre de l'action bar du
     * chrome). Null = repli sur le themeName. Un changement relance un
     * rendu immédiat (miroir de [definirThemeName]).
     */
    public fun definirLabelActivite(label: String?) {
        if (ferme || label == labelActiviteCourant) return
        labelActiviteCourant = label
        relancerSiTexte()
    }

    /**
     * v0.133.1 — G1 : Définit le classpath projet pour les vues custom.
     * Vide = pas de vues custom (repli ViewFactory par défaut + MockView).
     * Un changement relance un rendu immédiat (le cache survit si
     * signature inchangée).
     */
    public fun definirClasspath(entries: List<EntreeClasspath>) {
        if (ferme || entries == classpathCourant) return
        classpathCourant = entries
        relancerSiTexte()
    }

    /**
     * Définit le genre du document actif (PE3) : un CHANGEMENT relance un
     * rendu immédiat (la vue doit refléter le nouveau document, pas
     * attendre la prochaine frappe).
     */
    public fun definirGenre(genre: GenrePreview?) {
        if (ferme) return
        if (genre == genreCourant) return
        genreCourant = genre ?: GenrePreview.LAYOUT
        relancerSiTexte()
    }

    /**
     * Définit le profil d'appareil cible (PE3 — sélecteur) : un
     * CHANGEMENT relance un rendu immédiat (nouvelles dimensions).
     */
    public fun definirProfil(profil: ProfilPeripherique) {
        if (ferme || profil == profilCourant) return
        profilCourant = profil
        relancerSiTexte()
    }

    /**
     * Bascule le régime jour/nuit (PE3) : un CHANGEMENT relance un rendu
     * immédiat (thèmes et ressources `values-night`).
     */
    public fun definirNuit(nuit: Boolean) {
        if (ferme || nuit == nuitCourant) return
        nuitCourant = nuit
        relancerSiTexte()
    }

    /** Langue courante de l'aperçu (PE5a — null = défaut système). */
    public val langue: String? get() = langueCourante

    /** Définit la langue de l'aperçu (PE5a — LayoutLib v3.0.0 P1 résolu) :
     *  `null` = défaut système, `"fr"` = français, `"en"` = anglais.
     *  Un CHANGEMENT relance un rendu immédiat (les chaînes
     *  `values-fr` / `values-en` sont basculées par
     *  `ResourceResolverImpl.setLanguage()`). */
    public fun definirLangue(langue: String?) {
        if (ferme || langue == langueCourante) return
        langueCourante = langue
        relancerSiTexte()
    }

    /** Orientation paysage courante (PE6e-f). */
    public val paysage: Boolean get() = paysageCourant

    /** Échelle de police courante (PE6e-f). */
    public val echellePolice: Float get() = echellePoliceCourante

    /** Barre de navigation du chrome système courante (PE11j — menu Calques, groupe « Chrome système »). */
    public val barreNavigation: Boolean get() = barreNavigationCourante

    /** Mode gestuel de la barre de navigation courant (PE14-S1 — menu Calques, groupe « Chrome système »). */
    public val modeGestuel: Boolean get() = modeGestuelCourant

    /** Définit l'orientation de l'aperçu (PE6e-f — puce Portrait/Paysage de
     *  la maquette) : un CHANGEMENT relance un rendu immédiat (dimensions
     *  du profil permutées + qualifieur `values-land`). */
    public fun definirPaysage(paysage: Boolean) {
        if (ferme || paysage == paysageCourant) return
        paysageCourant = paysage
        relancerSiTexte()
    }

    /** Définit l'échelle de police de l'aperçu (PE6e-f — puce « Police ×n »
     *  de la maquette) : un CHANGEMENT relance un rendu immédiat (le
     *  `scaledDensity` du moteur et du convertisseur suivent). Valeurs
     *  hors borne (≤ 0) refusées silencieusement — la puce ne propose que
     *  des échelles valides, le contrat reste défensif. */
    public fun definirEchellePolice(echelle: Float) {
        if (ferme || echelle <= 0f || echelle == echellePoliceCourante) return
        echellePoliceCourante = echelle
        relancerSiTexte()
    }

    /** Définit la barre de navigation du chrome système (PE11j — miroir
     *  `S.nav` de la maquette, interrupteur « Barre de navigation » du
     *  menu Calques) : un CHANGEMENT relance un rendu immédiat (la barre
     *  de 48 dp est ajoutée ou retirée sous le contenu par le chrome). */
    public fun definirBarreNavigation(barre: Boolean) {
        if (ferme || barre == barreNavigationCourante) return
        barreNavigationCourante = barre
        relancerSiTexte()
    }

    /** Définit le MODE GESTUEL de la barre de navigation (PE14-S1, ADR
     *  0139 — interrupteur « Mode gestuel » du menu Calques) : un
     *  CHANGEMENT relance un rendu immédiat (la zone de 48 dp passe des
     *  trois glyphes à la PILULE gestuelle — la hauteur ne bouge pas,
     *  miroir ADR 0137 D6). */
    public fun definirModeGestuel(gestuel: Boolean) {
        if (ferme || gestuel == modeGestuelCourant) return
        modeGestuelCourant = gestuel
        relancerSiTexte()
    }

    /** Profil courant (observable par l'UI pour libeller le sélecteur). */
    public val profil: ProfilPeripherique get() = profilCourant

    /** Régime nuit courant (observable par l'UI pour libeller le bouton). */
    public val nuit: Boolean get() = nuitCourant

    /** Coupe debounce, worker et transport (déliaison :preview). Idempotent. */
    public override fun close() {
        if (ferme) return
        ferme = true
        travailleur?.cancel()
        travailleur = null
        transport.close()
    }

    /** Relance un rendu immédiat si un texte est branché (changement de paramètre).
     *  v0.133.5 — NE PAS reset dernierTexteRendu : on pose le flag
     *  parametresChanges à la place. L'anti-boucle bypassera le check
     *  si le flag est posé, puis le reset. */
    private fun relancerSiTexte() {
        val fournisseur = fournisseurTexte ?: return
        parametresChanges = true
        soumettreImmediat(fournisseur)
    }

    /** v0.133.3 — anti-boucle : hash du dernier texte rendu (évite de
     *  re-rendre le même texte si relancerSiTexte est appelé en boucle). */
    private var dernierTexteRendu: String? = null

    /** v0.133.5 — flag pour bypasser l'anti-boucle quand les paramètres ont
     *  changé (profil, nuit, langue, thème, classpath). Reset après usage. */
    private var parametresChanges = false

    @Suppress("ReturnCount") // v0.133.3 — anti-boucle + 4 gardes = 4 issues naturelles
    private suspend fun traiter(fournisseur: () -> String) {
        verrouRendu.withLock {
            val texte = withContext(dispatchers.default) { fournisseur() }
            if (texte.isBlank()) {
                journal?.w(TAG_PREVIEW) { "rendu annulé : texte vide" }
                return
            }
            // v0.133.5 — anti-boucle : skip si le texte est IDENTIQUE au
            // dernier rendu ET les paramètres n'ont pas changé. Le flag
            // parametresChanges est posé par relancerSiTexte (changement
            // de profil/nuit/etc.) et consommé ici.
            if (texte == dernierTexteRendu && !parametresChanges) {
                journal?.d(TAG_PREVIEW) { "rendu annulé : texte inchangé, rendu précédent conservé" }
                return
            }
            parametresChanges = false
            if (!jo.codeide.preview.xml
                    .xmlBienForme(texte)
            ) {
                // Frappe en cours : signal discret, le rendu précédent reste.
                journal?.d(TAG_PREVIEW) { "rendu annulé : XML malformé (frappe en cours)" }
                if (etatInterne.value !is EtatPreviewXml.RenduValide) {
                    etatInterne.value = EtatPreviewXml.EnEdition
                }
                return
            }
            dernierTexteRendu = texte
            val requete =
                RequeteRendu(
                    texte = texte,
                    dossierRes = dossierRes,
                    genre = genreCourant,
                    profil = profilCourant,
                    nuit = nuitCourant,
                    langue = langueCourante,
                    themeName = themeNameCourant,
                    labelActivite = labelActiviteCourant,
                    classpath = classpathCourant,
                    paysage = paysageCourant,
                    echellePolice = echellePoliceCourante,
                    barreNavigation = barreNavigationCourante,
                    modeGestuel = modeGestuelCourant,
                )
            journal?.i(TAG_PREVIEW) {
                "rendu soumis : genre=$genreCourant, texte=${texte.length}c, " +
                    "profil=$profilCourant, nuit=$nuitCourant, " +
                    "res=${dossierRes ?: "null"}"
            }
            val resultat = withContext(dispatchers.default) { transport.rendre(requete) }
            when (resultat) {
                is ResultatTransport.Succes -> {
                    journal?.i(TAG_PREVIEW) {
                        "rendu OK : ${resultat.largeur}x${resultat.hauteur}, " +
                            "${resultat.nombreVues} vue(s), ${resultat.dureeMs}ms"
                    }
                }

                is ResultatTransport.Erreur -> {
                    journal?.w(TAG_PREVIEW) { "rendu ÉCHEC : ${resultat.message}" }
                }
            }
            etatInterne.value = etatDe(resultat)
        }
    }

    /** Traduit un résultat de transport en état du panneau. */
    private fun etatDe(resultat: ResultatTransport): EtatPreviewXml = when (resultat) {
        is ResultatTransport.Succes -> {
            EtatPreviewXml.RenduValide(
                bitmap = resultat.bitmap,
                dureeMs = resultat.dureeMs,
                nombreVues = resultat.nombreVues,
                largeur = resultat.largeur,
                hauteur = resultat.hauteur,
                vues = resultat.vues,
                vuesSysteme = resultat.vuesSysteme,
            )
        }

        is ResultatTransport.Erreur -> {
            EtatPreviewXml.ErreurRendu(resultat.message, resultat.ligne)
        }
    }

    private companion object {
        private const val DEBOUNCE_MS = 400L
        private const val TAG_PREVIEW = "MoteurPreviewXml"
    }
}
