// SPDX-License-Identifier: GPL-3.0-or-later
package dev.may.taskmanager.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `isVersionGreater recognizes newer beta version over older stable`() {
        // Cenário que falhava anteriormente com 26-beta sendo descartado por toIntOrNull()
        assertTrue(UpdateManager.isVersionGreater("1.0.26-beta", "1.0.24"))
        assertTrue(UpdateManager.isVersionGreater("v1.0.26-beta", "1.0.25"))
        assertTrue(UpdateManager.isVersionGreater("1.0.26-beta", "v1.0.25"))
        assertTrue(UpdateManager.isVersionGreater("1.0.27-beta", "1.0.26"))
    }

    @Test
    fun `isVersionGreater recognizes stable version is newer than pre-release of same version`() {
        // Conforme SemVer 2.0.0: versão normal estável tem maior precedência que pré-release correspondente
        assertTrue(UpdateManager.isVersionGreater("1.0.26", "1.0.26-beta"))
        assertTrue(UpdateManager.isVersionGreater("v1.0.26", "1.0.26-beta"))
    }

    @Test
    fun `isVersionGreater returns false when remote beta is compared against same stable`() {
        // Usuário já na 1.0.26 estável não deve receber oferta para regredir para a 1.0.26-beta
        assertFalse(UpdateManager.isVersionGreater("1.0.26-beta", "1.0.26"))
    }

    @Test
    fun `isVersionGreater compares identical versions as not greater`() {
        assertFalse(UpdateManager.isVersionGreater("1.0.26", "1.0.26"))
        assertFalse(UpdateManager.isVersionGreater("1.0.26-beta", "1.0.26-beta"))
        assertFalse(UpdateManager.isVersionGreater("v1.0.26-beta", "1.0.26-beta"))
    }

    @Test
    fun `isVersionGreater compares pre-release revisions correctly`() {
        assertTrue(UpdateManager.isVersionGreater("1.0.26-beta2", "1.0.26-beta1"))
        assertTrue(UpdateManager.isVersionGreater("1.0.26-beta.2", "1.0.26-beta.1"))
        assertFalse(UpdateManager.isVersionGreater("1.0.26-beta1", "1.0.26-beta2"))
    }

    @Test
    fun `isVersionGreater handles patch and minor revisions correctly`() {
        assertTrue(UpdateManager.isVersionGreater("1.1.0", "1.0.26"))
        assertTrue(UpdateManager.isVersionGreater("2.0.0", "1.99.99"))
        assertTrue(UpdateManager.isVersionGreater("1.0.26.1", "1.0.26"))
        assertFalse(UpdateManager.isVersionGreater("1.0.26", "1.0.26.1"))
        assertFalse(UpdateManager.isVersionGreater("1.0.23", "1.0.26"))
    }

    @Test
    fun `ParsedVersion handles clean parsing of tags with v and metadata`() {
        val parsed = UpdateManager.ParsedVersion.parse("v1.0.26-beta+build123")
        assertEquals(listOf(1, 0, 26), parsed.numbers)
        assertEquals("beta", parsed.preRelease)

        val stable = UpdateManager.ParsedVersion.parse("V2.1.3")
        assertEquals(listOf(2, 1, 3), stable.numbers)
        assertEquals(null, stable.preRelease)
    }
}
