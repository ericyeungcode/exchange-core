#! /bin/bash

# replace with your java 8 home

cd ..
export JAVA_HOME=~/.sdkman/candidates/java/8.0.462-tem && mvnd clean test -Dtest=PerfLatency#testLatencyMargin