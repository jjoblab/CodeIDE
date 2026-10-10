package jo.codeide.applog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import java.util.ArrayList;
import java.util.List;

/**
 * Expéditeur des trames vers l'IDE (ADR 0103 §1 et §5).
 *
 * <p>Un fil daemon boucle : toutes les 100 ms, l'anneau est vidé en LOTS
 * (au plus {@value #TRAMES_PAR_LOT} trames et {@value #CARACTERES_PAR_LOT}
 * caractères par lot — bien sous la limite mesurée des transactions
 * Binder), chaque appel est {@code oneway} : l'application n'attend jamais
 * l'IDE.
 *
 * <p>Liaison : le pont SE LIE au service de l'IDE (composant explicite) —
 * tant que l'application vit, la connexion vit, que l'IDE soit au
 * premier plan ou non ; la mort du processus IDE est vue par
 * {@code linkToDeath} (l'expédition s'arrête, l'anneau tamponne, la
 * liaison est retentée : la reconnexion vide l'anneau).
 *
 * <p>Fiabilité : aucun échec d'expédition ne remonte jamais à
 * l'application — le pont est un observateur muet.
 */
final class Expediteur extends Thread implements ServiceConnection, IBinder.DeathRecipient {

    /** Paquet de l'IDE (applicationId de CodeIDE). */
    private static final String PAQUET_IDE = "jo.codeide";

    /** Composant du service de journaux de l'IDE (explicite — jamais implicite). */
    private static final ComponentName SERVICE_IDE =
            new ComponentName(PAQUET_IDE, "jo.codeide.logcat.ServicePontJournaux");

    /** Trames maximum par lot (ADR 0103 §5 : lots courts). */
    private static final int TRAMES_PAR_LOT = 256;

    /** Caractères maximum par lot (limite de transaction Binder). */
    private static final int CARACTERES_PAR_LOT = 48000;

    /** Lots maximum expédiés en un tick (le reste attend le suivant). */
    private static final int LOTS_PAR_TICK = 4;

    /** Cadence de la boucle d'expédition. */
    private static final long CADENCE_MS = 100L;

    /** Cadence de RETENTATIVE de liaison quand l'IDE est absent. */
    private static final long RETENTATIVE_LIAISON_MS = 10000L;

    private final Context contexte;

    private final AnneauTrames anneau;

    private final String nomPaquet;

    private final int pid;

    private final String nomProcessus;

    /** Binder de contrôle local (IControlePont) — confié à l'IDE à la connexion. */
    private final IBinder controle;

    /** Proxy de l'IDE (null : pas connecté — l'anneau tamponne). */
    private volatile ILiaisonJournaux proxy;

    /** Liaison en cours (évite les bindService répétés). */
    private volatile boolean lie;

    /** Instant de la dernière tentative de liaison. */
    private volatile long derniereTentative;

    /** Arrêt demandé. */
    private volatile boolean arrete;

    Expediteur(
            Context contexte,
            AnneauTrames anneau,
            String nomPaquet,
            int pid,
            String nomProcessus,
            IBinder controle) {
        super("applog-expediteur");
        this.contexte = contexte;
        this.anneau = anneau;
        this.nomPaquet = nomPaquet;
        this.pid = pid;
        this.nomProcessus = nomProcessus;
        this.controle = controle;
        setDaemon(true);
    }

    @Override
    public void run() {
        while (!arrete) {
            assurerLiaison();
            expedierLots(false);
            dormir(CADENCE_MS);
        }
    }

    /**
     * Vidage immédiat, meilleur effort — appelé par le gestionnaire
     * d'exceptions AVANT que le processus ne meure : les lots partent
     * tout de suite, sans attendre le prochain tick.
     */
    void viderAvantMort() {
        if (proxy != null) {
            expedierLots(true);
        }
    }

    /**
     * Vidage de l'anneau vers l'IDE en lots bornés. Le compteur de pertes
     * est envoyé avec le PREMIER lot du vidage.
     *
     * @param immediat vrai depuis le chemin de plantage (au retrait du
     *                 plafond de lots par tick)
     */
    private void expedierLots(boolean immediat) {
        if (proxy == null) {
            return; // personne n'écoute : l'anneau tamponne
        }
        boolean pertesDejaEnvoyees = false;
        int plafondLots = immediat ? LOTS_PAR_TICK * 2 : LOTS_PAR_TICK;
        for (int numero = 0; numero < plafondLots; numero++) {
            List<String> lot = new ArrayList<String>(TRAMES_PAR_LOT);
            int prises = anneau.extraire(lot, TRAMES_PAR_LOT, CARACTERES_PAR_LOT);
            if (prises == 0) {
                break; // anneau vide : rien à expédier
            }
            try {
                // Le compteur de pertes part avec le PREMIER lot du
                // vidage, prélevé seulement quand quelque chose part
                // (un échec de lot ne l'avale pas).
                proxy.envoyerLot(lot, pertesDejaEnvoyees ? 0L : anneau.prendrePertes());
                pertesDejaEnvoyees = true;
            } catch (RemoteException ideDisparu) {
                proxy = null; // la boucle retentera la liaison
                return;
            }
        }
    }

    /**
     * Lie le service de l'IDE si ce n'est pas déjà fait — tentatives
     * espacées : l'IDE peut ne pas encore tourner quand l'app démarre
     * (lancement depuis l'écran d'accueil après un arrêt de CodeIDE).
     */
    private void assurerLiaison() {
        if (lie || proxy != null || arrete) {
            return;
        }
        long maintenant = System.currentTimeMillis();
        if (maintenant - derniereTentative < RETENTATIVE_LIAISON_MS) {
            return;
        }
        derniereTentative = maintenant;
        try {
            Intent intention = new Intent();
            intention.setComponent(SERVICE_IDE);
            lie = contexte.bindService(intention, this, Context.BIND_AUTO_CREATE);
        } catch (Throwable silencieuse) {
            // Visibilité du paquet refusée, IDE absent : on retentera —
            // un échec de LIAISON ne doit jamais toucher l'application.
            lie = false;
        }
    }

    /** Connexion établie : annoncer le pont, surveiller la mort de l'IDE. */
    @Override
    public void onServiceConnected(ComponentName composant, IBinder service) {
        proxy = ILiaisonJournaux.Stub.asInterface(service);
        try {
            service.linkToDeath(this, 0);
            proxy.connecter(nomPaquet, pid, nomProcessus, controle, Pont.VERSION_PROTOCOLE);
        } catch (RemoteException illisible) {
            proxy = null;
        }
        // La boucle vide l'anneau au tick suivant : les lignes émises
        // pendant l'absence de l'IDE partent alors (ADR 0103 §5).
    }

    /** Le service est parti (processus IDE mort) : veille, puis retentative. */
    @Override
    public void onServiceDisconnected(ComponentName composant) {
        proxy = null;
    }

    /** Mort du processus IDE : même veille. */
    @Override
    public void binderDied() {
        proxy = null;
        lie = false;
    }

    /** Arrêt (fermeture, tests). */
    void arreter() {
        arrete = true;
        interrupt();
    }

    /** Sommeil interrompu silencieux. */
    private void dormir(long millisecondes) {
        try {
            Thread.sleep(millisecondes);
        } catch (InterruptedException interrompu) {
            Thread.currentThread().interrupt();
        }
    }
}
