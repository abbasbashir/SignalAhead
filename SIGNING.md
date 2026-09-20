# Signing and updates

This repository builds testing APKs. A fixed GitHub Actions cache reuses the debug
keystore. DEBUG-CERT-SHA256.txt (once enrolled) pins its certificate: a missing or
different key then fails the build instead of silently producing an incompatible APK.
The cache can expire; it is not a durable production keystore backup.

For durable signing, configure these repository Actions secrets:

- ANDROID_KEYSTORE_BASE64: your backed-up JKS/PKCS12 signing key, base64 encoded
- ANDROID_STORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

The workflow supports these secrets. This session cannot configure them through the
available GitHub tools. Never commit signing keys or passwords into this public repository.
Changing from the existing debug certificate to a different signing key requires a
one-time reinstall. Export before uninstalling; 0.3 JSON backup can be restored,
whereas 0.2 CSV export is only a readable archive.

Protect the production key independently of GitHub. Debug signing is for testing only.
