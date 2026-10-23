package jo.codeide.preview.compose

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executor

/**
 * Service de rendu Compose du processus `:preview` (C1 — PREVIEW.md §4
 * PC4, miroir ServicePreview) : bound-only (aucun startService — le
 * processus meurt de LUI-MÊME à la déliaison), stub SYNCHRONE
 * sérialisé par verrou (le runtime Compose n'est pas thread-safe), et
 * l'exécuteur vit sur le FIL PRINCIPAL du processus (le sien propre —
 * jamais un fil Binder, jamais le processus de l'IDE).
 *
 * Journal : le processus `:preview` n'a PAS de conteneur d'injection —
 * l'AppLogger vit côté app (miroir ServicePreview : journal null) ;
 * les échecs voyagent en DONNÉE dans [ResultatRenduCompose], la
 * politique de transport journalise côté app.
 */
public class ServicePreviewCompose : Service() {
    /** L'exécuteur du processus : le fil principal (Choreographer y vit). */
    private val executeurPrincipal: Executor by lazy { ContextCompat.getMainExecutor(this) }

    /** Le moteur de rendu — paresseux, possédé par le service. */
    private val rendu: RenduCompose by lazy {
        RenduCompose(
            contexte = this,
            executeur = executeurPrincipal,
            dispatcheurComposition = Dispatchers.Main,
            journal = { },
        )
    }

    /** Verrou du stub : les rendus sont SÉRIALISÉS (pas thread-safe). */
    private val verrou = Any()

    override fun onBind(intention: Intent?): IBinder = object : IRenduCompose.Stub() {
        override fun rendre(requete: RequeteRenduCompose): ResultatRenduCompose =
            synchronized(verrou) { rendu.rendre(requete) }
    }

    override fun onDestroy() {
        rendu.fermer()
        super.onDestroy()
    }
}
