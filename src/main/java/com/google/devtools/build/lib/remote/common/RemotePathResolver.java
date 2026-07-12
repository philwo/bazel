// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.common;


import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.actions.WorkingDirectoryPathMapper;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link RemotePathResolver} is used to resolve input/output paths for remote execution from
 * Bazel's internal path, or vice versa.
 */
public interface RemotePathResolver {

  /**
   * Returns the {@code working_directory} the remote command runs in, relative to the input root.
   * Empty if the command runs in the input root.
   *
   * <p>Non-empty only for GN-style output directories, where inputs stay at true exec paths (input
   * root) but the command runs in {@code bazel-out/<mnemonic>} with paths rendered relative to it.
   */
  default PathFragment getCommandWorkingDirectory() {
    return PathFragment.EMPTY_FRAGMENT;
  }

  /** Resolves the output path relative to input root for the given {@link Path}. */
  String localPathToOutputPath(Path path);

  /**
   * Resolves the output path relative to input root for the given {@link PathFragment}.
   *
   * @param execPath a path fragment relative to {@code execRoot}.
   */
  String localPathToOutputPath(PathFragment execPath);

  /** Resolves the output path relative to input root for the {@link ActionInput}. */
  default String localPathToOutputPath(ActionInput actionInput) {
    return localPathToOutputPath(actionInput.getExecPath());
  }

  /**
   * Resolves the local {@link Path} of an output file.
   *
   * @param outputPath the return value of {@link #localPathToOutputPath(PathFragment)}.
   */
  Path outputPathToLocalPath(String outputPath);

  /** Returns the exec path for the given local path. */
  PathFragment localPathToExecPath(PathFragment localPath);

  /** Creates the default {@link RemotePathResolver}. */
  static RemotePathResolver createDefault(Path execRoot) {
    return new DefaultRemotePathResolver(execRoot);
  }

  /** The default {@link RemotePathResolver} which uses {@code execRoot} as the input root. */
  class DefaultRemotePathResolver implements RemotePathResolver {

    private final Path execRoot;

    public DefaultRemotePathResolver(Path execRoot) {
      this.execRoot = execRoot;
    }

    @Override
    public String localPathToOutputPath(Path path) {
      return path.relativeTo(execRoot).getPathString();
    }

    @Override
    public String localPathToOutputPath(PathFragment execPath) {
      return execPath.getPathString();
    }

    @Override
    public Path outputPathToLocalPath(String outputPath) {
      return execRoot.getRelative(outputPath);
    }

    @Override
    public PathFragment localPathToExecPath(PathFragment localPath) {
      return localPath.relativeTo(execRoot.asFragment());
    }
  }

  /**
   * Adapts a given base {@link RemotePathResolver} to also apply a {@link PathMapper} to map (and
   * inverse map) paths.
   */
  static RemotePathResolver createMapped(
      RemotePathResolver base, Path execRoot, PathMapper pathMapper) {
    return createMapped(base, execRoot, pathMapper, PathFragment.EMPTY_FRAGMENT);
  }

  /**
   * Like {@link #createMapped(RemotePathResolver, Path, PathMapper)}, but additionally runs the
   * remote action with the given {@code workingDirectory} (relative to the input root) and renders
   * output paths relative to it. Input staging is unaffected: the Merkle/input root stays at true
   * exec paths, and command-line paths (already rendered relative by the spawn) resolve within it.
   */
  static RemotePathResolver createMapped(
      RemotePathResolver base,
      Path execRoot,
      PathMapper pathMapper,
      PathFragment workingDirectory) {
    if (pathMapper.isNoop() && workingDirectory.isEmpty()) {
      return base;
    }
    // Fold the working-directory relativization into the path mapper so that output paths are
    // rendered relative to the working directory and the inverse map reverses them back to true
    // local paths.
    PathMapper effectiveMapper =
        workingDirectory.isEmpty()
            ? pathMapper
            : new WorkingDirectoryPathMapper(pathMapper, workingDirectory);
    return new RemotePathResolver() {
      private final ConcurrentHashMap<PathFragment, PathFragment> inverse =
          new ConcurrentHashMap<>();

      @Override
      public PathFragment getCommandWorkingDirectory() {
        return workingDirectory.isEmpty() ? base.getCommandWorkingDirectory() : workingDirectory;
      }

      @Override
      public String localPathToOutputPath(Path path) {
        return localPathToOutputPath(path.relativeTo(execRoot));
      }

      @Override
      public String localPathToOutputPath(PathFragment execPath) {
        return base.localPathToOutputPath(map(execPath));
      }

      @Override
      public Path outputPathToLocalPath(String outputPath) {
        return execRoot.getRelative(inverseMap(PathFragment.create(outputPath)));
      }

      @Override
      public PathFragment localPathToExecPath(PathFragment localPath) {
        return base.localPathToExecPath(localPath);
      }

      private PathFragment map(PathFragment path) {
        PathFragment mappedPath = effectiveMapper.map(path);
        PathFragment previousPath = inverse.put(mappedPath, path);
        Preconditions.checkState(
            previousPath == null || previousPath.equals(path),
            "Two different paths %s and %s map to the same path %s",
            previousPath,
            path,
            mappedPath);
        return mappedPath;
      }

      private PathFragment inverseMap(PathFragment path) {
        return Preconditions.checkNotNull(
            inverse.get(path), "Failed to find original path for mapped path %s", path);
      }
    };
  }
}
