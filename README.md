# AWSOpenVPN

**Created by MR.TRYCATCH @ CODEMARVEL INDONESIA**

AWSOpenVPN is a modified build of [OpenVPN for Android](https://github.com/schwabe/ics-openvpn) (ics-openvpn v0.7.69) for **AWS Client VPN profiles with SAML sign-in (`auth-federate`)**. The sign-in opens in the browser. Android's VpnService then carries the private network to the apps you choose: by default Tiny Container, Termux, Chrome and Edge. Linux environments on an Android tablet can reach the company network this way.

The package is `id.tryca.awsopenvpn`, so it installs next to the original OpenVPN for Android. The interface is in English and Indonesian (Settings → App language).

> AWSOpenVPN is an independent project. It is not affiliated with OpenVPN Inc., Arne Schwabe or Amazon Web Services. "OpenVPN" is a trademark of OpenVPN Inc.

## Why not the stock OpenVPN apps?

OpenVPN Connect and the regular OpenVPN for Android cannot sign in to AWS Client VPN with SAML, and the official AWS VPN Client exists only for Windows, macOS and Linux. AWSOpenVPN adds:

1. **The SAML flow:**
   1. The app answers `Auth` with `N/A` / `ACS::35001`.
   2. The server replies with a `CRV1` challenge carrying the sign-in URL.
   3. The browser opens the sign-in page.
   4. A local receiver on `127.0.0.1:35001` takes the identity provider's `SAMLResponse` POST.
   5. The second `Auth` is answered with `CRV1::<session>::<SAMLResponse>`.
2. **A patched OpenVPN 2 core** whose buffers accept the ±10 KB SAML response. The stock core fails with *Key Method #2 write failed*.
3. **A pinned server IP:** `<random>.<endpoint>` is resolved once, because the SAML session belongs to that server.
4. **Per-app VPN by default**, so the SSO sign-in in the browser still works:
   - there is no tunnel during sign-in;
   - the callback goes to 127.0.0.1;
   - only private routes are tunnelled.

The SAML response is a credential. It stays in memory and is never logged.

### Sign-in providers

AWSOpenVPN is not tied to Microsoft. It works with whichever SAML 2.0 identity provider (IdP) the AWS Client VPN endpoint is configured with: Microsoft Entra ID (Azure AD), Okta, Google Workspace, OneLogin, JumpCloud, AWS IAM Identity Center or another SAML 2.0 IdP. The browser shows that provider's sign-in page.

| Authentication | Status |
|---|---|
| SAML, Microsoft Entra ID | tested |
| SAML, other IdPs | same flow, not tested yet (reports welcome) |
| Mutual certificate only | works as in regular OpenVPN for Android |
| Active Directory (user name/password) | not tested |

> AWS officially supports SAML sign-in only in its own AWS VPN Client. This app uses the same protocol as that client, so a change on the AWS side can break it without notice.

## Repository layout

```
README.md                 this file
README.ics-openvpn.md     the original ics-openvpn README
awsopenvpn/
  apply-patches.sh        applies the OpenVPN core patch to the submodule (after cloning)
  patches/                openvpn-aws-saml-buffers.patch (TLS_CHANNEL_BUF_SIZE, USER_PASS_LEN, OPTION_PARM_SIZE, management line)
  tools/                  translate_strings.py + l10n-overrides.json (Indonesian UI), vpn-dns / vpn-check.sh (DNS helpers for Tiny/Termux)
  branding/               icon sources (a_shield.svg is the app icon) and the logo options preview
main/                     the Android app (ics-openvpn sources with the AWSOpenVPN changes)
doc/LICENSE.txt           licence (GPLv2+ with the OpenSSL and Apache-2.0 linking exceptions)
```

Main changes in `main/`:

| Area | Change |
|---|---|
| `core/AwsSamlFlow.java` (new) | CRV1 parsing, random-hostname resolve, receiver on `127.0.0.1:35001` |
| `core/OpenVpnManagementThread.java` | AWS sign-in steps on the management interface |
| `core/OpenVPNService.java`, `core/Connection.java`, `VpnProfile.java` | server IP pinning, `auth-retry interact`, no password dialog for AWS profiles |
| `core/ConfigParser.java` | `auth-federate` marks an AWS profile; default allowed apps |
| `ui/…/VPNPreferences.kt`, `VPNProfileList.kt` | back arrow, fixed profile result key, no scroll-through tab switch, import guide |
| `ui/…/GeneralSettings.kt`, `ui/AndroidManifest.xml` | App language (System / English / Indonesian, AppCompat per-app locales) |
| `main/res/values-in`, `ui/res/values-in` | Indonesian strings (machine-translated on a private server with Qwen3-4B, validated and hand-corrected) |
| `ui/res/layout/about.xml` | AWSOpenVPN credit, why a custom build, icon note, licence summary |
| launcher icon | own icon (orange shield + cloud) to tell the apps apart |
| `main/build.gradle.kts`, `cpp/CMakeLists.txt` | `applicationId` `id.tryca.awsopenvpn`; OpenVPN 3 skipped by default (`-PwithOvpn3` restores it; it needs SWIG) |

## Build

Requirements:
- JDK 21.
- Android SDK with `platforms/android-37.0`, `build-tools/37.0.0`, `ndk/30.0.14904198` and `cmake/3.31.6`.

```sh
git clone --recursive <this repository> AWSOpenVPN && cd AWSOpenVPN
sh awsopenvpn/apply-patches.sh          # patch the openvpn submodule (once)
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleUiOvpn2Debug          # APK: main/build/outputs/apk/uiOvpn2/debug/
```

Notes:
- **Windows:** build natively with `gradlew.bat`, not through WSL `/mnt/c`, which is much slower.
- **Git on Windows:**
  - Set `core.autocrlf=false` before cloning, or the submodules show line-ending changes.
  - The `asio` symlinks appear as type changes; that is harmless.
- **Signing:** debug builds use the local debug keystore. Updates must be signed with the same key.

## Using it

1. Import the `.ovpn` file: + → Import. A guide explains the supported files.
2. Connect.
3. The Microsoft (or other identity provider) sign-in opens in the browser.
4. After you sign in, the page shows *login accepted* and the tunnel comes up.
5. Optional, inside Tiny Container: run `vpn-dns on <vpc-resolver-ip>` to point DNS at the VPC resolver (usually the VPC network address + 2), and `vpn-dns off` to restore it. Source: `awsopenvpn/tools/vpn-dns`.

## Version history

| Version | Change |
|---|---|
| 0.2.3 | Own launcher icon (option A) with an icon note in About; repository prepared for GitHub |
| 0.2.2 | About: why a custom build, licence summary |
| 0.2.1 | About: AWSOpenVPN credit and description |
| 0.2.0 | Import guide; App language setting; Indonesian UI |
| 0.1.3 | Fix: Back from the profile editor crashed the app |
| 0.1.2 | Back arrow in the profile editor; tab switch without scrolling through every page; Allowed apps header follows the dark theme |
| 0.1.1 | AWS profiles also route Chrome and Edge |
| 0.1.0 | AWS SAML sign-in, patched OpenVPN core, IP pinning, per-app VPN |

## App icon

The AWSOpenVPN icon (orange shield with a keyhole and a small cloud, `awsopenvpn/branding/a_shield.svg`) was designed by MR.TRYCATCH @ CODEMARVEL INDONESIA. It exists only to tell this app apart from OpenVPN for Android when both are installed. It does not replace the original OpenVPN for Android logo by Helen Beierling, which stays in the project and is credited in the app. It is not a logo of OpenVPN Inc. or Amazon Web Services.

## Support

If AWSOpenVPN helps you, you can support its development on Saweria: **https://saweria.co/trycatchs**

## License

AWSOpenVPN is a derivative work of OpenVPN for Android by Arne Schwabe. It is distributed under the **GNU GPL version 2 or later**, with the OpenSSL and Apache-2.0 linking exceptions; see `LICENSE` (same text as `doc/LICENSE.txt`). Third-party components keep their own licences (OpenVPN, OpenSSL, LZO, LZ4, mbed TLS, asio, fmt).

Anyone who distributes an APK of AWSOpenVPN must also provide the complete corresponding source code, including the patched OpenVPN submodule. This repository and `awsopenvpn/patches/` cover that.

---

Created by **MR.TRYCATCH @ CODEMARVEL INDONESIA**.
