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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link IncludeScanningHeaderData.Builder}. */
@RunWith(JUnit4.class)
public final class IncludeScanningHeaderDataTest {
  private final Path execRoot = new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/exec");
  private final ArtifactRoot outRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out");

  /**
   * Declared headers added for one action resolve for that action without changing the header map,
   * which other actions may share and which may be unmodifiable.
   */
  @Test
  public void addDeclaredHeaders_keepsSharedHeaderMapUnchanged() {
    Artifact own = ActionsTestUtil.createArtifact(outRoot, "out/own.h");
    Artifact toolchain = ActionsTestUtil.createArtifact(outRoot, "out/toolchain.h");
    Map<PathFragment, Artifact> shared = ImmutableMap.of(own.getExecPath(), own);

    IncludeScanningHeaderData data =
        new IncludeScanningHeaderData.Builder(shared, ImmutableSet.of())
            .addDeclaredHeaders(NestedSetBuilder.create(Order.STABLE_ORDER, toolchain))
            .build();

    assertThat(data.getHeaderArtifact(toolchain.getExecPath())).isEqualTo(toolchain);
    assertThat(data.isDeclaredHeader(toolchain.getExecPath())).isTrue();
    assertThat(data.getHeaderArtifact(own.getExecPath())).isEqualTo(own);
    assertThat(shared).containsExactly(own.getExecPath(), own);
  }
}
