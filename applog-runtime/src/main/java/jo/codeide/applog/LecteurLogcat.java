package jo.codeide.applog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecteur du logcat de SON PROPRE processus (ADR 0103 §4).
 *
 * <p>Une application non privilégiée ne lit que les journaux de son
 * propre UID — exactement le périmètre voulu : « les journaux de votre
 * application uniquement ».
 *
 * <p>Détails mesurés :
 *
 * <ul>
 *   <li>{@code logcat -v threadtime --pid=<pid>} filtre côté service de
 *       journaux ; si l'appareil refuse l'option {@code --pid}, le repli
 *       filtre côté lecteur (comparaison du pid de chaque ligne).</li>
 *   <li>Le PREMIER démarrage lit TOUT le tampon du pid : les lignes entre
 *       la naissance du processus et l'amorçage du pont (initialiseurs
 *       statiques) sont vues — l'honnêteté affichée « depuis la création
 *       du processus » est vraie. Les redémarrages du lecteur reprennent
 *       à l'horodatage de la dernière ligne lue ({@code -T <epoch>}) :
 *       ni doublon, ni trou.</li>
 *   <li>Les lignes miroir de {@code System.out}/{@code System.err} sont
 *       IGNORÉES ici : le miroir de console les fournit avec une
 *       meilleure provenance (dé-doublonnage ADR 0103 §4).</li>
 * </ul>
 */
final class LecteurLogcat extends Thread {

    /** Motif d'une ligne threadtime : date, heure, pid, tid, niveau, reste. */
    private static final Pattern MOTIF_LIGNE =
            Pattern.compile("^(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEF])\\s+(.*)$");

    /** Horizon de tri de l'année (un tampon ne couvre pas 12 mois). */
    private static final long HORIZON_MS = 24L * 60L * 60L * 1000L;

    /** Marqueur d'étiquettes miroir de la console (le miroir les fournit déjà). */
    private static final String ETIQUETTE_SORTIE = "System.out";

    /** Marqueur d'étiquettes miroir de la console d'erreur. */
    private static final String ETIQUETTE_ERREUR = "System.err";

    private final AnneauTrames anneau;

    private final int pid;

    /** Pause demandée par l'IDE via le binder de contrôle (veille). */
    private volatile boolean suspendu;

    /** Arrêt demandé (fermeture, tests). */
    private volatile boolean arrete;

    /** Horodatage de la dernière ligne lue (epoch ms) — reprise sans doublon. */
    private long derniereLigneMs;

    /** L'appareil a refusé {@code --pid} : filtrer côté lecteur. */
    private boolean repliFiltragePid;

    LecteurLogcat(AnneauTrames anneau, int pid) {
        super("applog-lecteur");
        this.anneau = anneau;
        this.pid = pid;
        setDaemon(true);
    }

    /** Suspension commandée par l'IDE (personne n'écoute). */
    void suspendre() {
        suspendu = true;
    }

    /** Reprise commandée par l'IDE. */
    void reprendre() {
        suspendu = false;
    }

    /** Arrêt du lecteur. */
    void arreter() {
        arrete = true;
        interrupt();
    }

    @Override
    public void run() {
        while (!arrete) {
            Process processus = null;
            try {
                processus = new ProcessBuilder(commande()).start();
                BufferedReader lecteur =
                        new BufferedReader(new InputStreamReader(processus.getInputStream()), 8192);
                String ligne;
                while (!arrete && (ligne = lecteur.readLine()) != null) {
                    consommer(ligne);
                }
                int code = processus.waitFor();
                if (!arrete && code != 0 && !repliFiltragePid) {
                    // L'appareil ne connaît pas --pid : filtrer côté lecteur.
                    repliFiltragePid = true;
                }
            } catch (InterruptedException interrompu) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception silencieuse) {
                // logcat indisponible ou tué : le lecteur réessaie — un
                // échec de LECTURE ne doit jamais toucher l'application.
            } finally {
                if (processus != null) {
                    processus.destroy();
                }
            }
            dormirAvantRetentative();
        }
    }

    /**
     * Consomme une ligne brute du logcat : analyse, filtre, trame.
     * Visible pour les tests du côté parsing.
     */
    void consommer(String ligne) {
        if (suspendu || ligne == null || ligne.isEmpty()) {
            return;
        }
        Matcher m = MOTIF_LIGNE.matcher(ligne);
        if (!m.matches()) {
            return;
        }
        int pidLigne = entier(m.group(7));
        if (pidLigne != pid) {
            return;
        }
        String reste = m.group(10).trim();
        // Le miroir de console fournit déjà ces lignes (avec meilleure
        // provenance) : on ne les double pas.
        if (reste.startsWith(ETIQUETTE_SORTIE) || reste.startsWith(ETIQUETTE_ERREUR)) {
            return;
        }
        long epochMs = horodatage(m);
        if (epochMs > 0) {
            derniereLigneMs = epochMs;
        }
        String etiquette = reste;
        String message = "";
        // Le séparateur étiquette/message de logcat est le PREMIER
        // deux-points — le message peut en contenir d'autres, l'étiquette
        // jamais (cas réels : « tag: », « tag: message », « tag :  msg »).
        int coupure = reste.indexOf(':');
        if (coupure >= 0) {
            message = reste.substring(coupure + 1);
            if (message.startsWith(" ")) {
                message = message.substring(1);
            }
            etiquette = reste.substring(0, coupure).trim();
        }
        anneau.ajouter(
                Trames.ligne(
                        m.group(9).charAt(0),
                        epochMs,
                        pidLigne,
                        entier(m.group(8)),
                        etiquette,
                        Trames.tronquer(message)));
    }

    /**
     * Reconstruit l'horodatage Unix : threadtime ne porte ni année ni
     * fuseau — l'année COURANTE est prise, reculée d'un an si la date
     * obtenue dépasserait l'horizon (roulement du 31 déc. au 1er janv.).
     */
    private long horodatage(Matcher m) {
        Calendar calendrier = Calendar.getInstance();
        calendrier.set(
                calendrier.get(Calendar.YEAR),
                entier(m.group(1)) - 1,
                entier(m.group(2)),
                entier(m.group(3)),
                entier(m.group(4)),
                entier(m.group(5)));
        calendrier.set(Calendar.MILLISECOND, entier(m.group(6)));
        long ms = calendrier.getTimeInMillis();
        if (ms > System.currentTimeMillis() + HORIZON_MS) {
            calendrier.add(Calendar.YEAR, -1);
            ms = calendrier.getTimeInMillis();
        }
        return ms;
    }

    /**
     * La commande logcat, argument par argument (jamais de shell — les
     * caractères des options restent littéraux) : filtre {@code --pid} au
     * premier essai, repli par filtrage de lecture si l'appareil la
     * refuse ; reprise à l'horodatage de la dernière ligne lue pour les
     * redémarrages.
     */
    private List<String> commande() {
        List<String> cmd = new ArrayList<String>();
        cmd.add("logcat");
        cmd.add("-v");
        cmd.add("threadtime");
        if (repliFiltragePid) {
            cmd.add("*:V");
        } else {
            cmd.add("--pid=" + pid);
        }
        if (derniereLigneMs > 0) {
            cmd.add("-T");
            cmd.add((derniereLigneMs / 1000L) + "." + String.format("%03d", derniereLigneMs % 1000L));
        }
        return cmd;
    }

    /**
     * Pause entre deux tentatives de lecture : bornée, discrète — le
     * lecteur doit rester vivant sans jamais charger l'appareil.
     */
    private void dormirAvantRetentative() {
        if (arrete) {
            return;
        }
        try {
            Thread.sleep(2000L);
        } catch (InterruptedException interrompu) {
            Thread.currentThread().interrupt();
        }
    }

    /** Entier décimal tolérant (−1 si illisible — la ligne est rejetée après). */
    private static int entier(String texte) {
        try {
            return Integer.parseInt(texte);
        } catch (NumberFormatException illisible) {
            return -1;
        }
    }
}
