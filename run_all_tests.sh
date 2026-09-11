#!/usr/bin/env bash
sbt clean scalafmt Test/scalafmt IntegrationTest/scalafmt scalafmtCheckAll coverage test it/test coverageOff coverageReport