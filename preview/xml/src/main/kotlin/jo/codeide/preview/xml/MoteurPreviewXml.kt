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
 * Moteur de preview XML — PE1 → PE2 (PREVIEW.md §3, P1–P5).
 *
 * Orchestration : debounce 400 ms (défaut — « RenderService adapté »),
 * single-flight par mutex, états publiés ([etat]) — le RENDU lui-même
 * est DÉLÉGUÉ à un [TransportRendu] (PE2, P3) :
 *  - par défaut [TransportIsolation] : processus `:preview` isolé
 *    (AIDL, DeathRecipient, UNE reprise puis repli local honnête —
 *    §6) ;
 *  - les tests injectent le transport local (le pipeline PE1 inchangé).
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
    private var ferme = false

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
        soumissions.tryEmit(fournisseur)
    }

    /**
     * Soumet un rendu immédiat (ouverture du panneau, bascule d'onglet) :
     * PAS de debounce — mais TOUJOURS le mutex de rendu (single-flight).
     */
    public fun soumettreImmediat(fournisseur: () -> String) {
        if (ferme) return
        scope.launch { traiter(fournisseur) }
    }

    /**
     * Définit le dossier `res/` du projet (P5 — chemin POSIX résolu par
     * l'hôte via FUSE). Null = plus de projet : repli ressources natives.
     */
    public fun definirDossierRes(chemin: String?) {
        dossierRes = chemin
    }

    /** Coupe debounce, worker et transport (déliaison :preview). Idempotent. */
    public override fun close() {
        if (ferme) return
        ferme = true
        travailleur?.cancel()
        travailleur = null
        transport.close()
    }

    /** Traite une soumission (debouncée ou immédiate) : validation puis rendu, en single-flight. */
    private suspend fun traiter(fournisseur: () -> String) {
        verrouRendu.withLock {
            val texte = withContext(dispatchers.default) { fournisseur() }
            if (texte.isBlank()) return
            if (!jo.codeide.preview.xml
                    .xmlBienForme(texte)
            ) {
                // Frappe en cours : signal discret, le rendu précédent reste.
                if (etatInterne.value !is EtatPreviewXml.RenduValide) {
                    etatInterne.value = EtatPreviewXml.EnEdition
                }
                return
            }
            val resultat = withContext(dispatchers.default) { transport.rendre(texte, dossierRes) }
            etatInterne.value = etatDe(resultat)
        }
    }

    /** Traduit un résultat de transport en état du panneau. */
    private fun etatDe(resultat: ResultatTransport): EtatPreviewXml =
        when (resultat) {
            is ResultatTransport.Succes -> {
                EtatPreviewXml.RenduValide(
                    bitmap = resultat.bitmap,
                    dureeMs = resultat.dureeMs,
                    nombreVues = resultat.nombreVues,
                    largeur = resultat.largeur,
                    hauteur = resultat.hauteur,
                )
            }

            is ResultatTransport.Erreur -> {
                EtatPreviewXml.ErreurRendu(resultat.message)
            }
        }

    private companion object {
        private const val DEBOUNCE_MS = 400L
    }
}
