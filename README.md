# devops.bb

An agent sandbox for collaborating on [devops.el][devops-el] notebooks.

[devops-el]: https://github.com/kpassapk/devops.el
[babashka]: https://babashka.org

## Why?

Best practices for security dictate that agents should be running in a
sandbox, where they cannot access the user's file system or other
credentialed services. "Sandboxes" and other controls built into agent
harnesses themselves are fragile, and the agent can overcome them easily.

The best sandbox is at the operating system level: running agents in a
separate machine or container.  Here, the agent writes files, runs
local tools (grep, jq, etc.); compiles and runs code.

Yet some of the most powerful use cases for agents involve real-world
information systems, provider APIs, and deployed apps. These systems
are "situated".

If you want to write [devops.el][devops-el] notebooks together with 
an agent, you may want to try this out.

[devops-el]: https://github.com/kpassapk/devops.el

## Requirements

- [babashka][babashka]
- Emacs 31+
- [ob-posframe](https://github.com/kpassapk/ob-posframe)

## Installation

Add to your init file

```
(use-package obp-confirm-posframe
  :vc (:url "https://github.com/kpassapk/ob-confirm-posframe" :rev :newest)
  :hook (org-mode . ob-confirm-posframe-mode))

(use-package devops-bb
  :load-path "~/src/github.com/kpassapk/devops.bb/emacs"
  :config
  (devops-bb-log-mode +1)
  :custom
  (devops-bb-execution-log "~/.cache/devops/executions.jsonl"))
```

## How it works

`devops.bb` implements a protocol for collaborating on
[devops.el][devops-el] notebooks with executable blocks, together with
a sandboxed agent. The sandboxed agent uses the notebook to
communciate with the outside world, then uses that information to
advise you or write more blocks.

**Flow**

```
     servers
   cloud accounts
     git push
        ▲
        │ blocks you review and run
 ┌──────┴──────────────────────┐            ┌───────────────────────────┐
 │ (2) host: Emacs             │            │ (1) sandbox: the agent    │
 │                             │            │                           │
 │ devops-bb publish ══════════╪═ push ════▶│ /feed (4)                 │
 │                             │            │   devops-bb follow        │
 │                             │            │                           │
 │ workbook dir ◀──────────────┼─ mount ───▶│ /work  writes blocks,     │
 │ |- (3) workbook.org         │            │        scripts            │
 └─────────────────────────────┘            └───────────────────────────┘
                                              no route to the host or
                                              the servers
```

The agent (1) runs in a sandbox. It edits code, tests, builds, runs tools.

When the the agent needs to run something via the host (2), it prints source code
blocks in a shared Emacs workbook (3).

You (the operator) review the blocks. When you run them, the agent
reads their output via a feed.

The agent continues working on the code, or prints more blocks. Repeat.

**Commands**

`bin/devops-bb publish` - starts writing the feed
`bin/devops-bb follow`  - reads the feed (usually via a skill.)

## Claude Code image

```
docker build -f sandbox/Dockerfile -t devops-bb-sandbox .
```

```
docker run -d --name wb-box -e CLAUDE_CODE_OAUTH_TOKEN
  -v ~/work/wb:/work:rw devops-bb-sandbox
docker exec -it wb-box claude
```

The image has babashka, `git`, Claude Code, devops-bb, and the
`devops-bb-sandbox` skill.

## Security

Claude runs as `claude` (uid 1000), which doesn't own `/feed`, and
`DEVOPS_BB_FEED=/feed` is set. There are no ssh keys, cloud CLIs or
git credentials. Claude Code needs `CLAUDE_CODE_OAUTH_TOKEN` (from
`claude setup-token`) or `ANTHROPIC_API_KEY`, passed with `-e`. The
image skips the theme picker and turns off the auto-updater.

Mount the workbook's directory, never the file: Emacs saves by renaming the
file, and a file mount stops working.

## Attaching a workbook



```emacs-lisp
(setq devops-bb-sandbox-sinks
      '(("wb-box" :via "docker exec -i -u root wb-box" :to "/feed"
         :map (("/Users/me/work/wb" . "/work")))))
(global-auto-revert-mode 1)   ; pick up the agent's writes
```

| Sink | Plist | Feed in the container |
|---|---|---|
| container, no mount | `:via "docker exec -i -u root BOX" :to "/feed"` | the image's own `/feed` |
| container, mount | `:to "~/feeds/wb"`, and `-v ~/feeds/wb:/feed:ro` on `docker run` | a host dir, read-only |
| local directory | `:to DIR` | for tests; no isolation by itself |

`:map` rewrites host paths to the sandbox's; `:max-lines` and
`:max-bytes` change the output cap (500 lines or 64 KB, keeping the
first and last 250 lines).

Open the workbook, `M-x devops-bb-sandbox-attach`, pick a sink and a
feed name. The mode line says `sandbox: NAME`. Attach refuses when
`ob-confirm-posframe-mode` is off, when the file is remote (TRAMP), or
when the dir fails its check (below).

- `M-x devops-bb-sandbox-detach`, killing the buffer, or quitting Emacs
  closes the feed; the publisher pushes what it has and marks it
  detached.
- `sandbox: DEAD` in the mode line: the publisher died. Attach again to
  start a new feed.
- One sink serves one workbook at a time; many workbooks can each have
  their own.

## What the review does

In an attached workbook, every run is confirmed in ob-confirm-posframe,
unless you confirmed and ran exactly the same thing before: the same
block, params, target, and every file in the workbook dir.

- **Notices** above the body: not run since attach, changed since you
  ran it (+n/-m), files changed in the dir, the target a dynamic target
  resolves to, a target that changed, the credential a scheme target
  uses. `d` shows the full diffs; `q` comes back.
- **Noweb**: references are shown expanded; a call `<<name()>>` is
  shown as written, since expanding it would run it.
- **Refused**, with the reason, before anything runs:
  - elisp in a header value (`:var x=(…)`, `:dir (…)`, an elisp
    `#+PROPERTY`), since org evaluates it before any prompt;
  - `:file` or `:output-dir` outside the workbook dir;
  - a scheme target (`/aws:NAME:`) whose scheme has no `:allow`.
- **Suspended**: a dotfile, dot-dir, symlink or special file anywhere
  in the dir (`.git`, `.envrc`, `.dir-locals.el`, …). Every run is
  refused and the mode line says `sandbox: SUSPENDED` until it's gone.
- **Hardened**: file-local and dir-local variables, vc, lock files,
  backups and auto-saves are off for the workbook, so nothing the agent
  writes is loaded or run by Emacs on its own.

### In the sandbox

With `DEVOPS_BB_FEED=DIR` (or `--feed DIR`), the commands read the feed
and never start `emacsclient`. Feed mode is never turned on by itself.

| Command | Answers |
|---|---|
| `devops-bb status` | the feed, the workbook, `attached`, the publisher's heartbeat, `queued` |
| `devops-bb follow [--after SEQ] [--json]` | `SEQ event status file:line id` per new event; notices on stderr |
| `devops-bb run-output ID` | that run's output, as on the host, with `truncated` when cut |
| `devops-bb modified` | whether you have unsaved edits to the workbook |

`output`, `sessions`, `revert`, `tools` and `drift` fail with "needs
Emacs, which this sandbox cannot reach".

### Code in a repo

Keep the repo out of the workbook dir: its `.git` would suspend it.
The agent clones from a bundle you make, and hands commits back as one:

```
# you, in a block: seed it from your checkout
git -C ~/src/REPO bundle create ~/work/wb/REPO.bundle --all
# the agent, in the sandbox
git clone /work/REPO.bundle ~/REPO
git -C ~/REPO bundle create /work/REPO.bundle origin/main..BRANCH
# you, in blocks: fetch into a namespace, then push
git -C ~/src/REPO -c transfer.fsckObjects=true \
  fetch ~/work/wb/REPO.bundle 'refs/heads/*:refs/sandbox/*'
git -C ~/src/REPO --no-pager log --stat origin/main..refs/sandbox/BRANCH
git -C ~/src/REPO push origin refs/sandbox/BRANCH:refs/heads/BRANCH
```

The host never runs git in a directory the agent writes. A new bundle
changes the dir, so the push block prompts again.

### Caveats

- **Turn LSP auto-start off** in workbook dirs: clojure-lsp evaluates
  `bb.edn` and `deps.edn` on visit, which no check can catch.
- **Shell sessions are shared by target.** Two workbooks on
  `/ssh:web-1:` share one shell, and its cwd and exports.
- **The feed holds what Emacs holds.** Output is not redacted; the
  skill tells the agent to treat it as data and not to quote secrets.
- **The container needs network** for the Claude API. Nothing limits
  where else it can connect.
- Not yet: the `--ssh` sink and pulling a remote workbook, for an agent
  on a Tailscale node.



[devops.el](https://github.com/kpassapk/devops.el) runs org-babel blocks
on remote targets. devops.bb's Emacs side logs each run, and its
commands read that log and ask the Emacs that wrote it what each run
printed:

- `devops-bb dashboard`, a TUI that follows the log
- `devops-bb output`, `follow`, `sessions`, … commands that print JSON
- **sandboxes**: an agent in a container writes blocks into a workbook,
  you review and run them in Emacs, and it reads each run from a
  one-way *feed*. It never touches a server, a cloud account or your
  Emacs itself.

## Install

Requires:

- babashka 1.13.226 or later.
- Emacs with an `emacsclient` server, devops.el with `devops-mode` on,
  and [cljbang](https://github.com/borkdude/cljbang.el), with this
  repository's Emacs side loaded (below).
- For sandboxes, [ob-confirm-posframe](https://github.com/kpassapk/ob-confirm-posframe)
  with `ob-confirm-posframe-mode` on, and Docker.
- Optionally [cljbang-org](https://github.com/kpassapk/cljbang-org) in
  that Emacs, for the dashboard to show a block's heading and source.

Link the command onto PATH. It finds `bb.edn` through the link, so it
runs from any directory:

```
ln -s ~/src/github.com/kpassapk/devops.bb/bin/devops-bb ~/.local/bin/
devops-bb status
```

Load the Emacs side, and turn on the log:

```emacs-lisp
(add-to-list 'load-path "~/src/github.com/kpassapk/devops.bb/emacs")
(require 'devops-bb)
(setq devops-bb-execution-log "~/.cache/devops/executions.jsonl")
(devops-bb-log-mode 1)
```

With use-package, the mode goes under `:config`; `:custom` would set
the variable without turning the mode on. Don't use `:ensure`: the
package is not in an archive.

[`emacs/reference/`](emacs/reference/) is a whole Emacs config:
[Emacs Bedrock](https://codeberg.org/ashton314/emacs-bedrock) (main,
18ca33a; MIT, see `LICENSE.bedrock`), unchanged but for two extras that
its `init.el` loads:

- [`extras/devops-bb.el`](emacs/reference/extras/devops-bb.el):
  devops.el, ob-confirm-posframe, cljbang, this repository's Emacs side
  and the server. Copy it into your own Bedrock config, setting
  `ref-devops-bb-dir` to your checkout.
- [`extras/macos.el`](emacs/reference/extras/macos.el): the shell's
  `PATH` in GUI Emacs, and [Calle 24](https://github.com/kickingvegas/calle24)'s
  SF Symbols tool bar icons. Run `M-x calle24-install` once and restart.

It needs Emacs 31 or later. Try it beside your own config; the path
must be absolute:

```
emacs --init-directory=~/src/github.com/kpassapk/devops.bb/emacs/reference
```

| File                            | Does                                                          |
|---------------------------------|---------------------------------------------------------------|
| `emacs/devops-bb.el`            | loads the ones below                                          |
| `emacs/devops-bb-scripting.el`  | noninteractive functions the commands call over `emacsclient` |
| `emacs/devops/bb/log.clj`       | `devops-bb-log-mode`, in cljbang: emits each event to the log and to subscribers |
| `emacs/devops/bb/sandbox.clj`   | `devops-bb-sandbox-attach`, in cljbang: feeds one workbook's runs to a sandbox |
| `emacs/devops-bb-review.el`     | the review of every run in an attached workbook              |

`devops-bb-log-mode` writes a line when a block runs, when an async
block's result arrives, when `devops-tangle` or `devops-drift` runs, and
when an org buffer is first modified, saved, reverted, or killed with
unsaved edits. The log holds no output.

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

The log path comes from `devops-bb-execution-log` in Emacs. Without Emacs,
pass it: `devops-bb dashboard --log ~/.cache/devops/executions.jsonl`.
The list works from the log alone; opening a block run needs Emacs. In a
sandbox (`DEVOPS_BB_FEED` set) it follows the feed instead.

⏎ opens the selected event. A block run is answered by Emacs, and
nothing runs:

- **output**: `devops-bb-scripting-run-output`, by the run's session and
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
| `devops-bb publish --to DIR …` | stdin to a sandbox's feed (Emacs starts it; see below) |

```
$ devops-bb follow --dir ~/ops
execute running /home/me/ops/web.org:12 089d9332-bcff-4ae3-9916-d891ec394804
result done /home/me/ops/web.org:12 089d9332-bcff-4ae3-9916-d891ec394804

$ devops-bb run-output 089d9332-bcff-4ae3-9916-d891ec394804 | jq -r .output
```

Only `drift` does more than read: it reads every target over TRAMP, and
expanding `<<name()>>` references runs those blocks.

## Agent skills

`sandbox/skills/devops-bb-sandbox` teaches Claude Code the sandbox loop:
do what needs no credential itself, write a block for the rest, follow
the feed, read each run, and keep the workbook dir clean. It is in the
image already.

## Development

| Namespace             | Does                                                        |
|-----------------------|-------------------------------------------------------------|
| `devops.bb.log`       | log bytes → event maps; follows appends and truncation      |
| `devops.bb.model`     | events → state; pairs `execute` and `result` by run id      |
| `devops.bb.emacs`     | `emacsclient --eval`, the value back as base64 JSON         |
| `devops.bb.view`      | state → screen text; pure                                   |
| `devops.bb.cli`       | the commands, and feed mode                                 |
| `devops.bb.dashboard` | the [charm.clj](https://github.com/kpassapk/charm.clj) loop |
| `devops.bb.feed`      | the feed's layout, its sinks (`put`, `append`), the reader   |
| `devops.bb.publish`   | stdin → queue → capped outputs and numbered events → sink    |

`bb test` runs the tests. The view tests compare whole screens as text.

`bb test-emacs` runs the Emacs side's ERT tests in batch Emacs, with
devops.el, cljbang.el and ob-confirm-posframe from `../devops.el`,
`../cljbang.el` and `../ob-confirm-posframe`, or from `DEVOPS_EL`,
`CLJBANG` and `OB_CONFIRM_POSFRAME`.

`test/container-check.sh` checks both container sinks end to end with
Docker; it is not part of `bb test`.

The design, and why each piece is there, is in
`work/20261016T150300--devops-sandbox.org`.
