#!/usr/bin/env bash
#
# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Workspace status command for local builds of Bazel itself.
#
# Emitting BUILD_EMBED_LABEL sets the "Build label" reported by the resulting
# binary's `bazel version` command (it is a special stable key, see
# BazelWorkspaceStatusModule). Combined with --stamp in .bazelrc, this makes a
# locally built //src:bazel report the Git commit it was built from.
#
# BUILD_EMBED_LABEL is also the binary's own version string (it is what
# BlazeVersionInfo.getVersion() and thus native.bazel_version return), so it must
# parse as a valid Bazel version. We therefore prefix the Git revision with the
# source tree's version from MODULE.bazel, e.g. "10.0.0-prerelease-156a0bd560".
# bazel_features' parse_version() splits the release part off at the first "-",
# so the trailing revision is treated as a prerelease tag and parsing succeeds.

# The module() version from MODULE.bazel, e.g. "10.0.0-prerelease". Matches the
# standalone `    version = "..."` line, not the inline one in bazel_dep() calls.
base_version=$(sed -n 's/^[[:space:]]*version = "\(.*\)",$/\1/p' MODULE.bazel | head -1)
if [[ -z "${base_version}" ]]; then
  # Without a valid base version we can't emit a parseable label; leave the build
  # unstamped (reports "development version") rather than break version parsing.
  exit 0
fi

git_rev=$(git rev-parse --short HEAD) || exit 1

# Mark the label as dirty if the working tree has uncommitted changes.
if ! git diff-index --quiet HEAD --; then
  git_rev="${git_rev}-dirty"
fi

echo "BUILD_EMBED_LABEL ${base_version}-${git_rev}"
