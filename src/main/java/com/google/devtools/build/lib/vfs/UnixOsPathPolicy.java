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
package com.google.devtools.build.lib.vfs;

import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;

@VisibleForTesting
class UnixOsPathPolicy implements OsPathPolicy {

  static final UnixOsPathPolicy INSTANCE = new UnixOsPathPolicy();

  @Override
  public int needsToNormalize(String path) {
    int n = path.length();
    int dotCount = 0;
    char prevChar = 0;
    for (int i = 0; i < n; i++) {
      char c = path.charAt(i);
      if (c == '/') {
        if (prevChar == '/') {
          return NEEDS_NORMALIZE;
        }
        if (dotCount == 1 || dotCount == 2) {
          return NEEDS_NORMALIZE;
        }
        dotCount = 0;
      } else if (c == '.' && dotCount >= 0) {
        dotCount++;
      } else {
        dotCount = -1;
      }
      prevChar = c;
    }
    if ((n > 1 && prevChar == '/') || dotCount == 1 || dotCount == 2) {
      return NEEDS_NORMALIZE;
    }
    return NORMALIZED;
  }

  @Override
  public int needsToNormalizeSuffix(String normalizedSuffix) {
    // We know that the string is normalized
    // In this case only suffixes starting with ".." may cause
    // normalization once concatenated with other strings
    return normalizedSuffix.startsWith("..") ? NEEDS_NORMALIZE : NORMALIZED;
  }

  @Override
  public String normalize(String path, int normalizationLevel) {
    if (normalizationLevel == NORMALIZED) {
      return path;
    }
    if (path.isEmpty()) {
      return path;
    }
    // Single-pass equivalent of splitting on "/+", running Utils.removeRelativePaths, and rejoining
    // (the segment-array form allocated a Splitter Matcher, the split list, a String[], and a
    // substring per segment). Builds the result directly, recording each kept segment's start
    // offset
    // in segStart so a ".." can pop the previous segment exactly as removeRelativePaths does.
    boolean isAbsolute = path.charAt(0) == '/';
    int n = path.length();
    StringBuilder sb = new StringBuilder(n);
    if (isAbsolute) {
      sb.append('/');
    }
    int[] segStart = new int[10];
    int depth = 0;
    int i = 0;
    while (i < n) {
      while (i < n && path.charAt(i) == '/') {
        i++; // collapse "/+" runs (and skip the leading slash)
      }
      if (i >= n) {
        break;
      }
      int start = i;
      while (i < n && path.charAt(i) != '/') {
        i++;
      }
      int len = i - start;
      if (len == 1 && path.charAt(start) == '.') {
        continue; // drop "."
      }
      if (len == 2 && path.charAt(start) == '.' && path.charAt(start + 1) == '.') {
        if (depth > 0 && !lastSegmentIsDotDot(sb, segStart[depth - 1])) {
          sb.setLength(segStart[--depth]); // pop the previous segment (and its trailing '/')
          continue;
        } else if (isAbsolute) {
          continue; // drop ".." at the root
        }
        // Otherwise (relative, no poppable segment) keep the "..".
      }
      if (depth == segStart.length) {
        segStart = Arrays.copyOf(segStart, depth * 2);
      }
      segStart[depth++] = sb.length();
      sb.append(path, start, i).append('/');
    }
    if (depth > 0) {
      sb.setLength(sb.length() - 1); // strip the trailing '/'
    }
    return sb.toString();
  }

  /** Whether the kept segment starting at {@code s} (ending before its trailing '/') is "..". */
  private static boolean lastSegmentIsDotDot(StringBuilder sb, int s) {
    return sb.length() - 1 - s == 2 && sb.charAt(s) == '.' && sb.charAt(s + 1) == '.';
  }

  @Override
  public int getDriveStrLength(String path) {
    if (path.length() == 0) {
      return 0;
    }
    return (path.charAt(0) == '/') ? 1 : 0;
  }

  @Override
  public boolean isSeparator(char c) {
    return c == '/';
  }

  @Override
  public char additionalSeparator() {
    return 0;
  }

  @Override
  public String postProcessPathStringForExecution(String callablePathString) {
    return callablePathString;
  }
}
