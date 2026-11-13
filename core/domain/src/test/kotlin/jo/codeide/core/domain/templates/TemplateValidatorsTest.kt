package jo.codeide.core.domain.templates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests des validateurs nommés (étape 8 — section 11 : `project-name`,
 * `package-name`, `identifier`, `semver`, `regex:<motif>`).
 *
 * Les valeurs valides rendent `null`, les invalides un message français
 * jamais vide — le wizard les affichera via le bloc « détails ».
 */
class TemplateValidatorsTest {
    @Test
    fun `un nom de projet simple est valide`() {
        assertNull(TemplateValidators.valider("project-name", "Mon Projet"))
    }

    @Test
    fun `un nom de projet est trimmé avant contrôle`() {
        assertNull(TemplateValidators.valider("project-name", "  Demo  "))
    }

    @Test
    fun `un nom de projet vide est refusé`() {
        assertNotNull(TemplateValidators.valider("project-name", "   "))
        assertNotNull(TemplateValidators.valider("project-name", ""))
    }

    @Test
    fun `un nom de projet trop long est refusé`() {
        assertNotNull(TemplateValidators.valider("project-name", "a".repeat(65)))
        assertNull(TemplateValidators.valider("project-name", "a".repeat(64)))
    }

    @Test
    fun `les caractères de chemin sont interdits dans un nom`() {
        for (interdit in "/\\:*?\"<>|") {
            assertNotNull(TemplateValidators.valider("project-name", "a${interdit}b"))
        }
    }

    @Test
    fun `un point final est refusé mais un espace est trimmé`() {
        assertNotNull(TemplateValidators.valider("project-name", "Demo."))
        // L'espace final est retiré par le trim avant contrôle (section 12.3).
        assertNull(TemplateValidators.valider("project-name", "Demo "))
    }

    @Test
    fun `les noms réservés Windows sont refusés`() {
        assertNotNull(TemplateValidators.valider("project-name", "CON"))
        assertNotNull(TemplateValidators.valider("project-name", "aux"))
        assertNotNull(TemplateValidators.valider("project-name", "com1"))
    }

    @Test
    fun `un package en segments minuscules est valide`() {
        assertNull(TemplateValidators.valider("package-name", "com.exemple.app"))
        assertNull(TemplateValidators.valider("package-name", "app"))
        assertNull(TemplateValidators.valider("package-name", "com.exemple.mon_projet"))
    }

    @Test
    fun `un package vide ou à segment vide est refusé`() {
        assertNotNull(TemplateValidators.valider("package-name", ""))
        assertNotNull(TemplateValidators.valider("package-name", "com..app"))
        assertNotNull(TemplateValidators.valider("package-name", "com.exemple."))
    }

    @Test
    fun `un segment de package doit commencer par une minuscule`() {
        assertNotNull(TemplateValidators.valider("package-name", "Com.exemple"))
        assertNotNull(TemplateValidators.valider("package-name", "com.Exemple"))
        assertNotNull(TemplateValidators.valider("package-name", "com.1exemple"))
    }

    @Test
    fun `un mot-clé Java ou Kotlin est refusé comme segment`() {
        assertNotNull(TemplateValidators.valider("package-name", "com.val"))
        assertNotNull(TemplateValidators.valider("package-name", "com.object"))
        assertNotNull(TemplateValidators.valider("package-name", "com.true"))
    }

    @Test
    fun `un identifiant standard est valide`() {
        assertNull(TemplateValidators.valider("identifier", "monIdentifiant"))
        assertNull(TemplateValidators.valider("identifier", "_prive"))
        assertNull(TemplateValidators.valider("identifier", "x1"))
    }

    @Test
    fun `un identifiant mal formé ou mot-clé est refusé`() {
        assertNotNull(TemplateValidators.valider("identifier", "1x"))
        assertNotNull(TemplateValidators.valider("identifier", "mon-identifiant"))
        assertNotNull(TemplateValidators.valider("identifier", "when"))
        assertNotNull(TemplateValidators.valider("identifier", "package"))
    }

    @Test
    fun `semver accepte les versions complètes`() {
        assertNull(TemplateValidators.valider("semver", "0.1.0"))
        assertNull(TemplateValidators.valider("semver", "1.0.0"))
        assertNull(TemplateValidators.valider("semver", "1.0.0-rc.1"))
        assertNull(TemplateValidators.valider("semver", "2.1.3+meta.donnees"))
    }

    @Test
    fun `semver refuse les formes approximatives`() {
        assertNotNull(TemplateValidators.valider("semver", "1.0"))
        assertNotNull(TemplateValidators.valider("semver", "01.0.0"))
        assertNotNull(TemplateValidators.valider("semver", "v1.0.0"))
        assertNotNull(TemplateValidators.valider("semver", "1.0.0.0"))
        assertNotNull(TemplateValidators.valider("semver", ""))
    }

    @Test
    fun `regex exige la correspondance complète`() {
        assertNull(TemplateValidators.valider("regex:^v\\d+$", "v42"))
        assertNotNull(TemplateValidators.valider("regex:^v\\d+$", "v42x"))
        assertNotNull(TemplateValidators.valider("regex:^v\\d+$", "42"))
    }

    @Test
    fun `un motif regex invalide est signalé`() {
        val erreur = TemplateValidators.valider("regex:[", "x")
        assertNotNull(erreur)
        assertTrue(erreur!!.contains("motif"))
    }

    @Test
    fun `les noms de validateurs connus sont exacts`() {
        assertEquals(
            setOf("project-name", "file-name", "package-name", "identifier", "semver"),
            TemplateValidators.NOMS,
        )
        assertTrue(TemplateValidators.nomConnu("project-name"))
        assertTrue(TemplateValidators.nomConnu("file-name"))
        assertTrue(TemplateValidators.nomConnu("regex:^a$"))
        assertFalse(TemplateValidators.nomConnu("regex:"))
        assertFalse(TemplateValidators.nomConnu("email"))
        assertFalse(TemplateValidators.nomConnu(""))
    }
}
