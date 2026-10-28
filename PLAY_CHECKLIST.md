# Water Logged — Play-ready checklist

Status as of Oct 28, 2026, evening (updated for the merge plan):
- Phone: `com.colehydro.phone`, version 0.1.0 / code 14 (v14 DELIVERED Oct 28 ~17:36, commit 541f2cc8)
- Wear: `com.colehydro.wear`, version 0.1.0 / code 4 (wear v4 DELIVERED same time)
- v15 CANDIDATE (compiled, NOT packaged): notification manifest fix (POST_NOTIFICATIONS + v2 re-ask key) + audit fixes (pending mark-synced, undo feedback, clear timestamp, HC error-vs-0, pagination, seen pruning, HC-total sync to watch, persistent Undo, Settings default watch tap, widget dark mode, notification icon) + all-buttons corner morph (already in v14)
- Current builds are debug APKs, signed with pinned debug keystores: phone `dc9d…`, wear `28b6…` (different keys, different packages — see merge note below)
- TargetSdk 36 / compileSdk 36 already in both modules
- End-to-end watch → phone → Health Connect tap: still the key unproven path on the split-package builds, and the merge is the cleanest way to prove it on a Play-correct setup

## REVISED MERGE PLAN — com.taescience.waterlogged (Oct 28, 2026)

This replaces the old "merge v12/v2 first" wording below, which is out of date. The steps themselves are unchanged; the versions and the v15 question are new.

**Confirm first, before any code change (cannot be undone after Play app creation):**
- Spelling: `com.taescience.waterlogged` — all lowercase, no dot between water and logged

**Phase 1 — applicationId only (recommended, lowest risk, do this first):**
- `phone/build.gradle.kts`: `applicationId = "com.taescience.waterlogged"`
- `wear/build.gradle.kts`: `applicationId = "com.taescience.waterlogged"`
- `phone/src/main/res/xml/shortcuts.xml`: `targetPackage` → `com.taescience.waterlogged` (targetClass stays `com.colehydro.phone.ShortcutLogActivity`)
- Leave alone: Kotlin packages (`com.colehydro.phone` / `.wear`), `namespace` values, broadcast actions (`com.colehydro.phone.ACTION_LOG_8`, `com.colehydro.wear.ACTION_LOG_DEFAULT`), Data Layer paths (`/hydro/add`, `/hydro/goal`, `/hydro/reset`, `/hydro/total`, `/hydro/default`)
- Phase 2 (separate, later, optional): rename source packages / namespaces to `com.taescience.waterlogged.phone` / `.wear` — do NOT combine with Phase 1

**Signing — unify to one key:**
- Local merged test: use the phone's pinned `debug.keystore` (`dc9d…`) for BOTH modules. Phone updates cleanly over v14; wear must uninstall v4 once (its `28b6…` key is different), then v5+ updates cleanly.
- Play release (later, separate): generate a NEW release keystore, back it up offline, never push it to GitHub, enable Play App Signing. Never use a debug keystore for Play.

**Fresh-install consequence (do this before testing the merged build):**
- Uninstall BOTH old apps first, or two "Water Logged" apps will coexist and confuse testing
- Before uninstalling the watch app: open the Tile once so pending taps flush, or those taps are lost
- Health Connect history survives (system store, not the app); local prefs reset: phone goal/reminders/cached total, watch goal/queued taps, all permissions must be re-granted (including notifications, now fixed in v15)

**Version codes:**
- Local merged test: keep incrementing from today — phone 15, wear 5 — so Android does not treat it as a downgrade
- First Play release: restart under a scheme that never collides across form factors, e.g. phone 10001, 10002… / wear 20001, 20002… (Play needs distinct codes across form factors in a release)

**Testing order for the merged build (do not skip to Play before this passes):**
1. Clean build of both modules succeeds
2. Fresh install phone + watch, same signing key, old apps uninstalled
3. Grant Health Connect on phone; set a goal, confirm it syncs to the watch
4. Watch Tile +8 → phone Today total +8 → Hydration entry in Health Connect
5. Complication tap logs; phone add → watch displayed total updates (new HC-total sync)
6. Goal change, clear-today, reset, widget / QS Tile / shortcut all move the same total, no double-counts
7. Watch tap while phone app is backgrounded / closed — queues or delivers, not silently dropped

**Open decisions (answer before executing):**
1. Order: merged-only test build FIRST (applicationId + signing + shortcuts only, no other code changes, so a sync failure can be attributed to the merge), then package v15 on the merged package — RECOMMENDED — or merge + v15 together in one build (faster, harder to attribute a failure)?
2. Local signing key: phone `dc9d…` for both (recommended) or a brand-new shared debug key (both apps reinstall)?
3. Play timeline: developer account ($25, identity verification), privacy policy hosting (GitHub Pages vs personal site — must be live before submission), and if it's a new personal account, closed test with 12+ testers for 14 continuous days before production (see sections 5–7 below, still current)

## 1. Merge to one package: com.taescience.waterlogged

Why this matters beyond Play: Google Play services only routes Data Layer messages
between a phone and a watch when the package name AND the signing signature match.
So the current split (`com.colehydro.phone` vs `com.colehydro.wear`, different debug
signatures) may itself be why watch sync has never been proven — merging is a
functional fix, not just a listing requirement. Play has the same rule for a
companion phone + Wear app in one listing.

- [ ] Confirm the spelling once, before anything else: `com.taescience.waterlogged`
      (all lowercase, no dot between water and logged). A Play app's package can
      never be changed after the app is created.
- [ ] Phase 1 — applicationId only (recommended, lowest risk):
  - [ ] `phone/build.gradle.kts`: `applicationId = "com.taescience.waterlogged"`
  - [ ] `wear/build.gradle.kts`: `applicationId = "com.taescience.waterlogged"`
  - [ ] Leave the Kotlin packages (`com.colehydro.phone`, `com.colehydro.wear`) and
        the `namespace` values alone for now. applicationId, namespace, and source
        package are three separate things, and Play / Data Layer check applicationId.
        No source files need to move for Phase 1.
- [ ] Phase 2 — optional cleanup, separate change, do NOT combine with Phase 1:
      rename source packages / namespaces to `com.taescience.waterlogged.phone` and
      `.wear`, moving the directories and updating manifest class references.
- [ ] Update the one hardcoded applicationId reference:
  - [ ] `phone/src/main/res/xml/shortcuts.xml`: `targetPackage` → the new package.
        (`targetClass` stays `com.colehydro.phone.ShortcutLogActivity` while the
        Kotlin package is unchanged.)
- [ ] Leave alone in the merge (internal only, still work, rename later if wanted):
      broadcast action strings `com.colehydro.phone.ACTION_LOG_8` and
      `com.colehydro.wear.ACTION_LOG_DEFAULT` — each must change in its definition
      AND its manifest/sender together, so don't half-rename them.
- [ ] Data Layer paths stay exactly as they are: `/hydro/add`, `/hydro/goal`,
      `/hydro/reset`, `/hydro/ack`. No code change needed there.
- [ ] Signing — both modules, same key, at every stage:
  - [ ] Local merged test: give `wear/build.gradle.kts` the same persistent-debug
        signing config the phone already has (same `debug.keystore`). The wear
        module currently has no signing config, so it falls back to a default
        debug key that does not survive VM rebuilds — the same failure mode that
        broke phone updates before.
  - [ ] Play release: generate a NEW release keystore (never the debug keystore),
        back it up offline, never push it to GitHub (public repo), enable Play
        App Signing so Google holds the final signing key and a lost upload key
        can be reset. Use that one upload key for both modules.
- [ ] Fresh-install consequence (tell yourself before testing): a new applicationId
      is a new app to Android. Uninstall BOTH old apps (`com.colehydro.phone` and
      `com.colehydro.wear`) before installing the merged build, or two apps both
      named "Water Logged" will coexist and confuse testing. Health Connect history
      survives (it lives in the system store, not the app), but local prefs start
      fresh: phone `hydro_prefs` (goal, reminders, cached total), watch DataStore
      (goal, queued taps), and all permissions must be re-granted.

## 2. Version codes for one listing, two form factors

Play's phone track and `wear:` track need version codes that are unique across
all form factors and increase monotonically within each track.

- [ ] First Play release: versionName `1.0.0` for both modules.
- [ ] Adopt a scheme and never break it, e.g.:
  - Phone: 10001, 10002, 10003, … (10000 + release number)
  - Wear:  20001, 20002, 20003, … (20000 + release number)
- [ ] Do NOT carry over phone 12 / wear 2. A new package has no Play history, so
      the codes can restart under the new scheme. Differing phone/wear codes are
      fine for Data Layer sync — the requirement there is package + signature,
      not version code.

## 3. Release build (replaces the debug APKs)

- [ ] Decide the v13 question BEFORE the merge build, so a failure can be attributed:
      recommended order is merge v12/v2 first, prove sync, then package v13 —
      v13 changes the Tile and complication logging paths, which are exactly what
      the sync test exercises.
- [ ] Build release bundles, not APKs: `:phone:bundleRelease` and
      `:wear:bundleRelease`, with the release signing config.
- [ ] Verify each bundle: applicationId `com.taescience.waterlogged`, versionCode
      per the scheme above, targetSdk 36, phone bundle has no watch feature,
      wear bundle declares `android.hardware.type.watch`.
- [ ] Test on a release build, not a debug build — permissions, signing, and
      Data Layer routing all behave differently enough to matter.

## 4. Verification, in order (do not skip to Play before this passes)

- [ ] Clean build of both modules succeeds.
- [ ] Fresh install on phone (old app uninstalled), fresh install on watch
      (old app uninstalled), same signing key on both.
- [ ] Grant Health Connect on the phone; set a goal and confirm it syncs to the
      watch — this is the first proof Data Layer now works.
- [ ] Watch Tile +8 → phone Today total +8 → a Hydration entry in Health Connect.
- [ ] Watch complication tap logs (v13 direct log, if included).
- [ ] Goal change, clear-today, and reset paths: `/hydro/goal`, `/hydro/reset`.
- [ ] Phone-side adds, widget, Quick Settings Tile, and launcher shortcut
      (whichever are in the build) all move the same Today total, no double-counts.
- [ ] Watch tap while the phone app is backgrounded / closed — confirm the tap
      queues or delivers rather than silently dropping.

Note on tonight's test (Oct 28): testing the old split-package builds is still
useful for Tile / complication UI, but if watch → phone sync fails on those
builds, do not spend long debugging it — the package/signature mismatch is a
known, sufficient explanation, and the merge is the fix to retest against.

## 5. Play Console — account and listing

- [ ] Create a developer account (one-time $25 fee), complete identity verification,
      accept the Developer Distribution Agreement.
- [ ] Create the app with package `com.taescience.waterlogged` — double-check the
      spelling; it cannot be changed later.
- [ ] Store listing: name "Water Logged", short + full description, 512×512 icon,
      1024×500 feature graphic.
- [ ] Phone screenshots (at least 2, showing the real shipping UI).
- [ ] Wear screenshots: at least 1, square 1:1 (min 384×384), showing ONLY the app
      interface — no device frames, no added text/graphics, no transparent
      backgrounds. Include a Tile screenshot (recommended for Tile apps).
- [ ] Enable the Wear OS form factor in the listing (`wear:` track) and upload the
      wear bundle to the wear track, the phone bundle to the phone track.
- [ ] Category, contact email, and content rating questionnaire.
- [ ] Target audience + content declarations.

## 6. Play Console — policy forms (the Health Connect ones matter most)

- [ ] Data safety form: declare exactly what is collected. Current truth to declare:
      hydration amounts/times, stored locally and in Health Connect, no server,
      no account, no ads, no sale or sharing of health data. Update this form if
      that ever changes — do not copy a generic template.
- [ ] Health apps declaration (required because the app reads/writes Health Connect).
- [ ] Privacy policy: hosted on a public, non-PDF URL, linked in Play Console AND
      inside the app. The phone's Health Connect rationale screen already points
      at MainActivity — that screen must link the real policy URL before submission.
      The policy must cover: what hydration data is accessed, how it is used,
      retention, and how a user deletes it. Be accurate about deletion limits:
      phone undo deletes only the last phone add; watch taps are not undoable
      from the app today — say so, or fix that before claiming full deletion.
- [ ] Financial features declaration: free app, no in-app purchases → no merchant
      account needed. (If purchases are ever added, a payments profile is required
      before that release.)
- [ ] Permissions audit before submission: remove any permission not needed for a
      user-facing feature. Health Connect permissions must be tied to the water
      logging feature the user can see.

## 7. Closed testing, then production

- [ ] If the developer account is a NEW personal account (created after Nov 13, 2023):
      run a closed test with at least 12 testers opted in CONTINUOUSLY for at
      least 14 days. Invite 15–20 people on day 1 — anyone who opts out or joins
      late breaks their own 14-day streak and does not count yet. Internal and
      open testing do not count toward this.
- [ ] During the closed test, collect real feedback and ship at least one fix;
      the production application asks what testers actually did.
- [ ] Apply for production access from the Play Console dashboard; review is
      usually 7 days or less, longer if engagement was thin.
- [ ] Realistic timeline from a ready build: ~3–4 weeks (14 days closed test +
      up to ~7 days review), plus account verification time up front. Start the
      closed-test clock as early as possible — it is the longest pole in the tent.
- [ ] After launch: keep the release keystore backup, the version-code scheme,
      and this checklist with the project. Every update must increase the
      versionCode in its own track and keep package + signing key identical
      across phone and wear.

## Decisions still open (answer before executing the merge)

1. Merge now, or wait until after tonight's old-build watch test?
2. Ship the merge as v12/v2 first, or merge + v13 together?
3. Privacy policy hosting: GitHub Pages from the existing repo, or a page on a
   personal site once a domain is registered? (Policy must be live before submission.)
