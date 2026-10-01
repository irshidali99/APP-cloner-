package com.example.engine.core

/** Outcome of re-targeting a manifest. */
data class ManifestRewriteReport(
    val originalPackage: String,
    val newPackage: String,
    val applicationLabelChanged: String?,
    val qualifiedComponents: Int,
    val authoritiesChanged: Int,
    val attributesChanged: Int,
    /** Authorities that did not belong to the original package and were moved into the clone's namespace. */
    val foreignAuthorities: List<String>,
    /** Permissions owned by the original app that were re-targeted to the new package. */
    val renamedPermissions: List<String> = emptyList()
)

/**
 * Rewrites a parsed `AndroidManifest.xml` so that the same code base can be installed a second time under a
 * new package name.
 *
 * What has to change for Android to treat the clone as a separate app:
 *  1. the `package` attribute - defines the application id;
 *  2. `android:authorities` of every `<provider>` - authorities are globally unique, a duplicate makes the
 *     install fail with `INSTALL_FAILED_CONFLICTING_PROVIDER`;
 *  3. component class names that rely on package-relative resolution;
 *  4. optionally the launcher label.
 */
object ManifestRewriter {

    /**
     * @param retargetComponents when `false` only the `package` attribute is rewritten. That is all a
     *   split APK of an app bundle needs: splits carry no launcher entry, no label and usually no
     *   providers, and their `split` attribute has to stay untouched.
     */
    fun rewrite(
        editor: AxmlEditor,
        newPackage: String,
        cloneLabel: String? = null,
        retargetComponents: Boolean = true
    ): ManifestRewriteReport {
        val root = editor.startElements().firstOrNull { editor.elementName(it) == "manifest" }
            ?: throw IllegalArgumentException("manifest element not found")

        val packageAttribute = editor.findAttribute(root, null, "package")
            ?: throw IllegalArgumentException("manifest has no package attribute")

        val originalPackage = editor.string(packageAttribute.rawValue)
            ?: throw IllegalArgumentException("manifest package attribute is empty")

        if (originalPackage == newPackage) {
            throw IllegalArgumentException("new package must differ from the original package")
        }

        var attributesChanged = 0
        var qualifiedComponents = 0

        // 1. application id ---------------------------------------------------------------
        editor.setStringAttribute(packageAttribute, newPackage)
        attributesChanged++

        // 2. providers, permissions, class names ------------------------------------------
        val foreignAuthorities = mutableListOf<String>()
        var authoritiesChanged = 0
        var labelChanged: String? = null

        if (!retargetComponents) {
            return ManifestRewriteReport(
                originalPackage = originalPackage,
                newPackage = newPackage,
                applicationLabelChanged = null,
                qualifiedComponents = 0,
                authoritiesChanged = 0,
                attributesChanged = attributesChanged,
                foreignAuthorities = emptyList()
            )
        }

        // Permissions this app declares for itself (for example the
        // "<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" that recent build tools add) must move to the
        // new package, otherwise the install fails with INSTALL_FAILED_DUPLICATE_PERMISSION because another
        // installed package already owns that permission.
        val renamedPermissions = LinkedHashSet<String>()

        // Permissions the app declares but names outside its own namespace (for example a bundled SDK).
        // They are globally unique too, so the clone cannot declare them again; they are renamed and every
        // reference to them follows.
        val foreignPermissions = LinkedHashMap<String, String>()

        for (element in editor.startElements()) {
            val elementName = editor.elementName(element) ?: continue

            for (attribute in element.attributes) {
                val attributeName = editor.attributeName(attribute) ?: continue
                val namespace = editor.attributeNamespace(attribute)
                val android = namespace == ManifestRules.ANDROID_NAMESPACE
                val noNamespace = namespace == null

                // authorities: com.original.app.fileprovider -> com.clone.app.fileprovider
                if (android && attributeName == "authorities") {
                    val current = editor.string(attribute.rawValue) ?: continue
                    val rewritten = ManifestRules.rewriteAuthorities(current, originalPackage, newPackage)
                    if (rewritten != current) {
                        editor.setStringAttribute(attribute, rewritten)
                        authoritiesChanged++
                        attributesChanged++
                    }
                    // Authorities outside this package are moved into the clone's namespace, otherwise the
                    // clone collides with the app that is already installed.
                    current.split(';').forEach { part ->
                        val trimmed = part.trim()
                        if (trimmed.isNotEmpty() &&
                            ManifestRules.rewritePackagePrefix(trimmed, originalPackage, newPackage) == null
                        ) {
                            foreignAuthorities.add(trimmed)
                        }
                    }
                    continue
                }

                // permissions owned by this app are renamed together with the package: a permission name is
                // globally unique, so declaring "<original>.SOME_PERMISSION" from a second package fails
                if ((android || noNamespace) && attributeName == "name" &&
                    (elementName in ManifestRules.DECLARED_PERMISSION_ELEMENTS ||
                        elementName in ManifestRules.CONSUMED_PERMISSION_ELEMENTS)
                ) {
                    val current = editor.string(attribute.rawValue) ?: continue
                    val rewritten = ManifestRules.rewritePackagePrefix(current, originalPackage, newPackage)
                    if (rewritten != null && rewritten != current) {
                        editor.setStringAttribute(attribute, rewritten)
                        renamedPermissions.add(current)
                        attributesChanged++
                    } else if (rewritten == null && elementName in ManifestRules.DECLARED_PERMISSION_ELEMENTS) {
                        val unique = ManifestRules.uniquePermissionName(current, newPackage)
                        foreignPermissions[current] = unique
                        editor.setStringAttribute(attribute, unique)
                        renamedPermissions.add(current)
                        attributesChanged++
                    }
                    continue
                }

                // class names that resolve relative to the manifest package
                if ((android || noNamespace) && attributeName == "name" &&
                    elementName in ManifestRules.CLASS_NAME_ELEMENTS
                ) {
                    val current = editor.string(attribute.rawValue) ?: continue
                    if (ManifestRules.needsQualification(current)) {
                        editor.setStringAttribute(
                            attribute,
                            ManifestRules.qualifyClassName(current, originalPackage)
                        )
                        qualifiedComponents++
                        attributesChanged++
                    }
                    continue
                }

                if (android && attributeName == "targetPackage") {
                    val current = editor.string(attribute.rawValue) ?: continue
                    val rewritten = ManifestRules.rewritePackagePrefix(current, originalPackage, newPackage)
                    if (rewritten != null && rewritten != current) {
                        editor.setStringAttribute(attribute, rewritten)
                        attributesChanged++
                    }
                    continue
                }

                if (android && attributeName == "targetActivity") {
                    val current = editor.string(attribute.rawValue) ?: continue
                    if (ManifestRules.needsQualification(current)) {
                        editor.setStringAttribute(
                            attribute,
                            ManifestRules.qualifyClassName(current, originalPackage)
                        )
                        qualifiedComponents++
                        attributesChanged++
                    }
                    continue
                }

                // package derived values such as taskAffinity / process / sharedUserId
                if (android && attributeName in ManifestRules.PACKAGE_PREFIX_ATTRIBUTES) {
                    val current = editor.string(attribute.rawValue) ?: continue
                    val rewritten = ManifestRules.rewritePackagePrefix(current, originalPackage, newPackage)
                    if (rewritten != null && rewritten != current) {
                        editor.setStringAttribute(attribute, rewritten)
                        attributesChanged++
                    }
                    continue
                }

                // launcher label
                if (cloneLabel != null && android && attributeName == "label" && elementName == "application") {
                    editor.setStringAttribute(attribute, cloneLabel)
                    labelChanged = cloneLabel
                    attributesChanged++
                }
            }
        }

        // A renamed permission has to be renamed everywhere it is referenced: the components that are
        // protected by it (android:permission / readPermission / writePermission) and the uses-permission
        // entries of the same name.
        if (foreignPermissions.isNotEmpty()) {
            for (element in editor.startElements()) {
                val elementName = editor.elementName(element) ?: continue
                for (attribute in element.attributes) {
                    val attributeName = editor.attributeName(attribute) ?: continue
                    val referencesPermission = attributeName in ManifestRules.PERMISSION_REFERENCE_ATTRIBUTES
                    val namesPermission = attributeName == "name" &&
                        (elementName in ManifestRules.DECLARED_PERMISSION_ELEMENTS ||
                            elementName in ManifestRules.CONSUMED_PERMISSION_ELEMENTS)
                    if (!referencesPermission && !namesPermission) continue

                    val current = editor.string(attribute.rawValue) ?: continue
                    val renamed = foreignPermissions[current] ?: continue
                    editor.setStringAttribute(attribute, renamed)
                    attributesChanged++
                }
            }
        }

        return ManifestRewriteReport(
            originalPackage = originalPackage,
            newPackage = newPackage,
            applicationLabelChanged = labelChanged,
            qualifiedComponents = qualifiedComponents,
            authoritiesChanged = authoritiesChanged,
            attributesChanged = attributesChanged,
            foreignAuthorities = foreignAuthorities,
            renamedPermissions = renamedPermissions.toList()
        )
    }
}
