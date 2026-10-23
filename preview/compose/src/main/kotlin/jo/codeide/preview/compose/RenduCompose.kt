package jo.codeide.preview.compose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewParent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Exécuteur de programmes Compose dans le processus `:preview` (C1) —
 * la composition vit HORS FENÊTRE : un [ComposeView] jamais attaché à
 * un WindowManager reçoit le VRAI [android.view.View.AttachInfo] d'un
 * vrai [android.view.ViewRootImpl] SANS fenêtre (technique
 * RenderSessionImpl d'Android Studio, miroir de la référence layoutlib —
 * le pipeline de dessin complet de `View.draw` est
 * actif sur des vues non attachées), ses propriétaires de cycle de vie
 * sont FACTICES ([ProprietaireFenetreFactice] : LifecycleRegistry +
 * SavedStateRegistry hors fenêtre) et son [Recomposer] est POSSÉDÉ :
 * une [BroadcastFrameClock] que l'exécuteur déclenche LUI-MÊME —
 * aucune dépendance à un vrai Choreographer d'activité.
 *
 * SESSION PERSISTANTE (cœur du C1, « re-render sans re-inflation » de
 * la référence) : une composition vivante par IDENTITÉ
 * (`uri#nom#index`) ; un rendu de la même identité ne fait que POUSSER
 * le nouveau programme (et le régime nuit, la densité du profil) dans
 * les états observés — c'est la RECOMPOSITION Compose qui diff (les
 * groupes inchangés ne se ré-exécutent pas) et les `remember` du
 * registre survivent aux éditions (état préservé, PC1). Les sessions
 * meurent avec le processus (idle-shutdown du transport + bound-only
 * du service — jamais de fuite au-delà).
 *
 * NON-BLOCAGE (leçon d'écriture) : le travail de rendu vit dans la
 * PORTÉE de composition (fil principal du processus) comme une
 * coroutine SUSPENDANTE — l'attente de stabilisation
 * ([attendreRecomposition]) suspend le rendu PENDANT que le fil pompe
 * la recomposition ; l'attente de la FRONTIÈRE (verrou borné) vit sur
 * le FIL BINDER de l'appelant. Bloquer le fil de composition en
 * attendant une recomposition qui a BESOIN de ce fil serait un
 * interblocement garanti.
 *
 * Garde-fous (PC4) : le programme n'exécute QUE les composables du
 * REGISTRE [RendreNoeud] — sandbox par construction (réseau, stockage,
 * réflexion : IMPOSSIBLES, ils ne sont pas interprétables) ;
 * [LocalInspectionMode] est VRAI (les composables s'annoncent en mode
 * inspection) ; chaque rendu est BORNÉ ([DELAI_RENDU_MS],
 * [DELAI_PREMIER_RENDU_MS], [DELAI_RECOMPOSITION_MS]) ; un rendu qui
 * DÉPASSE laisse un zombie qui OCCUPE sa session (verrou tryLock — le
 * rendu suivant répond honnêtement, jamais un rendu fantôme) ; le
 * watchdog de gel avec KILL du processus est le chantier C3 (PREVIEW.md
 * §4 PC4 complet).
 *
 * @param contexte contexte du service (processus `:preview`).
 * @param executeur fil des opérations de cycle (fermetures) —
 *        production : fil principal du processus.
 * @param dispatcheurComposition dispatcheur du recomposer et des
 *        rendus (production : `Dispatchers.Main` du processus ;
 *        tests : dispatcheur de test).
 * @param journal sorties de diagnostic (pannes d'attach, délais).
 * @param resolveurs C2 — les résolveurs du PROJET (cache du service) ;
 *        `null` = la composition rend sur les DÉFAUTS DU RUNTIME
 *        (comportement C1 exact — tests et rendu sans projet).
 */
internal class RenduCompose(
    private val contexte: Context,
    private val executeur: Executor,
    private val dispatcheurComposition: CoroutineDispatcher,
    private val journal: (String) -> Unit = {},
    private val resolveurs: ResolveursCompose? = null,
) {
    /** Sessions vivantes : identité → composition persistante. */
    private val sessions = LinkedHashMap<String, Session>()

    /** Portée des travaux de rendu (le fil de composition, suspendant). */
    private val porteeRendu = CoroutineScope(SupervisorJob() + dispatcheurComposition)

    /**
     * Rend UN programme : pousse les états de la session (recomposition
     * Compose = diff d'identité à l'échelle des groupes), attend la
     * stabilisation (frames pilotées + awaitIdle bornés),
     * mesure/pose/dessine le bitmap off-screen. La FRONTIÈRE reste
     * SYNCHRONE (AIDL) : l'appelant (fil Binder) attend UN délai borné
     * — le budget du PREMIER rendu d'une session (composition à froid)
     * est élargi, les suivants visent l'interactif.
     */
    internal fun rendre(requete: RequeteRenduCompose): ResultatRenduCompose {
        val verrou = CountDownLatch(1)
        var resultat: ResultatRenduCompose? = null
        val premiereSession = synchronized(this) { sessions[requete.identite] == null }
        val budget = if (premiereSession) DELAI_PREMIER_RENDU_MS else DELAI_RENDU_MS
        porteeRendu.launch {
            resultat =
                try {
                    rendreSurFil(requete)
                } catch (t: Throwable) {
                    // Un composable ou le pipeline a levé : échec en
                    // DONNÉE (jamais d'exception à travers Binder) ; la
                    // pile complète part au JOURNAL (diagnostic des
                    // pannes de rendu — le message seul ne suffit pas).
                    journal(
                        "rendu compose échoué : ${t.javaClass.simpleName} : ${t.message}\n" +
                            t.stackTrace.take(PILES_JOURNALISEES).joinToString("\n") { "  à $it" },
                    )
                    echec("${t.javaClass.simpleName} : ${t.message}")
                }
            verrou.countDown()
        }
        return if (verrou.await(budget, TimeUnit.MILLISECONDS)) {
            resultat ?: echec("le rendu n'a pas rendu de résultat")
        } else {
            echec("délai de rendu dépassé ($budget ms)")
        }
    }

    /** Ferme TOUTES les sessions (le service meurt : hygiène). */
    internal fun fermer() {
        executeur.execute {
            sessions.values.forEach(Session::fermer)
            sessions.clear()
        }
    }

    /**
     * Sonde interne pour les tests : nombre de sessions VIVANTES (la
     * persistance par identité — deux rendus de la même identité ne
     * créent QU'UNE session, cœur du live-edit C1).
     */
    internal fun sessionsVivantes(): Int = synchronized(this) { sessions.size }

    /** Le rendu proprement dit — coroutine SUSPENDANTE du fil de composition. */
    private suspend fun rendreSurFil(requete: RequeteRenduCompose): ResultatRenduCompose {
        val debut = SystemClock.elapsedRealtime()
        // C2 : les résolveurs du projet AVANT la session — la création
        // d'une session NOUVELLE doit déjà voir ses résolveurs (la
        // première composition peut partir à l'ATTACH de la vue, états
        // fondateurs complets — leçon du correctif C2).
        val resolveur = resolveurDe(requete)
        val premiereSession = synchronized(this) { sessions[requete.identite] == null }
        val session =
            synchronized(this) {
                sessions.getOrPut(requete.identite) { creerSession(requete, resolveur) }
            }
        if (!session.verrou.tryLock()) {
            // Un rendu précédent de CETTE session a dépassé son budget
            // et occupe encore la composition (zombie) : échec honnête
            // en donnée — jamais un rendu concurrent fantôme.
            return echec("un rendu précédent occupe encore la session")
        }
        try {
            // LIVE-EDIT : le programme change, la composition RESTE —
            // c'est la recomposition qui diff (état préservé, PC1) ;
            // le régime nuit et la densité du profil SUIVENT (re-theme
            // et re-mesure par recomposition, jamais gelés à la
            // création de session). C2 : le SCHÉMA du projet suit AUSSI
            // (les résolveurs viennent du cache du service — un scan
            // disque par changement de combinaison, pas par rendu) et
            // le contexte du registre est RE-POLARISÉ (les bornes et
            // erreurs du rendu précédent sont vidées, jamais reportées).
            // Premier rendu : la composition d'ATTACH a déjà collecté —
            // POLARISER sans vider ; rendus suivants : VIDER avant la
            // recomposition qui re-collecte (bornes et erreurs ne
            // survivent JAMAIS au rendu précédent).
            if (premiereSession) {
                session.etats.contexte.polariser(resolveur)
            } else {
                session.etats.contexte.debuter(resolveur)
            }
            session.etats.programme.value = requete.programme
            session.etats.nuit.value = requete.nuit
            session.etats.densite.value = Density(requete.densiteDpi / DENSITE_REFERENCE, 1f)
            session.etats.schema.value = schemaDe(requete, resolveur)
            session.horloge.sendFrame(System.nanoTime())
            attendreRecomposition(session)
            val densite = requete.densiteDpi / DENSITE_REFERENCE
            val largeurPx = (requete.largeurDp * densite).toInt().coerceAtLeast(1)
            val hauteurPx = (requete.hauteurDp * densite).toInt().coerceAtLeast(1)
            session.vue.measure(
                View.MeasureSpec.makeMeasureSpec(largeurPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(hauteurPx, View.MeasureSpec.EXACTLY),
            )
            session.vue.layout(0, 0, largeurPx, hauteurPx)
            val bitmap = Bitmap.createBitmap(largeurPx, hauteurPx, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (requete.nuit) COULEUR_FOND_NUIT else COULEUR_FOND_JOUR)
            val canevas = Canvas(bitmap)
            session.vue.draw(canevas)
            return ResultatRenduCompose(
                ok = true,
                message = null,
                bitmap = bitmap,
                erreurs = session.etats.contexte.erreurs.toTypedArray(),
                noeuds = session.etats.contexte.noeuds,
                metriques =
                MetriquesRenduCompose(
                    dureeMs = SystemClock.elapsedRealtime() - debut,
                    nombreNoeuds = compter(requete.programme.racine),
                    largeur = largeurPx,
                    hauteur = hauteurPx,
                ),
            )
        } finally {
            session.verrou.unlock()
        }
    }

    /**
     * Les résolveurs du projet d'UNE requête — cache du service : un
     * scan disque par changement de combinaison, jamais par rendu.
     */
    private fun resolveurDe(requete: RequeteRenduCompose): ResolveurCompose? = resolveurs?.pour(
        dossierRes = requete.dossierRes,
        nuit = requete.nuit,
        langue = requete.langue,
        themeName = requete.themeName,
        densiteDpi = requete.densiteDpi,
    )

    /**
     * Le SCHÉMA Material du rendu : celui du PROJET (slots résolus sur
     * le thème du manifeste), à défaut les couleurs PAR DÉFAUT du
     * runtime (comportement C1).
     */
    private fun schemaDe(requete: RequeteRenduCompose, resolveur: ResolveurCompose?): ColorScheme =
        resolveur?.schema(requete.nuit)
            ?: if (requete.nuit) darkColorScheme() else lightColorScheme()

    /** Crée la session persistante d'une identité (composition vivante). */
    private fun creerSession(requete: RequeteRenduCompose, resolveur: ResolveurCompose?): Session {
        val vue = ComposeView(contexte)
        val proprietaire = ProprietaireFenetreFactice()
        vue.setViewTreeLifecycleOwner(proprietaire)
        vue.setViewTreeSavedStateRegistryOwner(proprietaire)
        val horloge = BroadcastFrameClock()
        val portee = CoroutineScope(SupervisorJob() + dispatcheurComposition + horloge)
        val recomposer = Recomposer(portee.coroutineContext)
        portee.launch { recomposer.runRecomposeAndApplyChanges() }.invokeOnCompletion { erreur ->
            // La mort du recomposer est un ÉVÉNEMENT DE DIAGNOSTIC : une
            // composition qui lève laisse un rendu VIDE (bitmap de fond)
            // sans qu'AUCUNE exception ne remonte au moteur — le journal
            // porte la cause (règle 14 : jamais de stderr direct).
            if (erreur != null) {
                journal("recomposer mort : ${erreur.javaClass.simpleName} : ${erreur.message}")
            }
        }
        vue.setParentCompositionContext(recomposer)
        val programme = mutableStateOf(requete.programme)
        val nuit = mutableStateOf(requete.nuit)
        val densite = mutableStateOf(Density(requete.densiteDpi / DENSITE_REFERENCE, 1f))
        val schema =
            mutableStateOf(
                resolveur?.schema(requete.nuit) ?: if (requete.nuit) darkColorScheme() else lightColorScheme(),
            )
        val contexteRendu = ContexteRendu(journal = journal)
        contexteRendu.debuter(resolveur)
        val etats = EtatsRendu(programme, nuit, densite, schema, contexteRendu)
        vue.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalDensity provides etats.densite.value,
            ) {
                MaterialTheme(
                    colorScheme = etats.schema.value,
                ) {
                    ContenuProgramme(etats.programme.value, etats.contexte)
                }
            }
        }
        AttacherCompose.injecter(vue, journal, contexte)
        return Session(vue, recomposer, horloge, portee, etats, proprietaire)
    }

    /**
     * Attend la stabilisation de la recomposition — SUSPENDANT (le fil
     * de composition reste LIBRE de pomper la recomposition) : une
     * coroutine POMPIER pousse des frames à cadence (la mesure de
     * texte peut en exiger PLUSIEURS), [Recomposer.awaitIdle] lève
     * l'attente, le tout borné par [DELAI_RECOMPOSITION_MS] (PC4 —
     * jamais d'attente infinie : au délai, le rendu continue sur
     * l'état atteint, honnêtement mesuré).
     */
    private suspend fun attendreRecomposition(session: Session) {
        try {
            withTimeout(DELAI_RECOMPOSITION_MS) {
                coroutineScope {
                    val pompier =
                        launch {
                            while (isActive) {
                                session.horloge.sendFrame(System.nanoTime())
                                delay(CADENCE_FRAME_MS)
                            }
                        }
                    session.recomposer.awaitIdle()
                    pompier.cancel()
                }
            }
        } catch (e: TimeoutCancellationException) {
            journal(
                "stabilisation non atteinte en ${DELAI_RECOMPOSITION_MS} ms (${e.message}) — rendu sur l'état atteint",
            )
        }
    }

    /** Compte les nœuds d'un programme (métrique de fidélité). */
    private fun compter(racine: List<NoeudProgramme>): Int {
        var total = 0
        fun parcourir(noeuds: List<NoeudProgramme>) {
            noeuds.forEach {
                total++
                parcourir(it.contenu)
            }
        }
        parcourir(racine)
        return total
    }

    /** Un échec en DONNÉE (frontière AIDL — jamais d'exception). */
    private fun echec(message: String): ResultatRenduCompose = ResultatRenduCompose(
        ok = false,
        message = message,
        bitmap = null,
        erreurs = arrayOf(ErreurCompose(GenreErreurCompose.ERREUR_RENDU, null, 0, message)),
        metriques = MetriquesRenduCompose.NULLES,
    )

    /** Une composition vivante (états + vue + machinerie de recomposition). */
    private class Session(
        val vue: ComposeView,
        val recomposer: Recomposer,
        val horloge: BroadcastFrameClock,
        val portee: CoroutineScope,
        val etats: EtatsRendu,
        val proprietaire: ProprietaireFenetreFactice,
    ) {
        /** Rendus sérialisés par session (un zombie dépassé OCCUPE sa session). */
        val verrou: Mutex = Mutex()

        fun fermer() {
            proprietaire.cycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            recomposer.close()
            portee.coroutineContext[Job]?.cancel()
        }
    }

    /**
     * Les états OBSERVÉS de la composition — regroupés : le live-edit
     * pousse le NOUVEAU programme dans l'état SANS recréer la session,
     * la recomposition diff les groupes inchangés (PC1) ; C2 ajoute le
     * SCHÉMA du projet (re-thème par recomposition, miroir du régime
     * nuit) et le CONTEXTE de rendu (résolveur + collecteur de bornes,
     * re-lus à chaque recomposition) ; le regroupement garde le
     * constructeur de [Session] lisible (detekt LongParameterList tué
     * par RESTRUCTURE, pas par desserrage).
     */
    private class EtatsRendu(
        val programme: MutableState<ProgrammeCompose>,
        val nuit: MutableState<Boolean>,
        val densite: MutableState<Density>,
        val schema: MutableState<ColorScheme>,
        val contexte: ContexteRendu,
    )

    /**
     * Propriétaire de cycle de vie ET d'état sauvegardé HORS FENÊTRE —
     * les DEUX rôles sur le MÊME propriétaire (miroir d'une
     * ComponentActivity) : LifecycleRegistry created → resumed, registre
     * d'état vide restauré (les `rememberSaveable` du registre y
     * survivent entre les rendus de la session).
     */
    private class ProprietaireFenetreFactice :
        LifecycleOwner,
        SavedStateRegistryOwner {
        val cycle = LifecycleRegistry(this)
        private val controleur = SavedStateRegistryController.create(this)

        init {
            controleur.performRestore(null)
            cycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            cycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            cycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        override val lifecycle: Lifecycle
            get() = cycle

        override val savedStateRegistry: SavedStateRegistry
            get() = controleur.savedStateRegistry
    }

    private companion object {
        /**
         * Délai d'un rendu CHAUD — SOUS le budget chaud du transport
         * (10 s côté app) : un rendu enlisé répond l'échec en DONNÉE
         * avant que le transport ne le déclare panne de liaison.
         */
        internal const val DELAI_RENDU_MS: Long = 9_000L

        /**
         * Délai du PREMIER rendu d'une session (composition à froid :
         * runtime Compose + premier attach off-screen — sous le budget
         * froid du transport, 45 s côté app).
         */
        internal const val DELAI_PREMIER_RENDU_MS: Long = 40_000L

        /** Délai de stabilisation de la recomposition. */
        internal const val DELAI_RECOMPOSITION_MS: Long = 5_000L

        /** Cadence du pompier de frames (≈60 i/s — la recomposition en attend). */
        internal const val CADENCE_FRAME_MS: Long = 16L

        /** Densité de référence (160 dpi = ×1) — FLOTTANTE : 440/160 = 2,75. */
        internal const val DENSITE_REFERENCE: Float = 160f

        /** Fonds de la planche : jour `#FFFFFF`, nuit `#1C1B1F` (M3 sombre). */
        internal const val COULEUR_FOND_JOUR: Int = 0xFFFFFFFF.toInt()
        internal const val COULEUR_FOND_NUIT: Int = 0xFF1C1B1F.toInt()

        /**
         * Plafond de lignes de pile au journal d'un échec de rendu : les
         * piles Compose sont PROFONDES (le runtime ajoute ses propres
         * trames) — les premières lignes portent l'origine du composable
         * fautif, la suite n'est que mécanique de composition.
         */
        internal const val PILES_JOURNALISEES: Int = 15
    }
}

/**
 * Le CONTENU renderable du programme — LE REGISTRE (C1) : chaque genre
 * rend son composable réel de la bibliothèque embarquée ; les pastilles
 * d'erreur isolent le nœud fautif (le reste se rend — miroir
 * `createMockView` de la référence : jamais un rendu tout-ou-rien).
 * C2 : [contexte] porte les RÉSOLVEURS du projet (chaque référence
 * `R.string/x`/`R.color/x`/`R.dimen/x`/`MaterialTheme.colorScheme.x`
 * se RÉSOUT ici, au rendu) et le COLLECTEUR de bornes (chaque nœud
 * publie sa géométrie bitmap + sa ligne source — le sésame
 * surlignage de la carte, écho `ComposeViewInfoParser`).
 */
@Composable
internal fun ContenuProgramme(programme: ProgrammeCompose, contexte: ContexteRendu) {
    Column {
        programme.racine.forEach { RendreNoeud(it, contexte) }
        if (programme.coupureBudget) {
            PastilleErreur("Interprétation tronquée (budget)", GenreErreurCompose.BUDGET_INTERPRETATION)
        }
    }
}

/** Rend UN nœud du programme (dispatch du registre C1 + résolution C2 + bornes C2). */
@Composable
internal fun RendreNoeud(noeud: NoeudProgramme, contexte: ContexteRendu) {
    when (noeud.genre) {
        GenreNoeudCompose.TEXTE -> RendreTexte(noeud, contexte)

        GenreNoeudCompose.COLONNE ->
            Column(modifier = Modifier.bornesDe(noeud, contexte)) {
                noeud.contenu.forEach { RendreNoeud(it, contexte) }
            }

        GenreNoeudCompose.RANGEE ->
            Row(modifier = Modifier.bornesDe(noeud, contexte)) {
                noeud.contenu.forEach { RendreNoeud(it, contexte) }
            }

        GenreNoeudCompose.BOITE ->
            Box(modifier = Modifier.bornesDe(noeud, contexte)) {
                noeud.contenu.forEach { RendreNoeud(it, contexte) }
            }

        GenreNoeudCompose.ESPACE -> Spacer(modifier = Modifier.espaceDu(noeud, contexte))

        GenreNoeudCompose.BOUTON ->
            // C1 statique : le clic n'est PAS transmis (C3 interactif) —
            // l'exigence de M3 est honorée par un no-op honnête.
            Button(
                onClick = {},
                modifier = Modifier.bornesDe(noeud, contexte),
            ) {
                noeud.contenu.forEach { RendreNoeud(it, contexte) }
            }

        GenreNoeudCompose.INCONNU ->
            PastilleErreur(
                "« ${noeud.nom} » hors registre (C1)",
                GenreErreurCompose.COMPOSABLE_INCONNU,
                modificateur = Modifier.bornesDe(noeud, contexte),
            )

        GenreNoeudCompose.ERREUR_EXPRESSION ->
            PastilleErreur(
                "Non interprété : ${ellipseNom(noeud.nom)}",
                GenreErreurCompose.EXPRESSION_NON_INTERPRETEE,
                modificateur = Modifier.bornesDe(noeud, contexte),
            )

        GenreNoeudCompose.ERREUR_INLINE ->
            PastilleErreur(
                "« ${noeud.nom} » non inliné (projet)",
                GenreErreurCompose.COMPOSABLE_PROJET_PARAMETRES,
                modificateur = Modifier.bornesDe(noeud, contexte),
            )
    }
}

/**
 * Le TEXTE du registre — extrait du dispatch : sa résolution C2
 * (chaîne du projet, couleur du projet ou du THÈME) est la PLUS
 * riche du registre et portait la complexité du when au-delà du
 * seuil detekt (règle 16 : RESTRUCTURE, pas de desserrage).
 */
@Composable
private fun RendreTexte(noeud: NoeudProgramme, contexte: ContexteRendu) {
    Text(
        text = texteDe(noeud, contexte),
        color = couleurTexteDe(noeud, contexte) ?: Color.Unspecified,
        modifier = Modifier.bornesDe(noeud, contexte),
    )
}

/**
 * Ellipse du nom de nœud dans la pastille « Non interprété » : le `nom`
 * d'un nœud expression est le TEXTE BRUT de l'expression (potentiellement
 * longue) — la pastille labelSmall plein cadre reste lisible en le bornant.
 * NIVEAU FICHIER : les composables du registre sont de PREMIER NIVEAU,
 * hors de la classe.
 */
private const val LONGUEUR_PASTILLE: Int = 32

/** Ellipse honnête d'un nom long (points de suspension — miroir nomCourt de l'hôte). */
private fun ellipseNom(nom: String): String =
    if (nom.length <= LONGUEUR_PASTILLE) nom else nom.take(LONGUEUR_PASTILLE - 1) + "…"

/** Les genres d'argument acceptés comme TEXTE d'un nœud. */
private val GENRES_TEXTE: Set<GenreArgumentCompose> =
    setOf(GenreArgumentCompose.LITTERAL_CHAINE, GenreArgumentCompose.REFERENCE_CHAINE)

/**
 * La chaîne d'un Text : `text`/`texte` nommé ou premier texte — C2 : la
 * référence `R.string.NOM` se RÉSOUT sur le résolveur du projet ; une
 * référence introuvable rend le message par DÉFAUT de C1 (le nom du
 * nœud) ET l'erreur typée [GenreErreurCompose.RESSOURCE_INCONNUE]
 * accompagne le rendu (jamais un faux rendu silencieux).
 */
private fun texteDe(noeud: NoeudProgramme, contexte: ContexteRendu): String {
    val arguments = noeud.arguments
    val cible =
        arguments.firstOrNull { it.genre in GENRES_TEXTE && (it.nom == "text" || it.nom == "texte") }
            ?: arguments.firstOrNull { it.genre in GENRES_TEXTE }
    return when (cible?.genre) {
        GenreArgumentCompose.LITTERAL_CHAINE -> cible.valeur ?: noeud.nom

        GenreArgumentCompose.REFERENCE_CHAINE ->
            cible.valeur?.let { nom -> contexte.chaine(nom, noeud.ligne) } ?: noeud.nom

        else -> noeud.nom
    }
}

/** Les genres d'argument acceptés comme COULEUR d'un nœud. */
private val GENRES_COULEUR: Set<GenreArgumentCompose> =
    setOf(GenreArgumentCompose.REFERENCE_COULEUR, GenreArgumentCompose.REFERENCE_THEME)

/**
 * La couleur explicite d'un Text (`color`/`couleur` nommé) : C2 — la
 * référence `R.color.NOM` ou `MaterialTheme.colorScheme.SLOT` se RÉSOUT
 * sur le thème/les ressources du PROJET (`null` = la couleur Material
 * par défaut du contenu, comportement C1).
 */
private fun couleurTexteDe(noeud: NoeudProgramme, contexte: ContexteRendu): Color? {
    val cible =
        noeud.arguments.firstOrNull {
            it.genre in GENRES_COULEUR && (it.nom == "color" || it.nom == "couleur")
        } ?: return null
    return when (cible.genre) {
        GenreArgumentCompose.REFERENCE_COULEUR ->
            cible.valeur?.let { nom -> contexte.couleur(nom, noeud.ligne) }

        GenreArgumentCompose.REFERENCE_THEME ->
            cible.valeur?.let { slot -> contexte.couleurDuSlot(slot, noeud.ligne) }

        else -> null
    }
}

/**
 * Le modificateur d'un ESPACE (EXTENSION de Modifier — règle lint
 * Compose `ModifierFactoryExtensionFunction`) : hauteur explicite,
 * sinon largeur explicite, sinon pleine largeur ; les bornes du nœud
 * se posent en QUEUE de chaîne (l'étendue publiée est l'étendue
 * dessinée). C2 : les références `R.dimen.NOM` se résolvent sur le
 * projet.
 */
private fun Modifier.espaceDu(noeud: NoeudProgramme, contexte: ContexteRendu): Modifier =
    hauteurDe(noeud, contexte)?.let { hauteur -> height(hauteur).bornesDe(noeud, contexte) }
        ?: largeurDe(noeud, contexte)?.let { largeur -> width(largeur).bornesDe(noeud, contexte) }
        ?: fillMaxWidth().bornesDe(noeud, contexte)

/** Les genres d'argument acceptés comme DIMENSION d'un nœud. */
private val GENRES_DIMENSION: Set<GenreArgumentCompose> =
    setOf(GenreArgumentCompose.VALEUR_DP, GenreArgumentCompose.REFERENCE_DIMENSION)

/** La hauteur dp d'un Spacer (`height`/`hauteur` ou positionnel) — littéral ou `R.dimen.NOM` du projet. */
private fun hauteurDe(noeud: NoeudProgramme, contexte: ContexteRendu): Dp? {
    val cible =
        noeud.arguments.firstOrNull {
            it.genre in GENRES_DIMENSION && (it.nom == "height" || it.nom == "hauteur" || it.nom == null)
        } ?: return null
    return when (cible.genre) {
        GenreArgumentCompose.VALEUR_DP -> cible.valeur?.toFloatOrNull()?.dp

        GenreArgumentCompose.REFERENCE_DIMENSION ->
            cible.valeur?.let { nom -> contexte.dimensionDp(nom, noeud.ligne) }

        else -> null
    }
}

/** La largeur dp d'un Spacer (`width`/`largeur`) — littéral ou `R.dimen.NOM` du projet. */
private fun largeurDe(noeud: NoeudProgramme, contexte: ContexteRendu): Dp? {
    val cible =
        noeud.arguments.firstOrNull {
            it.genre in GENRES_DIMENSION && (it.nom == "width" || it.nom == "largeur")
        } ?: return null
    return when (cible.genre) {
        GenreArgumentCompose.VALEUR_DP -> cible.valeur?.toFloatOrNull()?.dp

        GenreArgumentCompose.REFERENCE_DIMENSION ->
            cible.valeur?.let { nom -> contexte.dimensionDp(nom, noeud.ligne) }

        else -> null
    }
}

/** Pastille d'erreur inline (isole le nœud fautif — le reste se rend). */
@Composable
private fun PastilleErreur(message: String, genre: GenreErreurCompose, modificateur: Modifier = Modifier) {
    Box(
        modifier =
        modificateur
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = "$message [${genre.name}]",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Injection du VRAI [android.view.View.AttachInfo] porté par un VRAI
 * [android.view.ViewRootImpl] SANS fenêtre — le miroir de la référence
 * layoutlib (`AttachInfo_Accessor.setAttachInfo`, technique
 * RenderSessionImpl d'Android Studio) : sans attach, la composition
 * d'un AbstractComposeView ne démarre JAMAIS (elle vit sur
 * onAttachedToWindow) et `View.draw` saute le fond des vues opaques.
 * C'est le CONSTRUCTEUR du ViewRootImpl qui fabrique l'AttachInfo
 * COMPLET — `mViewRootImpl` posé, `mHandler` réel du fil de
 * composition, `mDisplay` réel, ET le `mChoreographer` du looper
 * courant : un AttachInfo « assemblé à la main » (constructeur +
 * doubles inertes) laisse `mViewRootImpl` null et fait sauter
 * `onLayout` d'AndroidComposeView (RectManager
 * .removeScheduledCallback → View.removeCallbacks →
 * `attachInfo.mViewRootImpl.mChoreographer` — constaté).
 *
 * DIVERGENCES ASSUMÉES de la référence, documentées :
 * 1. le PARENT de la vue reste un DOUBLE INERTE (la référence fait
 *    `assignParent(root)`) : l'exécuteur off-screen POSSEDE
 *    mesure/pose/dessin — un requestLayout ne doit JAMAIS déclencher
 *    de performTraversals asynchrone sur le vsync réel du processus
 *    :preview (le root ne doit PAS devenir un ViewParent vivant) ;
 * 2. la session est le stub `IWindowSession.Default()` SANS binder —
 *    exactement la référence : aucune IPC fenêtre n'est possible, les
 *    appels relayout retombent en silencieux par construction.
 *
 * **Lint PrivateApi** : l'accès réflexif aux API internes de la
 * plateforme (`IWindowSession[.Default]`, `View.AttachInfo`,
 * `ViewRootImpl`, `WindowLayout`, champs/méthodes `m*` de `View` et de
 * l'AttachInfo) est flagué par lint `PrivateApi`. C'est DELIBÉRÉ —
 * miroir exact de `AttacheurVue` (:preview:xml) : c'est l'approche
 * d'Android Studio lui-même (RenderSessionImpl/`AttachInfo_Accessor`
 * font exactement la même chose). Supprimé au niveau de la classe.
 */
@Suppress("PrivateApi") // Réflexion View.AttachInfo/ViewRootImpl — approche AS RenderSessionImpl, miroir AttacheurVue.
internal object AttacherCompose {
    /** `android.view.IWindowSession` (absente du SDK public — réflexion). */
    private val classeSession: Class<*> by lazy { Class.forName("android.view.IWindowSession") }

    /** Injecte + propage l'attach à la vue ; faux si la réflexion casse. */
    fun injecter(racine: View, journal: (String) -> Unit, contexte: Context): Boolean {
        val attachInfo = fabriquerAttachInfo(racine, journal, contexte) ?: return false
        return try {
            poserFauxParent(racine)
            val classe = Class.forName("android.view.View\$AttachInfo")
            val methode =
                View::class.java.getDeclaredMethod(
                    "dispatchAttachedToWindow",
                    classe,
                    Int::class.javaPrimitiveType,
                )
            methode.isAccessible = true
            methode.invoke(racine, attachInfo, View.VISIBLE)
            true
        } catch (t: Throwable) {
            val cause = t.cause
            journal(
                "attach du ComposeView échoué : ${t.javaClass.simpleName} : ${t.message}" +
                    (cause?.let { " (cause : ${it.javaClass.simpleName} : ${it.message})" } ?: ""),
            )
            false
        }
    }

    /**
     * Fabrique l'AttachInfo du root — JOURNAL DÉDIÉ PAR CAUSE : une
     * exception de la réflexion a SA chaîne de causes, un root NON
     * CONSTRUCTIBLE a SON message ; null = échec.
     */
    private fun fabriquerAttachInfo(racine: View, journal: (String) -> Unit, contexte: Context): Any? = try {
        val info = creerAttachInfo(racine, contexte)
        if (info == null) {
            journal("ViewRootImpl non constructible pour le ComposeView (aucun constructeur du socle disponible)")
        }
        info
    } catch (t: Throwable) {
        journal("attach du ComposeView échoué :${chaineDeCauses(t)}")
        null
    }

    /** La chaîne des causes d'une exception de réflexion (diagnostic complet). */
    private fun chaineDeCauses(t: Throwable): String {
        val chaine = StringBuilder()
        var cause: Throwable? = t
        while (cause != null) {
            chaine.append(" → ${cause.javaClass.simpleName} : ${cause.message}")
            cause = cause.cause
        }
        return chaine.toString()
    }

    /**
     * Le MIROIR de `AttachInfo_Accessor.setAttachInfo` (référence
     * layoutlib) : construit le ViewRootImpl RÉEL — c'est SON
     * CONSTRUCTEUR qui fabrique l'AttachInfo complet (`mViewRootImpl`
     * posé, `mHandler` réel du fil de composition, `mDisplay` réel) ET
     * le Choreographer du looper courant — puis pose `mView` (miroir
     * `ViewRootImpl_Accessor.setChild` : la vue devient
     * `root.getView()`, les chemins d'insets la lisent) et les drapeaux
     * de la référence (fenêtre concentrée, VISIBLE, hors mode tactile —
     * les sélections se dessinent — échelle 1).
     */
    private fun creerAttachInfo(racine: View, contexte: Context): Any? {
        val root = construireViewRootImpl(contexte) ?: return null
        val champInfo = root.javaClass.getDeclaredField("mAttachInfo")
        champInfo.isAccessible = true
        return champInfo.get(root)?.also { info -> poserSurRoot(root, racine, info) }
    }

    /**
     * Pose `mView` et les DRAPEAUX de la référence sur le root — UN PAR
     * UN : un drapeau manquant sur une API donnée ne doit jamais casser
     * l'attach (chaque pose est indépendante).
     */
    private fun poserSurRoot(root: Any, racine: View, attachInfo: Any) {
        runCatching {
            val champVue = root.javaClass.getDeclaredField("mView")
            champVue.isAccessible = true
            champVue.set(root, racine)
        }
        // Drapeaux de la référence : fenêtre concentrée, VISIBLE, hors
        // mode tactile — les sélections se dessinent — échelle 1.
        poserDrapeau(attachInfo, "mHasWindowFocus", true)
        poserDrapeau(attachInfo, "mWindowVisibility", View.VISIBLE)
        poserDrapeau(attachInfo, "mInTouchMode", false)
        poserDrapeau(attachInfo, "mApplicationScale", 1.0f)
        // Ceinture-bretelles historiques : mTmpOutline DOIT exister
        // (View.rebuildOutline le déréférence sur TOUTE vue à provider
        // par défaut — sinon NPE) ; mIgnoreDirtyState honore
        // drawBackground des vues opaques non attachées (miroir
        // AttacheurVue :preview:xml).
        runCatching {
            val champ = attachInfo.javaClass.getDeclaredField("mTmpOutline")
            champ.isAccessible = true
            if (champ.get(attachInfo) == null) {
                champ.set(attachInfo, android.graphics.Outline())
            }
        }
        runCatching {
            val ignore = attachInfo.javaClass.getDeclaredField("mIgnoreDirtyState")
            ignore.isAccessible = true
            ignore.setBoolean(attachInfo, true)
        }
    }

    /**
     * Construit le ViewRootImpl réel — le stub `IWindowSession.Default`
     * de la référence (AUCUN binder : les appels fenêtre retombent en
     * silencieux), l'écran par défaut du service, `WindowLayout` quand
     * l'API l'exige (variante `(…, WindowLayout)` des API récentes) et
     * `false` pour les variantes `useSfChoreographer` : le Choreographer
     * du LOOPER COURANT (fil de composition) est celui que
     * `View.removeCallbacks` déréférencera (`mChoreographer`). Le
     * constructeur le plus PARAMÉTRÉ d'abord — chaîne de repli API par
     * API ; un paramètre hors socle connu fait passer au suivant.
     */
    @Suppress(
        "SpreadOperator", // newInstance exige un varargs ; pas d'alternative sans copie.
        // getDefaultDisplay est le BON choix hors fenêtre (le remplacement
        // context.display exige un contexte VISUEL — un service :preview
        // n'en est pas un).
        "DEPRECATION",
    )
    private fun construireViewRootImpl(contexte: Context): Any? {
        val classe = Class.forName("android.view.ViewRootImpl")
        val session =
            Class.forName("android.view.IWindowSession\$Default")
                .getDeclaredConstructor()
                .newInstance()
        val ecran =
            (contexte.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay ?: return null
        val dispositionFenetre =
            runCatching {
                Class.forName("android.view.WindowLayout").getDeclaredConstructor().newInstance()
            }.getOrNull()
        return classe.declaredConstructors
            .asSequence()
            .filter { it.parameterCount >= 2 }
            .filter { Context::class.java.isAssignableFrom(it.parameterTypes[0]) }
            .filter { Display::class.java.isAssignableFrom(it.parameterTypes[1]) }
            .sortedByDescending { it.parameterCount }
            .firstNotNullOfOrNull { constructeur ->
                val arguments =
                    argumentsDuConstructeur(
                        types = constructeur.parameterTypes,
                        contexte = contexte,
                        ecran = ecran,
                        session = session,
                        dispositionFenetre = dispositionFenetre,
                    ) ?: return@firstNotNullOfOrNull null
                constructeur.isAccessible = true
                runCatching { constructeur.newInstance(*arguments) }.getOrNull()
            }
    }

    /**
     * Les arguments d'un constructeur ViewRootImpl — `null` (=
     * constructeur NON fillable, on passe au suivant) dès qu'un
     * paramètre sort du socle connu (Context, Display, IWindowSession,
     * WindowLayout, boolean) ; JAMAIS de double inerte ici : un
     * ViewRootImpl à moitié vivant est pire qu'aucun ViewRootImpl.
     */
    private fun argumentsDuConstructeur(
        types: Array<Class<*>>,
        contexte: Context,
        ecran: Display,
        session: Any,
        dispositionFenetre: Any?,
    ): Array<Any?>? {
        val arguments = arrayOfNulls<Any?>(types.size)
        for (indice in types.indices) {
            val type = types[indice]
            arguments[indice] =
                when {
                    Context::class.java.isAssignableFrom(type) -> contexte

                    Display::class.java.isAssignableFrom(type) -> ecran

                    classeSession.isAssignableFrom(type) -> session

                    dispositionFenetre != null && type.isAssignableFrom(dispositionFenetre.javaClass) ->
                        dispositionFenetre

                    type == Boolean::class.javaPrimitiveType -> false

                    else -> return null
                }
        }
        return arguments
    }

    /** Pose un drapeau de l'AttachInfo (silencieux si absent : les champs varient selon l'API). */
    private fun poserDrapeau(attachInfo: Any, nom: String, valeur: Any) {
        runCatching {
            val champ = attachInfo.javaClass.getDeclaredField(nom)
            champ.isAccessible = true
            champ.set(attachInfo, valeur)
        }
    }

    /** Parent no-op : évite les NPE des callbacks de fenêtre. */
    @Suppress("SoonBlockedPrivateApi") // targetSdk=28 — réflexion safe (ADR 0045, miroir AttacheurVue).
    private fun poserFauxParent(racine: View) {
        val fauxParent =
            Proxy.newProxyInstance(
                ViewParent::class.java.classLoader,
                arrayOf(ViewParent::class.java),
            ) { _, methode, _ ->
                when (methode.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    else -> null
                }
            } as ViewParent
        val champParent = View::class.java.getDeclaredField("mParent")
        champParent.isAccessible = true
        champParent.set(racine, fauxParent)
    }
}
