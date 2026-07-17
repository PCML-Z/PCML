#!/bin/bash
# PMCL 实例 1 — Alice
# 数据目录: /tmp/pmcl-test1/home
cd /Users/peddlejumper/PMCL
mkdir -p /tmp/pmcl-test1/home/.pmcl/friend-data
java -Xmx1g -Duser.home=/tmp/pmcl-test1/home -jar ui/build/libs/pmcl-1.0.0-all.jar
