package jo.codeide.feature.diagnostics

import jo.codeide.core.domain.RapportDiagnosticGit
import jo.codeide.core.domain.ResultatCommandeGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du formateur du Diagnostic Git (v0.90.1, mission « section Git
 * figée » étape A) : l'affichage montre les valeurs réelles, la COPIE
 * est expurgée (règle 15 : aucune donnée personnelle) — le test éprouve
 * la frontière exactement.
 */
class FormateurDiagnosticGitTest {
    /** Sortie réaliste de `git config --list --show-origin` (identités + safe.directory). */
    private val configListeReelle =
        listOf(
            "file:/data/user/0/jo.codeide/files/home/.gitconfig  user.name=Jean Dupont",
            "file:/data/user/0/jo.codeide/files/home/.gitconfig  user.email=jean.dupont@exemple.fr",
            "file:/etc/gitconfig  safe.directory=/storage/emulated/0/Documents/MonProjet",
            "file:/data/user/0/jo.codeide/files/home/.gitconfig  " +
                "http.https://github.com/.extraheader=AUTHORIZATION: basic c2VjcmV0",
        ).joinToString("\n") + "\n"

    /** Rapport réaliste : uid divergents (cas dubious ownership), stderr complet. */
    private val rapport =
        RapportDiagnosticGit(
            nomProjet = "MonProjet",
            cheminFuse = "/storage/emulated/0/Documents/MonProjet",
            cheminBinaire = "/data/user/0/jo.codeide/files/usr/bin/git",
            versionGit = "git version 2.47.3",
            uidEffectif = 10_123L,
            uidProprietaireDossier = 10_247L,
            pointDeMontage = "/storage/emulated/0",
            typeSystemeFichiers = "fuse",
            revParse =
                ResultatCommandeGit(
                    code = 128,
                    sortieStandard = "",
                    // Message canonique de git 2.35.2+ (dubious ownership).
                    sortieErreur =
                        "fatal: detected dubious ownership in repository at " +
                            "/storage/emulated/0/Documents/MonProjet",
                ),
            configList =
                ResultatCommandeGit(
                    code = 0,
                    sortieStandard = configListeReelle,
                    sortieErreur = "",
                ),
            environnement =
                mapOf(
                    "HOME" to "/data/user/0/jo.codeide/files/home",
                    "PATH" to "/data/user/0/jo.codeide/files/usr/bin",
                ),
        )

    @Test
    fun `l affichage porte les valeurs reelles`() {
        val texte = FormateurDiagnosticGit.formater(rapport)

        assertTrue(texte.contains("MonProjet"))
        assertTrue(texte.contains("/storage/emulated/0/Documents/MonProjet"))
        assertTrue(texte.contains("git version 2.47.3"))
        assertTrue(texte.contains("10 123".replace(" ", "")) || texte.contains("10123"))
        assertTrue(texte.contains("dubious ownership"))
        // La preuve uid divergents est lisible côte à côte.
        assertTrue(texte.contains("uid effectif (application) : 10123"))
        assertTrue(texte.contains("uid propriétaire du dossier : 10247"))
    }

    @Test
    fun `la copie ne contient ni chemin ni nom de projet ni identite`() {
        val copie = FormateurDiagnosticGit.formaterExpurge(rapport)

        // Nom de projet masqué.
        assertFalse(copie.contains("MonProjet"))
        assertTrue(copie.contains("<projet>"))
        // Chemins masqués (LogRedactor).
        assertFalse(copie.contains("/storage/emulated/0/Documents/MonProjet"))
        assertFalse(copie.contains("/data/user/0/jo.codeide"))
        // Courriel masqué, identité masquée.
        assertFalse(copie.contains("jean.dupont@exemple.fr"))
        assertFalse(copie.contains("Jean Dupont"))
        assertFalse(copie.contains("c2VjcmV0"))
        assertTrue(copie.contains("user.name=<masqué>"))
        assertTrue(copie.contains("extraheader=<masqué>"))
    }

    @Test
    fun `la copie reste diagnostique — type du refus, uid, code, safe directory`() {
        val copie = FormateurDiagnosticGit.formaterExpurge(rapport)

        // Le TYPE de refus survit au masquage du chemin : c'est lui qui
        // départage les hypothèses.
        assertTrue(copie.contains("dubious ownership"))
        // Les uid et codes sont des nombres : ils survivent.
        assertTrue(copie.contains("uid effectif (application) : 10123"))
        assertTrue(copie.contains("uid propriétaire du dossier : 10247"))
        assertTrue(copie.contains("Code de sortie : 128"))
        // La PRÉSENCE de safe.directory reste visible (la valeur, elle,
        // est un chemin — masquée).
        assertTrue(copie.contains("safe.directory"))
    }

    @Test
    fun `les valeurs indisponibles sont explicites pas inventees`() {
        val vide =
            RapportDiagnosticGit(
                nomProjet = null,
                cheminFuse = null,
                cheminBinaire = null,
                versionGit = null,
                uidEffectif = null,
                uidProprietaireDossier = null,
                pointDeMontage = null,
                typeSystemeFichiers = null,
                revParse = null,
                configList = null,
                environnement = emptyMap(),
            )

        val texte = FormateurDiagnosticGit.formater(vide)

        assertTrue(texte.contains("(indisponible)"))
        assertTrue(texte.contains("Non exécuté"))
    }

    @Test
    fun `l environnement est rendu ligne par ligne`() {
        val texte = FormateurDiagnosticGit.formater(rapport)

        assertTrue(texte.contains("HOME="))
        assertTrue(texte.contains("PATH="))
    }
}
