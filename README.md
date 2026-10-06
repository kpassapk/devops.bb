# devops.bb

A babashka dashboard for [devops.el](../devops.el): it follows
`devops-execution-log` and shows each block run, tangle and drift check
as it happens.

```
bb dashboard
```

```
 devops.bb · ~/.cache/devops/executions.jsonl · emacs ●                11:23:05
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 11:23:00  ▶ execute  db.org:77            db-1 /ssh:db-1:              running…
 11:22:10  ✗ tangle   db.org:12            No target on this h…            error
 11:21:47  ≠ drift    infra.org            all headings          1 drift, 1 same
 11:20:02  ⇣ tangle   infra.org:100        nginx                         2 files
 11:17:50  ✓ result   infra.org:118        web-1 /ssh:web-1:                3.4s
 11:17:47  ▶ execute  infra.org:118        web-1 /ssh:web-1:
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ▶1 running ✓1 done ⇣2 tangled =1 drift ✗2 problems     ⏎ open  e emacs  q quit
```

Newest first. With the cursor at the top, the feed follows new lines;
further down, it stays on the event it is on.

## Requires

- [babashka](https://babashka.org)
- A running Emacs that `emacsclient` reaches, with devops.el's
  `devops-mode` on and `devops-execution-log` set
- [cljbang-org](https://github.com/kpassapk/cljbang-org) in that Emacs,
  for a block's heading and source

The log path comes from `devops-execution-log` in that Emacs. Without
Emacs, pass it: `bb dashboard --log ~/.cache/devops/executions.jsonl`.
The feed works from the log alone; opening a block run needs Emacs.

## Opening an event

⏎ opens the selected event.

A block run is answered by Emacs, and nothing runs:

- **output**: `devops-scripting-run-output`, by the run's session and
  id, so it is that run's output even after the block ran again. If
  the session no longer holds the run, the block's latest run or its
  `#+RESULTS` is shown, and the view says so.
- **source**: the block now at the logged line, from
  `cljbang-org-src-blocks`, if its code is what the run sent. If the
  file has changed, the view shows what was sent instead.

A tangle or drift check is shown from its log line: the targets and how
many files, or each file's drift status.

| Key            | Feed                | Detail          |
|----------------|---------------------|-----------------|
| `j` `k` `↑` `↓`| move                | scroll          |
| `space` `PgDn` `PgUp` | page         | page            |
| `g` `G`        | newest, oldest      | top             |
| `⏎` `l` `→`    | open                |                 |
| `h` `←` `esc`  |                     | back            |
| `r`            |                     | ask Emacs again |
| `e`            | show the line in Emacs | show the line in Emacs |
| `q` `C-c`      | quit                | quit            |

## Command line

`bin/devops-bb` runs this project's tasks from any directory; link it
onto PATH. Each command asks Emacs one question and prints JSON:

| Command | Answers |
|---|---|
| `devops-bb status` | whether `devops-mode` is on, and where it logs |
| `devops-bb output FILE LINE [--tag T]` | what a block printed: session, else `#+RESULTS` |
| `devops-bb run-output ID [--session S]` | what one run printed |
| `devops-bb sessions` | the live async sessions |
| `devops-bb follow [--dir D] [--json]` | a line per new log event under D |
| `devops-bb modified FILE` | whether Emacs holds unsaved edits to FILE |
| `devops-bb revert FILE` | reload FILE's buffer from disk |
| `devops-bb tools [REGEXP]` | the `tools.org` blocks loaded for `#+call:` |
| `devops-bb drift FILE [--heading H \| --custom-id ID]` | tangles compared with targets |

`skills/devops-bb` is an agent skill built on these, the counterpart of
devops.el's `skills/devops-el`.

## Code

| Namespace             | Does                                                  |
|-----------------------|-------------------------------------------------------|
| `devops.bb.log`       | log bytes → event maps; follows appends and truncation |
| `devops.bb.model`     | events → state; pairs `execute` and `result` by run id |
| `devops.bb.emacs`     | `emacsclient --eval`, the value back as base64 JSON    |
| `devops.bb.view`      | state → screen text; pure                              |
| `devops.bb.cli`       | the `devops-bb` commands                               |
| `devops.bb.dashboard` | the [charm.clj](https://github.com/kpassapk/charm.clj) loop |

`bb test` runs the tests. The view tests compare whole screens as text.
