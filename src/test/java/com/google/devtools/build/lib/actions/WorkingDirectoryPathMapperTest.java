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

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.vfs.PathFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link WorkingDirectoryPathMapper}, in particular the relativization geometry. */
@RunWith(JUnit4.class)
public final class WorkingDirectoryPathMapperTest {

  private static final PathFragment OUT_DEBUG = PathFragment.create("bazel-out/out_debug");

  private static String relativize(String path) {
    return WorkingDirectoryPathMapper.relativize(PathFragment.create(path), OUT_DEBUG)
        .getPathString();
  }

  private static String mapHeuristically(String variableName, String arg) {
    return new WorkingDirectoryPathMapper(PathMapper.NOOP, OUT_DEBUG)
        .mapHeuristically(variableName, arg);
  }

  private static String mapHeuristically(String arg) {
    // Combined-flag / copt-style tokens arrive via the heterogeneous user_compile_flags variable.
    return mapHeuristically("user_compile_flags", arg);
  }

  @Test
  public void source_rendersWithUplevelReferences() {
    // The output tree root is two segments deep, so a source two levels up matches GN's ../../.
    assertThat(relativize("foo/bar.cc")).isEqualTo("../../foo/bar.cc");
  }

  @Test
  public void sameTreeOutput_stripsWorkingDirectoryPrefix() {
    assertThat(relativize("bazel-out/out_debug/bin/foo/bar.o")).isEqualTo("bin/foo/bar.o");
  }

  @Test
  public void otherTreeOutput_rendersWithSingleUplevel() {
    assertThat(relativize("bazel-out/out_debug-exec/bin/tool"))
        .isEqualTo("../out_debug-exec/bin/tool");
  }

  @Test
  public void external_rendersWithUplevelReferences() {
    assertThat(relativize("external/repo/pkg/file.h")).isEqualTo("../../external/repo/pkg/file.h");
  }

  @Test
  public void absolutePath_unchanged() {
    assertThat(relativize("/usr/bin/clang")).isEqualTo("/usr/bin/clang");
  }

  @Test
  public void emptyBase_leavesPathUnchanged() {
    assertThat(
            WorkingDirectoryPathMapper.relativize(
                    PathFragment.create("foo/bar.cc"), PathFragment.EMPTY_FRAGMENT)
                .getPathString())
        .isEqualTo("foo/bar.cc");
  }

  @Test
  public void pathEqualToBase_rendersAsCurrentDirectory() {
    // Degenerate case (a real file artifact is never the output-tree root itself): the "." that
    // denotes the working directory normalizes to the empty PathFragment.
    assertThat(relativize("bazel-out/out_debug")).isEmpty();
  }

  @Test
  public void includeDirEqualToWorkingDirectory_rendersAsDot() {
    assertThat(mapHeuristically("-Ibazel-out/out_debug")).isEqualTo("-I.");
  }

  @Test
  public void strictPrefixOfBase_rendersUplevelOnly() {
    assertThat(relativize("bazel-out")).isEqualTo("..");
  }

  @Test
  public void relativizeOutputTree_leavesOtherTokens() {
    // A literal that equals a source's exec path stays as written; only output-tree tokens move.
    assertThat(
            WorkingDirectoryPathMapper.relativizeOutputTree(
                "cp ../../package.json package.json && ls bazel-out/out_debug/bin/pkg", OUT_DEBUG))
        .isEqualTo("cp ../../package.json package.json && ls bin/pkg");
  }

  @Test
  public void mapHeuristically_relativizesLinkerFileFlagsInBothSpellings() {
    assertThat(mapHeuristically("-Wl,--version-script=pkg/exports.map"))
        .isEqualTo("-Wl,--version-script=../../pkg/exports.map");
    assertThat(mapHeuristically("-Wl,--dynamic-list,pkg/symbols.sym"))
        .isEqualTo("-Wl,--dynamic-list,../../pkg/symbols.sym");
    assertThat(mapHeuristically("-Wl,-soname=libfoo.so")).isEqualTo("-Wl,-soname=libfoo.so");
  }

  @Test
  public void mapHeuristically_rewritesCombinedCppPathFlags() {
    assertThat(mapHeuristically("-I.")).isEqualTo("-I../..");
    assertThat(mapHeuristically("-Iv8")).isEqualTo("-I../../v8");
    assertThat(mapHeuristically("-isystemthird_party/libc++/src/include"))
        .isEqualTo("-isystem../../third_party/libc++/src/include");
    assertThat(mapHeuristically("--sysroot=build/linux/sysroot"))
        .isEqualTo("--sysroot=../../build/linux/sysroot");
    assertThat(mapHeuristically("--warning-suppression-mappings=build/config/warnings.txt"))
        .isEqualTo("--warning-suppression-mappings=../../build/config/warnings.txt");
    assertThat(mapHeuristically("-Ibazel-out/out_debug/bin/codegen/gen"))
        .isEqualTo("-Ibin/codegen/gen");
  }

  @Test
  public void mapHeuristically_relativizesSingleSegmentPathListValues() {
    // Toolchain path-list variables carry bare exec-path values (the -I/-iquote/-isystem flag is
    // added by the flag template), so even a single-segment include dir must be relativized.
    assertThat(mapHeuristically("system_include_paths", "v8")).isEqualTo("../../v8");
    assertThat(mapHeuristically("quote_include_paths", "v8")).isEqualTo("../../v8");
    assertThat(mapHeuristically("include_paths", "bazel-out/out_debug/bin/gen"))
        .isEqualTo("bin/gen");
    // A single-segment value from a heterogeneous flag variable is left alone: it may be a non-path
    // token such as the "c++" in a separate "-x c++".
    assertThat(mapHeuristically("user_compile_flags", "c++")).isEqualTo("c++");
  }

  @Test
  public void mapHeuristically_leavesNonPathFlagsUnchanged() {
    assertThat(mapHeuristically("-DNAME=value")).isEqualTo("-DNAME=value");
    assertThat(mapHeuristically("-fmodule-name=//foo:bar")).isEqualTo("-fmodule-name=//foo:bar");
    assertThat(mapHeuristically("--sysroot=/opt/sysroot")).isEqualTo("--sysroot=/opt/sysroot");
  }

  @Test
  public void mapHeuristically_leavesBareKeyValueTokensUnchanged() {
    // Preprocessor-define values reach the command line as bare key=value tokens (before -D is
    // prepended). Even when the value contains a slash they are not exec paths, so they must pass
    // through rather than be relativized into ../../-prefixed garbage.
    assertThat(mapHeuristically("GIT_REV=abc/def")).isEqualTo("GIT_REV=abc/def");
    assertThat(mapHeuristically("PREFIX=usr/local")).isEqualTo("PREFIX=usr/local");
  }
}
