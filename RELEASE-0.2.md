# Signal Ahead 0.2 — daily dashboard and adaptive learning

Dashboard opens automatically; journey autostart is opt-in under Settings.
Start, pause, resume and stop use the service's shared state. No boot startup.

## Changes
- Teal/navy dashboard, radio bars, observation age, accuracy and sample counts.
- Local weak and strong spots, confidence, last check and individual alert mute.
- Eco, Balanced and Responsive modes. Low battery overrides fast sampling.
- Stationary journeys reduce location checks to about two minutes.
- Confirmed places skip telephony reads until the selected 6/24/72-hour refresh window expires.
- Location still updates during journeys; cached reception is never displayed as a live measurement.
- Silent weak-place notifications once per journey, with a six-hour persistent cooldown.
- Settings for theme, autostart, alert confidence, retention and selective CSV export.
- Database version 1 to 2 migration preserves existing rows.
- Freshness checks reject old radio observations; two distinct fresh readings per cell are required before a journey votes.

## Validation and limits
GitHub Actions runs policy unit tests and compiles the debug APK.
Real-device battery consumption, manufacturer restrictions and warning accuracy require road testing.
No map tiles, passive learning, remote telemetry, cellular data tests or separate database encryption.
Location intervals are requests; Android may delay them.
Strong signal does not guarantee internet availability.
Existing 0.1 weak spots remain visible; new votes use the revised spatial grid.

Raw observation retention is enforced on app launch and during active journeys.
Visit votes expire after 90 days; aggregated spots remain until deleted, with old alerts suppressed.
The dashboard refresh loop runs only while the activity is resumed.

## Installation
This is a debug APK. If Android rejects the update because its signing key differs,
export history from the older version before uninstalling it. Uninstalling deletes local data;
CSV import is not implemented, so export is an archive, not a restorable backup.
