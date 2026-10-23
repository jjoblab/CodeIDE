package jo.codeide.preview.xml

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Service de rendu du processus `:preview` (PE2, PREVIEW.md §3 P3 —
 * manifest : `android:process=":preview"`, bound-only, non exporté).
 *
 * Architecture d'isolation (`:preview` + AIDL + DeathRecipient + repli) :
 * le rendu des layouts du PROJET ÉDITÉ vit ICI — une vue
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
    /**
     * Pipeline LayoutLib — propriété de CE processus, PARESSEUX (PE11l).
     *
     * Correctif PE11l (bug rapporté par le propriétaire : « le bandeau
     * erreurs est toujours affiché ») : la construction vivait dans
     * [onCreate] — la MOINDRE exception de [RenduLayoutLib] (une donnée
     * d'appareil inattendue, un état de plateforme absent) tuait le
     * processus `:preview` AVANT le binder : la liaison échouait DEUX
     * fois, la politique d'isolation désactivait le transport pour la
     * SESSION ([RegimeRendu.LOCAL_DEGRADE]) et le bandeau « processus
     * arrêté » restait affiché TANT QUE l'app vivait, rendu local
     * fonctionnel mais bandeau permanent. En PARESSEUX, la construction
     * glisse SOUS le filet du stub : son échec part en DONNÉE
     * (`ResultatRendu` ok=false, règle 6), le processus SURVIT et la
     * reprise de l'isolation (bouton « Réessayer ») retente un état
     * propre au lieu de mourir à chaque relance.
     */
    private val rendu: RenduLayoutLib by lazy { RenduLayoutLib(applicationContext, journal = null) }

    /** Verrou de sérialisation des rendus (inflater non thread-safe). */
    private val verrou = Any()

    override fun onCreate() {
        super.onCreate()
        // La construction du pipeline est PARESSEUSE (voir KDoc de
        // [rendu]) — onCreate ne fait RIEN qui puisse mourir.
    }

    override fun onBind(intention: Intent): IBinder = object : IRenduApercu.Stub() {
        /**
         * Correctif PE11k : la doc de contrat promet « l'échec de rendu
         * voyage en DONNÉE » — le filet [RenduLayoutLib.rendre] couvre
         * le pipeline, mais TOUTE exception résiduelle (parcel hostile,
         * erreur avant le pipeline) tuait le PROCESSUS `:preview` sur
         * le fil du binder : le propriétaire voyait le bandeau « a
         * cessé de répondre » revenir à chaque « Réessayer ». Le filet
         * du stub renvoie l'erreur en DONNÉE (règle 6) — le processus
         * survit, l'app affiche le message honnête.
         */
        override fun rendre(requete: RequeteRendu): ResultatRendu = synchronized(verrou) {
            try {
                // v0.166.4 — le bitmap voyage en PARCELABLE (ashmem)
                // : plus de compression PNG à cette frontière (retour
                // propriétaire : le PNG coûtait 1 à 2 s par rendu,
                // ressenti comme un gel avant chaque mise à jour).
                rendu.rendre(requete).rendu
            } catch (t: Throwable) {
                ResultatRendu(
                    ok = false,
                    message = t.message?.trim()?.ifEmpty { null } ?: t.javaClass.simpleName,
                    ligne = -1,
                    largeur = 0,
                    hauteur = 0,
                    nombreVues = 0,
                    dureeMs = 0,
                    bitmap = null,
                )
            }
        }
    }

    override fun onDestroy() {
        // Rien à disposer (bitmaps au GC d'ART, pas de fil possédé).
        super.onDestroy()
    }
}
