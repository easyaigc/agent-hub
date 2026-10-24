FROM node:24.19.0-bookworm-slim@sha256:a9f5f7c91a432850b2a8a7797adf5eadb6c733ceed61167806cee7ea7fbc29df

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
      bash ca-certificates curl git procps unzip zip python3 make g++ \
    && rm -rf /var/lib/apt/lists/*

# Only this disposable Linux CI image supplies the research fixture's metadata CLI.
COPY research-posix-tools.py /usr/local/libexec/agent-hub-research-posix-tools.py
RUN mkdir -p /usr/libexec \
    && cp -L /usr/bin/ps /usr/local/libexec/agent-hub-ps.real \
    && chmod 755 /usr/local/libexec/agent-hub-research-posix-tools.py \
    && ln -sf /usr/local/libexec/agent-hub-research-posix-tools.py /usr/bin/ps \
    && ln -s /usr/local/libexec/agent-hub-research-posix-tools.py /usr/libexec/PlistBuddy
ENV AGENT_HUB_CI_RESEARCH_TOOLS=1
