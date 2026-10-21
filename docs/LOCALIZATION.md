# Localization maintenance

Crisis Connect uses [Hosted Weblate](https://hosted.weblate.org/projects/crisis-connect/)
with English source strings. Translation changes are delivered through the existing
Weblate GitHub App as pull requests for maintainer review.

## Components

| Component | Translation file mask | English base |
| --- | --- | --- |
| Android | `Android/app/src/main/res/values-*/strings.xml` | `Android/app/src/main/res/values/strings.xml` |
| Android Sensors | `Android/app/src/main/res/values-*/sensor_monitor_localized_strings.xml` | Same filename in `values/` |
| Android Survival Guide | `Android/app/src/main/res/values-*/guide_strings.xml` | Same filename in `values/` |
| iOS | `iOS/Crisis Connect/*.lproj/Localizable.strings` | Same filename in `en.lproj/` |
| iOS Breadcrumb Trail | `iOS/Crisis Connect/*.lproj/Breadcrumb.strings` | Same filename in `en.lproj/` |
| iOS CPR Assist | `iOS/Crisis Connect/*.lproj/CPR.strings` | Same filename in `en.lproj/` |
| iOS Flashlight | `iOS/Crisis Connect/*.lproj/Flashlight.strings` | Same filename in `en.lproj/` |
| iOS App Metadata | `iOS/Crisis Connect/*.lproj/InfoPlist.strings` | Same filename in `en.lproj/` |
| iOS Widgets | `iOS/WidgetExtension/*.lproj/Localizable.strings` | Same filename in `en.lproj/` |
| Glossary | `l10n/glossary/*.tbx` | English terms embedded in each bilingual TBX file |

All repository-backed components should share `weblate://crisis-connect/android`
so that hooks, pending commits and pull request delivery use one repository.
Keep automatic merging off. Push on commit is enabled; push on update is disabled
to avoid pull requests caused only by upstream updates. Weblate may batch pending
translation edits for 24 hours; repository maintenance can commit them sooner.

## Widget resources

The widget uses Apple's UTF-8 `.strings` format because Hosted Weblate does not
currently offer `.xcstrings` as an editable component format. All 19 languages,
the eight widget message keys, and the `SOS` / `!` literals are preserved from the
original catalog. The filesystem-synchronized widget target includes these files
without changing its Swift localization lookups.

Do not add another `Localizable.xcstrings` to the widget target alongside these
files: both formats compile to the same localized resource table. Keep developer
comments in the English `.strings` file. The duplicate app file named
`Localizable 2.strings` is deliberately not a separate translation component;
update the primary `Localizable.strings` table instead.

## Terminology and review

The glossary starts with English/Turkish project terms. Add reviewed bilingual
TBX files for additional languages; do not populate translations with English
fallbacks just to increase the completion percentage.

Preserve `%1$s`, `%1$d`, `%@`, escaped `%%`, markup and line breaks. Some checks
need a language-specific decision: Turkish number phrases can have identical
singular and plural wording; `dk.` is an abbreviation; Bluetooth, BLE, SOS and
product names may remain unchanged. Review individual findings rather than
disabling checks for entire components. The literal `10%` in
`FLASHLIGHT_LOW_POWER_NOTE` is not a printf argument.

Safety instructions require a reviewer who understands the target language and
the emergency context. Keep unfinished or uncertain translations visible for
review rather than marking them complete automatically.

## Validation

Run `./iOS/Scripts/ci-validate.sh` on macOS. It validates project metadata, Swift
syntax, and the Apple strings files shipped by the application and widget.
Android translation pull requests also run the repository's Android CI.
Before merging, confirm that resource keys and format arguments are preserved
and that GitHub reports no conflicts. After merging, verify that Weblate has
pulled the current `main` and has no repository errors.
