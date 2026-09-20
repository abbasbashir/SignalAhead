# Signal Ahead 0.3

Signal Ahead learns recurring weak-signal areas from journeys you choose to track
and can warn you when one is likely ahead. Every journey requires a Start tap;
opening the app never starts recording, including for older saved preferences.

- Bright turquoise/amber adaptive icon, blue background.
- Route-stretch learning accepts two fresh observations across spatial cells.
- Independent evidence: one vote per journey, at least 30 minutes between supporting visits.
- Recency-weighted evidence, contradictory good/weak readings reduce confidence.
- Predictions require the same local network context, compatible direction, recent stable
  heading, acceptable accuracy and a narrow approach corridor.
- Variable lead distance based on speed; late-warning feedback extends the horizon.
- "Signal was fine" feedback reduces confidence until fresh observations revalidate the stretch.
- Unknown default network and ambiguous multi-modem cell readings do not teach predictions.
- Legacy locations remain visible but are excluded from network-specific warnings.
- Local map overview, manual names, optional user-opened external street map.
- Journey summaries and alert feedback. Interrupted tracking is never counted as proven recovery.
- Versioned full JSON backup/restore with validation, size limits, explicit replacement
  confirmation and transactional database writes. Backup files contain unencrypted locations.
- Reused debug signing cache plus certificate pinning; durable signing is configurable
  through repository secrets, see SIGNING.md.

Limits: route corridor is geometric, not road-network map matching. Nearby parallel roads,
curves, tunnels with stale radio samples and short outages still need field testing.
No automatic place-name lookup or background network upload. No measured battery saving claim.
The first 0.3 install may differ from 0.2's ephemeral debug key. Do not uninstall without
exporting; 0.2 CSV cannot be restored as a full backup. 0.3 JSON supports restoration.
