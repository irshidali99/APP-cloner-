package com.example.engine.core

/** Outcome of re-targeting a manifest. */
data class ManifestRewriteReport(
    val originalPackage: String,
    val newPackage: String,
    val applicationLabelChanged: String?,
    val qualifiedComponents: Int,
    val authoritiesChanged: Int,
    val attributesChanged: Int,
    val foreignAuthorities: List<String>
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

    fun rewrite(
        editor: AxmlEditor,
        newPackage: String,
        cloneLabel: String? = null
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
                    // Authorities that do not belong to this package would collide with the original app.
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

                // declared permissions keep their identity so the app's own permission checks still work
                if (noNamespace && attributeName == "name" &&
                    elementName in ManifestRules.DECLARED_PERMISSION_ELEMENTS
                ) {
                    continue
                }

                // class names that resolve relative to the manifest package
                if ((android || noNamespace) && attributeName == "name" &&
                    (elementName in ManifestRules.CLASS_NAME_ELEMENTS ||
                        elementName in ManifestRules.CONSUMED_PERMISSION_ELEMENTS)
                ) {
                    if (elementName in ManifestRules.CONSUMED_PERMISSION_ELEMENTS) continue
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

        return ManifestRewriteReport(
            originalPackage = originalPackage,
            newPackage = newPackage,
            applicationLabelChanged = labelChanged,
            qualifiedComponents = qualifiedComponents,
            authoritiesChanged = authoritiesChanged,
            attributesChanged = attributesChanged,
            foreignAuthorities = foreignAuthorities
        )
    }
}
