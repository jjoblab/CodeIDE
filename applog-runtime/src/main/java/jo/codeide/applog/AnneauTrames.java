package jo.codeide.applog;

import java.util.List;

/**
 * Anneau borné des trames en attente d'expédition (ADR 0103 §5).
 *
 * <p>Plusieurs fils produisent (lecteur logcat, miroir System.out/err,
 * gestionnaire d'exceptions) ; un seul consomme (l'expéditeur). Les
 * producteurs ne bloquent JAMAIS : l'anneau déborde par le VIEUX bout et
 * compte chaque trame perdue — le compteur voyage avec le lot suivant,
 * l'IDE l'affiche (jamais de perte silencieuse).
 *
 * <p>Les lignes émises pendant que l'IDE était absent ou en veille
 * tamponnent ici : la reconnexion vide l'anneau, elles ne sont pas
 * perdues — c'est la promesse « les lignes émises pendant que l'IDE
 * était en arrière-plan ne sont pas perdues » (bornée à la capacité de
 * l'anneau, annoncée honnêtement par le compteur de pertes).
 */
final class AnneauTrames {

    private final String[] tampon;

    /** Indice de la prochaine écriture. */
    private int curseurEcriture;

    /** Nombre de trames actuellement présentes. */
    private int nombre;

    /** Trames perdues par débordement, depuis le dernier rapport. */
    private long pertesNonRapportees;

    AnneauTrames(int capacite) {
        this.tampon = new String[capacite];
    }

    /**
     * Ajoute une trame — déborde par le vieux bout si plein (la perte est
     * comptée, jamais silencieuse).
     */
    synchronized void ajouter(String trame) {
        if (trame == null) {
            return;
        }
        tampon[curseurEcriture] = trame;
        curseurEcriture = (curseurEcriture + 1) % tampon.length;
        if (nombre < tampon.length) {
            nombre++;
        } else {
            pertesNonRapportees++;
        }
    }

    /**
     * Vide l'anneau vers la liste cible — au plus {@code maxTrames} trames
     * ET {@code maxCaracteres} caractères cumulés (limite de taille des
     * transactions Binder, ADR 0103 §5 : lots sous la limite mesurée).
     * Le reste attend le vidage suivant.
     *
     * @return le nombre de trames ajoutées à la cible
     */
    synchronized int extraire(List<String> cible, int maxTrames, int maxCaracteres) {
        int ajoutees = 0;
        int caracteres = 0;
        // Lecture dans l'ordre d'arrivée : la plus ancienne d'abord.
        int lecture = (curseurEcriture - nombre + tampon.length) % tampon.length;
        while (ajoutees < maxTrames && ajoutees < nombre) {
            String trame = tampon[lecture];
            int taille = trame == null ? 0 : trame.length();
            if (caracteres + taille > maxCaracteres && ajoutees > 0) {
                // La limite de caractères est atteinte : le lot part tel
                // quel, la trame attend la suivante.
                break;
            }
            cible.add(trame);
            caracteres += taille;
            ajoutees++;
            tampon[lecture] = null;
            lecture = (lecture + 1) % tampon.length;
        }
        nombre -= ajoutees;
        return ajoutees;
    }

    /**
     * Prend (et remet à zéro) le nombre de trames perdues depuis le dernier
     * rapport — l'expéditeur l'envoie avec le lot.
     */
    synchronized long prendrePertes() {
        long pertes = pertesNonRapportees;
        pertesNonRapportees = 0;
        return pertes;
    }

    /** Trames en attente (sonde de test). */
    synchronized int enAttente() {
        return nombre;
    }
}
