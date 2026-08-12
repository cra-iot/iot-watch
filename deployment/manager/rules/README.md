# Rules backups

Backups of the Groovy rules authored in the Manager UI. **Not auto-loaded** — the manager
creates rules from the UI (or setup code), not from this directory. The `build.gradle` here
only compile-checks these files against the OpenRemote + custom model classpath so a broken
backup fails CI. Keep a file here in sync whenever the corresponding Manager-UI rule
changes, and paste from here when re-creating a rule.

| File | Rule name (Manager UI) | Asset type |
|------|------------------------|------------|
| `tracker-gnss-location.groovy` | Tracker LoRaWAN GNSS → Location | `TrackerAsset` |
| `ship-tracker-gnss-location.groovy` | Ship LoRaWAN GNSS → Location | `ShipTrackerAsset` |
| `water-meter-decode.groovy` | Water meter → measurements (standalone) | `WaterMeterAsset` |
| `electric-meter-decode.groovy` | Electric meter → measurements (standalone) | `ElectricMeterAsset` |

All rules read `rawValue.data_decoded` (the parsed device message written by the HTTP
ingest endpoint) and write typed attributes:

- **Trackers** (`tracker-gnss-location`, `ship-tracker-gnss-location`) decode
  `gnss_latitude` / `gnss_longitude` into `location` and `battery_pct` into `battery`.
- **Meters** (`water-meter-decode`, `electric-meter-decode`) copy each `data_decoded` key
  into the attribute of the **same name**, but only for keys declared by the asset model
  (other decoder keys such as `datetime`/diagnostics are ignored). A modelled key the
  operator did not add to that instance is dispatched anyway and harmlessly dropped by the
  manager. Attribute names are the decoder's canonical keys; see
  `docs/water-meter-dictionary.md` and `docs/electric-meter-dictionary.md`.

Do not re-add writes to removed attributes (`lastMessageTime`, `rssi`, `snr` on the tracker
types; `consumption`, `status` on water meters) — the manager rejects events for attributes
that do not exist on the current model.
