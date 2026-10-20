# lint-baseline.xml — what is in it, and what is not

Gate: `./gradlew :app:lintDebug` is green with **0 errors**. `lint-baseline.xml` holds the **918 warnings and hints**
that remain from the review of 2026-10-12 (B14-4, audit T-06; 1,021 then). It holds no error, and a new warning is not in it, so it still shows up
in the report. Regenerate it only after reading the diff (`./gradlew :app:updateLintBaselineDebug`); an error is fixed or
justified in code (`@SuppressLint` with the reason beside it), never baselined.

`AppBundleLocaleChanges` (the in-app language picker needs every language in the bundle) is not in the baseline: it is fixed, `bundle.language.enableSplit = false` (B14-5).

## Where the 932 errors went

| Was | Count | What was done |
| --- | ---: | --- |
| `MissingTranslation` | 883 | Ten partial languages (ar de fr in it ko nb ru tr zh-rCN, about 63 % translated) moved out of the build to `translations-unbuilt/` (see its README); shipped: English and Spanish. Seven brand/URL/format keys marked `translatable="false"`, five Spanish strings added. |
| `UnsafeOptInUsageError` | 15 | `FadingPlayer` opts in to Media3's `UnstableApi` explicitly. |
| `NonObservableLocale` | 14 | `currentLocale()` (reads `LocalConfiguration`) replaces `Locale.getDefault()` in composables. |
| `MissingQuantity` / `ImpliedQuantity` | 11 / 1 | Russian plurals: left with the Russian folder, unbuilt. |
| `MissingPermission` | 3 | `DapDeviceOwnerController.setBluetoothEnabled` checks `BLUETOOTH_CONNECT` and catches `SecurityException` (a real defect); `SafetyMaxRefusalNotifier.post` is guarded by `canPost()` and `runCatching` (suppressed, reason beside it). |
| `RestrictedApi` | 3 | `MainActivity.dispatchKeyEvent` override delegating to super: suppressed, reason beside it. |
| `LocalContextConfigurationRead` | 1 | `BackupPresentation` reads `LocalConfiguration`. |
| `UnusedMaterial3ScaffoldPaddingParameter` | 1 | `EditTransitionScreen` uses the Scaffold padding (navigation bar inset). |

## What the baseline keeps, by category

| Id | Count | Decision |
| --- | ---: | --- |
| `UnusedResources` | 405 | Dead strings and drawables from upstream and from the dropped languages (`setcat_language_*`). Harmless; removed with the rebranding pass, which rewrites those files anyway. B38 removed the 84 entries of upstream's dead About and beta-sheet strings, and one `PluralsCandidate` hint on `about_contributions_format`, together with the strings. |
| `PluralsCandidate`, `MissingQuantity` | 66, 18 | Wording that could be a plural; Spanish `many` quantity. Cosmetic. |
| `LogNotTimber`, `BinaryOperationInTimber`, `StringFormatInTimber` | 56, 4, 3 | Logging style. |
| `LocalContextGetResourceValueCall`, `LocalContextResourcesRead`, `ConfigurationScreenWidthHeight`, `FrequentlyChangingValue`, `ModifierParameter`, `AutoboxingStateCreation`, `UseOfNonLambdaOffsetOverload`, `ModifierNodeInspectableProperties` | 54, 5, 11, 4, 26, 26, 3, 1 | Compose style/performance hints; reading these in composition can be stale on a configuration change that recreates the Activity anyway. Take them when a screen is touched. |
| `ObsoleteSdkInt`, `UseKtx`, `UnusedAttribute`, `InlinedApi` | 52, 40, 14, 8 | `minSdk` is 30; checks against lower levels are dead code. |
| `VectorRaster`, `VectorPath`, `IconDuplicates`, `Overdraw`, `UselessParent`, `Typos`, `TypographyDashes`, `DefaultLocale` | 27, 6, 5, 1, 1, 2, 2, 1 | Asset and copy polish. |
| `HardcodedText` | 12 | `res/layout/widget_preview_*`: tool-only preview layouts. |
| `GradleDependency`, `NewerVersionAvailable`, `AndroidGradlePluginVersion` | 31, 12, 4 | Dependency updates: a decision per update, not a lint fix (these only appear when lint can reach Maven, so regenerate the baseline online). |
| `EmptySuperCall`, `ApplySharedPref`, `UnclosedTrace`, `SwitchIntDef`, `DiscouragedApi` | 5, 3, 2, 1, 2 | Reviewed: no defect found (`commit()` is a synchronous write in the debug switches and the product-mode store; the traces are in the diagnostics code). |
| `QueryPermissionsNeeded` | 3 | Package visibility for `resolveActivity` calls in the dedicated-mode and maintenance code. Revisit with the Play declarations (next batch). |
| `LockedOrientationActivity` | 2 | Two Activities pin their orientation (manifest lines 61 and 101): a product decision for the DAP form factor, not changed here. |
| `AcceptsUserCertificates` | 1 | `network_security_config.xml` trusts user certificates (audit §5.3: not a demonstrated vulnerability). Revisit with the privacy pass. |
