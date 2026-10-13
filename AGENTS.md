# AGENTS.md — Grout

> Working notes for AI agents and humans changing this repo. Start with
> [README.md](README.md) (what Grout is, its design rules, API, how to run and
> test it) and [PLAN.md](PLAN.md) (what's being worked on).

## Where Grout fits

```
   Tunarr Scheduler ──upload bumpers──►  Grout  ◄──query + play by path── Pseudovision
   (also the dimension catalog)           │
                                          ├──► Tunabrain   (enrichment)
   Marquee (UI) ──browse/edit────────────►│
   grout-cli / grout-bulk ──upload───────►│
```

- **Pseudovision** queries Grout for filler and plays the
  returned `path` off the shared `arr-data` mount.
- **Tunarr Scheduler** composes bumpers on its own disk and uploads them with
  multipart `POST /grout/media`. No filesystem is shared with TS.
- **Tunabrain** does all AI work. Grout calls `/enrich/short-form` per item and
  `/enrich/profile` per directory group.
- **Jellyfin** is deliberately not involved. Keep Grout files out of Jellyfin
  scan paths.

In the cluster Grout runs at `grout.pseudovision.svc.cluster.local:8080`,
next to PV.

## Source layout

```
src/grout/
├── main.clj, system.clj      entry point and Integrant system
├── http/                     reitit routes, handlers, upload, stream, schemas
├── media/                    intake, probe, normalize (accel), hash, store, enrich merge
├── enrichment/               per-item and directory workers
├── directory_profiles.clj    shared profiles for parent-directory groups
├── dimensions.clj            validation against TS's dimension vocabulary
├── tunabrain.clj             Tunabrain client
├── tunarr_scheduler.clj      TS dimension-catalog client
├── retention.clj             per-(channel, kind, duration-bucket) caps
└── db.clj, migrate.clj
bin/grout-cli.bb, bin/grout-bulk.bb   Babashka CLIs (tests in test-bb/)
```

## Common pitfalls

1. **No collections or hierarchy.** Adding folders, seasons, or series is the
   most common design drift. Use tags and directory profiles.
2. **No model credentials in Grout.** If you're about to add an
   `OPENAI_API_KEY`, extend Tunabrain's API instead.
3. **Play by path, stream as fallback.** Don't make `/stream` PV's primary
   path; it adds latency and byte-range overhead.
4. **Normalize at intake, never on read.** If a new item doesn't show up in
   queries, check the intake logs for ffprobe or transcode errors first.
5. **Soft delete is the default.** `DELETE` sets `superseded_at`; only
   `?hard=true` unlinks the file.
6. **`channel = null` means "any channel".** Don't filter generic items out of
   channel queries.
7. **Never let AI overwrite human edits.** Enrichment fills blank `name` and
   `description` only, and locked directory profiles skip Tunabrain.
8. **Two test suites.** `clojure -M:test` covers the server; `bb test` covers
   the CLIs, which can't load on the JVM classpath. `nix flake check` runs
   only the first, plus lint.
