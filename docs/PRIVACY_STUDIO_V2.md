# Privacy Studio 2.0

Privacy Studio adds a rule engine on top of the original domain, keyword, and regular-expression lists. Existing preferences migrate without conversion and continue to work.

## Feature coverage

| # | Feature | Android | Firefox Desktop |
|---|---|---|---|
| 1 | Allowlist and exact-page exceptions | Exact URL `ALLOW` rules override all normal lists | Same precedence |
| 2 | Visual rule builder | Native builder in Settings | Full options-page builder |
| 3 | Quick action from the current page | Share the page to **Add Fenix Privacy rule** | Right-click page menu |
| 4 | Collapse specific pages to the homepage | Replaces the visit before Places stores it | Removes the page visit and adds only the site root |
| 5 | Private rule tester | In-memory URL/title decision dialog | In-memory tester with matched action |
| 6 | Live shield | Optional Android Quick Settings tile and last-action state | Firefox toolbar badge and popup |
| 7 | Dashboard and milestones | Today, week, total, action categories, next milestone | Today, week, total, collapsed, integrity status |
| 8 | Temporary modes | 15 minutes, one hour, or until the app process closes | Same, plus true current-tab mode until that tab closes |
| 9 | Rule profiles | Active profiles stored as names; new profiles activate automatically | Profile editor and per-rule profile |
| 10 | Settings lock | Device biometric/PIN prompt | Firefox-controlled extension access; no biometric data is available to WebExtensions |
| 11 | Encrypted transfer/sync | AES-256-GCM `.fprules` import/export | Compatible file import/export plus encrypted `storage.sync` push/pull |
| 12 | Per-rule actions | Block, allow, collapse; optional cookies/site storage, cache, download-list, and restored-tab actions | Same with optional Firefox permissions |
| 13 | Cleanup preview | Aggregate matching and collapse counts before confirmation | Same; no matching URL list is shown or retained |
| 14 | Integrity self-test | Runs at startup/maintenance and on demand | Runs at extension initialization and on demand |
| 15 | Update center | Installed/Mozilla/feed version, runtime certificate verification, status, last check and release notes | Installed add-on version, signed update feed and integrity state |

## Visual matchers

- `DOMAIN`: the domain and all subdomains.
- `DOMAIN_EXCEPT_ROOT`: every path, query, or fragment while allowing a clean `/` homepage.
- `PATH_PREFIX`: a section such as `example.com/threads`.
- `URL_CONTAINS`: a word or phrase in the decoded URL.
- `TITLE_CONTAINS`: a word or phrase that becomes known after page load.
- `QUERY_PARAMETER`: optionally target a named parameter such as `q`.
- `REGEX`: advanced expression matching.
- `EXACT_URL`: an exact allow or block exception.

Allow rules have priority over ordinary visual and legacy rules. Global temporary mode intentionally overrides allow rules because it means “save no history for this period.”

Domain matchers accept a bare registrable domain such as `sitoacaso.it`; a scheme, `www`, wildcard,
or trailing slash is not required. When **close site tabs** is explicitly enabled on a
`DOMAIN_EXCEPT_ROOT` rule, matching pages stay open and fully usable for the current session while
being excluded from Places history. Restored tabs for that domain are removed only at the next app
start. The periodic history scrub never closes a live tab. An exact allow rule can keep a
particular restored tab open.

## Privacy invariants

- Aggregate counters never contain URLs, titles, search terms, or rule text.
- The tester never persists its input.
- Cleanup preview returns counts only.
- Normal rules touch history and recent-search metadata only.
- Cookies, logins, sessions, cache, downloads, and tabs remain untouched by default.
- A destructive action runs only when it is explicitly enabled on one visual rule. Android labels that choice directly; Firefox Desktop also requests the corresponding optional permission.
- Encrypted bundles exclude history and counters. They use PBKDF2-HMAC-SHA256 (210,000 iterations) and AES-256-GCM with a fresh salt and IV.
- The encryption passphrase is held only for the requested operation and is never stored or synchronized.

## Example: retain only a homepage

Create a visual rule with:

- Match: **Everything except the homepage**
- Value: `sitoacaso.it`
- Action: **Never save**

`https://www.sitoacaso.it/` remains in history, while `/threads`, `/search/anything`, root query parameters, and fragments do not.

Choose **Save only the site homepage** instead if internal visits should be represented by a single root-domain history entry.
