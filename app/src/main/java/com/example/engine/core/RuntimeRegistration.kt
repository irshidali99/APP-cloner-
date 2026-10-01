package com.example.engine.core

import com.example.model.RuntimeOptions

/**
 * Writes the runtime patch into the clone's manifest.
 *
 * The clone gets two extra components:
 *
 *  - `com.appcloner.runtime.PatchProvider`, a `ContentProvider` **without** an intent filter. Android
 *    creates every provider of an app before its first activity, service or receiver, so this is the
 *    earliest hook a patch can use without touching the app's own classes (`Application.onCreate` has
 *    already run at that point). Its meta-data carries the configuration (passcode hash, feature flags).
 *  - `com.appcloner.runtime.LockActivity`, the passcode screen, which is opened over the app when a lock
 *    is configured.
 *
 * Both classes live in the dex file that [com.example.engine.runtime.RuntimePatcher] adds as an extra
 * `classesN.dex`, so the platform can resolve them.
 */
object RuntimeRegistration {

    const val PROVIDER_CLASS = "com.appcloner.runtime.PatchProvider"
    const val LOCK_ACTIVITY_CLASS = "com.appcloner.runtime.LockActivity"
    const val NOTIFICATION_LISTENER_CLASS = "com.appcloner.runtime.CloneNotificationListener"
    const val CONFIG_META = "com.appcloner.runtime.CONFIG"

    /** The platform only binds a notification listener that is guarded by this permission. */
    const val BIND_NOTIFICATION_LISTENER = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    const val NOTIFICATION_LISTENER_ACTION = "android.service.notification.NotificationListenerService"

    private const val AUTHORITY_SUFFIX = ".appcloner.runtime"
    private const val INIT_ORDER = 1000

    /** Component the provider is registered under; the authority has to be unique per device. */
    fun authority(clonePackage: String): String = clonePackage + AUTHORITY_SUFFIX

    /**
     * Adds the provider, its configuration and the lock activity to [editor].
     *
     * @return the human readable list of what was written, for the clone report.
     */
    fun apply(editor: AxmlEditor, options: RuntimeOptions, clonePackage: String): List<String> {
        if (options.isEmpty) return emptyList()
        val application = editor.startElements()
            .firstOrNull { editor.elementName(it) == "application" }
            ?: return listOf("runtime patch skipped (the manifest has no <application>)")

        val applied = ArrayList<String>()

        // ContentProvider: the entry point of the patch.
        val provider = editor.addChildElement(application, "provider")
        stringAttribute(editor, provider, "name", android.R.attr.name, PROVIDER_CLASS)
        stringAttribute(editor, provider, "authorities", android.R.attr.authorities, authority(clonePackage))
        booleanAttribute(editor, provider, "exported", android.R.attr.exported, false)
        booleanAttribute(editor, provider, "enabled", android.R.attr.enabled, true)
        intAttribute(editor, provider, "initOrder", android.R.attr.initOrder, INIT_ORDER)

        // The configuration travels with the manifest: the dex file itself stays unchanged for every clone.
        val metaData = editor.addChildElement(provider, "meta-data")
        stringAttribute(editor, metaData, "name", android.R.attr.name, CONFIG_META)
        stringAttribute(editor, metaData, "value", android.R.attr.value, options.configString())

        applied.add("runtime bootstrap provider")

        if (options.lockEnabled) {
            val lockActivity = editor.addChildElement(application, "activity")
            stringAttribute(editor, lockActivity, "name", android.R.attr.name, LOCK_ACTIVITY_CLASS)
            booleanAttribute(editor, lockActivity, "exported", android.R.attr.exported, false)
            booleanAttribute(
                editor, lockActivity, "excludeFromRecents", android.R.attr.excludeFromRecents, true
            )
            applied.add("passcode lock screen")
        }

        if (options.notificationFeatures) {
            // The clone's own notifications are filtered inside the clone; Android binds this service only
            // after the user allowed notification access for the clone.
            val service = editor.addChildElement(application, "service")
            stringAttribute(editor, service, "name", android.R.attr.name, NOTIFICATION_LISTENER_CLASS)
            stringAttribute(
                editor, service, "permission", android.R.attr.permission, BIND_NOTIFICATION_LISTENER
            )
            booleanAttribute(editor, service, "exported", android.R.attr.exported, true)
            booleanAttribute(editor, service, "enabled", android.R.attr.enabled, true)

            val filter = editor.addChildElement(service, "intent-filter")
            val action = editor.addChildElement(filter, "action")
            stringAttribute(
                editor, action, "name", android.R.attr.name, NOTIFICATION_LISTENER_ACTION
            )
            applied.add("notification filter service")
        }
        return applied
    }

    private fun stringAttribute(
        editor: AxmlEditor,
        element: AxmlNode.StartElement,
        name: String,
        resourceId: Int,
        value: String
    ) {
        val index = editor.intern(value)
        editor.addAttribute(
            element = element,
            namespaceUri = ManifestRules.ANDROID_NAMESPACE,
            name = name,
            resourceId = resourceId,
            valueType = 0x03, // string
            valueData = index,
            rawValue = index
        )
    }

    private fun booleanAttribute(
        editor: AxmlEditor,
        element: AxmlNode.StartElement,
        name: String,
        resourceId: Int,
        value: Boolean
    ) {
        editor.addAttribute(
            element = element,
            namespaceUri = ManifestRules.ANDROID_NAMESPACE,
            name = name,
            resourceId = resourceId,
            valueType = 0x12, // int boolean
            valueData = if (value) -1 else 0
        )
    }

    private fun intAttribute(
        editor: AxmlEditor,
        element: AxmlNode.StartElement,
        name: String,
        resourceId: Int,
        value: Int
    ) {
        editor.addAttribute(
            element = element,
            namespaceUri = ManifestRules.ANDROID_NAMESPACE,
            name = name,
            resourceId = resourceId,
            valueType = 0x10, // int dec
            valueData = value
        )
    }
}
