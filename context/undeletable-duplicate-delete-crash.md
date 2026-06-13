# Undeletable duplicate → debug crash loop on delete: diagnosis & fix

Working notes for the `feature/aggregate-folder-tabs` branch. Captures a reproducible
crash-loop that makes a *duplicated* email impossible to delete on **debug builds**, a
consolidated register of the distinct defects behind it, their root cause in the legacy
move/delete path, the on-device remediation, and what the upstream issue tracker does (and
does not) already cover.

Diagnosed 2026-06-13 on a physical device running `net.thunderbird.android.debug`
(21.0-SNAPSHOT) against a Gmail/IMAP account.

## 1. Symptom

Deleting a duplicated email (Gmail "delete" = move to `[Gmail]/Bin`) crash-loops the app:
the message will not delete, and the process dies every sync. logcat shows the same fatal
exception repeating every few minutes:

```
FATAL EXCEPTION: MessagingController
Process: net.thunderbird.android.debug
java.lang.AssertionError: Placeholder message must have the DELETED flag set
    at com.fsck.k9.controller.MessagingController.destroyPlaceholderMessages(MessagingController.java:1041)
    at com.fsck.k9.controller.MessagingController.processPendingMoveOrCopy(MessagingController.java:994)
    at com.fsck.k9.controller.MessagingController.processPendingMoveAndRead(MessagingController.java:957)
    at com.fsck.k9.controller.MessagingControllerCommands$PendingMoveAndMarkAsRead.execute(MessagingControllerCommands.java:94)
    at com.fsck.k9.controller.MessagingController.processPendingCommandsSynchronous(MessagingController.java:807)
    ...
```

## 2. Consolidated bug register

What presents as one crash is a chain of distinct, separately-fixable defects. Collected
here so they can be tracked individually:

| ID | Defect | Where | Type | Scope | Related |
| --- | --- | --- | --- | --- | --- |
| **BUG-1** | Duplicate `messages` rows for one email (sync race) | non-unique `(uid, folder_id)` index; `ImapSync` check-then-save dedup | **root cause** | all builds | [#2705](https://github.com/thunderbird/thunderbird-android/issues/2705) |
| **BUG-2** | A move leaves a source-folder row at the moved uid that was never flagged `DELETED` | move/delete path → violates `destroyPlaceholderMessages` precondition | trigger state (downstream of BUG-1) | all builds | — |
| **BUG-3** | Data-integrity assertion is **fatal in debug** instead of only logged | `MessagingController.java:1040-1042` (`if (BuildConfig.DEBUG) throw new AssertionError`) | debug-only crash | debug | — |
| **BUG-4** | Poison-command cleanup catches `Exception` but not `Error`, so the failed command is **never dequeued** → single crash becomes an infinite loop | `processPendingCommandsSynchronous` `:819-826`; cleanup at `:821` is unreachable for an `AssertionError` (an `Error`, not `Exception`). Plus TODO `:828-830`: no local-state revert / user notification on command failure | robustness | debug (only debug throws the `Error` here) | — |
| **BUG-5** | After delete the email persists duplicated in Trash/Bin (local placeholder + server copy) | reconcile loop `processPendingMoveOrCopy:1003-1024` | symptom | release self-heals on sync; stuck here | [#2705](https://github.com/thunderbird/thunderbird-android/issues/2705) |
| **BUG-6** | Aggregate-tabs prototype operates in the Gmail-label space (one message = N IMAP rows), raising the rate of BUG-1 | branch `feature/aggregate-folder-tabs`; [`gmail-semantics-and-extension-points.md`](./gmail-semantics-and-extension-points.md) | amplifier | this branch only | — |

**Relationships:** BUG-1 → BUG-2 → (BUG-3 fires + BUG-4 fails to dequeue) = the unbounded
crash loop; BUG-5 is the user-visible duplicate; BUG-6 makes BUG-1 more frequent here.
BUG-3 alone would crash *once*; it is BUG-4 that makes it forever. Related upstream reports
that are *near* but not identical are catalogued in §5.

## 3. Root cause

A `move_and_mark_as_read` pending command (the delete) re-runs on every sync and aborts
mid-execution, so it never clears from `pending_commands` — a permanent crash loop. *Why it
never clears* is itself a defect (BUG-4): `processPendingCommandsSynchronous` removes a
failing command only inside `catch (Exception e)` (`:819-821`), but the assertion throws an
`AssertionError` — an `Error`, not an `Exception` — which slips past that cleanup entirely
and propagates uncaught to kill the thread. Had `:1041` thrown a `RuntimeException`, the
command would have been dequeued (`:821`) and the app would have crashed at most once.

The abort is an assertion in `destroyPlaceholderMessages`
(`legacy/core/.../controller/MessagingController.java:1027-1045`). A move first writes a
placeholder copy in the destination, flags the source row `DELETED`, then queues the
command; when the command runs it destroys the now-`DELETED` source placeholder. The method
assumes any leftover source row at that uid is a `DELETED` placeholder — and when it is not:

```java
if (placeholderMessage.isSet(Flag.DELETED)) {
    placeholderMessage.destroy();
} else {
    Log.w("Expected local message %s in folder %s to be a placeholder, but DELETE flag wasn't set", ...);
    if (BuildConfig.DEBUG) {
        throw new AssertionError("Placeholder message must have the DELETED flag set");   // line 1041
    }
}
```

Two things combine:

1. **A source row exists that was never flagged `DELETED`.** Its origin is a duplicate-row
   sync race: the `messages` table has only a *non-unique* `(uid, folder_id)` index and
   dedup is code-level (`ImapSync` check-then-save), so a race can leave two rows for one
   message. On Gmail this is amplified — a label/category folder and the Inbox hold the same
   email as two IMAP rows sharing one `Message-ID` (see
   [`gmail-semantics-and-extension-points.md`](./gmail-semantics-and-extension-points.md)).
   This branch's aggregate-tabs feature operates squarely in that Gmail-label space.
2. **The assertion is fatal only in debug.** `BuildConfig.DEBUG` turns a recoverable,
   logged warning into a thrown `AssertionError`. In beta/release the same row is logged and
   skipped, the command completes, and the duplicate self-heals on the next sync. **Only
   debug builds crash** — which is why this bites the local prototype and not shipped builds.

Because the crash happens on a background `MessagingController` thread, the whole process
dies; on relaunch the queued command fires again → loop.

### Observed data

One stuck command and the email present as three rows (one Message-ID):

```
pending_commands: id=8  move_and_mark_as_read  {srcFolderId:121, destFolderId:30, newUidMap:{"101":"K9LOCAL:f4cefc75-..."}}

messages (same message_id):
  id=1749  uid=201707          folder=30  (Bin)     deleted=0   ← real server copy (move succeeded server-side)
  id=1750  uid=K9LOCAL:f4cefc..folder=30  (Bin)     deleted=0   ← duplicate local placeholder
  id=1739  uid=101             folder=121 (Forums)  deleted=0   ← orphan source, DELETED flag never set  ← trips the assert
```

The server move had already succeeded (the real `201707` copy is in Bin), so each retry's
`moveMessagesAndMarkAsRead` is tolerated and it always crashes at the `destroyPlaceholderMessages`
step.

### 3.1 Forensic evidence (device snapshots, 2026-06-13)

Mined from the pre-fix snapshot (`account_fresh.db`) plus a post-fix one. DB schema v92;
Gmail/IMAP account; 904 message rows.

- **The duplication is overwhelmingly Gmail-label coexistence, not corruption.** 132
  Message-IDs appear in >1 folder; top folder-sets: `All Mail+bill` (46), `Forums+INBOX`
  (33), `Promotions+INBOX` (23), `Social/Updates+INBOX` (3/2). One server message wearing
  multiple Gmail labels = multiple IMAP rows sharing a Message-ID — the substrate the
  aggregate-tabs feature dedups (BUG-6 context).
- **BUG-1 is proven, not hypothetical.** `All Mail` (folder 29) holds server uid `421242` in
  **three rows** — identical uid/date/Message-ID but **three distinct `message_part_id`s
  (792 / 448 / 197)** — i.e. the same server message fetched and inserted three separate
  times. Only a check-then-save race against the non-unique `(uid, folder_id)` index permits
  this. It **persists across syncs and the fix** (still 3 rows post-fix) — does not self-heal.
- **The crash was a batch delete; BUG-2 hit every message in it.** PR #2919 produced two
  adjacent Forums messages `101`/`102`, moved to Bin as `201707`/`201706`. **Both** Forums
  source rows were left `deleted=0` (BUG-2). They differed only downstream: `101`'s command
  crash-looped (stuck in queue); `102`'s command completed (placeholder renamed to the server
  uid) yet *still* left the Forums orphan. So BUG-2 is the consistent defect; the crash is a
  separate consequence. After the fix unblocked the queue a sync reconciled **both** orphans
  away (`Bin∩Forums` shared Message-IDs went 2 → 0).
- **Reconciliation leaves orphan placeholders routinely.** Bin held **4** un-destroyed
  `K9LOCAL` placeholders pre-fix: ids `224` and `1750` each with a real server sibling in Bin
  (duplicate-in-Bin), and `1073`/`1074` with **no backing copy anywhere** (pure orphans).
  Post-fix **3 remain** (224, 1073, 1074) — still-visible duplicates in Bin, not yet cleaned.
- **Cadence:** 15-minute poll, **no push** on any folder (`files/thunderbird-sync-debug.txt`).
  During the loop, `last sync time` froze at `1781336575968` from 17:49 on and the worker
  re-scheduled with `initial delay: 0 ms` repeatedly — no sync ever completed.
- **Flags** carry only download state (`X_DOWNLOADED_FULL` ×541, `X_DOWNLOADED_PARTIAL`
  ×183); **no `X_REMOTE_COPY_STARTED`**, so the dedup-on-copy path
  (`MessagingController` ~:867-888) was not involved here.

### 3.2 What the static data cannot show (next collection step)

The snapshots prove *that* the source row is unflagged and *that* rows get tripled, but not
the *interleaving* that causes it — e.g. whether a Forums poll re-inserts uid 101 after the
local move flagged it, or two All Mail syncs overlap. The sync-debug log is scheduling-only.
To capture the live sequence: enable Thunderbird's debug + sensitive (IMAP-protocol) logging,
reproduce a delete-from-aggregate-tab on a label-coexisting message, and capture `logcat`
(`ImapConnection` protocol dumps + `MessagingController` ops). Requires changing a setting
and a user-driven repro — not yet done.

## 4. Remediation (on-device, debug build)

Let the app self-heal rather than hand-deleting rows (which would orphan `message_parts` /
`threads` / fulltext entries). Set the `DELETED` flag on the orphan source row so the queued
command can complete through the app's own destroy/reconcile logic:

```sql
UPDATE messages SET deleted = 1, read = 1
WHERE folder_id = <srcFolderId> AND uid = '<uid>';   -- here: folder_id=121, uid='101'
```

Procedure:

1. `adb shell am force-stop net.thunderbird.android.debug`
2. Pull a backup: `adb exec-out run-as <pkg> cat databases/<uuid>.db > backup.db`
   (use the Bash tool's `>` for binary; PowerShell `>` corrupts it).
3. Apply the `UPDATE` (no on-device sqlite3 — edit a pulled copy with the SDK's
   `platform-tools/sqlite3`, then push it back:
   `adb push fixed.db /data/local/tmp/x.db` →
   `adb shell run-as <pkg> cp /data/local/tmp/x.db databases/<uuid>.db`).
   **Run the adb path args from PowerShell, not Git Bash** — MSYS rewrites `/data/...`
   into `C:/Program Files/Git/data/...` and the copy silently lands nowhere.
4. Relaunch. The command drains, `destroyPlaceholderMessages` destroys the orphan, the
   reconcile loop (`processPendingMoveOrCopy` lines 1003-1024) drops the duplicate Bin
   placeholder, and only the real server copy remains in Bin. Verify `pending_commands`
   is empty.

## 5. Upstream issue tracker status (searched 2026-06-13)

**The exact crash is not reported.** Searches of `thunderbird/thunderbird-android` for
`destroyPlaceholderMessages`, `"Placeholder message"` + the assertion text, and
`AssertionError MessagingController` return nothing matching this crash. Expected: the
`throw` is gated behind `BuildConfig.DEBUG`, so only locally-built debug installs ever hit
the fatal path — shipped users get the silent warning.

Related, user-visible reports of the same underlying placeholder/duplicate mechanism:

| Issue | State | Relevance |
| --- | --- | --- |
| [#2705](https://github.com/thunderbird/thunderbird-android/issues/2705) "Deleted mail from inbox is doubled in trash directory" | closed (2017, K-9 5.207) | Same duplicate-in-Trash symptom (placeholder + server copy); reporter notes it self-heals after a manual sync — i.e. the release-build, non-fatal version of this bug. |
| [#11019](https://github.com/thunderbird/thunderbird-android/issues/11019) "Unified inbox. Have to delete messages twice (first time only marks them read)" | open (2026, v18) | The `MOVE_AND_MARK_AS_READ` delete path misbehaving from the unified inbox — "first only marks read" is exactly that flavor. |
| [#823](https://github.com/thunderbird/thunderbird-android/issues/823) "Cannot copy or move message that is not synchronized with the server (IMAP)" | open | Local-only / placeholder (`K9LOCAL:` uid) messages and move edge cases. |
| [#8622](https://github.com/thunderbird/thunderbird-android/issues/8622) "First moving, then deleting a message in offline mode won't delete the message from the server" | open | Move-then-delete pending-command interaction leaving inconsistent state. |

The *duplicate creation* that triggers all this is specific to this branch's
aggregate-tabs/Gmail-label handling and is not an upstream concern as-is.

## 6. Follow-ups (not done)

- **BUG-1** — fix the duplicate-row root cause: the `ImapSync` check-then-save race against
  the non-unique `(uid, folder_id)` index that lets two rows exist for one message.
- **BUG-4** — widen the poison-command cleanup in `processPendingCommandsSynchronous` to
  catch `Throwable`/`Error` (not just `Exception`), so a command that throws an
  `AssertionError` is still dequeued instead of re-firing forever; and address the TODO at
  `:828-830` (revert local changes / notify the user on command failure). This is the fix
  that stops *any* such fault from becoming an infinite loop.
- **BUG-3** — decide whether this branch should soften the `BuildConfig.DEBUG` assertion so a
  stray duplicate degrades to a warning (as in release) instead of crashing during prototype
  testing. Lower priority than BUG-4 once BUG-4 makes the crash non-looping.
- **BUG-2** — investigate why the source row is left without the `DELETED` flag after a move
  (likely the un-flagged member of a BUG-1 duplicate pair).
