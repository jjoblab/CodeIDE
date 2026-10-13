package jo.codeide.preview.xml

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service de rendu du processus `:preview` (PE2, PREVIEW.md §3 P3 —
 * manifest : `android:process=":preview"`, bound-only, non exporté).
 *
 * Miroir de la leçon CodeAssist (`:preview` + AIDL + DeathRecipient +
 * repli) : le rendu des layouts du PROJET ÉDITÉ vit ICI — une vue
 * framework qui crashe (une classe custom, un drawable hostile) tue CE
 * processus, l'éditeur observe la mort par DeathRecipient et reprend
 * (politique [TransportIsolation] côté app).
 *
 * Sémantique :
 *  - BOUND-ONLY : jamais démarré par startService — l'éditeur délié,
 *    Android détruit le service et le PROCESSUS meurt seul
 *    (idle-shutdown côté app, PREVIEW.md §3.2 : c'est le mécanisme
 *    même de l'arrêt, aucun minuteur interne nécessaire) ;
 *  - l'appel AIDL est SYNCHRONE (Binder thread) et SÉRIALISÉ (le
 *    pipeline LayoutLib n'est pas thread-safe — même contrat que le
 *    single-flight du moteur) ;
 *  - l'échec de rendu voyage en DONNÉE ([ResultatRendu] ok=false),
 *    seule la panne de TRANSPORT est une exception côté app.
 */
public class ServicePreview : Service() {
    /** Pipeline LayoutLib — propriété de CE processus. */
    private lateinit var rendu: RenduLayoutLib

    /** Verrou de sérialisation des rendus (inflater non thread-safe). */
    private val verrou = Any()

    override fun onCreate() {
        super.onCreate()
        rendu = RenduLayoutLib(applicationContext, journal = null)
    }

    override fun onBind(intention: Intent): IBinder =
        object : IRenduApercu.Stub() {
            override fun rendre(requete: RequeteRendu): ResultatRendu =
                synchronized(verrou) {
                    rendu.rendre(requete, compresser = true).rendu
                }
        }

    override fun onDestroy() {
        // Rien à disposer (bitmaps au GC d'ART, pas de fil possédé).
        super.onDestroy()
    }
}
