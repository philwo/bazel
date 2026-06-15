// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A build-scoped pool of action outputs that were moved aside instead of being deleted before an
 * action is re-executed, so their bytes can be reused if the action turns out to reproduce them.
 *
 * <p>Enabled by {@code --experimental_remote_reuse_existing_outputs}. When an action's key changes
 * (e.g. an input was added to a filegroup it depends on) but the action is deterministic, the
 * action re-runs yet produces byte-identical outputs. {@link RemoteActionFileSystem#delete} moves
 * each previous output here keyed by its digest (an {@code O(1)} rename, not a copy) rather than
 * unlinking it; {@link RemoteExecutionService} then {@link #reclaim reclaims} any output whose
 * expected digest matches, avoiding the redundant download. Moving the file away preserves the
 * clean-output-slate guarantee that the eager deletion otherwise provides.
 *
 * <p>Entries reclaimed during the build are renamed back into place and freed immediately. Entries
 * that are never reclaimed (because the output changed or is no longer downloaded) remain in the
 * stash directory until {@link #discardAll} is called at the end of the build.
 *
 * <p>All operations resolve paths on the filesystem of the {@code Path} they are given, which is
 * expected to be the same local filesystem the stash directory lives on.
 */
final class OutputReuseStash {
  private final Path stashDir;
  private final ConcurrentHashMap<String, Path> stashedByDigest = new ConcurrentHashMap<>();
  private final AtomicInteger counter = new AtomicInteger();

  OutputReuseStash(Path stashDir) {
    this.stashDir = stashDir;
  }

  /**
   * Moves {@code file} into the stash keyed by {@code digest} instead of deleting it. If bytes with
   * the same digest are already stashed, {@code file} is simply deleted.
   */
  void stash(Path file, byte[] digest) throws IOException {
    String key = HashCode.fromBytes(digest).toString();
    if (stashedByDigest.containsKey(key)) {
      file.delete();
      return;
    }
    stashDir.createDirectoryAndParents();
    Path dest = stashDir.getChild(key + "." + counter.getAndIncrement());
    file.renameTo(dest);
    if (stashedByDigest.putIfAbsent(key, dest) != null) {
      // Another output with the same digest won the race; we already have those bytes stashed.
      dest.delete();
    }
  }

  /**
   * If bytes with {@code digest} are stashed, moves them to {@code dest} and returns true;
   * otherwise returns false. A given stashed digest is reclaimed at most once. If the move fails
   * for any reason, returns false so the caller falls back to downloading; the orphaned entry is
   * cleaned up by {@link #discardAll}.
   */
  boolean reclaim(Path dest, byte[] digest) {
    Path stashed = stashedByDigest.remove(HashCode.fromBytes(digest).toString());
    if (stashed == null) {
      return false;
    }
    try {
      dest.getParentDirectory().createDirectoryAndParents();
      stashed.renameTo(dest);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /** Deletes any stashed bytes that were never reclaimed. Safe to call more than once. */
  void discardAll() throws IOException {
    stashedByDigest.clear();
    if (stashDir.exists()) {
      stashDir.deleteTree();
    }
  }
}
