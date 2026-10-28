---
name: devops-feature
description: >
  You are in a sandbox and the operator's workbook heading asks for a
  code change in a repo: it has `:BRANCH:` (and maybe `:BASE:`,
  `:REPO:`), and the operator seeded `/work/NAME.bundle`. Clone it with
  `devops-bb repo clone`, commit on the branch, hand back with
  `devops-bb repo handoff`, and write the push as a block. Use when the
  prompt names a heading with a `:BRANCH:`, when you need to clone,
  commit, hand off or rebase, when "the base moved", or when writing the
  Result of a code task. The workbook loop itself (Monitor, ack, writing
  blocks) is devops-link.
---

# devops-feature: code in your own clone

The loop with the operator, and every write to the workbook, follow
devops-link. This is the code side.

```org
* TODO API handlers
:PROPERTIES:
:BRANCH: api         <- commit on this branch
:BASE:   core        <- cut from this one (default main)
:REPO:   ~/src/...   <- the operator's checkout; names the bundle files
:END:
```

The repo is never in /work (a `.git` there suspends the workbook, and
you hold no credential to push anyway). The operator seeds a bundle,
`/work/NAME.bundle`, and you work in your own clone, from these
commands. They read the heading's `:BRANCH:` and `:BASE:` from the
workbook; pass `--heading "<its title or :ID:>"`.

| Command | Does |
|---|---|
| `devops-bb repo clone --heading H` | clones to the `:REPO:` path (`~/src/…`; `~` is your home), else `~/NAME`, on `:BRANCH:` cut from `:BASE:`; sets a git identity, no signing |
| `devops-bb repo handoff --heading H` | bundles every branch with commits origin lacks into `/work/NAME.out.bundle`, by name |
| `devops-bb repo rebase --heading H` | moves your branch onto its base's new tip, from `/work/NAME.base.bundle` |

Clone, edit, test and commit as freely as in any repo, and write no
block for it. Use `git --no-pager` (or `GIT_PAGER=cat`).

- **Never commit under `work/`.** It is the operator's workbook;
  `repo handoff` refuses, and so does the host.
- **Babashka here, no JVM.** `bb` tasks and tests run (`bb test`); `clj`,
  `lein` and a REPL don't. If the repo's own notes (AGENTS.md, a README)
  need one, read the code instead, and say in your Result what you could
  not run.
- **Hand back with `repo handoff`, not your own `git bundle`.** It names
  the branches, which the host needs, and checks what you committed. Tell
  the operator which branches it holds, then wait: they fetch the bundle
  into `refs/sandbox/*` and read the commits. Nothing goes further
  without them.

## The Result and the push

When you are done, add a `** Result` under your heading: the branch,
one line per commit, and anything the operator must decide. Under it,
the push, as a block you don't run. It is the one step needing a
credential; `:dir` is the operator's checkout:

```org
** Result
Branch =api=, on =core=:
- 1a2b3c4 handlers: list and get
- 5d6e7f8 handlers: 404 for a missing id

#+begin_src sh :dir ~/src/...
git push origin refs/sandbox/api:refs/heads/api
gh pr create --head api --base core
#+end_src
```

The `--base` is `:BASE:`, which makes a stacked PR when it is not
`main`.

## When the base is wrong

If working on your branch shows that the base needs a change (a data
model your API can't use), make it on the base branch, in your clone:

```
git switch -c BASE origin/BASE      # once
# edit, test, commit
git switch BRANCH && git rebase BASE
```

Then `repo handoff` carries both branches. Say in your Result which
commits are for `BASE` (they belong to its heading, and the operator
reviews them there).

## When the base has moved

The operator says "the base moved" and puts `/work/NAME.base.bundle`
there. Run `devops-bb repo rebase --heading H`, not your own rebase: it
knows the base you were cut from, and which of your commits the new base
already holds.

- `base unchanged`: nothing to do.
- `conflict in: FILES`: resolve them, `git add`, then `devops-bb
  repo rebase --heading H --continue`. Don't `git rebase --abort` without
  saying so.
- If you made commits on a local `BASE`, bring it up to date after:
  `git switch BASE && git merge --ff-only origin/BASE`, then back to
  your branch.

Then `repo handoff` again.
