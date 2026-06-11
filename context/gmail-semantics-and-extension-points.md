# Gmail semantics in the aggregate-tabs feature: analysis & upstream design sketch

Working notes for the `feature/aggregate-folder-tabs` branch. Captures why the feature contains
Gmail-shaped heuristics, what provider-specific machinery already exists in the codebase, and the
idiomatic design for making the heuristics provider-aware if this work ever heads upstream.

## 1. The problem: Gmail labels vs IMAP folders

Gmail stores one message and exposes labels *as* IMAP folders. A categorised email (e.g. in
`auto/Promotions`) therefore exists locally as TWO IMAP messages — different folder ids and
per-folder UIDs — sharing one RFC 5322 `Message-ID`. Standard IMAP has no such cross-folder
identity: copies of a message in two folders are independent, and deleting one never affects the
other. (JMAP models this properly: a message has a set of `mailboxIds`.)

The aggregate-tabs feature relies on Gmail's behaviour twice (`MessageListLoader.buildSelection`):

1. **Inbox dedup** — a message is hidden from the inbox/unified list when its `Message-ID` also
   exists in an aggregate-tab folder. On plain IMAP this would hide a genuinely independent copy.
2. **Delete bridging** — deleting from an aggregate folder MOVES the local row to Trash, so the
   Trash folder is included in the exclusion set to keep the Inbox copy hidden until the server
   sync removes it. Correct for Gmail (server deletes the message from all labels); on plain IMAP
   the Inbox copy would stay hidden indefinitely even though the server will never remove it.

Both are correct for Gmail accounts and unsafe-in-principle for arbitrary IMAP. The prototype does
not gate them; this document is the map for doing so properly.

## 2. Inventory: provider-specific machinery that exists today

Audited 2026-06 (main source only; tests/translations/store-metadata excluded).

### Gmail/Google-keyed code (all auth/setup or cosmetic — none semantic)

| Where | What | Class |
| --- | --- | --- |
| `feature/account/oauth/.../CheckIsGoogleSignIn.kt` | hostname suffix match (`.gmail.com`, `.googlemail.com`, `.google.com`) → route to Google OAuth | auth/setup |
| `feature/settings/import/.../GoogleOAuthHelper.kt` | second, divergent `isGoogle()` (omits `.google.com`) for settings import | auth/setup |
| `legacy/storage/.../K9MessageStoreFactory.kt:23-46` | third `isGoogle()` (suffix match) gating the `[Gmail]/` folder-name sanitizer at store creation | cosmetic |
| `legacy/storage/.../FolderNameSanitizer.kt`, `UpdateFolderOperations.changeFolder()` | strip `[Gmail]/` & `[Google Mail]/` prefixes from folder names | cosmetic |
| `legacy/storage/.../migration/StorageMigrationTo19.kt:45-52` | exact-host match (`imap.gmail.com` etc.) → flag `migrateToOAuth`; consumed by `MessagingController.handleAuthenticationFailure()` which rewrites the account to XOAUTH2 | auth/setup |
| `mail/common/.../XOAuth2ChallengeParser.java` | XOAUTH2 challenge retry logic written against Gmail's spec, but runs generically (host param is logging-only) | auth/setup |
| `feature/account/oauth/.../SignInWithGoogleButton.kt`, `GoogleSignInSupportText.kt` | Google-branded sign-in UI + Gmail KB link | cosmetic |
| `mail/protocols/imap/.../RealImapStore.kt:178` | RFC 6154 SPECIAL-USE mapping folds Gmail's `\All` (All Mail) into `FolderType.ARCHIVE` (read side only; `FolderTypeAttribute` never emits `\All`) | protocol |

### Notable absences

- **No `X-GM-EXT-1` / `X-GM-MSGID` / `X-GM-THRID` / `X-GM-LABELS` / `X-GM-RAW` usage anywhere** in
  main source. K-9 treats Gmail labels as ordinary independent folders — which is exactly why the
  inbox duplication this feature works around exists.
- **No provider-quirks table, profile object, or hook registry** keyed on server identity. The
  three `isGoogle()` helpers are ad hoc and mutually divergent.
- **No Gmail-specific delete/archive/expunge semantics** in `backend/imap` or `MessagingController`.

### Existing cross-folder message identity (precedent for our heuristic)

`Backend.findByMessageId` → `RealImapFolder.getUidFromMessageId()` (`UID SEARCH HEADER MESSAGE-ID`)
is consumed in `MessagingController.java:867-888` to avoid re-uploading a message that already
exists remotely (dedup-on-copy via `X_REMOTE_COPY_STARTED`). So "same `Message-ID` ⇒ same email"
already has standards-based precedent in the sync path; the aggregate-tabs exclusion applies the
same convention on the read path.

## 3. Extension points a "Gmail semantics" gate could plug into

Three real seams exist; nothing needs to be invented.

1. **`Backend` trait flags** (`backend/api/.../Backend.kt:11-19`): `supportsMove`,
   `supportsExpunge`, `isPushCapable`, … — a per-backend behaviour profile already surfaced to the
   UI layer via `MessagingController.isPushCapable(account)` etc. (`MessagingController.java:1752+`).
   This is the natural home for a new trait.
2. **IMAP capability negotiation**: behaviour variation in the IMAP layer is capability-driven
   (`hasCapability(MOVE/CONDSTORE/NAMESPACE/...)`), never hostname-sniffed. The capability parser
   stores whatever the server advertises, so `connection.hasCapability("X-GM-EXT-1")` works today
   with zero parser changes — and unlike hostname matching it also catches Google Workspace on
   custom domains.
3. **`ServerSettings.extra: Map<String, String?>`**: an open, persisted per-server property bag
   with typed accessors (`ImapStoreSettings.kt`: `autoDetectNamespace`, `pathPrefix`,
   `useCompression`, `sendClientInfo`). Suitable for persisting a detected capability across
   restarts without a live connection. (`ImapStoreConfig` is a related app→store injection point.)

## 4. Proposed upstream design

Connect the seams; each link follows an existing pattern:

1. **Detect** at the protocol layer: on connect, `hasCapability("X-GM-EXT-1")`.
2. **Persist** the result (e.g. `ServerSettings.extra["crossFolderMessageIdentity"]`) so it is
   available without a live connection.
3. **Surface** as a `Backend` trait, e.g. `supportsCrossFolderMessageIdentity` — IMAP: true iff the
   capability was detected; JMAP: true inherently; POP3: false.
4. **Expose** via `MessagingController.supportsCrossFolderMessageIdentity(account)` (mirroring
   `isPushCapable`).
5. **Consume** in `MessageListLoader.buildSelection`: apply the Message-ID exclusion (and the Trash
   bridge) only when the account supports cross-folder message identity; otherwise fall back to
   folder-id-only exclusion, which is always safe.

Out of scope for the prototype (current code applies the heuristics unconditionally, with comments
noting the Gmail assumption); this sketch exists so the gating can be added without re-deriving the
analysis.
