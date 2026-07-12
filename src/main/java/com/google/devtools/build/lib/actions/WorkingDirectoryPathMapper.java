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

package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A {@link PathMapper} used purely to render command lines and param files relative to a spawn's
 * working directory (the output-tree root for GN-style {@code -C} output directories).
 *
 * <p>It composes a {@code delegate} mapper (which may already perform output-path stripping) and
 * then rewrites each resulting exec path to be relative to {@code workingDirectory}, allowing
 * {@code ..} segments. For example, with {@code workingDirectory = bazel-out/out_debug}:
 *
 * <ul>
 *   <li>a source at {@code foo/bar.cc} renders as {@code ../../foo/bar.cc};
 *   <li>an output at {@code bazel-out/out_debug/bin/foo/bar.o} renders as {@code bin/foo/bar.o};
 *   <li>an output in another tree at {@code bazel-out/out_debug-exec/bin/x} renders as {@code
 *       ../out_debug-exec/bin/x}.
 * </ul>
 *
 * <p>This mapper produces paths containing {@code ..}, which violates the {@link PathMapper}
 * invariant that the root-relative suffix is preserved. It must therefore be used <b>only</b> to
 * render command-line strings and param-file contents — never to stage inputs or to compute
 * artifact roots. {@link #mapRoot} deliberately delegates to the underlying mapper to avoid handing
 * out relative roots, and the unmapped (true exec path) mapper is what gets stored on the spawn for
 * input staging.
 */
public final class WorkingDirectoryPathMapper implements PathMapper {
  // Toolchain build variables whose values are pure exec paths: the include flag (-I/-iquote/
  // -isystem/...) is added by the flag template, so the value arrives as a bare path that must be
  // relativized even when it is a single segment. Matched by name to avoid a dependency on the C++
  // rules; kept in sync with CompileBuildVariables.
  private static final ImmutableSet<String> PATH_LIST_VARIABLES =
      ImmutableSet.of(
          "include_paths",
          "quote_include_paths",
          "system_include_paths",
          "external_include_paths",
          "framework_include_paths");

  private final PathMapper delegate;
  private final PathFragment workingDirectory;

  public WorkingDirectoryPathMapper(PathMapper delegate, PathFragment workingDirectory) {
    this.delegate = delegate;
    this.workingDirectory = workingDirectory;
  }

  @Override
  public PathFragment map(PathFragment execPath) {
    return relativize(delegate.map(execPath), workingDirectory);
  }

  @Override
  public String getMappedExecPathString(ActionInput artifact) {
    return relativize(
            PathFragment.create(delegate.getMappedExecPathString(artifact)), workingDirectory)
        .getPathString();
  }

  @Override
  public String mapHeuristically(@Nullable String variableName, String arg) {
    // Include directories (-iquote/-I/-isystem) reach the command line as bare path tokens through
    // plain string variables rather than through map(), so relativize the recognizable exec-path
    // tokens here. Only tokens that are unambiguously exec paths are touched; flags, defines and
    // other strings pass through unchanged.
    if (arg.isEmpty()) {
      return arg;
    }
    for (String prefix :
        List.of(
            "-iquote",
            "-isystem",
            "-I",
            "--sysroot=",
            "--warning-suppression-mappings=",
            // Chromium's raw-ptr clang plugin takes an exec-path prefix to exempt
            // from its checks. It arrives as a bare plugin-arg token (no leading
            // dash) following -plugin-arg-raw-ptr-plugin, so without this entry it
            // falls through to relativizeExecPathString, whose PathFragment
            // normalization eats the "raw-ptr-exclude-path=" prefix via the value's
            // leading ".." segments and mangles the token.
            "raw-ptr-exclude-path=",
            // Linker flags whose value is an exec-path to an input file (version
            // scripts, dynamic lists). The path follows -Wl,<flag>= and must be
            // relativized like an include dir so the linker, running from the
            // output-tree root, can find it. The path follows either "=" or ",".
            // Name-valued -Wl flags (e.g. -Wl,-soname=libfoo.so) are not in this set
            // and pass through.
            "-Wl,--version-script=",
            "-Wl,--version-script,",
            "-Wl,--dynamic-list=",
            "-Wl,--dynamic-list,",
            "-Wl,--retain-symbols-file=",
            "-Wl,--retain-symbols-file,")) {
      if (arg.startsWith(prefix) && arg.length() > prefix.length()) {
        return prefix + relativizeExecPathString(arg.substring(prefix.length()), true);
      }
    }
    if (arg.charAt(0) == '-') {
      return arg;
    }
    // A bare key=value token (e.g. a preprocessor define FOO=a/b reaching the command line through
    // the preprocessor_defines variable, before -D is prepended) is not an exec path, even when its
    // value contains a slash. Exec paths never contain '=', so leave such tokens unchanged.
    if (arg.indexOf('=') >= 0) {
      return arg;
    }
    // Values of toolchain path-list variables are pure exec paths, so relativize them even when
    // they
    // are a single path segment (e.g. a top-level include dir "v8"). For other variables — notably
    // user_compile_flags, which mixes flags, defines and paths — a single-segment value is left
    // alone to avoid mangling non-path tokens such as the "c++" in a separate "-x c++".
    boolean pathValued = variableName != null && PATH_LIST_VARIABLES.contains(variableName);
    return relativizeExecPathString(arg, pathValued);
  }

  private String relativizeExecPathString(String arg, boolean mapSingleSegment) {
    if (arg.equals(".")) {
      // The execroot (source tree root), as an include directory.
      return relativize(PathFragment.EMPTY_FRAGMENT, workingDirectory).getPathString();
    }
    if (arg.startsWith("../") || arg.startsWith("/")) {
      return arg;
    }
    String outputRoot = workingDirectory.getSegment(0) + "/";
    if (mapSingleSegment
        || arg.startsWith(outputRoot)
        || arg.startsWith("external/")
        || arg.indexOf('/') >= 0) {
      String mapped = relativize(PathFragment.create(arg), workingDirectory).getPathString();
      // The working directory itself, such as an include directory naming the output root,
      // relativizes to the empty PathFragment, which would leave a bare "-I".
      return mapped.isEmpty() ? "." : mapped;
    }
    return arg;
  }

  @Override
  public boolean isNoop() {
    return false;
  }

  @Override
  public Object cacheKey() {
    return new Key(delegate.cacheKey(), workingDirectory);
  }

  // mapRoot, mapCustomStarlarkArgs and getMapFn intentionally fall back to the PathMapper
  // defaults: those concern Starlark root objects, which must not receive relative-with-`..` paths.

  /**
   * Returns {@code path} relative to {@code base}, inserting {@code ..} segments as needed.
   * Absolute paths are returned unchanged, and {@code base} itself becomes the empty fragment.
   */
  public static PathFragment relativize(PathFragment path, PathFragment base) {
    if (path.isAbsolute() || base.isEmpty()) {
      return path;
    }
    if (path.equals(base)) {
      return PathFragment.EMPTY_FRAGMENT;
    }
    return FileSystemUtils.relativePath(base, path);
  }

  private record Key(Object delegateKey, PathFragment workingDirectory) {
    Key {
      Objects.requireNonNull(workingDirectory);
    }
  }
}
