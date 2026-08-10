# Rules backups

Backups of the Groovy rules authored in the Manager UI. **Not auto-loaded** — the
manager creates rules from the UI (or setup code), not from this directory. Keep a
file here in sync whenever the corresponding Manager-UI rule changes, and paste from
here when re-creating a rule.

| File | Rule name (Manager UI) | Asset type |
|------|------------------------|------------|
| `tracker-gnss-location.groovy` | Tracker LoRaWAN GNSS → Location | `TrackerAsset` |
| `ship-tracker-gnss-location.groovy` | Ship LoRaWAN GNSS → Location | `ShipTrackerAsset` |
| `water-meter-decode.groovy` | Water meter → measurements (standalone) | `WaterMeterAsset` |

Both decode `rawValue.data_decoded` GNSS coordinates into `location` and
`battery_pct` into `battery`. They write only attributes that exist on the current
model — do not re-add writes to `lastMessageTime`, `rssi`, or `snr` (removed from the
tracker asset types; the manager rejects events for non-existent attributes).
