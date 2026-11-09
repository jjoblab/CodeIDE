// Banc de mesure JVM du parseur de flux Gradle — lot L0 du chantier
// « Centre de sorties » (aucun code de production : outillage de mesure).
//
// But : donner un ordre de grandeur HONNÊTE du débit d'un parseur de lignes
// de sortie (classification par expressions régulières précompilées) sur le
// corpus réel docs/archive/tooling-scenarios/sorties/*.log et sur un flux
// synthétique de ~100 000 lignes, pour alimenter l'ADR 0159 (choix du moteur
// d'affichage — renuméroté : 0153–0158 déjà pris). Le parseur de production
// (lot L1, Kotlin) reste à écrire ; ces chiffres sont un PLANCHER de
// faisabilité JVM, pas une mesure appareil.
//
// Lancement (Java 11+, aucune dépendance) :
//   java scripts/banc-parseur-sorties/BancParseur.java \
//        docs/archive/tooling-scenarios/sorties/*.log
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.*;

public class BancParseur {

    // Règles de classification (prototype du parseur de L1 — mêmes motifs).
    static final Pattern TACHE = Pattern.compile("^> Task (:\\S+)");
    static final Pattern BUILD_OK = Pattern.compile("^BUILD SUCCESSFUL(?: in (\\S+))?");
    static final Pattern BUILD_KO = Pattern.compile("^BUILD FAILED(?: in (\\S+))?");
    static final Pattern ERREUR_KOTLIN = Pattern.compile("^e: (?:file://)?(\\S+):(\\d+):(\\d+): (.*)$");
    static final Pattern ERREUR_JAVAC = Pattern.compile("^(\\S+\\.java):(\\d+): (?:error|warning): (.*)$");
    static final Pattern FAILURE = Pattern.compile("^FAILURE: Build failed");
    static final Pattern WHERE = Pattern.compile("^\\* Where:");
    static final Pattern WHAT_WENT_WRONG = Pattern.compile("^\\* What went wrong:");
    static final Pattern TRY = Pattern.compile("^\\* Try:");
    static final Pattern CAUSED_BY = Pattern.compile("^Caused by: (.*)$");
    static final Pattern AVERT_KOTLIN = Pattern.compile("^w: (?:file://)?(\\S+):(\\d+):(\\d+): (.*)$");

    // Compteurs cumulés (empêche toute élimination de code morte).
    static long nTaches, nErreurs, nAvertissements, nVerdicts, nBlocs, nCauses, nAutres;

    static void classer(String ligne) {
        if (TACHE.matcher(ligne).find()) { nTaches++; return; }
        if (ERREUR_KOTLIN.matcher(ligne).find()) { nErreurs++; return; }
        if (ERREUR_JAVAC.matcher(ligne).find()) { nErreurs++; return; }
        if (AVERT_KOTLIN.matcher(ligne).find()) { nAvertissements++; return; }
        if (BUILD_OK.matcher(ligne).find() || BUILD_KO.matcher(ligne).find()) { nVerdicts++; return; }
        if (FAILURE.matcher(ligne).find() || WHERE.matcher(ligne).find()
                || WHAT_WENT_WRONG.matcher(ligne).find() || TRY.matcher(ligne).find()) { nBlocs++; return; }
        if (CAUSED_BY.matcher(ligne).find()) { nCauses++; return; }
        nAutres++;
    }

    static void reinitialiser() {
        nTaches = nErreurs = nAvertissements = nVerdicts = nBlocs = nCauses = nAutres = 0;
    }

    public static void main(String[] args) throws Exception {
        List<Path> corpus = new ArrayList<>();
        for (String a : args) corpus.add(Paths.get(a));

        System.out.printf(Locale.ROOT, "== Corpus réel : %d fichiers ==%n", corpus.size());
        long totalLignes = 0;
        long debutTotal = System.nanoTime();
        for (Path p : corpus) {
            byte[] octets = Files.readAllBytes(p);
            String texte = new String(octets, StandardCharsets.UTF_8);
            String[] lignes = texte.split("\n", -1);
            reinitialiser();
            long t0 = System.nanoTime();
            for (String l : lignes) classer(l);
            long ns = System.nanoTime() - t0;
            totalLignes += lignes.length;
            System.out.printf(Locale.ROOT,
                "%-46s %6d lignes %8d o   %8.1f ms   %9.0f lignes/s%n",
                p.getFileName().toString(), lignes.length, octets.length,
                ns / 1e6, lignes.length / (ns / 1e9));
            if (nAutres == Long.MAX_VALUE) System.out.print("x"); // garde-fou
        }
        long nsTotal = System.nanoTime() - debutTotal;
        System.out.printf(Locale.ROOT,
            "TOTAL corpus (classification seule, lecture comprise) : %d lignes en %.1f ms — %.0f lignes/s moyen%n%n",
            totalLignes, nsTotal / 1e6, totalLignes / (nsTotal / 1e9));

        // Flux synthétique : ~100 000 lignes réalistes (2 000 répétitions
        // d'un bloc de 50 lignes : tâches, erreurs cliquables, avertissements).
        StringBuilder sb = new StringBuilder(4 << 20);
        for (int i = 0; i < 2_000; i++) {
            sb.append("> Task :app:compileDebugKotlin\n");
            for (int j = 0; j < 6; j++) sb.append("ligne de sortie ordinaire ").append(j).append('\n');
            sb.append("e: file:///home/z/projet/app/src/main/kr/MainActivity.kt:")
              .append(i % 400).append(":12: Référence non résolue fooBar\n");
            for (int j = 0; j < 3; j++)
                sb.append("w: file:///home/z/projet/app/src/main/kr/Vieille.kt:")
                  .append(j).append(":1: variable inutilisée\n");
            sb.append("> Task :app:mergeDebugResources\n> Task :app:checkDebugAarMetadata\n");
            for (int j = 0; j < 34; j++)
                sb.append("Sortie Gradle plaine sans structure ").append(j).append('\n');
        }
        String synth = sb.toString();
        String[] lignesSynth = synth.split("\n", -1);
        long meilleurNs = Long.MAX_VALUE;
        for (int passe = 0; passe < 3; passe++) {
            reinitialiser();
            long t0 = System.nanoTime();
            for (String l : lignesSynth) classer(l);
            long ns = System.nanoTime() - t0;
            if (ns < meilleurNs) meilleurNs = ns;
        }
        System.out.printf(Locale.ROOT,
            "Synthétique : %d lignes — meilleur des 3 passes : %.1f ms — %,.0f lignes/s%n",
            lignesSynth.length, meilleurNs / 1e6, lignesSynth.length / (meilleurNs / 1e9));
        System.out.printf(Locale.ROOT,
            "Classées (dernière passe) : taches=%d erreurs=%d avertissements=%d verdicts=%d blocs=%d autres=%d%n",
            nTaches, nErreurs, nAvertissements, nVerdicts, nBlocs, nAutres);
    }
}
