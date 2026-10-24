package jo.codeide.preview.compose

import android.content.Context
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.lspkotlin.api.PreviewKotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
 * Moteur de preview Compose (C1 — PREVIEW.md §4, miroir assumé de
 * [MoteurPreviewXml] du frère XML avec DEUX divergences de
 * conception) :
 *
 *  - la DÉCOUVERTE est INJECTÉE : le moteur ne connaît PAS le LSP —
 *    chaque soumission porte l'URI du document et un FOURNISSEUR
 *    paresseux de previews (`@Preview @Composable` + arbre d'appels
 *    neutre, frontière D6 du pack codeide-lsp 1.4.0). Le fournisseur
 *    est invoqué APRÈS le debounce, sur le fil du moteur : une frappe
 *    ne coûte AUCUN parse ;
 *  - le rendu est TOUJOURS une session PERSISTANTE du processus
 *    `:preview` : le programme converti ([ConvertisseurProgramme]) est
 *    POUSSÉ dans la composition vivante de l'identité
 *    (`uri#nom#index`) — c'est la RECOMPOSITION Compose qui diff, les
 *    `remember` du registre survivent aux éditions (live-edit par
 *    diff d'identité, PC1 — « re-render sans re-inflation » de la
 *    référence).
 *
 * Anti-boucle par DIGESTE : chaque programme porte son empreinte
 * SHA-256 (ProgrammeCompose.digesteDe) ; un rendu dont le digeste ET
 * les paramètres (profil, nuit) sont INCHANGÉS réutilise le rendu
 * précédent — zéro appel Binder, zéro recomposition.
 *
 * C3 — HORLOGE INTERACTIVE ([definirInteractif] + [transmettreTouch],
 * écho `RealTimeSessionClock` + `triggerTouchEvent` de la référence,
 * PREVIEW.md §4.4) : tant que la carte est OUVERTE sur un onglet
 * rendu, un POMPIER appelle le rendu à cadence ADAPTATIVE (cible
 * 30 i/s, paliers de dégradation honnêtes quand la frame déborde, écho
 * `DefaultRenderQualityPolicy`) — chaque appel RE-POUSSE le même
 * programme (zéro recomposition : seule l'HORLOGE de la session
 * avance — [BroadcastFrameClock] reçoit une frame au TIMESTAMP RÉEL)
 * et rapporte le bitmap AVANCÉ. Le modèle est PULL (l'app tire ses
 * frames) : la backpressure est NATURELLE (le prochain appel attend
 * le retour du précédent), l'arrêt est GRATUIT (carte fermée = plus
 * d'appels, les animations se figent), et un rendu d'ÉDITION passe
 * TOUJOURS d'abord (le pompier cède le verrou par tryLock — le
 * live-edit ne se laisse jamais doubler par l'animation).
 *
 * Orchestration (miroir du frère) : debounce 400 ms, single-flight
 * par mutex, états publiés ([etat]), regime publié ([regime] —
 * AUCUN repli local : l'indisponibilité du processus est un ÉTAT
 * TYPIQUE, voir [IsolationCompose]).
 *
 * Cycle de vie : [close] coupe debounce, worker, pompier interactif
 * ET transport (déliaison → le processus :preview meurt seul —
 * idle-shutdown).
 */
@Suppress("TooManyFunctions") // Contrat miroir du frère XML : un setter par paramètre de rendu
@OptIn(kotlinx.coroutines.FlowPreview::class)
public class MoteurPreviewCompose(
    contexte: Context,
    private val dispatchers: DispatcherProvider,
    private val journal: AppLogger?,
    private val scope: CoroutineScope,
    transport: TransportCompose? = null,
) : AutoCloseable {
    private val etatInterne = MutableStateFlow<EtatPreviewCompose>(EtatPreviewCompose.Inactif)

    /** État courant (rendus par preview — l'indice = l'onglet de la carte). */
    public val etat: StateFlow<EtatPreviewCompose> = etatInterne.asStateFlow()

    /**
     * Transport de rendu — défaut : politique d'isolation Compose
     * (processus :preview, UNE reprise, puis état typé INDISPONIBLE) ;
     * les tests injectent un faux.
     */
    private val transport: TransportCompose =
        transport ?: IsolationCompose(contexte = contexte, journal = journal, scope = scope)

    /** Régime de rendu courant (processus isolé / indisponible). */
    public val regime: StateFlow<RegimeCompose>
        get() = regimeTransport

    private val regimeTransport: StateFlow<RegimeCompose> =
        (this.transport as? IsolationCompose)?.regime
            ?: MutableStateFlow(RegimeCompose.PROCESSUS_ISOLE).asStateFlow()

    /**
     * RÉACTIVE le processus `:preview` (bouton « Réessayer » — la
     * carte appelle au RÉ-OUVERTURE, geste utilisateur = nouvelle
     * tentative) : la politique repart du régime nominal et le rendu
     * courant est RELANCÉ.
     */
    public fun reactiverIsolation() {
        (transport as? IsolationCompose)?.reactiver()
        relancerSiPreviews()
    }

    /** Soumissions debouncées — les previews sont tirées À LA DEMANDE. */
    private val soumissions =
        MutableSharedFlow<suspend () -> Unit>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private var travailleur: Job? = null
    private val verrouRendu = Mutex()

    /** Profil d'appareil courant (C1 : fixé par l'hôte, pas de sélecteur). */
    private var profilCourant: ProfilApercuCompose = ProfilApercuCompose.PAR_DEFAUT

    /** Régime nuit courant (thème Material clair/sombre du rendu). */
    private var nuitCourante: Boolean = false

    /** C2 — le PROJET courant : dossier `res/` du rendu (null = défauts runtime). */
    private var dossierResCourant: String? = null

    /** C2 — la langue du rendu (`values-<lang>/`, null = défaut). */
    private var langueCourante: String? = null

    /** C2 — le thème d'activité du manifeste (null = chaîne par défaut du résolveur). */
    private var themeCourant: String? = null

    /** Document branché : (uri, fournisseur paresseux de previews). */
    private var documentCourant: Document? = null

    /** Anti-boucle : derniers rendus par identité (reportés en cas de skip). */
    private val derniersRendus = LinkedHashMap<String, RenduPreviewCompose>()

    /** Anti-boucle : derniers digestes par identité. */
    private val derniersDigestes = HashMap<String, String>()

    /**
     * C3 — dernières REQUÊTES par identité : le pompier interactif
     * re-rend le MÊME programme (l'horloge avance, les frames suivent)
     * sans repasser par la conversion ni le diff de digeste.
     */
    private val dernieresRequetes = HashMap<String, RequeteRenduCompose>()

    /** C3 — la session interactive courante (null = carte fermée). */
    private var identiteInteractive: String? = null

    /** C3 — le pompier interactif (frames à cadence adaptative). */
    private var pompeInteractive: Job? = null

    /** v0.133.5 du frère — flag de re-rendu complet (paramètres changés). */
    private var parametresChanges = false

    private var ferme = false

    private class Document(val uri: String, val fournisseur: () -> List<PreviewKotlin>)

    init {
        travailleur =
            scope.launch {
                soumissions
                    .debounce(DEBOUNCE_MS)
                    .collect { action -> action() }
            }
    }

    /**
     * Branche un document (changement d'onglet) : le moteur re-rend
     * IMMÉDIATEMENT (pas d'attente de debounce — l'utilisateur a changé
     * de fichier, la carte doit se vider de l'ancien contenu sans
     * tarder), les frappes suivantes passent par [soumettre]
     * (debounce).
     *
     * Le fournisseur est invoqué sur le fil du moteur, APRÈS le
     * debounce — il PEUT parser (coût différé, jamais sur l'UI).
     */
    public fun brancher(uri: String, fournisseur: () -> List<PreviewKotlin>) {
        if (ferme) return
        documentCourant = Document(uri, fournisseur)
        // Nouveau document : les rendus de l'ancien ne se REPORTENT
        // plus (les identités changent), l'anti-boucle repart propre.
        derniersRendus.clear()
        derniersDigestes.clear()
        soumettreImmediat()
    }

    /** Re-soumet le document branché (le contenu a changé — debounce). */
    public fun soumettre() {
        if (ferme) return
        documentCourant ?: return
        soumissions.tryEmit(::traiter)
    }

    /** Re-soumet SANS debounce (branchement, « Réessayer », paramètres). */
    public fun soumettreImmediat() {
        if (ferme) return
        documentCourant ?: return
        scope.launch { traiter() }
    }

    /**
     * Définit le profil d'appareil du rendu : un CHANGEMENT relance un
     * rendu complet (les dimensions du canevas changent).
     */
    public fun definirProfil(profil: ProfilApercuCompose) {
        if (ferme || profil == profilCourant) return
        profilCourant = profil
        relancerSiPreviews()
    }

    /**
     * Bascule le régime jour/nuit du rendu (thème Material sombre) :
     * un CHANGEMENT relance un rendu complet (la session vivante
     * re-thème par recomposition).
     */
    public fun definirNuit(nuit: Boolean) {
        if (ferme || nuit == nuitCourante) return
        nuitCourante = nuit
        relancerSiPreviews()
    }

    /**
     * C2 — Définit le PROJET du rendu : dossier `res/` (ressources et
     * thème de la composition, miroir `definirDossierRes` du frère
     * XML), langue et thème d'activité. Un CHANGEMENT relance un rendu
     * complet (le schéma, les chaînes et les dimensions du projet
     * suivent — la session vivante se re-thème par recomposition).
     * `null` sur les trois = retour aux DÉFAUTS DU RUNTIME (C1).
     */
    public fun definirProjet(dossierRes: String?, langue: String? = null, themeName: String? = null) {
        if (ferme) return
        if (dossierRes == dossierResCourant &&
            langue == langueCourante &&
            themeName == themeCourant
        ) {
            return
        }
        dossierResCourant = dossierRes
        langueCourante = langue
        themeCourant = themeName
        relancerSiPreviews()
    }

    /** Coupe debounce, worker, pompier interactif et transport (déliaison :preview). Idempotent. */
    public override fun close() {
        if (ferme) return
        ferme = true
        identiteInteractive = null
        pompeInteractive?.cancel()
        pompeInteractive = null
        travailleur?.cancel()
        travailleur = null
        transport.close()
    }

    /**
     * C3 — HORLOGE INTERACTIVE : arme (ou éteint) le pompier de
     * l'ONGLET affiché de la carte. `identite` non nulle = la carte est
     * OUVERTE sur cet onglet RENDU → frames à cadence adaptative (la
     * requête du DERNIER rendu est rejouée : l'horloge de la session
     * avance, les animations tournent) ; `null` = carte fermée → le
     * pompier s'arrête, les animations se FIGENT (jamais d'arrière-fond).
     *
     * Idempotent : re-brancher la MÊME identité alors que le pompier
     * vit est un NO-OP (le pompier suit les requêtes à la volée via
     * [dernieresRequetes]) ; re-brancher une identité dont le pompier
     * est MORT (arrêt sur erreurs, « Réessayer ») le REPART.
     */
    public fun definirInteractif(identite: String?, onglet: Int) {
        when {
            ferme -> {}

            identite == null -> {
                // Carte fermée : le pompier s'arrête, les animations se
                // FIGENT (jamais d'arrière-fond).
                identiteInteractive = null
                pompeInteractive?.cancel()
                pompeInteractive = null
            }

            identite == identiteInteractive && pompeInteractive?.isActive == true -> {}

            else -> {
                identiteInteractive = identite
                pompeInteractive?.cancel()
                pompeInteractive = scope.launch { pomper(identite, onglet) }
            }
        }
    }

    /**
     * C3 — FORWARDING DES ENTRÉES : pousse UNE touche du premier doigt
     * à la session interactive courante (no-op honnête sans session —
     * la carte ne transmet que ce qu'elle a armé). Le CONTRAT voyage
     * ENTIER (action, point en px bitmap, horodatages, pression) ; le
     * relais est ASYNCHRONE (le doigt n'attend jamais Binder — la frame
     * suivante du pompier montre l'effet, ≤ 33 ms plus tard).
     */
    public fun transmettreTouch(evenement: EvenementTactileCompose) {
        if (ferme) return
        val identite = identiteInteractive ?: return
        scope.launch { transport.transmettre(identite, evenement) }
    }

    /** Relance un rendu immédiat si un document est branché (changement de paramètre). */
    private fun relancerSiPreviews() {
        if (documentCourant == null) return
        parametresChanges = true
        soumettreImmediat()
    }

    /**
     * Le rendu d'un document : découvertes → conversions → rendus
     * (session persistante par identité), single-flight par mutex.
     *
     * Exemption detekt ciblée (règle 16) : ReturnCount — les gardes
     * (fermé, sans document, sans preview) sont des ISSUES naturelles
     * distinctes, même patron que le frère XML.
     */
    @Suppress("ReturnCount")
    private suspend fun traiter() {
        verrouRendu.withLock {
            val document = documentCourant ?: return
            val reRendreTout = parametresChanges
            parametresChanges = false
            val previews =
                try {
                    withContext(dispatchers.default) { document.fournisseur() }
                } catch (t: Throwable) {
                    // Défensif : la façade LSP n'échoue jamais, mais le
                    // moteur ne doit PAS mourir d'un fournisseur hostile.
                    journal?.w(TAG) { "fournisseur de previews échoué : ${t.javaClass.simpleName}" }
                    etatInterne.value = EtatPreviewCompose.ErreurRendu(t.message ?: "découverte échouée")
                    return
                }
            if (previews.isEmpty()) {
                // Frappe en cours ou fichier sans @Preview : signal
                // discret, les DERNIERS rendus restent (contrat).
                journal?.d(TAG) { "aucune preview découverte (${document.uri})" }
                if (etatInterne.value !is EtatPreviewCompose.RenduValide) {
                    etatInterne.value = EtatPreviewCompose.EnEdition
                }
                return
            }
            val rendus =
                previews.mapIndexed { index, preview ->
                    renduUnePreview(document.uri, preview, index, reRendreTout)
                }
            journal?.i(TAG) {
                "rendu compose : ${rendus.size} preview(s), " +
                    "${rendus.count { it.bitmap != null }} bitmap(s), profil=$profilCourant, nuit=$nuitCourante"
            }
            etatInterne.value = EtatPreviewCompose.RenduValide(rendus)
        }
    }

    /** Le rendu d'UNE preview : conversion → diff d'identité → transport. */
    private suspend fun renduUnePreview(
        uri: String,
        preview: PreviewKotlin,
        index: Int,
        reRendreTout: Boolean,
    ): RenduPreviewCompose {
        val identite = RequeteRenduCompose.identiteDe(uri, preview.nom, index)
        val (programme, erreursConversion) =
            withContext(dispatchers.default) { ConvertisseurProgramme.convertir(preview) }
        // Diff d'identité (PC1) : digeste inchangé + paramètres
        // inchangés + rendu précédent DISPONIBLE → rien à pousser, la
        // composition vivante garde son état (zéro appel Binder).
        val precedent = derniersRendus[identite]
        if (!reRendreTout &&
            precedent?.bitmap != null &&
            derniersDigestes[identite] == programme.digeste
        ) {
            return precedent
        }
        derniersDigestes[identite] = programme.digeste
        val requete =
            RequeteRenduCompose(
                identite = identite,
                programme = programme,
                largeurDp = profilCourant.largeurDp,
                hauteurDp = profilCourant.hauteurDp,
                densiteDpi = profilCourant.densiteDpi,
                nuit = nuitCourante,
                dossierRes = dossierResCourant,
                langue = langueCourante,
                themeName = themeCourant,
            )
        dernieresRequetes[identite] = requete
        val rendu: RenduPreviewCompose =
            when (val resultat = withContext(dispatchers.default) { transport.rendre(requete) }) {
                is ResultatTransportCompose.Succes -> {
                    RenduPreviewCompose(
                        nom = preview.nom,
                        index = index,
                        bitmap = resultat.bitmap,
                        metriques = resultat.metriques,
                        erreurs = erreursConversion + resultat.erreurs,
                        noeuds = resultat.noeuds,
                        messageErreur = null,
                    )
                }

                is ResultatTransportCompose.Erreur -> {
                    RenduPreviewCompose(
                        nom = preview.nom,
                        index = index,
                        bitmap = null,
                        metriques = MetriquesRenduCompose.NULLES,
                        erreurs = erreursConversion + resultat.erreurs,
                        noeuds = emptyList(),
                        messageErreur = resultat.message,
                    )
                }
            }
        derniersRendus[identite] = rendu
        return rendu
    }

    /**
     * C3 — LE POMPIER INTERACTIF : rejoue la requête du DERNIER rendu de
     * l'identité à cadence ADAPTATIVE. Chaque itération CÈDE d'abord le
     * pas aux rendus d'ÉDITION (tryLock : le live-edit passe toujours
     * en premier, l'animation attend) puis re-pousse le programme — la
     * session :preview fait avancer son horloge (frame au timestamp
     * RÉEL), l'animation tourne, le bitmap AVANCÉ remplace celui de
     * l'onglet dans l'état. TROIS frames échouées de suite éteignent le
     * pompier honnêtement (le dernier rendu VALIDE reste — jamais de
     * carte vide) ; la carte fermée ([definirInteractif] null) l'annule.
     */
    private suspend fun pomper(identite: String, onglet: Int) {
        var cadence = CADENCE_INTERACTIVE_MS
        var echecsConsecutifs = 0
        while (identiteInteractive == identite && !ferme) {
            delay(cadence)
            val issue = frameInteractive(identite, onglet, cadence, echecsConsecutifs)
            if (issue.arret) return
            cadence = issue.cadenceSuivante
            echecsConsecutifs = issue.echecsConsecutifs
        }
    }

    /**
     * Le verdict d'UNE frame du pompier : la cadence SUIVANTE (adaptée à
     * la durée MESURÉE de la frame — écho `DefaultRenderQualityPolicy`),
     * le compteur d'échecs CONSECUTIFS et l'arrêt honnête (trois frames
     * échouées de suite : le dernier rendu VALIDE reste, jamais de
     * carte vide).
     */
    private class IssueFrame(val cadenceSuivante: Long, val echecsConsecutifs: Int, val arret: Boolean)

    /**
     * UNE frame du pompier, en UNE expression : requête absente ou verrou
     * tenu par une ÉDITION → frame SAUTÉE (l'animation cède la place au
     * live-edit, jamais l'inverse) ; succès → publication + cadence
     * adaptée ; échec → compteur et, au seuil, l'arrêt.
     */
    private suspend fun frameInteractive(identite: String, onglet: Int, cadence: Long, echecs: Int): IssueFrame {
        val requete = dernieresRequetes[identite]
        return if (requete == null || !verrouRendu.tryLock()) {
            IssueFrame(cadence, echecs, false)
        } else {
            try {
                issueDuResultat(identite, onglet, cadence, echecs, transport.rendre(requete))
            } finally {
                verrouRendu.unlock()
            }
        }
    }

    /**
     * Le verdict d'un résultat de frame : succès → publication + cadence
     * ADAPTÉE à la durée mesurée (écho `DefaultRenderQualityPolicy`) ;
     * échec → compteur CONSECUTIF et arrêt honnête au seuil (le dernier
     * rendu VALIDE reste, jamais de carte vide).
     */
    private fun issueDuResultat(
        identite: String,
        onglet: Int,
        cadence: Long,
        echecs: Int,
        resultat: ResultatTransportCompose,
    ): IssueFrame = when (resultat) {
        is ResultatTransportCompose.Succes -> {
            publierFrame(identite, onglet, resultat)
            IssueFrame(cadenceAdaptee(cadence, resultat.metriques.dureeMs), 0, false)
        }

        is ResultatTransportCompose.Erreur -> {
            val consecutifs = echecs + 1
            val arret = consecutifs >= SEUL_ERREURS_FRAMES
            if (arret) {
                journal?.w(TAG) {
                    "pompier interactif éteint : $SEUL_ERREURS_FRAMES frames " +
                        "échouées de suite (${resultat.message}) — le dernier rendu reste"
                }
            }
            IssueFrame(cadence, consecutifs, arret)
        }
    }

    /**
     * C3 — publie UNE frame interactive : le bitmap AVANCÉ (et ses
     * géométries ré-extraites) remplace l'onglet dans l'état RenduValide
     * COURANT ; les autres onglets gardent leurs rendus (jamais de
     * carte vide, jamais de re-rendu des onglets invisibles).
     */
    private fun publierFrame(identite: String, onglet: Int, resultat: ResultatTransportCompose.Succes) {
        val actuel = etatInterne.value as? EtatPreviewCompose.RenduValide ?: return
        val ancien = actuel.rendus.getOrNull(onglet) ?: return
        val rendu =
            RenduPreviewCompose(
                nom = ancien.nom,
                index = onglet,
                bitmap = resultat.bitmap,
                metriques = resultat.metriques,
                erreurs = resultat.erreurs,
                messageErreur = null,
                noeuds = resultat.noeuds,
            )
        val rendus = actuel.rendus.toMutableList()
        rendus[onglet] = rendu
        derniersRendus[identite] = rendu
        etatInterne.value = EtatPreviewCompose.RenduValide(rendus)
    }

    /**
     * C3 — cadence ADAPTATIVE (écho `DefaultRenderQualityPolicy` de la
     * référence) : une frame qui déborde son budget DOUBLE la cadence
     * (30 → 15 → 8 → 4 i/s, plancher honnête — le device de référence
     * tient la cible, un device chargé ralentit au lieu de sauter) ;
     * une frame à l'AISE la DIVISE en retour (jamais plus vite que la
     * cible).
     */
    private fun cadenceAdaptee(cadence: Long, dureeMs: Long): Long = when {
        dureeMs > cadence -> (cadence * FACTEUR_DEGRADATION).coerceAtMost(CADENCE_PLANCHER_MS)

        dureeMs * AISE_FRAME < cadence && cadence > CADENCE_INTERACTIVE_MS ->
            (cadence / FACTEUR_DEGRADATION).coerceAtLeast(CADENCE_INTERACTIVE_MS)

        else -> cadence
    }

    private companion object {
        private const val DEBOUNCE_MS = 400L
        private const val TAG = "MoteurPreviewCompose"

        /** C3 — cadence CIBLE du pompier interactif : 33 ms ≈ 30 i/s (PREVIEW.md §6). */
        internal const val CADENCE_INTERACTIVE_MS: Long = 33L

        /** C3 — plancher de dégradation : ≈ 4 i/s (une frame lente ne gèle jamais, elle ralentit). */
        internal const val CADENCE_PLANCHER_MS: Long = 250L

        /** C3 — chaque palier DOUBLE la cadence (30 → 15 → 8 → 4 i/s). */
        internal const val FACTEUR_DEGRADATION: Long = 2L

        /** C3 — une frame est À L'AISE en dessous de la MOITIÉ de sa cadence. */
        internal const val AISE_FRAME: Long = 2L

        /** C3 — trois frames échouées de suite éteignent le pompier (le dernier rendu reste). */
        internal const val SEUL_ERREURS_FRAMES: Int = 3
    }
}
