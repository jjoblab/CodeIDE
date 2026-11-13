package jo.codeide.applog;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Miroir de la console standard : tout ce que l'application écrit sur
 * {@code System.out} et {@code System.err} rejoint l'anneau du pont, en
 * PLUS de la destination d'origine (la console Android, qui le journalise
 * sous les étiquettes « System.out »/« System.err » — le lecteur logcat
 * ignore ces miroirs, ADR 0103 §4 : dé-doublonnage).
 *
 * <p>Ce miroir existe parce que le miroir logcat est pauvre : niveau et
 * étiquette fixes, fil de l'émetteur inconnu de l'app. Ici, chaque ligne
 * part avec le fil ÉMETTEUR réel et l'horodatage local exact.
 *
 * <p>Le miroir est posé une fois par processus ; les écritures
 * concurrentes de plusieurs fils sont sérialisées par le tampon interne
 * (la concaténation inter-fils serait de toute façon indéfinie, comme
 * sur la vraie console).
 */
final class TeeConsole {

    /** Taille au-delà de laquelle une ligne accumulée est coupée. */
    private static final int TAILLE_LIGNE_MAX = 8000;

    private TeeConsole() {
        // Classe utilitaire — jamais instanciée.
    }

    /**
     * Pose les miroirs sur les deux flux — retourne la clé d'annulation
     * (utilisé par les tests ; en production le miroir vit avec le
     * processus).
     */
    static void brancher(final AnneauTrames anneau, final int pid) {
        System.setOut(miroir(System.out, anneau, pid, 'I', "System.out"));
        System.setErr(miroir(System.err, anneau, pid, 'E', "System.err"));
    }

    /**
     * Fabrique le flux miroir : chaque ligne complètement écrite part dans
     * l'anneau ; TOUT passe aussi vers le flux d'origine (aucune perte,
     * aucune modification du comportement de l'app).
     */
    private static PrintStream miroir(
            final PrintStream origine,
            final AnneauTrames anneau,
            final int pid,
            final char niveau,
            final String etiquette) {
        OutputStream canal = new OutputStream() {
            private final StringBuilder ligne = new StringBuilder(128);

            @Override
            public void write(int octet) {
                origine.write(octet);
                accumuler((char) octet);
            }

            @Override
            public void write(byte[] octets, int decale, int taille) {
                origine.write(octets, decale, taille);
                for (int i = 0; i < taille; i++) {
                    accumuler((char) (octets[decale + i] & 0xFF));
                }
            }

            @Override
            public void flush() {
                origine.flush();
            }

            /** Accumule ; une fin de ligne emporte la trame. */
            private void accumuler(char caractere) {
                if (caractere == '\n') {
                    expedier();
                } else if (caractere != '\r') {
                    if (ligne.length() < TAILLE_LIGNE_MAX) {
                        ligne.append(caractere);
                    }
                }
            }

            /** La ligne est complète : trame vers l'anneau. */
            private void expedier() {
                if (ligne.length() == 0) {
                    return;
                }
                int tid = (int) Thread.currentThread().getId();
                anneau.ajouter(
                        Trames.ligne(
                                niveau,
                                System.currentTimeMillis(),
                                pid,
                                tid,
                                etiquette,
                                Trames.tronquer(ligne.toString())));
                ligne.setLength(0);
            }
        };
        // autoflush = true : même rythme de vidage que la console d'origine.
        return new PrintStream(canal, true);
    }
}
