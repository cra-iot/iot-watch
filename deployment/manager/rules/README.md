# Rules backups

Backups of the Groovy rules authored in the Manager UI. **Not auto-loaded** — the manager
creates rules from the UI (or setup code), not from this directory. The `build.gradle` here
only compile-checks these files against the OpenRemote + custom model classpath so a broken
backup fails CI. Keep a file here in sync whenever the corresponding Manager-UI rule
changes, and paste from here when re-creating a rule.

Device-message decode (rawValue → typed attributes) is done in Java by
`IotWatchDecodeService` (manager module), not by rules. See
`docs/superpowers/specs/2026-08-13-ingest-side-decode-design.md`.

This directory is currently empty of rule backups — the four decode rules formerly backed
up here (`water-meter-decode.groovy`, `electric-meter-decode.groovy`,
`tracker-gnss-location.groovy`, `ship-tracker-gnss-location.groovy`) were retired along with
their Manager-UI counterparts. It is retained for future Manager-UI rule backups and the
`build.gradle` compile-check.
