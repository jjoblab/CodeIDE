package jo.codeide.core.bootstrap.installation

import jo.codeide.core.bootstrap.DispositionsBootstrap
import jo.codeide.core.bootstrap.LocalisationOutils
import jo.codeide.core.domain.CommandResult
import jo.codeide.core.domain.CommandSpec
import jo.codeide.core.domain.InstallPhase
import jo.codeide.core.domain.InstallStep
import jo.codeide.core.domain.StepContext
import jo.codeide.core.domain.StepId
import jo.codeide.core.domain.ToolchainCatalog
import jo.codeide.core.model.AppError
import jo.codeide.core.model.AppError.CommandOutput
import jo.codeide.core.model.AppError.EnvironmentSetupReason
import java.io.File

/**
 * Phase 3 du parcours — `JAVA` (§ 5.3 du cahier, ADR 0088) : installation
 * du JDK du catalogue via `pkg`, résolution **unique** de `JAVA_HOME`
 * (règle de [LocalisationOutils], conservée et testée), vérification par
 * exécution réelle — `java -version` et `javac -version` démarrent, la
 * version majeure est analysée contre l'exigence du catalogue — puis
 * **test TLS** : une requête HTTPS Java réelle vers `dl.google.com`,
 * compilée et exécutée par le JDK fraîchement installé. Un truststore
 * cassé (`ca-certificates-java` n'est qu'un `Recommends` du paquet APT,
 * ADR 0084) est une cause classique d'échec **ultérieur** de
 * `sdkmanager` : elle est détectée ici, pas en phase 4.
 *
 * Aucune version n'est affichée sans exécution : la version recensée est
 * lue sur la sortie réelle de `java -version`, jamais en dur — le dépôt
 * APT est interrogé (`apt-cache policy`) et journalisé avant installation
 * (§ 5.3 : « interroge-le »).
 */
internal class PhaseJava(
    private val racine: File,
    private val catalogue: ToolchainCatalog,
) : PhaseInstallation {
    override val phase: InstallPhase = InstallPhase.JAVA

    override fun etapes(): List<InstallStep> = listOf(EtapePaquetJdk(racine, catalogue), EtapeVerificationTls(racine))

    override suspend fun recenserVersions(contexte: StepContext): Map<String, String> {
        val javaHome = LocalisationOutils.trouverJavaHome(racine) ?: return emptyMap()
        val resultat =
            contexte.commands.run(
                CommandSpec(
                    program = File(javaHome, "bin/java").absolutePath,
                    arguments = listOf("-version"),
                    workingDir = DispositionsBootstrap.prefix(racine),
                    timeoutMillis = DELAI_VERIFICATION,
                ),
            )
        val version = EXTRACTION_VERSION.find(sortieComplete(resultat))?.groupValues?.getOrNull(1)
        return if (version != null) mapOf(CLE_JDK to version) else emptyMap()
    }

    internal companion object {
        /** Clé du recensement (récapitulatif final et écran Environnement). */
        internal const val CLE_JDK: String = "jdk"

        /** `java -version` écrit sa bannière sur **stderr** — stdout ET stderr sont lus. */
        private val EXTRACTION_VERSION: Regex = Regex("""version "([^"]+)"""")

        /** Délai des contrôles de version (aligné sur le cadre, ADR 0087). */
        internal const val DELAI_VERIFICATION: Long = 30_000L
    }
}

/**
 * Étape `openjdk` : installation du paquet JDK du catalogue (`pkg install
 * -y <paquet>`), précédée de l'interrogation journalisée du dépôt APT
 * (`apt-cache policy` — transparence sur ce que le dépôt fournit
 * réellement, jamais un verdict), suivie du contrôle par exécution : la
 * JVM qui ne démarre pas (bibliothèque manquante, ADR 0084 R6) échoue
 * ici avec sa sortie capturée — plus jamais de « SDK non fonctionnel »
 * muet.
 *
 * **Le code de sortie de `pkg install` n'est pas un verdict** (constat
 * appareil réel v0.60.0, ADR 0092) : `apt` peut renvoyer 100 APRÈS une
 * installation réussie (« `E: Directory … missing` », avertissement
 * `EIPP::OrderInstall`) — paquets posés et configurés. Après un code
 * non nul, le contrôle réel du JDK tranche donc avant tout échec :
 * opérationnel = l'installation a réussi malgré le code (journalisé) ;
 * inexistant = l'échec `Commande` avec la sortie apt à l'appui.
 */
private class EtapePaquetJdk(
    private val racine: File,
    private val catalogue: ToolchainCatalog,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.JAVA, "openjdk")

    override suspend fun execute(context: StepContext) {
        val prefixe = DispositionsBootstrap.prefix(racine)
        journalerPolitiqueDepot(context)
        context.journal("installation du JDK ${catalogue.jdkPackage} via pkg")
        val installation =
            context.commands.run(
                CommandSpec(
                    program = File(prefixe, "bin/sh").absolutePath,
                    arguments = listOf("-c", "pkg install -y ${catalogue.jdkPackage}"),
                    workingDir = prefixe,
                    timeoutMillis = DELAI_INSTALLATION,
                ),
            )
        if (!installation.succeeded) {
            // Sortie apt non fiable : l'exécution réelle tranche (§ 3.2).
            context.journal(
                "pkg install a renvoyé le code ${installation.exitCode} — contrôle réel du JDK avant verdict",
            )
            if (controlerJdk(context) == null) {
                context.journal(
                    "JDK vérifié par exécution malgré le code ${installation.exitCode} " +
                        "(anomalie apt connue : sortie non fiable) — poursuite",
                )
                return
            }
            throw EchecEtapeInstallation(
                ErreursInstallation.commande(
                    description = "installation du paquet ${catalogue.jdkPackage} impossible",
                    commande = "pkg install -y ${catalogue.jdkPackage}",
                    resultat = installation,
                ),
            )
        }
        // « Installé = vérifié en l'exécutant » appliqué immédiatement : un
        // paquet posé dont la JVM ne démarre pas est un échec **diagnostiqué**
        // (sortie de java -version à l'appui), pas un échec générique.
        controlerJdk(context)?.let { echec -> throw EchecEtapeInstallation(echec) }
    }

    override suspend fun verify(context: StepContext): Boolean = controlerJdk(context) == null

    /**
     * Interrogation du dépôt APT, journalisée (§ 5.3) : ce que le dépôt
     * fournit réellement pour le paquet du catalogue — la décision
     * d'installation, elle, vient du catalogue, jamais de cette sortie.
     */
    private suspend fun journalerPolitiqueDepot(context: StepContext) {
        val prefixe = DispositionsBootstrap.prefix(racine)
        val politique =
            context.commands.run(
                CommandSpec(
                    program = File(prefixe, "bin/apt-cache").absolutePath,
                    arguments = listOf("policy", catalogue.jdkPackage),
                    workingDir = prefixe,
                    timeoutMillis = PhaseJava.DELAI_VERIFICATION,
                ),
            )
        if (politique.succeeded) {
            politique.stdout.take(LIMITE_JOURNAL_POLICY).forEach { context.journal("apt-cache: $it") }
        } else {
            context.journal(
                "apt-cache policy ${catalogue.jdkPackage} indisponible (code ${politique.exitCode}) — " +
                    "installation tentée selon le catalogue",
            )
        }
    }

    /**
     * Contrôle du JDK par exécution réelle — décomposé en prédicats purs
     * (démarrage de chaque binaire, majeure).
     *
     * @return `null` si le JDK est vérifié ; sinon l'erreur typée avec la
     * sortie capturée de la commande en cause.
     */
    private suspend fun controlerJdk(context: StepContext): AppError.EnvironmentSetup? {
        val javaHome =
            LocalisationOutils.trouverJavaHome(racine)
                ?: return AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.Jvm,
                    details =
                        "aucun JDK complet (bin/java et bin/javac) trouvé dans le préfixe — " +
                            "le paquet ${catalogue.jdkPackage} ne s'est pas installé correctement",
                )
        val versionJava = executer(context, javaHome, binaire = "java", argument = "-version")
        val versionJavac = executer(context, javaHome, binaire = "javac", argument = "-version")
        return controlerDemarrage(versionJava, "java")
            ?: controlerDemarrage(versionJavac, "javac")
            ?: controlerMajeure(versionJava)
    }

    /** Un binaire du JDK démarre-t-il (`-version`, code 0) ? Sinon, erreur typée avec sortie. */
    private fun controlerDemarrage(
        resultat: CommandResult,
        binaire: String,
    ): AppError.EnvironmentSetup? =
        if (resultat.succeeded) {
            null
        } else {
            erreurJvm("$binaire -version échoue (code ${resultat.exitCode})", resultat)
        }

    /** La majeure de la bannière réelle correspond-elle à l'exigence du catalogue ? */
    private fun controlerMajeure(versionJava: CommandResult): AppError.EnvironmentSetup? {
        val majeure = extraireMajeure(sortieComplete(versionJava))
        return if (majeure == catalogue.jdkMajorVersion) {
            null
        } else {
            AppError.EnvironmentSetup(
                reason = EnvironmentSetupReason.Jvm,
                details =
                    "version majeure $majeure installée, ${catalogue.jdkMajorVersion} exigée " +
                        "(catalogue) — le paquet du dépôt ne correspond pas à l'exigence",
                sortie = sortieCapturee("java -version", versionJava),
            )
        }
    }

    /** Exécute un binaire du JDK résolu (`java`/`javac`) avec son argument de version. */
    private suspend fun executer(
        context: StepContext,
        javaHome: File,
        binaire: String,
        argument: String,
    ): CommandResult =
        context.commands.run(
            CommandSpec(
                program = File(javaHome, "bin/$binaire").absolutePath,
                arguments = listOf(argument),
                workingDir = DispositionsBootstrap.prefix(racine),
                timeoutMillis = PhaseJava.DELAI_VERIFICATION,
            ),
        )

    private fun erreurJvm(
        details: String,
        resultat: CommandResult,
    ): AppError.EnvironmentSetup =
        AppError.EnvironmentSetup(
            reason = EnvironmentSetupReason.Jvm,
            details = details,
            sortie = sortieCapturee("contrôle du JDK", resultat),
        )

    private fun sortieCapturee(
        commande: String,
        resultat: CommandResult,
    ): CommandOutput =
        CommandOutput(commande = commande, exitCode = resultat.exitCode, lastLines = resultat.tail(BORNE_SORTIE))

    private companion object {
        /** Le JDK et ses dépendances pèsent plusieurs centaines de Mio : 10 minutes. */
        private const val DELAI_INSTALLATION: Long = 600_000L

        /** Sortie d'`apt-cache policy` bornée au journal (§ 3.4). */
        private const val LIMITE_JOURNAL_POLICY: Int = 10

        /** Bornage des sorties attachées aux échecs (§ 3.4). */
        private const val BORNE_SORTIE: Int = 200
    }
}

/**
 * Étape `verification-tls` : sonde HTTPS **Java** compilée puis exécutée
 * par le JDK installé — le contrôle couvre d'un seul geste la compilation
 * (`javac`), le démarrage de la JVM (`java`), le chargement du truststore
 * et le dialogue TLS réseau, exactement le socle dont `sdkmanager` a
 * besoin en phase 4. La cible `https://dl.google.com` est une **cible de
 * sonde** fixée par le cahier (§ 5.3), pas une source de composant : les
 * sources d'artefacts viennent exclusivement du manifeste (§ 12.7).
 */
private class EtapeVerificationTls(
    private val racine: File,
) : InstallStep {
    override val id: StepId = StepId(InstallPhase.JAVA, "verification-tls")

    override suspend fun execute(context: StepContext) {
        tester(context)?.let { echec -> throw EchecEtapeInstallation(echec) }
    }

    /** Le contrôle rejoue la sonde : réelle, rapide, idempotente (§ 3.2). */
    override suspend fun verify(context: StepContext): Boolean = tester(context) == null

    /**
     * Compile puis exécute la sonde TLS — décomposé (compilation,
     * exécution, classification).
     *
     * @return `null` si le dialogue TLS réussit ; sinon l'erreur typée —
     * truststore cassé → [EnvironmentSetupReason.Jvm], réseau coupé →
     * [EnvironmentSetupReason.Reseau] — avec la sortie complète capturée.
     */
    private suspend fun tester(context: StepContext): AppError.EnvironmentSetup? {
        val javaHome =
            LocalisationOutils.trouverJavaHome(racine)
                ?: return AppError.EnvironmentSetup(
                    reason = EnvironmentSetupReason.Jvm,
                    details = "test TLS impossible : aucun JDK complet trouvé dans le préfixe",
                )
        return compiler(context, javaHome) ?: executerSonde(context, javaHome)
    }

    /** Compile la sonde (`javac`) ; sinon, erreur typée avec la sortie du compilateur. */
    private suspend fun compiler(
        context: StepContext,
        javaHome: File,
    ): AppError.EnvironmentSetup? {
        val dossierSonde = DispositionsBootstrap.tmpdir(racine)
        dossierSonde.mkdirs()
        val source = File(dossierSonde, "$NOM_CLASSE.java")
        source.writeText(SOURCE_SONDE)
        val compilation =
            context.commands.run(
                CommandSpec(
                    program = File(javaHome, "bin/javac").absolutePath,
                    arguments = listOf(source.absolutePath),
                    workingDir = dossierSonde,
                    timeoutMillis = DELAI_COMPILATION,
                ),
            )
        return if (compilation.succeeded) {
            null
        } else {
            AppError.EnvironmentSetup(
                reason = EnvironmentSetupReason.Jvm,
                details = "la sonde TLS ne compile pas (javac, code ${compilation.exitCode})",
                sortie = CommandOutput("javac $NOM_CLASSE.java", compilation.exitCode, compilation.tail(BORNE_SORTIE)),
            )
        }
    }

    /** Exécute la sonde compilée (`java`) ; succès = `TLS_OK` sur stdout. */
    private suspend fun executerSonde(
        context: StepContext,
        javaHome: File,
    ): AppError.EnvironmentSetup? {
        val dossierSonde = DispositionsBootstrap.tmpdir(racine)
        context.journal("test TLS : requête HTTPS Java vers $CIBLE_SONDE")
        val sonde =
            context.commands.run(
                CommandSpec(
                    program = File(javaHome, "bin/java").absolutePath,
                    arguments = listOf("-cp", dossierSonde.absolutePath, NOM_CLASSE, CIBLE_SONDE),
                    workingDir = dossierSonde,
                    timeoutMillis = DELAI_SONDE,
                ),
            )
        return if (sonde.succeeded && sonde.stdout.any { it.contains(MARQUEUR_SUCCES) }) {
            null
        } else {
            classerEchec(sonde)
        }
    }

    /**
     * Classe l'échec de la sonde en langage clair (§ 8) : la pile d'exception
     * capturée dit si le truststore est en cause (PKIX, ValidatorException,
     * SSLHandshake) ou le réseau (UnknownHost, ConnectException, délai).
     */
    private fun classerEchec(sonde: CommandResult): AppError.EnvironmentSetup {
        val pile = sortieComplete(sonde)
        val truststore = TRACES_TRUSTSTORE.any { pile.contains(it) }
        val reseau = TRACES_RESEAU.any { pile.contains(it) }
        val details =
            when {
                truststore -> {
                    "test TLS échoué : truststore Java inutilisable " +
                        "(certificats racines absents ou cassés)"
                }

                reseau -> {
                    "test TLS échoué : $CIBLE_SONDE injoignable (réseau)"
                }

                else -> {
                    "test TLS échoué (code ${sonde.exitCode})"
                }
            }
        return AppError.EnvironmentSetup(
            reason = if (truststore || !reseau) EnvironmentSetupReason.Jvm else EnvironmentSetupReason.Reseau,
            details = details,
            sortie = CommandOutput("java $NOM_CLASSE $CIBLE_SONDE", sonde.exitCode, sonde.tail(BORNE_SORTIE)),
        )
    }

    private companion object {
        /** Cible de la sonde (§ 5.3) — cible de contrôle, jamais une source d'artefact. */
        private const val CIBLE_SONDE: String = "https://dl.google.com"

        /** Classe de la sonde — le nom du fichier doit correspondre. */
        private const val NOM_CLASSE: String = "SondeTls"

        /** Marqueur de succès émis sur stdout par la sonde. */
        private const val MARQUEUR_SUCCES: String = "TLS_OK"

        /** Compilation d'une classe unique : 2 minutes suffisent largement. */
        private const val DELAI_COMPILATION: Long = 120_000L

        /** Poignée de main TLS + HEAD sur réseau mobile : 1 minute. */
        private const val DELAI_SONDE: Long = 60_000L

        /** Bornage des sorties attachées aux échecs (§ 3.4). */
        private const val BORNE_SORTIE: Int = 200

        /** Traces d'un truststore cassé dans la pile capturée (langage clair, § 8). */
        private val TRACES_TRUSTSTORE =
            listOf("PKIX", "ValidatorException", "SSLHandshakeException", "truststore", "cacerts")

        /** Traces d'un réseau coupé dans la pile capturée. */
        private val TRACES_RESEAU =
            listOf("UnknownHostException", "ConnectException", "SocketTimeoutException", "timed out")

        /**
         * Source de la sonde : une requête HTTPS HEAD réelle, délais bornés,
         * succès signalé sur stdout (`TLS_OK <code>`), toute exception remonte
         * sur stderr avec la pile — capturée intégralement par le cadre.
         */
        private val SOURCE_SONDE: String =
            """
            import java.net.URL;
            import javax.net.ssl.HttpsURLConnection;

            /** Sonde TLS du parcours d'installation (générée, phase 3 — ADR 0088). */
            public final class SondeTls {
                public static void main(String[] args) throws Exception {
                    HttpsURLConnection connexion = (HttpsURLConnection) new URL(args[0]).openConnection();
                    connexion.setConnectTimeout(15000);
                    connexion.setReadTimeout(15000);
                    connexion.setRequestMethod("HEAD");
                    int code = connexion.getResponseCode();
                    System.out.println("TLS_OK " + code);
                    if (code < 200 || code >= 400) {
                        System.exit(3);
                    }
                }
            }
            """.trimIndent()
    }
}

/** stdout **puis** stderr, dans l'ordre d'émission — `java -version` écrit sur stderr. */
internal fun sortieComplete(resultat: CommandResult): String = (resultat.stdout + resultat.stderr).joinToString("\n")

/** Majeure de la bannière `version "17.0.20"` (ou `1.8.0` → 8) — décomposée (prédicats purs). */
internal fun extraireMajeure(banniere: String): Int? {
    val correspondance = Regex("""version "(\d+)(?:\.(\d+))?""").find(banniere) ?: return null
    return majeureDe(correspondance)
}

/** `17.0.20` → 17 ; l'ancien style `1.8.0` → 8. */
private fun majeureDe(correspondance: MatchResult): Int? {
    val majeure = correspondance.groupValues[1].toIntOrNull() ?: return null
    return if (majeure == 1) correspondance.groupValues[2].toIntOrNull() else majeure
}
