package com.example.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules that keep a clone installable next to the original app.
 *
 * Authorities and permissions are unique across the whole device; a clone that repeats one of them is
 * refused by Android (`INSTALL_FAILED_CONFLICTING_PROVIDER`, `INSTALL_FAILED_DUPLICATE_PERMISSION`).
 */
class ManifestRulesTest {

    @Test
    fun ownedAuthorityFollowsTheNewPackage() {
        val renamed = ManifestRules.rewriteAuthorities(
            "com.original.game.provider",
            "com.original.game",
            "com.original.game.clone1"
        )
        assertEquals("com.original.game.clone1.provider", renamed)
    }

    @Test
    fun authorityOfAnotherNamespaceMovesIntoTheClone() {
        val renamed = ManifestRules.rewriteAuthorities(
            "com.thirdparty.sdk.provider",
            "com.original.game",
            "com.original.game.clone1"
        )
        assertEquals("com.original.game.clone1.com.thirdparty.sdk.provider", renamed)
    }

    @Test
    fun twoClonesNeverShareAForeignAuthority() {
        val first = ManifestRules.rewriteAuthorities("com.thirdparty.sdk.provider", "com.app", "com.app.clone1")
        val second = ManifestRules.rewriteAuthorities("com.thirdparty.sdk.provider", "com.app", "com.app.clone2")
        assertNotEquals(first, second)
        assertTrue(first.endsWith("provider"))
    }

    @Test
    fun mixedAuthorityListIsRewrittenElementByElement() {
        val renamed = ManifestRules.rewriteAuthorities(
            "com.original.game.provider;com.thirdparty.sdk.provider",
            "com.original.game",
            "com.original.game.clone1"
        )
        assertEquals(
            "com.original.game.clone1.provider;com.original.game.clone1.com.thirdparty.sdk.provider",
            renamed
        )
    }

    @Test
    fun foreignPermissionGetsANameInsideTheClone() {
        assertEquals(
            "com.app.clone1.com.thirdparty.sdk.PERMISSION",
            ManifestRules.uniquePermissionName("com.thirdparty.sdk.PERMISSION", "com.app.clone1")
        )
    }

    @Test
    fun classNameQualificationIsUnchanged() {
        assertEquals("com.original.game.MainActivity", ManifestRules.qualifyClassName(".MainActivity", "com.original.game"))
        assertEquals("com.original.game.MainActivity", ManifestRules.qualifyClassName("MainActivity", "com.original.game"))
        assertEquals("com.other.pkg.Activity", ManifestRules.qualifyClassName("com.other.pkg.Activity", "com.original.game"))
    }
}
