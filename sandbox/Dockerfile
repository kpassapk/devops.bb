# A sandbox for Claude Code: devops-bb in feed mode, no credentials.
#
# Build from the checkout's root:
#
#   docker build -f sandbox/Dockerfile -t devops-bb-sandbox .
#
# Run one per workbook. The workbook's own dir is rw at /work. The feed
# is either a host dir mounted ro at /feed, or the image's own /feed,
# which the publisher writes with `docker exec -i -u root`:
#
#   docker run -d --name wb-box -v "$WB_DIR":/work:rw \
#     [-v "$FEED":/feed:ro] devops-bb-sandbox
#   docker exec -it wb-box claude
#
# Nothing here can reach the host's Emacs or servers: no ssh keys, no
# cloud CLIs or profiles, no git credentials. Claude Code needs its own
# login or ANTHROPIC_API_KEY, and network to reach the API.

FROM babashka/babashka:1.13.226

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates git nodejs npm jq ripgrep less \
 && rm -rf /var/lib/apt/lists/*

RUN npm install -g @anthropic-ai/claude-code

# Claude's user is not root, and does not own /feed.
RUN (userdel -r ubuntu 2>/dev/null || true) \
 && useradd -m -u 1000 -s /bin/bash claude \
 && mkdir -p /feed /work \
 && chown claude:claude /work

COPY bb.edn /opt/devops.bb/bb.edn
COPY src /opt/devops.bb/src
COPY bin /opt/devops.bb/bin
RUN ln -s /opt/devops.bb/bin/devops-bb /usr/local/bin/devops-bb

COPY --chown=claude:claude sandbox/skills /home/claude/.claude/skills

USER claude
ENV DEVOPS_BB_FEED=/feed
WORKDIR /work

# Resolve bb.edn's git dep now, so the running sandbox needs no
# egress for it. bb 1.13 clones with git; no Java.
RUN devops-bb tasks >/dev/null

CMD ["sleep", "infinity"]
