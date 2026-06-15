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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link OutputReuseStash}. */
@RunWith(JUnit4.class)
public final class OutputReuseStashTest {

  private FileSystem fs;
  private Path stashDir;
  private OutputReuseStash stash;

  @Before
  public void setUp() {
    fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    stashDir = fs.getPath("/stash");
    stash = new OutputReuseStash(stashDir);
  }

  private Path writeOutput(String name, String contents) throws Exception {
    Path path = fs.getPath("/out/" + name);
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(path, contents);
    return path;
  }

  private static String read(Path path) throws Exception {
    return new String(FileSystemUtils.readContent(path), ISO_8859_1);
  }

  @Test
  public void reclaim_matchingDigest_movesStashedBytesIntoPlace() throws Exception {
    Path output = writeOutput("a", "hello");
    byte[] digest = output.getDigest();

    stash.stash(output, digest);
    // The output was moved aside, so it no longer exists at its original location.
    assertThat(output.exists()).isFalse();

    Path dest = fs.getPath("/out/a");
    assertThat(stash.reclaim(dest, digest)).isTrue();
    assertThat(read(dest)).isEqualTo("hello");
  }

  @Test
  public void reclaim_unknownDigest_returnsFalse() throws Exception {
    Path output = writeOutput("a", "hello");
    stash.stash(output, output.getDigest());

    byte[] otherDigest = writeOutput("other", "different").getDigest();
    assertThat(stash.reclaim(fs.getPath("/out/a"), otherDigest)).isFalse();
  }

  @Test
  public void reclaim_isOneShot() throws Exception {
    Path output = writeOutput("a", "hello");
    byte[] digest = output.getDigest();
    stash.stash(output, digest);

    assertThat(stash.reclaim(fs.getPath("/out/reclaimed1"), digest)).isTrue();
    assertThat(stash.reclaim(fs.getPath("/out/reclaimed2"), digest)).isFalse();
  }

  @Test
  public void stash_sameDigestTwice_keepsReclaimableCopy() throws Exception {
    Path a = writeOutput("a", "dup");
    Path b = writeOutput("b", "dup");
    byte[] digest = a.getDigest();

    stash.stash(a, digest);
    stash.stash(b, digest);
    // Both source files were consumed by the stash.
    assertThat(a.exists()).isFalse();
    assertThat(b.exists()).isFalse();

    Path dest = fs.getPath("/out/c");
    assertThat(stash.reclaim(dest, digest)).isTrue();
    assertThat(read(dest)).isEqualTo("dup");
  }

  @Test
  public void discardAll_removesUnreclaimedEntries() throws Exception {
    Path output = writeOutput("a", "hello");
    byte[] digest = output.getDigest();
    stash.stash(output, digest);
    assertThat(stashDir.exists()).isTrue();

    stash.discardAll();

    assertThat(stashDir.exists()).isFalse();
    // After discarding, nothing can be reclaimed.
    assertThat(stash.reclaim(fs.getPath("/out/a"), digest)).isFalse();
  }
}
