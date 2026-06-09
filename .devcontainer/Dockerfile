FROM mcr.microsoft.com/devcontainers/java:1-21-bookworm

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN apt-get update \
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
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get update \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g pnpm \
    && rm -rf /var/lib/apt/lists/*

USER vscode

WORKDIR /workspace/tesseraql
