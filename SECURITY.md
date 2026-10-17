# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue or on Discord.

Use GitHub's private reporting: go to the [Security tab](https://github.com/spandan-kumar/PageDrop/security/advisories/new) and choose "Report a vulnerability". Include what you found, how to reproduce it, and the PageDrop version and Android version. We'll acknowledge it as soon as we can, work on a fix, and credit you in the release notes if you'd like.

PageDrop is maintained by volunteers, so please be patient, and give us a reasonable chance to ship a fix before you go public.

## Supported versions

Only the latest release gets security fixes.

## What's in scope

- The Android app in `app/`.
- The optional conversion server in `server/`.
- The release pipeline and published APKs.

Worth knowing about how PageDrop works:

- It connects over SSH/SFTP to a Kindle that **you** own and have jailbroken, using the KOReader SSH server on your local network. That access is by design. Reports about the app mishandling those credentials or connection (logging them, sending them elsewhere, skipping host checks where it shouldn't) are very welcome.
- The in-app browser loads third-party websites. It deliberately has no JavaScript-to-native bridge, no file access and no certificate-error bypass. If you find a way around that, please tell us.
- The conversion server is optional and can be turned off. Self-hosted deployments are your responsibility to secure, but flaws in our code or default configs are in scope.

Out of scope: weaknesses in KOReader, the Kindle jailbreak, Amazon's firmware, or third-party book sites themselves.

## Verify your download

Release APKs are signed with the PageDrop release key. The SHA-256 fingerprint of the signing certificate is:

```
c42b4f0044ebfd1785cb8e30e182a7861a6ab51554353c81459f87de395a0533
```

Check it with `apksigner` from the Android SDK build tools:

```bash
apksigner verify --print-certs PageDrop-vX.Y.Z.apk
```

The "Signer #1 certificate SHA-256 digest" should match the fingerprint above. A SHA-256 checksum file is attached to each release as well. If the fingerprint doesn't match, don't install the APK, and let us know.
