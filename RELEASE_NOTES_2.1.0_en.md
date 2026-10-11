# Sparkle Morpher 2.1.0

> **English** | [中文](https://github.com/sdf123098/Sparkle-Morpher/blob/main/RELEASE_NOTES_2.1.0_zh.md)

This document covers Sparkle Morpher development records from October 7–11, 2026, and the Cloud upload screen redesign discussed in this conversation. The version number remains **2.1.0**.

[Download this README](https://github.com/sdf123098/Sparkle-Morpher/releases/download/v2.1.0/README_2.1.0.md) · [All GitHub release files](https://github.com/sdf123098/Sparkle-Morpher/releases/tag/v2.1.0)

## Supported versions

| Minecraft | Loader | Java |
| --- | --- | --- |
| 1.21.1 | Fabric / NeoForge | 21 |
| 26.1.2 (release filenames use 26.1.x) | Fabric / NeoForge | 25 |
| 26.2 | Fabric / NeoForge | 25 |
| 26.3 | Fabric / NeoForge | 25 |

Choose the JAR matching your Minecraft version and loader, and install only one variant. GitHub and Modrinth provide the standard distribution with native components. CurseForge provides a dedicated distribution without native binaries.

## Cloud upload screen: changes from this conversation

- Upload opens in a separate secondary screen. It shares the custom roulette editor's glass panel, translucent background, thin borders, list headers and button styling.
- Two tabs let you select existing local models or choose files on your computer through the system file picker.
- All tabs and controls use icons except the target Cloud button. Hover tooltips and narration labels remain available.
- Upload failure details use the same styling. A failed item's checkbox selects it for retry; clicking the item body opens the failure details.
- The window, list, spacing and toolbar adapt to available space, preserving list space at higher GUI scales. These interface changes are included in all eight release targets.

## Model panel and importing

- Search, filtering, navigation, pagination and actions share more compact areas, reducing toolbar space. Small logical viewports use a side menu for the main tabs.
- Local and Cloud cards share sizing and layout rules, starting at the top left. GUI scales 3–4 use compact cards to display more rows and columns.
- Cloud cards and classic lists hide favorite/public status. Cards no longer offer visibility controls. The community Cloud's "All models" view is for viewing and applying models.
- The 26.3 system file picker uses the game's existing SDL native dialog. It supports multiple selection and filters for ysm, zip, bbmodel, gltf and glb. Cancellation, repeated opening, callback path copying and native object lifetimes are handled.

## Related changes from October 7–11

- **Local model state:** client services and stores manage local scanning, favorites, authorization filtering, the last selection and sound preferences. Older settings migrate, and failed loads preserve the original files and caches.
- **Legacy path retirement:** the old Minecraft server model synchronization protocol, registration paths, events without consumers and old configuration were removed. Active client, Cloud and maid compatibility paths, along with persistence compatibility identifiers, are preserved.
- **Import stability:** persisted commits and runtime publication results are distinguished. Concurrent imports of the same model, stale requests, out-of-order directory scans, task cancellation and asynchronous callbacks after a screen closes are handled. Failed imports do not replace an existing valid source.
- **glTF and presentation capabilities:** external resource dependencies and content fingerprints are recorded, resource paths are restricted, and preview, texture and action controls follow each model's actual capabilities. Traditional GPU trimming is avoided for runtimes that do not support it.
- **Resource lifetimes:** client model residency ownership is explicit, active references are protected, and preview, cache cleanup, download queue and settings panel layout/lifetime handling were improved.
- **26.3 migration:** SDL input, poses/swing actions, RenderPearl pipelines, frame graphs, resource packs, item submission, Cloud SessionService and NeoForge client interfaces were adapted. Model scripts retain their previous key-value semantics.
- **Localization:** account and password validation errors use translatable reasons. Missing shared language entries were added, with corrections to target types, texture titles, developer status and upload error messages.

This document summarizes implemented features. Release records from other projects, workspace cleanup logs, raw build logs, deployment credentials and plans that were not implemented are excluded.

## Build and verification scope

- All eight targets completed clean builds for both the standard and CurseForge distributions: **16 JARs** in total. Build caching was disabled and tasks were forced to run again.
- Release checks covered ZIP integrity, loader metadata, interface classes and native component distribution. Standard JARs contain native components; CurseForge JARs contain no native binaries.
- Upload layout checks covered logical viewports from 320×180 to 1920×1080. Compilation passed on all eight branches.
- October 7–11 records include automated regression checks and some development-client/26.3 basic startup checks. These do not establish complete gameplay acceptance for the final JARs in this release.

**Still to verify:** complete in-game interaction with the final JARs, multiple clients and Cloud permission/reconnection scenarios, compatibility mod combinations, and actual Vulkan operation. The F11 native shader crash was investigated, but its cause remains undetermined and it is not claimed fixed. Experimental capabilities and items awaiting acceptance are not claimed as officially supported.

Use the language links at the top to switch between the English and Chinese versions. Both Markdown files are attached to the GitHub release.
