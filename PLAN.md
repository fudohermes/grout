# Grout plan

Last reviewed: 2026-10-13

This is the one place for ongoing work on Grout: what's being worked on,
what's next, known issues, and the decisions that still shape the code. Delete
items once they're done (git history keeps them). Cross-repo priorities live
in the [ecosystem plan](https://github.com/fudoniten/pseudovision/blob/master/PLAN.md#ecosystem).

## Now

- [ ] Send long-form `program` items to Tunabrain's `/enrich/long-form`
      instead of `/enrich/short-form` (ecosystem step 3). Tunabrain already
      implements it.
- [ ] Add GitHub CI that runs `nix flake check` (ecosystem step 4).

## Next

- [ ] Have PV fetch Grout clips over HTTP, or document the shared `arr-data`
      mount as a hard deployment requirement. Also decide how PV handles clips
      Grout has superseded or hard-deleted.
- [ ] Add `bb test` (the CLI tests) to the flake checks.

## Later

- YouTube / long-form ingest inside Grout (yt-dlp download → normalize →
  richer metadata). Today long-form arrives through `grout-bulk` from an
  external downloader (pinchflat).
- Auth on write and delete routes (`DELETE ?hard=true` is currently open),
  once development winds down.
- Richer tag queries (must / should / must-not) if AND turns out too blunt.
- Merge `grout-cli` with pvcli's Grout commands (see pvcli's plan).

## Known issues

- No service-level auth: anyone who can reach Grout can upload, edit, or
  hard-delete. Acceptable while `*.sea.fudo.link` is local-only behind NAT.

## Decisions

- `channel=X` matches that channel **or** generic (null-channel) items.
- Grout files stay out of Jellyfin scan paths.
- 2026-07-07: Grout talks to Tunabrain only through typed, purpose-built
  endpoints, never a generic chat-completions API.
- TS is the source of truth for the dimension vocabulary; Grout validates
  against it rather than keeping its own.
