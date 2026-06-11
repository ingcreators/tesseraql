# JDK stays on the Java 21 baseline; CI checks Java 25 compatibility (docs/build.md).
FROM mcr.microsoft.com/devcontainers/java:3-21-bookworm

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

USER root

# The base devcontainer image may contain an old Yarn apt source.
# It is not required for TesseraQL and can break apt-get update when its GPG key is missing.
RUN rm -f /etc/apt/sources.list.d/yarn.list \
    /etc/apt/sources.list.d/yarn.list.save \
    && find /etc/apt/sources.list.d -type f -iname '*yarn*' -delete \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    jq \
    maven \
    ripgrep \
    unzip \
    zip \
    && rm -rf /var/lib/apt/lists/*

# Node.js is used for TesseraQL Studio assets and Hypermedia Components-related tooling.
RUN rm -f /etc/apt/sources.list.d/yarn.list \
    /etc/apt/sources.list.d/yarn.list.save \
    && find /etc/apt/sources.list.d -type f -iname '*yarn*' -delete \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get update \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g pnpm \
    && rm -rf /var/lib/apt/lists/*

USER vscode

WORKDIR /workspace/tesseraql