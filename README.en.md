<p align="center">
  <img src="art/talktact-icon.svg" width="128" height="128" alt="TalkTact icon" />
</p>
<h1 align="center">TalkTact</h1>
<p align="center">Reply suggestions inside WeChat and QQ. You choose what to send.</p>
<p align="center">
  <a href="https://github.com/Sakura520222/TalkTact/releases">Releases</a> ·
  <a href="docs/QUICKSTART.md">Getting started (Chinese)</a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="README.md">中文</a>
</p>

This is the **independently maintained Sakura520222 fork** of [shibry88-netizen/TalkTact](https://github.com/shibry88-netizen/TalkTact). It keeps the original chat-assistant features, redesigns the app and floating panels, and adds optional QQ integration.

Choose a suggestion, fill the input box, and decide whether to send it. TalkTact never sends messages for you.

## What this fork adds

### Five redesigned pages

Status, Mentor, Profiles, Try it, and Settings each focus on their own task. HyperIsland-inspired layouts use Miuix components, large headings and grouped cards, with light and dark themes, wallpaper colors and custom backgrounds.

Common settings are directly visible. Search profiles, edit prompts separately, cancel or retry a trial, and rewrite individual suggestions. Settings include protection when leaving with unsaved changes.

### Liquid glass across navigation and chat panels

Background refraction, central frosting, soft highlights and gradient edges keep text and controls sharp. Navigation retains touch response and spring motion. The entire floating card consumes touch, so tapping its empty space does not activate the chat underneath.

Refraction is available at high quality on Android 13+. Choose automatic, high or low quality; low quality or unavailable effects use a plain card.

### Panels open when you ask

Results, sensitive-content notices and errors update the button without opening the card. **Only a user tap expands it.** Conversation changes clear the old context, and stale requests cannot overwrite a different chat.

### Optional QQ support

QQ integration is off by default. Enable it and grant framework scope to use **manual recognition**. QQ profiles and whitelist entries have separate identities from WeChat chats, including chats with matching names.

## Compared with the original

The comparison uses [upstream 0.8.12](https://github.com/shibry88-netizen/TalkTact/tree/b4c83d7f0326d1a205f5997283d33b7fc654dab1), the base of this fork. Inherited features are listed separately below.

| Area | Original | Current fork branch |
| --- | --- | --- |
| App UI | Original five pages, feature cards and expandable sections | Redesigned pages, grouped settings, profile search and visible common actions |
| Appearance | Gradient backgrounds and glass-style cards | HyperIsland-inspired pages, liquid-glass navigation and full-card chat backdrops |
| Hosts | WeChat | WeChat plus opt-in QQ; QQ always uses manual recognition |
| Expansion | Sensitive and error states can open the panel | Explicit user expansion; touches stay inside the card |
| Conversation state | Existing caching and request management | Stronger switch, pause and callback checks; separate WeChat and QQ identities |
| Distribution | Original project's releases and signing key | Independent fork releases and signing; original name and package preserved |

## Inherited chat features

- **Suggestions and individual rewrites:** multiple reply strategies, a recommended option, and shorter, more formal or rephrased variants. Tap to fill the input box; long-press to copy.
- **Mentors and profiles:** built-in and custom prompts, relationship notes, and optional learning from your own writing style.
- **Models and endpoints:** direct or graded modes, with Chat Completions, Responses, Anthropic and custom endpoint support.
- **Privacy and local tools:** conversation whitelists, sensitive-content checks, offline OCR, a local proxy, and configuration/profile backups.

Chat context is sent to the model endpoint you configure. Raw images are processed locally; recognized text is sent with the context. With the local proxy enabled, both provider keys stay in the app process. The module does not read WeChat or QQ databases. See the [privacy notes (Chinese)](publish/PRIVACY.md).

## Get a build

[Releases](https://github.com/Sakura520222/TalkTact/releases) contains published versions. The [Build workflow](https://github.com/Sakura520222/TalkTact/actions/workflows/build.yml) provides development builds.

**This page describes the `feature/miuix-liquid-ui` development branch.** The page redesign, QQ support and new glass panels are not included in the existing `0.8.12-fork.1` test release. Check the notes for the build you download.

This fork keeps the original package name but uses a different signing key, so it cannot coexist with the original app. Export a backup before uninstalling the original, then install this fork and import it. See [getting started (Chinese)](docs/QUICKSTART.md).

## Compatibility and progress

Requires **Android 12+, arm64**, and a module framework providing **libxposed Modern API 102**.

On 2026-10-25, floating-panel appearance and interaction were checked on Android 16 with WeChat 8.0.66 and QQ 9.1.60. Unreadable image pages and unresponsive force-stop cold launches remain under investigation. Other OS/host versions and the full OCR flow have not been fully validated.

## Documentation and feedback

| Topic | Link |
| --- | --- |
| Installation, migration and first use | [Getting started (Chinese)](docs/QUICKSTART.md) |
| Common questions | [FAQ (Chinese)](publish/FAQ.md) |
| Data and privacy | [Privacy notes (Chinese)](publish/PRIVACY.md) |
| Builds, signing and maintenance | [Fork guide (Chinese)](docs/FORK.md) · [Signing (Chinese)](signing/README.md) |
| Changes and feedback | [Changelog](CHANGELOG.md) · [Fork issues](https://github.com/Sakura520222/TalkTact/issues) |

## Credits and license

Thanks to **shibry88-netizen** and the upstream contributors. The app name, package and icon design are preserved; the header uses a corresponding SVG rendition. The interface takes inspiration from HyperIsland, with existing component and source attributions retained.

Licensed under [GPL-3.0](LICENSE). Distribute corresponding source with modified builds and clearly distinguish fork releases from the original project.
