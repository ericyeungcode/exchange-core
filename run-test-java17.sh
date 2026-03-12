#! /bin/bash

# replace with your java 17 home

export JAVA_HOME=/Users/ericyeung/.sdkman/candidates/java/17.0.18-tem && mvnd clean test -Dtest=PerfLatency#testLatencyMargin
