# TalkTact · WeChat Chat Copilot (Xposed module)

[中文 README](README.md) ｜ **English**

TalkTact puts a **small button** in the top-right corner of a WeChat chat screen. Tap it and you get a card with
**a risk read + 3 reply candidates** (safe / witty / push-forward). Reading the last few messages, calling an
LLM-compatible endpoint of your choice, and **filling the reply box** is all it does — it **never sends** for you.
The card is collapsed by default, so it does not cover your messages.

This is the independently maintained [Sakura520222/TalkTact](https://github.com/Sakura520222/TalkTact) fork of
[shibry88-netizen/TalkTact](https://github.com/shibry88-netizen/TalkTact), preserving the original attribution and GPL-3.0.
The app name **TalkTact** and package **io.github.shibry88_netizen.talktact** are unchanged.
Only selected valuable upstream changes are integrated; this fork does not submit code upstream.

The first independent fork test build is **0.8.12-fork.1** (versionCode 40), based on upstream 0.8.12.
Download it from the [prerelease](https://github.com/Sakura520222/TalkTact/releases/tag/v0.8.12-fork.1).
The app name and package are preserved, but the signing key differs from upstream: export a backup before uninstalling the original app, then install this fork and import it.
See [docs/FORK.md](docs/FORK.md) for maintenance and build instructions, and [CHANGELOG.md](CHANGELOG.md) for changes.

---

## Compatibility

Since v0.7.0 this module targets the **libxposed Modern API 102** (`minApiVersion=102`), so the framework itself
must provide API 102:

| Framework | Status |
| :--- | :--- |
| **LSPosed / Vector** | ✅ Requires libxposed API 102. With an older framework the module shows as incompatible and is never loaded — upgrade the framework first. |
| **NPatch** (no root) | ✅ Supports the modern API 102. |
| **Original LSPatch** | ❌ Not supported. It was archived in Dec 2023, before libxposed's modern API; its loader only understands the legacy `assets/xposed_init`, so it "does not recognise" modern modules at all. Use NPatch instead. |

> The no-root (NPatch) route patches the target APK: scope is chosen when patching, not via the module's `scope.list`.
> Make sure patched WeChat can log in on its own before worrying about the module.

The upstream baseline was tested against **WeChat 8.0.78**. This fork test build has not yet been revalidated on a physical device.

## Features

- **Reply card** — floats over the chat screen; reads the last N messages, gives 3 candidates with different
  strategies (safe / witty / push-forward). Tap to fill the input box, long-press to copy.
- **Multiple skills** — ships with several built-in prompts; you can also paste the URL of a `SKILL.md` on GitHub
  and the JSON output contract is added automatically.
- **Per-contact profiles** — archives what you chat about, keyed by WeChat name (the same line is kept once per
  hour). Write "who this person is to you" and "your relationship", and both (plus the archived lines) are folded
  into the prompt.
- **Liquid glass navigation** — on Android 13+ at high quality, the bar retains sharp background refraction at the rim and gradually adds Gaussian blur toward the center, with soft highlights, gradient edges and spring motion. Icons and labels stay sharp; each entire tab is tappable. Low quality or an unavailable shader uses a plain card.
- **HyperIsland-inspired pages** — a status dashboard, visible mentor presets and editor, searchable role list, reply preview and grouped settings. Direct entries replace accordions and the generic advanced-settings hub. Large headings, soft grouped cards, dynamic colors and light/dark themes retain the liquid-glass bottom bar.
- **★ Best pick + per-reply rewrite** — the recommended candidate is marked with a one-line reason
  (`best` / `why` in the output contract); each candidate has a ✎ button (**shorter / more formal / rephrase**) that
  rewrites **only that one**. Replies over 60 characters are flagged "· long".
- **Direct / graded mode** — the default "direct" mode uses one endpoint and one call. Switch to "graded" and the
  work is split into two **parallel** lanes (one judges intent + risk, one writes the 3 replies) merged into one
  card. Leave the second endpoint blank to reuse the first; **if one lane fails the other still renders**
  (a failed risk lane is shown as "not assessed") instead of showing an empty card.
- **Local proxy: the API key never reaches the WeChat process** — the app runs a foreground service bound to
  `127.0.0.1` only (random token required). The key stays in the app process; WeChat only talks to loopback and the
  app forwards the request.
- **Chat whitelist** — only the chats you tick work: everything else is not read, not archived, not sent. A chat
  whose name cannot be recognised counts as **not ticked** (fail-closed). Tap "pull chat list" → go back to WeChat's
  home / contacts list for two seconds → come back and tick; chats you opened are collected too.
- **Offline OCR for images** — text inside a chat image is recognised **on device** (ML Kit Chinese model) and used
  as the content of that message. **Raw images are not uploaded or written to disk**; the image only travels between the two local processes over loopback.
  Recognised text becomes chat context sent to the model endpoint configured by the user. If it cannot be recognised it falls back to the
  `[image/emoji/voice]` placeholder. The price is a bigger APK (~70 MB).
- **Steadier answers** — optional **strict JSON output** (only on the candidate-generation path); one automatic
  corrective retry when the model ignores the contract; when output is cut off by `max_tokens` the finished replies
  are salvaged instead of failing the whole call.
- **Diagnostics bundle** — "Advanced → Backup" exports a `.zip` (environment / config / profile counts /
  diagnostics / last call / usage). It **contains chat content and no API key**, so you can share it when reporting
  an issue.
- **Self-checks** — heartbeat (is the module really running inside WeChat right now), endpoint self-test, sensitive
  content check, view-hierarchy diagnostics, and the exact payload that was actually sent.

## Signing

This fork uses its own stable signing key. Because it retains the original package, it cannot coexist with the original app.
Before switching, export a backup in Settings, uninstall the previous app, install this fork and import the backup.

Local private files stay under ignored `keystore/`. Release builds use the repository secrets
`FORK_KEYSTORE_BASE64`, `FORK_KEYSTORE_PASSWORD`, and `FORK_KEYSTORE_ALIAS`; the resulting APK certificate
must match [signing/certificate.sha256](signing/certificate.sha256).
Ordinary CI produces temporary debug builds. Release builds require the stable key and fail if it is missing.
The inherited `signing/key.p12.enc` belongs to upstream and is not used by this fork.
See [signing/README.md](signing/README.md).

## Requirements

- **Android 12 or newer (API 31+)** — `minSdk = 31`. The real background blur behind the glass panel uses
  `Modifier.blur` (backed by `RenderEffect`), which only exists from Android 12 onwards.
- **A framework providing libxposed API 102**: LSPosed / Vector, or root-free NPatch.
- Upstream baseline: WeChat **8.0.78**. Physical-device behavior of this fork test build remains unverified.

## Building

Use **JDK 17, the Gradle 9.3.1 Wrapper, and AGP 9.1.1**. Android SDK packages and Android Studio setup are documented in [docs/FORK.md](docs/FORK.md).

```powershell
./gradlew.bat :app:testDebugUnitTest --no-daemon --stacktrace
./gradlew.bat :app:assembleDebug --no-daemon
./gradlew.bat :app:assembleRelease --no-daemon  # requires this fork's stable key
```

On Linux / macOS use `./gradlew`. Tests need no phone, but the Android SDK is still required.
APKs are under `app/build/outputs/apk/{debug,release}/`.
Branch pushes and PRs run tests and a debug build. Version tags prepare a draft Release only in
[this fork](https://github.com/Sakura520222/TalkTact/releases), including the verified release APK and SHA-256.
Existing releases and assets are not overwritten.

## Installing

1. Install the APK (package `io.github.shibry88_netizen.talktact`) → enable "TalkTact" in LSPosed → **tick WeChat in
   scope** → force-stop WeChat and reopen it.
2. Open the app: fill in the endpoint (OpenAI-compatible, including `/v1`), API key and model under "Settings".
3. Back on the home screen, tap "endpoint self-test" — it really sends one minimal request so you can confirm the
   address / key / model are right.
4. Open a chat in WeChat; a small "copilot" button appears in the top-right corner. Tap it to expand the reply card.

**Changing settings does not require restarting WeChat**: the configuration is pushed to the WeChat process live.
Only LSPosed-level changes (such as scope) need a force-stop.

## Privacy

In one sentence: **it only reads the few chat lines you can see right now, and sends them to the endpoint you
configured. It never sends automatically, never reports anywhere, and never touches the WeChat database.**

- Chat content, profiles and skill prompts go to **the endpoint you configured** (OpenAI-compatible). Make sure you
  trust it.
- The API key is stored in plain text in the app's private directory by default — injected code in WeChat has to read
  it across processes, and Keystore keys are per-UID, so encrypting it would make it unreadable. Turning on
  "Advanced → Local proxy" keeps the key inside the app process.
- **Image OCR happens on device**: the image only travels over loopback between the two processes, is recognised by
  an offline model, and is never uploaded or written to disk.
- The IP-geolocation lookup is the **only** third-party request this module makes, and it can be disabled.
- Messages that trip the sensitive-content check are **not archived into profiles**, so they can never end up in a
  future prompt either.

Full details (what is read, where it goes, what is stored, how to delete it, and what is explicitly out of scope):
[publish/PRIVACY.md](publish/PRIVACY.md).

## Known limitations

- Reliable for 1:1 chats only; group chats try to include nicknames, but their row structure changes often.
- Depends on WeChat's current message-list widget types; a WeChat update may break it.
- The APK is large (~70 MB): it bundles the ML Kit Chinese OCR model and native libraries — that is the price of
  offline recognition.
- Profile history is **partial**: the module only sees the lines that are on screen while you are in that chat.

## Layout

```
app/src/main/java/dev/goutou/wingman/
├── MainActivity.kt / ModuleStatus.kt / HeartbeatReceiver.kt   app entry, module probe, heartbeat
├── config/
│   ├── Config.kt           configuration read/write (shared key space with the injected side)
│   └── Roles.kt            profile archival / 1-hour dedup / serialisation (pure functions)
├── llm/
│   ├── Json.kt             minimal hand-written JSON
│   ├── Suggestion.kt       parsing and tolerance for model output
│   ├── Prompt.kt           built-in prompts
│   └── LlmClient.kt        OpenAI-compatible client (HttpURLConnection on purpose)
├── wechat/
│   ├── Snapshot.kt         plain intermediate representation (view tree → snapshot)
│   ├── ChatParser.kt       ★ snapshot → chat log: pure function, unit-testable
│   ├── Chrome.kt           rules for non-content rows (timestamps, unread counts, group labels)
│   ├── Sensitive.kt        local privacy check before sending
│   ├── ViewReader.kt       ★ the only part that depends on Android: view tree → snapshot
│   └── Overlay.kt          the floating card and its lifecycle
└── ui/
    ├── Ui.kt               theme / backdrop / page navigation
    ├── HyperComponents.kt  shared page and settings components
    ├── LiquidGlass.kt      liquid-glass bottom navigation
    ├── *Page.kt            mentor / roles / trial / settings
    └── Screens.kt          status dashboard / settings forms / profile details / diagnostics
```

## Support

- FAQ (risk of a ban, root or not, what to do when messages cannot be read): [publish/FAQ.md](publish/FAQ.md)
- Privacy and data boundaries: [publish/PRIVACY.md](publish/PRIVACY.md)
- Issues: <https://github.com/Sakura520222/TalkTact/issues>

## License

GPL-3.0 — see [LICENSE](LICENSE).
