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
package com.google.devtools.build.lib.runtime;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.server.IdleTask;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Audits whether command-scoped objects are still reachable after the command finishes, to catch
 * memory leaks where per-command state is pinned for the server's lifetime.
 *
 * <p>Enabled by {@code --audit_command_leaks}. While enabled, a curated set of sentinel objects
 * that <em>must</em> be unreachable once the command is over (the command's {@link
 * com.google.common.eventbus.EventBus}, {@link CommandEnvironment} and {@link
 * com.google.devtools.build.lib.events.Reporter}) are registered here via weak references. The
 * {@link AuditIdleTask} runs while the server is idle, forces a GC, and reports any sentinel that
 * is still reachable -- optionally dumping the heap so the retaining path can be found offline
 * (e.g. with Eclipse MAT's "merge shortest paths to GC roots").
 *
 * <p>The audit deliberately tracks only a small allowlist of obviously command-scoped objects
 * rather than the whole heap: most of the retained heap (the Skyframe graph) is kept intentionally
 * between builds, so auditing everything would be all false positives. The EventBus is the most
 * useful sentinel because almost everything command-scoped is reachable from it.
 */
public final class CommandLeakAuditor {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private record Sentinel(WeakReference<Object> ref, String label, String commandId) {}

  private static final List<Sentinel> sentinels = Collections.synchronizedList(new ArrayList<>());
  private static volatile boolean enabled = false;

  private CommandLeakAuditor() {}

  public static void setEnabled(boolean enabled) {
    CommandLeakAuditor.enabled = enabled;
  }

  public static boolean isEnabled() {
    return enabled;
  }

  /** Registers an object that must be unreachable once its command is over. No-op when disabled. */
  public static void track(@Nullable Object o, String label, String commandId) {
    if (enabled && o != null) {
      sentinels.add(new Sentinel(new WeakReference<>(o), label, commandId));
    }
  }

  /**
   * Forces a garbage collection and returns a description of every tracked sentinel that is still
   * reachable, then clears the registry.
   */
  public static ImmutableList<String> auditAndClear() {
    // Two rounds of GC so that weak references to genuinely-unreachable objects are cleared even if
    // the first collection only promotes them. WeakReferences clear during GC, before finalization,
    // so System.gc() alone is enough -- no need for the deprecated System.runFinalization().
    System.gc();
    System.gc();
    ImmutableList.Builder<String> leaked = ImmutableList.builder();
    synchronized (sentinels) {
      for (Sentinel s : sentinels) {
        Object o = s.ref.get();
        if (o != null) {
          leaked.add(
              String.format(
                  "%s [%s] from command %s", s.label, o.getClass().getName(), s.commandId));
        }
      }
      sentinels.clear();
    }
    return leaked.build();
  }

  /**
   * An {@link IdleTask} that audits the tracked sentinels for leaks and, if any are found and a
   * dump directory was given, writes a live heap dump for offline retainer analysis.
   */
  public static final class AuditIdleTask implements IdleTask {
    @Nullable private final String dumpDir;

    public AuditIdleTask(@Nullable String dumpDir) {
      this.dumpDir = dumpDir;
    }

    @Override
    public String displayName() {
      return "Command leak audit";
    }

    @Override
    public Duration delay() {
      // Run after GcAndInternerShrinkingIdleTask (10s when state is kept) so the heap has settled;
      // auditAndClear() forces its own GC, so correctness does not depend on this ordering.
      return Duration.ofSeconds(11);
    }

    @Override
    public void run() {
      ImmutableList<String> leaked = auditAndClear();
      long cleanStamp = System.currentTimeMillis();
      if (leaked.isEmpty()) {
        logger.atInfo().log("[Command leak audit] clean: no tracked command-scoped objects leaked");
        if (dumpDir != null) {
          try {
            Files.writeString(
                Path.of(dumpDir, "command-leak-audit-" + cleanStamp + ".txt"),
                "CLEAN: 0 tracked command-scoped objects still reachable at idle.\n");
          } catch (IOException e) {
            logger.atWarning().withCause(e).log("[Command leak audit] failed to write report file");
          }
        }
        return;
      }
      logger.atWarning().log(
          "[Command leak audit] %d command-scoped object(s) still reachable after the command:\n"
              + "  %s",
          leaked.size(), String.join("\n  ", leaked));

      // Idle tasks run after the per-command log handler is gone, so flogger output above may be
      // dropped. Write a self-contained report (and the heap dump) to disk so the result survives.
      if (dumpDir == null) {
        return;
      }
      long stamp = System.currentTimeMillis();
      String hprof = dumpDir + "/command-leak-" + stamp + ".hprof";
      StringBuilder report = new StringBuilder();
      report.append(leaked.size()).append(" command-scoped object(s) still reachable at idle:\n");
      for (String s : leaked) {
        report.append("  ").append(s).append('\n');
      }
      try {
        dumpHeap(hprof);
        report
            .append("\nLive heap dump: ")
            .append(hprof)
            .append("\nFind retainers offline, e.g.:\n")
            .append("  ParseHeapDump.sh ")
            .append(hprof)
            .append(" -data <tmp> <report.xml>   # merge_shortest_paths / inbounds OQL\n")
            .append("  on the command EventBus / CommandEnvironment instance.\n");
      } catch (IOException e) {
        report.append("\n(heap dump failed: ").append(e).append(")\n");
        logger.atWarning().withCause(e).log("[Command leak audit] failed to write heap dump");
      }
      try {
        Files.writeString(Path.of(dumpDir, "command-leak-audit-" + stamp + ".txt"), report);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("[Command leak audit] failed to write report file");
      }
    }
  }

  private static void dumpHeap(String path) throws IOException {
    HotSpotDiagnosticMXBean mxBean =
        ManagementFactory.newPlatformMXBeanProxy(
            ManagementFactory.getPlatformMBeanServer(),
            "com.sun.management:type=HotSpotDiagnostic",
            HotSpotDiagnosticMXBean.class);
    mxBean.dumpHeap(path, /* live= */ true);
  }
}
