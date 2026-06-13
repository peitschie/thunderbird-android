# Undeletable duplicate → debug crash loop on delete: diagnosis & fix

Working notes for the `feature/aggregate-folder-tabs` branch. Captures a reproducible
crash-loop that makes a *duplicated* email impossible to delete on **debug builds**, its
root cause in the legacy move/delete path, the on-device remediation, and what the upstream
issue tracker does (and does not) already cover.

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

## 2. Root cause

A `move_and_mark_as_read` pending command (the delete) re-runs on every sync and aborts
mid-execution, so it never clears from `pending_commands` — a permanent crash loop.

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

## 3. Remediation (on-device, debug build)

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

## 4. Upstream issue tracker status (searched 2026-06-13)

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

## 5. Follow-ups (not done)

- Fix the duplicate-row root cause: the `ImapSync` check-then-save race against the
  non-unique `(uid, folder_id)` index that lets two rows exist for one message.
- Decide whether this branch should soften the `BuildConfig.DEBUG` assertion so a stray
  duplicate degrades to a warning (as in release) instead of bricking the pending-command
  queue during prototype testing.
