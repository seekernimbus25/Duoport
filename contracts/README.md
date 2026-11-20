# Production package contract (copied from screenshot-match)

`validate-package.mjs` and `production-package.schema.json` are verbatim copies of
`seekernimbus25/screenshot-match/contracts/` so CI can check the native export with the web
team's independent validator. Update them together with the web copy.

    bash scripts/validate_export.sh   # runs JVM tests, then validates the generated package
