#!/usr/bin/env bash
# Container sinks, end to end, with Docker: not part of `bb test'.
#
# Two sandboxes, each a babashka container running as an unprivileged
# user, with this checkout ro at /code and a workbook dir rw at /work:
#
#   via: the feed lives in the container at /feed, pushed with
#        --via "docker exec -i -u root BOX" --to /feed
#   ro:  the feed is a host dir, mounted ro at /feed
#
# In each, devops-bb status, follow and run-output read the feed, and
# writing /feed fails.  A save by rename in the host's workbook dir is
# seen in the container.  Fake JSON lines stand in for Emacs.
#
# And busybox: --via into an alpine container, whose sh, head and wc
# run the push's scripts.
#
# Usage: test/container-check.sh [IMAGE]   (default babashka/babashka:1.13.225)
set -euo pipefail

image=${1:-babashka/babashka:1.13.225}
code=$(cd "$(dirname "$0")/.." && pwd)
root=$(mktemp -d)
boxes=()
pubs=()
failed=0

cleanup() {
  for p in "${pubs[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null || true; done
  for b in "${boxes[@]:-}"; do [ -n "$b" ] && docker rm -f "$b" >/dev/null 2>&1 || true; done
  rm -rf "$root"
}
trap cleanup EXIT

ok()   { echo "ok   $*"; }
fail() { echo "FAIL $*"; failed=1; }
check() { # check WHAT CMD...: CMD exits 0
  local what=$1; shift
  if "$@"; then ok "$what"; else fail "$what"; fi
}

id1=0b9e1b1a-1111-4222-8333-444455556666
id2=0b9e1b1a-1111-4222-8333-777788889999

# devops-bb in the container: bb.edn without its git deps (feed mode
# needs none, and the image has no java to resolve them), and
# /code/src, relative as bb wants it.
mkdir -p "$root/bin"
bb -e "(spit \"$root/bin/bb.edn\"
             (pr-str (-> (clojure.edn/read-string (slurp \"$code/bb.edn\"))
                         (dissoc :deps)
                         (assoc :paths [\"../../code/src\"]))))"
printf '#!/bin/sh\nexec bb --config /opt/devops-bb/bb.edn "$@"\n' > "$root/bin/devops-bb"
chmod +x "$root/bin/devops-bb"

line() { # line EVENT ID [OUTPUT]
  local wb=$1 ev=$2 id=$3 out=${4:-}
  if [ -n "$out" ]; then
    printf '{"event":"%s","file":"%s/wb.org","buffer":"wb.org","line":3,"id":"%s","status":"done","session":"s","output":{"session":"s","status":"done","id":"%s","input":"echo","output":"%s\\n"}}\n' \
      "$ev" "$wb" "$id" "$id" "$out"
  else
    printf '{"event":"%s","file":"%s/wb.org","buffer":"wb.org","line":3,"id":"%s","status":"running"}\n' \
      "$ev" "$wb" "$id"
  fi
}

run_box() { # run_box NAME WB [EXTRA-DOCKER-ARGS...]
  local name=$1 wb=$2; shift 2
  docker run -d --name "$name" --user 1000:1000 -e HOME=/tmp \
    -v "$code":/code:ro -v "$root/bin":/opt/devops-bb:ro -v "$wb":/work:rw \
    "$@" "$image" sleep infinity >/dev/null
  boxes+=("$name")
}

in_box() { local b=$1; shift; docker exec "$b" /opt/devops-bb/devops-bb "$@"; }

sandbox() { # sandbox KIND: the whole check for one sink
  local kind=$1 box="devops-bb-check-$1-$$" wb="$root/$1/wb" feed="$root/$1/feed"
  mkdir -p "$wb" "$feed"
  echo "* old" > "$wb/wb.org"
  echo "== $kind"

  local sink
  if [ "$kind" = via ]; then
    run_box "$box" "$wb"
    sink=(--via "docker exec -i -u root $box" --to /feed)
  else
    run_box "$box" "$wb" -v "$feed":/feed:ro
    sink=(--to "$feed")
  fi

  # The publisher, stdin a fifo held open as Emacs holds its pipe.
  mkfifo "$root/$kind/in"
  "$code/bin/devops-bb" publish "${sink[@]}" --map "$wb=/work" --name wb \
    < "$root/$kind/in" 2> "$root/$kind/publish.err" &
  pubs+=($!)
  exec 3> "$root/$kind/in"
  { echo '{"event":"status","file":"'"$wb"'/wb.org","devops-mode":true}'
    line "$wb" execute "$id1"
    line "$wb" result "$id1" hello; } >&3

  # follow, in the background in the container, from the first seq.
  docker exec "$box" sh -c '/opt/devops-bb/devops-bb follow --feed /feed --json --after 0 > /tmp/follow 2>&1 &'
  sleep 3

  check "$kind: status attached, workbook mapped" \
    grep -q '"workbook":"/work/wb.org".*"attached":true' <<<"$(in_box "$box" status --feed /feed)"
  check "$kind: run-output" \
    grep -q '"output":"hello\\n"' <<<"$(in_box "$box" run-output "$id1" --feed /feed)"

  # A run while follow is running: seen within a couple of seconds.
  line "$wb" result "$id2" again >&3
  sleep 2.5
  check "$kind: follow saw seqs 1-3, mapped" \
    sh -c "docker exec $box cat /tmp/follow | grep -c '\"file\":\"/work/wb.org\"' | grep -qx 3"
  check "$kind: the live run's output" \
    grep -q '"output":"again\\n"' <<<"$(in_box "$box" run-output "$id2" --feed /feed)"

  # Writing the feed from inside fails, as Claude's user.
  check "$kind: /feed/events.jsonl not writable" \
    sh -c "! docker exec $box sh -c 'echo x >> /feed/events.jsonl' 2>/dev/null"
  check "$kind: /feed/outputs not writable" \
    sh -c "! docker exec $box sh -c 'echo x > /feed/outputs/x' 2>/dev/null"
  check "$kind: /feed not replaceable" \
    sh -c "! docker exec $box sh -c 'mv /feed/outputs /feed/o && ln -s /tmp /feed/outputs' 2>/dev/null"

  # A save by rename on the host is seen through the dir mount.
  echo "* new" > "$wb/.wb.org.tmp" && mv "$wb/.wb.org.tmp" "$wb/wb.org"
  sleep 1
  check "$kind: save by rename seen in the container" \
    sh -c "docker exec $box cat /work/wb.org | grep -qx '\\* new'"
  check "$kind: the workbook dir is rw" \
    sh -c "docker exec $box sh -c 'echo code > /work/claude.sh' && grep -qx code '$wb/claude.sh'"

  # Detach: stdin closes, the publisher marks the feed detached.
  exec 3>&-
  wait "${pubs[${#pubs[@]}-1]}" || fail "$kind: publisher exited $?"
  check "$kind: detached" \
    grep -q '"attached":false' <<<"$(in_box "$box" status --feed /feed)"
  [ -s "$root/$kind/publish.err" ] && { echo "     publish stderr:"; sed 's/^/     /' "$root/$kind/publish.err"; }
  return 0
}

busybox() { # --via into alpine: busybox runs the scripts
  local box="devops-bb-check-alpine-$$"
  echo "== alpine"
  docker run -d --name "$box" alpine sleep infinity >/dev/null
  boxes+=("$box")
  docker exec "$box" ln -s /tmp /link
  { line /w execute "$id1"
    line /w result "$id1" hello
    line /w result "$id2" again; } |
    "$code/bin/devops-bb" publish --via "docker exec -i $box" --to /feed --name wb \
      2> "$root/alpine.err"
  check "alpine: three events" \
    sh -c "docker exec $box wc -l /feed/events.jsonl | grep -q '^3 '"
  check "alpine: outputs in place" \
    sh -c "docker exec $box cat /feed/outputs/$id2.json | grep -q '\"output\":\"again'"
  check "alpine: detached" \
    sh -c "docker exec $box cat /feed/status.json | grep -q '\"attached\":false'"
  check "alpine: no retries" test ! -s "$root/alpine.err"
  echo '{"event":"save"}' |
    "$code/bin/devops-bb" publish --via "docker exec -i $box" --to /link --name wb \
      2> "$root/link.err" &
  pubs+=($!)
  sleep 2
  check "alpine: a symlinked feed is refused" grep -q 'refusing to write through a symlink: /link' "$root/link.err"
  check "alpine: nothing written through it" \
    sh -c "! docker exec $box ls /tmp/events.jsonl 2>/dev/null"
}

sandbox via
sandbox ro
busybox
[ "$failed" = 0 ] && echo "all ok" || { echo "some checks failed"; exit 1; }
