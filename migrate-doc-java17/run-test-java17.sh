#! /bin/bash

# replace with your java 17 home


#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PARENT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

export JAVA_HOME=/Users/ericyeung/.sdkman/candidates/java/17.0.18-tem && mvnd -f "$PARENT_DIR/pom.xml" clean test -Dtest=PerfLatency#testLatencyMargin
