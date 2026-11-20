# Marketplace build

- CI's `verify-plugin` job (started by hand) builds the Rider-only zip (`-PriderOnly=true`), signs it with the
  repository secrets `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`, checks the signature and runs
  JetBrains' plugin checker against Rider 2026.1, 2026.2 and 2026.3.
- Publishing would need `PUBLISH_TOKEN` and is the maintainer's decision; nothing in CI publishes.
- Each release needs a higher `pluginVersion` (gradle.properties) and an entry in `docs/CHANGE-NOTES.html`.
