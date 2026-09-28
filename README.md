<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# App Cloner

Create a second, separately installable copy of a compatible Android app: the clone gets its own package
name, its own launcher label and badge, its own self-signed certificate, and it installs next to the
original instead of replacing it.

Built with Kotlin + Jetpack Compose. The cloning engine itself is pure Kotlin/JVM code (no Android
framework inside it), which is why it can be verified with plain unit tests on a laptop.

## How cloning works

1. **Inspect** – the selected app's `base.apk` is opened as a zip archive (streamed, never fully buffered).
2. **Validate** – system apps and split APKs (App Bundles) are rejected up front, and the target package
   name must fit into the source app's resource table header.
3. **Rewrite** – `AndroidManifest.xml` (binary AXML) is parsed and re-serialised with the new package
   name, provider authorities, class names and label; `resources.arsc` gets the new package name in its
   package chunk; the rendered launcher icon replaces the original bitmap (stored + 4-byte aligned as
   Android's resource loader requires, `.so` entries 16 KiB aligned).
4. **Sign** – a JAR v1 signature (`META-INF/MANIFEST.MF`, `CERT.SF`, `CERT.RSA` with a real PKCS#7
   structure) is written during the rewrite, then an APK Signature Scheme v2 signing block is added,
   because Android 11+ refuses to install targetSdk 30+ APKs without it.
5. **Verify** – the finished APK is re-opened, the v2 signature and content digest are recomputed, the
   manifest and resource table package names are read back, and only then is the APK offered for
   installation. The result screen shows the schemes found (`v1+v2`) and the certificate fingerprint.

Signing uses a persistent self-signed identity stored in the app's private storage
(`clone-signing.p12` + random password). It is generated on first use and reused afterwards, so clones
can be updated later (Android requires the same certificate for updates).

## Run locally

**Prerequisites:** Android Studio (or a JDK 17+ and the Android SDK for command line builds), plus an
Android device or emulator with API 24+.

1. Open the project in Android Studio and let it sync.
2. Build and install the debug variant, or from the command line:

   ```bash
   ./gradlew assembleDebug          # builds app/build/outputs/apk/debug/app-debug.apk
   ./gradlew testDebugUnitTest      # runs the cloning engine tests on the JVM
   ```

   The debug build uses AGP's own debug keystore and the application id gets a `.debug` suffix, so it
   installs next to a release build.

### Release builds

Release signing material is **not** stored in this repository. Provide it either through environment
variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) or a git-ignored
`keystore.properties` file in the project root:

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Without that material the release build produces an unsigned APK instead of failing on a missing file.
Never commit a keystore: `.gitignore` already blocks `*.jks`, `*.keystore`, `keystore.properties` and
`key.properties`.

## Verification status

The engine is covered by unit tests that build a synthetic APK fixture (`TestFixtures`), clone it, and
then check the result with independent verifiers:

* `java.util.jar` (the same v1 verifier Android uses) accepts the generated signature,
* the engine's v2 verifier recomputes the chunked content digest and validates the RSA signature,
* tampering with a signed APK is detected,
* old `META-INF` signature files are replaced, `resources.arsc` stays stored and aligned,
* package identity survives in both the manifest and the resource table.

The generated clone was additionally cross-checked with [androguard](https://github.com/androguard/androguard)
(independent v1/v2 implementation) and `openssl`.

## Limitations (by design, not bugs)

* **Split APKs / App Bundles**: apps installed as `base.apk` + `split_config.*.apk` cannot be merged into
  one valid APK without a source rebuild. They are rejected with an explanation.
* **System apps**: bound to platform keys, cannot be re-signed independently.
* **DRM / Play Integrity**: banking, streaming and other apps that check for the original signing
  certificate or Play Integrity will not work inside a clone.
* **Signature scheme v3/v4** is not produced (v1 + v2 only). Android accepts v1/v2 for sideloaded APKs.
* The clone keeps the original app's resources and code; this tool re-targets the package identity, it
  does not decompile or modify app logic.

## Security note

An earlier revision of this project shipped a real Play upload keystore inside the repository, so it must
be treated as compromised. If that key was ever uploaded to Google Play, request an upload key reset in
the Play Console before publishing again. The keystore has been removed from the working tree and is
blocked by `.gitignore`; purge it from the Git history of any public fork as well.
