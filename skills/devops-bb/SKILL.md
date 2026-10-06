---
name: devops-bb
description: >
  Work with the user through an org file with devops.el, using the
  devops-bb command line: you write org-babel blocks, the user edits and
  runs them in Emacs, you follow each run with `devops-bb follow`, read its
  output with `devops-bb output` or `devops-bb run-output`, and write the
  next blocks. Use when the task is done by running blocks in an org file
  with #+TARGET keywords, when a block left only a UUID or a truncated
  result in #+RESULTS, when the user says "done", a block failed, or "read
  the output", before writing the next block, or to check whether tangled
  config drifted from a server.
---

# devops.el, through devops-bb

devops.el runs org-babel blocks on the target that a heading's tag names
(`#+TARGET: /ssh:host: (tag)`). With `devops-enable-session-async` on, a
block runs in an async shell session in the user's Emacs. `#+RESULTS:`
holds a placeholder UUID while it runs. A block that fails can keep that
UUID or a cut-short result. The full output, stderr included, stays in the
session buffer.

`devops-bb` reads all of that for you. Use it instead of `emacsclient`.

## The loop

1. You write org-babel blocks into the org file, under a heading whose
   tag names the target (see "Writing blocks").
2. The user reads them, edits them if they want, and runs them. You
   don't run them.
3. You learn of each run from `devops-bb follow` (see "Following runs")
   and read its output with `devops-bb run-output` or `devops-bb output`.
4. You tell the user what happened and write the next blocks, or a fix.

The user's edits are theirs: read the block as it is in the file before
you reason about its output, and don't overwrite what they changed.

## The command

Every command but `follow` asks the user's Emacs one question, prints the
answer as one line of JSON on stdout, and exits 0. Pipe it to `jq`. On
failure it prints `error: ...` on stderr and exits 1. FILE may be
relative to the current directory.

| Command | Answers |
|---|---|
| `devops-bb status` | `devops-mode` (true/false) and `execution-log` (the file, or null) |
| `devops-bb output FILE LINE [--tag T]` | what the block at LINE printed (see "Block output") |
| `devops-bb run-output ID [--session S]` | what one run printed (see "Block output") |
| `devops-bb sessions` | the live async sessions (see "Sessions") |
| `devops-bb follow [--dir D] [--json]` | a line per new log event under D; runs until stopped |
| `devops-bb modified FILE` | `true` when Emacs holds unsaved edits to FILE |
| `devops-bb revert FILE` | `"reverted"`, or `"not-visited"` when Emacs doesn't have FILE open |
| `devops-bb tools [REGEXP]` | the loaded `tools.org` blocks: `name`, `language`, `vars` |
| `devops-bb drift FILE [--heading H \| --custom-id ID]` | drift entries (see "Drift") |

If it fails:

| Error | Meaning |
|---|---|
| `command not found: devops-bb` | Not on PATH. Ask the user to link `bin/devops-bb` from the devops.bb checkout onto PATH. |
| `can't find socket` / `connection refused` | No Emacs server. Ask the user to run `M-x server-start`. |
| `Cannot open load file ... devops` | devops.el isn't on the `load-path` of that Emacs. Ask the user how it is installed. |
| `Not an org buffer` | The file isn't org, or opened in another mode. |

## Writing blocks

A minimal file. Targets go at the top; a heading tag picks one:

```org
#+TITLE: Upgrade web servers
#+PROPERTY: header-args:sh :results output
#+TARGET: /ssh:web1.example.com: (web1)
#+TARGET: /ssh:deploy@web2.example.com|sudo::/etc/nginx (web2)

* Check disk                                                  :web1:

#+begin_src sh
df -h /
#+end_src

* Process locally                                             :web1:

#+name: pkgs
#+begin_src sh
dpkg -l | awk '/^ii/ {print $2}'
#+end_src

#+begin_src sh :stdin pkgs :target nil
grep -c nginx
#+end_src
```

- **A target** is a TRAMP prefix, a directory, or both. Multi-hop
  (`|sudo:`, `|podman:box:`) works. The tag in parentheses is what
  headings use. Read the file's existing `#+TARGET` lines before adding
  one, and reuse their tags.
- **The tag is inherited.** Blocks under a tagged heading, or any
  heading below it, run on that target: devops.el injects `:dir`. Give
  every new heading a tag, or put it under a tagged parent. A heading
  with no target tag runs locally.
- **One target tag per heading, counting inherited ones.** A `:web2:`
  heading under a `:web1:` parent has both. With two, the user gets a
  prompt on every run and `devops-bb output` needs `--tag`. Write one
  heading per target instead.
- **`:results output`.** Set it in `#+PROPERTY` (as above) or on each
  shell block. Without it, a shell block's result is its exit status,
  and it runs synchronously.
- **`:target nil`** runs one block locally under a tagged heading, for
  example to process a remote block's output with `:stdin`. An explicit
  `:dir` also wins over the tag.
- **Sessions are stateful.** With async sessions on, `cd`, `export` and
  activated virtualenvs carry over from one block to the next. Write
  blocks that don't depend on the ones above them, so a block that
  passes still passes after `M-x devops-restart-session`.
- **Tangling.** `:tangle PATH` under a tagged heading writes to the
  target when the user runs `devops-tangle`. A relative PATH lands under
  the target's directory; `/etc/f` and `~/f` are absolute on the target's
  machine. `:tangle yes`, a path with its own TRAMP prefix, or
  `:target nil` keep org's own destination. `:mkdirp`, `:shebang` and
  `:tangle-mode` work as in org.
- **References.** `:var x=name`, `:stdin name` and `<<name()>>` (with
  `:noweb yes`) work as in org, and run synchronously.
- **Dynamic targets.** `#+TARGET: <<server()>> (app)` names the host
  with the value of the `server` block, resolved when a block runs. Use
  it when the host is an input, not a constant.
- **Tools.** A project's `tools.org` can expose named blocks as tools
  for `#+call: name(arg="x")`. List the loaded ones with
  `devops-bb tools [REGEXP]` before writing your own.

Org's built-in header arguments mean what org's manual says. devops.el
adds only `:target`.

## Editing the org file

The user has the file open in Emacs. Before you write to it on disk:

```
devops-bb modified FILE
```

`true` means unsaved edits: ask the user to save, and don't write. After
you write, reload the buffer so it matches the disk:

```
devops-bb revert FILE
```

Add blocks; don't rewrite blocks the user already ran or edited. Leave
`#+RESULTS:` alone.

## Block output

There are two ways to ask, and both only read:

- **`devops-bb run-output ID`**: one run, by the `id` its log line
  carries. Use this for a run you saw in `follow`. It answers from the
  session alone, so it is that run's output even after the block moved
  or ran again. Without `--session`, every live session is searched.
- **`devops-bb output FILE LINE`**: a block's latest run. LINE can be
  any line of the block. Use this when you have no ID: the user said
  "done" without the log, or a synchronous run. It falls back to
  `#+RESULTS:` when the session has no run of the block.

`run-output` answers:

| Key       | Meaning                                                                      |
|-----------|------------------------------------------------------------------------------|
| `session` | the session buffer's name, or null when no session has the run               |
| `status`  | `done`, `running`, `not-found` (no live session holds the run), `no-session` |
| `id`      | the ID you asked for                                                         |
| `input`   | what was sent, as the session echoed it                                      |
| `output`  | what the run printed, stderr included                                        |

`output` answers:

| Key       | Meaning                                                                         |
|-----------|---------------------------------------------------------------------------------|
| `session` | the session buffer's name                                                       |
| `status`  | `done`, `running`, `not-found` (the session never ran this block), `no-session` |
| `id`      | the run's async ID                                                              |
| `source`  | where `output` came from: `session`, `results`, or null                         |
| `output`  | what the block printed                                                          |
| `result`  | the block's `#+RESULTS:` as plain text                                          |

`output` reads the session first: it has the full output, stderr
included. When the session is gone or never ran the block (a
synchronous block, a restarted session), `output` comes from
`#+RESULTS:` instead. A UUID placeholder left there is not output, so
`output` is null.

When the heading has more than one target tag, `output` fails with an
error that names the tags. Pass one as `--tag`.

Nothing is run, except that a dynamic target (`#+TARGET: <<name()>>`)
runs its block to resolve the session name, as the block itself did.

What to do next:

| You see                                         | Do                                                                                                       |
|-------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `done`, output looks right                      | Say so in a line, write the next blocks.                                                                 |
| `done`, output shows an error                   | Explain the cause, write a fixed block below (don't edit theirs unless asked).                           |
| `running` for a long time                       | Run `devops-bb sessions`: it may be `waiting` at a prompt.                                               |
| `run-output` says `not-found`                   | The session restarted or was killed. Try `devops-bb output FILE LINE` for `#+RESULTS:`.                  |
| `not-found` or `no-session`, `source` `results` | A synchronous run or a restarted session: read `output`.                                                 |
| `output` null, `result` a UUID                  | The run died before its result arrived. Ask the user to look at the session (`M-x devops-goto-session`). |
| error naming tags                               | Pass one as `--tag`, or ask which target the user meant.                                                 |

## Sessions

`devops-bb sessions` lists the live async sessions: `name`, `directory`
(the target), `state` (`idle`, `running`, `waiting`), `prompt`, and `id`.

`waiting` means the session stopped at a prompt that is not its own:
`[sudo] password`, an ssh host key, an `apt` question. Never answer it.
Tell the user which session is waiting and what it asks. The user answers
it with `M-x devops-goto-session` on the block's heading.

## Following runs

With `devops-mode` on and `devops-execution-log` set, Emacs appends a
line to the log when the user runs a block, once more when an async
block's result arrives, and when the user runs `devops-tangle` or
`devops-drift`. Watch it instead of waiting for the user to say "done".

Check first:

```
devops-bb status
```

If `devops-mode` is false or `execution-log` null, ask the user to turn
on `devops-mode` and set `devops-execution-log` (e.g. `M-x
customize-variable`). Don't turn it on yourself. Until it is on, wait for
the user to say "done", then run `devops-bb output` on the blocks you
wrote.

Then start `follow` as a background monitor (in Claude Code, the Monitor
tool). It prints one line per new event for org files under `--dir`
(default: the current directory), starting from now:

```
devops-bb follow --dir "$PWD"
```

```
execute running /home/me/ops/web.org:12 089d9332-bcff-4ae3-9916-d891ec394804
result done /home/me/ops/web.org:12 089d9332-bcff-4ae3-9916-d891ec394804
execute error /home/me/ops/web.org:30
tangle done /home/me/ops/web.org:8
drift drift /home/me/ops/web.org:all
```

That is: event, how it went, `file:line` (`all` for a command run on
every heading), and the run's ID for a block. An async block logs
`execute` `running`, then `result` `done`. A synchronous block logs only
`execute`.

- On a block line, run `devops-bb run-output ID` (or `devops-bb output
  FILE LINE` without an ID), check the output, tell the user what
  happened, and write the next blocks.
- On `execute error`, the block's heading has no target, or two. Run
  `devops-bb output FILE LINE` to see which.
- On a `tangle` or `drift` line, `--json` has the details: `targets`
  (`{tag, target, files}`, `files` a count) or `files` (`{status, tag,
  path, remote, detail}`), or `error`. For the diff of a drifting file,
  ask the user before you run `devops-bb drift` (see "Drift").

`follow --json` prints each event's whole log line instead: `event`,
`time` (to the millisecond), `file`, `buffer`, and per event `line`,
`session`, `status`, `id`, `all`, `heading`, `targets`, `files`, `error`.
A line holds no output, `#+RESULTS:` or diff.

Runs that devops.el makes on its own (references, dynamic targets,
tangling) are not logged, and neither are tangles and drift checks a
script asks for, including `devops-bb drift`.

## Drift

Drift compares what the org file would tangle with what is on each
target:

| Command | Checks |
|---|---|
| `devops-bb drift FILE` | every target-tagged heading |
| `devops-bb drift FILE --heading "Title"` | one subtree, by title |
| `devops-bb drift FILE --custom-id id` | one subtree, by `CUSTOM_ID` |

It prints one entry per tangled file: `status` (`same`, `drift`,
`missing`, `error`), `tag`, `path`, `remote`, `target`, `detail`, and
`diff` (a unified diff when drifting). All `same` means no drift:

```
devops-bb drift FILE | jq 'all(.status == "same")'
```

A drift check reads every target over TRAMP, and expanding
`<<name()>>` references runs those blocks. Ask the user before you run
one. Tangling (`devops-tangle`) writes to servers: that is the user's to
run.

## Rules

- Never run blocks, tangle, or write `#+RESULTS:` yourself. The user
  runs blocks.
- Read output; don't print secrets from it. If the output holds a
  credential, say that it does without quoting it.
