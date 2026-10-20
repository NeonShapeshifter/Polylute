# Translations that are not built

These ten folders (`ar de fr in it ko nb ru tr zh-rCN`) are what upstream translated of an older, smaller
string set. They are **not part of any source set**, so they are neither built nor linted.

Why they were taken out of `src/main/res` (B14-4):

- `values/` has 2,411 strings; these folders have about 1,520 each (`zh-rCN` 1,548, and 856 of the keys it
  has are missing). A user in one of these languages saw a screen that was two thirds translated and one third
  English, and a store listing claiming twelve languages on that basis would not have been honest.
- Android lint cannot be told to skip a locale, and 883 `MissingTranslation` errors in a baseline would have hidden
  every future gap in the two languages that are maintained.
- The Russian plurals are also incomplete (`few`/`many` quantities missing), which lint reports as errors.

What ships is English (`values/`) and Spanish (`values-es/`), see `localeFilters` in `app/build.gradle.kts`,
`AppLanguage` and `res/xml/locales_config.xml`.

To bring a language back: finish it (every key of `values/` that is translatable, plurals with every quantity the
language needs), move its folder back to `src/main/res/`, and add it to the three places above.
