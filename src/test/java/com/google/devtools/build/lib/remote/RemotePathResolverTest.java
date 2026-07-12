// Copyright 2017 The Bazel Authors. All rights reserved.
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

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.actions.PathMapper;
import com.google.devtools.build.lib.remote.common.RemotePathResolver;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemotePathResolver} */
@RunWith(JUnit4.class)
public class RemotePathResolverTest {

  private Path execRoot;

  @Before
  public void setup() throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    execRoot = fs.getPath("/execroot/main");
  }

  @Test
  public void convertPaths_relativeToExecRoot() {
    RemotePathResolver remotePathResolver = RemotePathResolver.createDefault(execRoot);

    String outputPath = remotePathResolver.localPathToOutputPath(PathFragment.create("bar"));
    Path localPath = remotePathResolver.outputPathToLocalPath(outputPath);

    assertThat(outputPath).isEqualTo("bar");
    assertThat(localPath).isEqualTo(execRoot.getRelative("bar"));
  }

  @Test
  public void gnWorkingDirectory_decouplesInputRootFromCommandWorkingDirectory() {
    // The command runs in the output tree, but inputs stay at true exec paths in the input root.
    PathFragment workingDirectory = PathFragment.create("bazel-out/out_debug");
    RemotePathResolver remotePathResolver =
        RemotePathResolver.createMapped(
            RemotePathResolver.createDefault(execRoot),
            execRoot,
            PathMapper.NOOP,
            workingDirectory);

    assertThat(remotePathResolver.getCommandWorkingDirectory()).isEqualTo(workingDirectory);

    // An output under the working directory is rendered relative to it, and reversed back.
    String outputPath =
        remotePathResolver.localPathToOutputPath(
            PathFragment.create("bazel-out/out_debug/bin/foo/bar.o"));
    assertThat(outputPath).isEqualTo("bin/foo/bar.o");
    assertThat(remotePathResolver.outputPathToLocalPath(outputPath))
        .isEqualTo(execRoot.getRelative("bazel-out/out_debug/bin/foo/bar.o"));
  }

  @Test
  public void gnWorkingDirectory_resolvesSiblingOutputRoot() {
    PathFragment workingDirectory = PathFragment.create("bazel-out/out_debug");
    RemotePathResolver remotePathResolver =
        RemotePathResolver.createMapped(
            RemotePathResolver.createDefault(execRoot),
            execRoot,
            PathMapper.NOOP,
            workingDirectory);

    String outputPath =
        remotePathResolver.localPathToOutputPath(
            PathFragment.create("bazel-out/out_debug-exec/bin/foo/bar.o"));
    assertThat(outputPath).isEqualTo("../out_debug-exec/bin/foo/bar.o");
    assertThat(remotePathResolver.outputPathToLocalPath(outputPath))
        .isEqualTo(execRoot.getRelative("bazel-out/out_debug-exec/bin/foo/bar.o"));
  }
}
