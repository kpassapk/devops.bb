# devops.bb

devops.el from the command line, in [babashka](https://babashka.org).

[devops.el](https://github.com/kpassapk/devops.el) runs org-babel blocks
on remote targets and logs each run to `devops-execution-log`. devops.bb
reads that log and asks the Emacs that wrote it what each run printed:

- `devops-bb dashboard`, a TUI that follows the log
- `devops-bb output`, `follow`, `sessions`, … commands that print JSON
- `skills/devops-bb`, an agent skill built on those commands

## Install

Requires:

- babashka
- Emacs with an `emacsclient` server, devops.el with `devops-mode` on
  and `devops-execution-log` set. `devops-bb run-output`, and the
  dashboard's output for a past run, need `devops-scripting-run-output`
  (devops.el `agentic-tools` branch).
- Optionally [cljbang-org](https://github.com/kpassapk/cljbang-org) in
  that Emacs, for the dashboard to show a block's heading and source.

Link the command onto PATH. It finds `bb.edn` through the link, so it
runs from any directory:

```
ln -s ~/src/github.com/kpassapk/devops.bb/bin/devops-bb ~/.local/bin/
devops-bb status
```

## Dashboard

```
devops-bb dashboard
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

The log path comes from `devops-execution-log` in Emacs. Without Emacs,
pass it: `devops-bb dashboard --log ~/.cache/devops/executions.jsonl`.
The feed works from the log alone; opening a block run needs Emacs.

⏎ opens the selected event. A block run is answered by Emacs, and
nothing runs:

- **output**: `devops-scripting-run-output`, by the run's session and
  id, so it is that run's output even after the block ran again. If
  the session no longer holds the run, the block's latest run or its
  `#+RESULTS` is shown, and the view says so.
- **source**: the block now at the logged line, from
  `cljbang-org-src-blocks`, if its code is what the run sent. If the
  file has changed, the view shows what was sent instead.

A tangle or drift check is shown from its log line: the targets and how
many files, or each file's drift status.

| Key                   | Feed                   | Detail                 |
|-----------------------|------------------------|------------------------|
| `j` `k` `↑` `↓`       | move                   | scroll                 |
| `space` `PgDn` `PgUp` | page                   | page                   |
| `g` `G`               | newest, oldest         | top                    |
| `⏎` `l` `→`           | open                   |                        |
| `h` `←` `esc`         |                        | back                   |
| `r`                   |                        | ask Emacs again        |
| `e`                   | show the line in Emacs | show the line in Emacs |
| `q` `C-c`             | quit                   | quit                   |

## Commands

Each command asks Emacs one question and prints the answer as one line
of JSON. An error goes to stderr as `error: ...`, with exit status 1.
FILE may be relative to the current directory.

| Command | Answers |
|---|---|
| `devops-bb status` | whether `devops-mode` is on, and where it logs |
| `devops-bb output FILE LINE [--tag T]` | what a block printed: session, else `#+RESULTS` |
| `devops-bb run-output ID [--session S]` | what one run printed, from its session |
| `devops-bb sessions` | the live async sessions |
| `devops-bb follow [--dir D] [--json]` | a line per new log event under D, until stopped |
| `devops-bb modified FILE` | whether Emacs holds unsaved edits to FILE |
| `devops-bb revert FILE` | reload FILE's buffer from disk |
| `devops-bb tools [REGEXP]` | the `tools.org` blocks loaded for `#+call:` |
| `devops-bb drift FILE [--heading H \| --custom-id ID]` | tangles compared with targets |

```
$ devops-bb follow --dir ~/ops
execute running /home/me/ops/web.org:12 089d9332-bcff-4ae3-9916-d891ec394804
result done /home/me/ops/web.org:12 089d9332-bcff-4ae3-9916-d891ec394804

$ devops-bb run-output 089d9332-bcff-4ae3-9916-d891ec394804 | jq -r .output
```

Only `drift` does more than read: it reads every target over TRAMP, and
expanding `<<name()>>` references runs those blocks.

## Agent skill

`skills/devops-bb` teaches an agent the devops.el loop with these
commands: it writes blocks, you run them, it follows the log and reads
each run's output. It is the counterpart of devops.el's
`skills/devops-el`, which does the same with `emacsclient --eval`.
Install one of the two; they answer the same requests. For Claude Code:

```
ln -s ~/src/github.com/kpassapk/devops.bb/skills/devops-bb ~/.claude/skills/
```

## Development

| Namespace             | Does                                                        |
|-----------------------|-------------------------------------------------------------|
| `devops.bb.log`       | log bytes → event maps; follows appends and truncation      |
| `devops.bb.model`     | events → state; pairs `execute` and `result` by run id      |
| `devops.bb.emacs`     | `emacsclient --eval`, the value back as base64 JSON         |
| `devops.bb.view`      | state → screen text; pure                                   |
| `devops.bb.cli`       | the commands                                                |
| `devops.bb.dashboard` | the [charm.clj](https://github.com/kpassapk/charm.clj) loop |

`bb test` runs the tests. The view tests compare whole screens as text.
