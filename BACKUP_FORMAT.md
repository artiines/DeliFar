# DeliFar business backup, format version 1

UTF-8 JSON, maximum 50 MiB. Top-level fields are mandatory:

```json
{
  "format": "DeliFar",
  "version": 1,
  "exported": "2026-11-19T20:00:00Z",
  "stores": [],
  "jobs": [],
  "preferences": [
    {"key": "language", "value": "system"},
    {"key": "area", "value": "la"},
    {"key": "daily", "value": "true"},
    {"key": "summaryMinute", "value": "420"},
    {"key": "jobReminders", "value": "true"},
    {"key": "advance", "value": "60"}
  ]
}
```

Each `stores` element is `{ "store": {...}, "hours": [...] }`.

| Store field | Type and meaning |
|---|---|
| id | Stable nonempty string ID |
| name, address | User's name and provider-selected full address |
| lat, lon | Finite WGS84 numbers, internal to normal UI |
| providerId | OSM element identity string, or explicit null |
| phone | Optional phone represented by an empty string when absent |
| created, modified | Integer Unix milliseconds; modified ≥ created |
| archived | Boolean |

Each hours element has `storeId`, ISO weekday `day` (1=Monday through 7=Sunday), `mode` (`unknown`, `closed`, `open`), and `windows`. Open windows are semicolon-separated 24-hour pairs, e.g. `09:00-12:00;13:00-17:00`. They must be non-overlapping, start before end, and exist only for open days. Unknown and closed days have an empty window string. Missing weekday records mean unknown. Cross-midnight hours must be split across days.

Each `jobs` element is `{ "job": {...}, "items": [...] }`.

| Job field | Type and meaning |
|---|---|
| id, storeId | Stable job ID and existing store reference |
| type | `delivery` or `pickup` |
| date, time | ISO `YYYY-MM-DD`, optional `HH:mm` or explicit null; Los Angeles business zone |
| status | `pending`, `progress`, `completed`, `canceled` |
| issue | `partial`, `closed`, `refused`, `other`, or explicit null |
| issueNote | Optional note string; empty when there is no issue |
| snapshotName, snapshotAddress | Historical name/address |
| snapshotLat, snapshotLon | Historical WGS84 coordinates |
| created, modified | Integer Unix milliseconds |
| completed | Integer timestamp only for completed jobs, otherwise null |
| reminderVersion | Stable alert-version string; changed on scheduling changes/reopening or changing the job reminder switch |
| reminderEnabled | Boolean; whether this timed job receives its own reminder |

Each product item has `id`, `jobId`, zero-based `position`, `name`, `planned`, `unit`, and `actual`. Quantities are JSON **strings**, not binary floats. Planned quantities must be positive; actual quantities can be zero. Up to 12 integer digits and 6 fractional digits are accepted. A job contains 1–50 lines with contiguous positions. Actual quantities are present on all partial-outcome lines and absent (`null`) otherwise. Planned quantities are never replaced by actual quantities.

Only the six relevant preference keys in the example are accepted. `language` is `system/en/ru/hy`; `area` is `la/sac`; booleans are strings `true/false`; summary minute is 0–1439; advance interval is 1–10080 minutes. Search/tile endpoints and credentials are excluded.

The reader validates field sets and types, supported version, dates, bounds, hours, timestamps, IDs, provider duplicates, item IDs, quantities, and every relationship before showing the preview. Unknown/missing fields, unsupported versions, and invalid records reject the entire file. No partial import occurs.

Preview counts store aggregates (store plus hours) and job aggregates (job plus items), not individual product lines. Stable IDs identify matches. Identical aggregates are skipped regardless of array order. Existing conflicting aggregates are retained by default; replacing them requires selecting replacement and confirming it. Preferences have a separate explicit restore option. Merged referential integrity is validated again inside the import transaction; conflicts such as an unfinished imported job at an archived retained store reject the transaction. Existing records absent from a backup are retained. Import is a merge, never a destructive reset.

All accepted writes commit in one Room transaction. Reminder schedules are rebuilt afterward. Do not hand-edit a production backup without keeping an untouched copy. Files contain private business records and are not encrypted.
