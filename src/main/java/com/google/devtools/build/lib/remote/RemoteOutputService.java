// Copyright 2019 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.LostInputsActionExecutionException;
import com.google.devtools.build.lib.actions.LostInputsExecException;
import com.google.devtools.build.lib.actions.OutputChecker;
import com.google.devtools.build.lib.actions.OutputMetadataStore;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionPhaseCompleteEvent;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.server.FailureDetails.Execution;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.BatchStat;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.OutputService;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/** Output service implementation for the remote build without local output service daemon. */
public class RemoteOutputService implements OutputService {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final BlazeDirectories directories;
  private final boolean rewindLostInputs;
  private final boolean reuseExistingOutputs;

  private RewoundActionSynchronizer rewoundActionSynchronizer = RewoundActionSynchronizer.NOOP;

  @Nullable private RemoteOutputChecker remoteOutputChecker;
  @Nullable private RemoteActionInputFetcher actionInputFetcher;
  @Nullable private LeaseService leaseService;
  // Build-scoped pool of outputs moved aside instead of deleted, for reuse across re-execution.
  // Created lazily when the first action filesystem is built; discarded in finalizeBuild.
  @Nullable private volatile OutputReuseStash reuseStash;

  RemoteOutputService(
      BlazeDirectories directories, boolean rewindLostInputs, boolean reuseExistingOutputs) {
    this.directories = checkNotNull(directories);
    this.rewindLostInputs = rewindLostInputs;
    this.reuseExistingOutputs = reuseExistingOutputs;
  }

  void setRemoteOutputChecker(RemoteOutputChecker remoteOutputChecker) {
    this.remoteOutputChecker = remoteOutputChecker;
  }

  void setActionInputFetcher(RemoteActionInputFetcher actionInputFetcher, WalkableGraph graph) {
    this.actionInputFetcher = checkNotNull(actionInputFetcher, "actionInputFetcher");
    if (rewindLostInputs) {
      this.rewoundActionSynchronizer =
          new RemoteRewoundActionSynchronizer(actionInputFetcher, graph);
    }
  }

  void setLeaseService(LeaseService leaseService) {
    this.leaseService = leaseService;
  }

  @Override
  public ActionFileSystemType actionFileSystemType() {
    return actionInputFetcher != null
        ? ActionFileSystemType.REMOTE_FILE_SYSTEM
        : ActionFileSystemType.DISABLED;
  }

  @Nullable
  @Override
  public FileSystem createActionFileSystem(
      FileSystem delegateFileSystem,
      PathFragment execRootFragment,
      String relativeOutputPath,
      ImmutableList<Root> sourceRoots,
      InputMetadataProvider inputArtifactData,
      Iterable<Artifact> outputArtifacts,
      boolean rewindingEnabled) {
    checkNotNull(actionInputFetcher, "actionInputFetcher");
    return new RemoteActionFileSystem(
        delegateFileSystem,
        execRootFragment,
        relativeOutputPath,
        inputArtifactData,
        actionInputFetcher,
        reuseExistingOutputs ? getOrCreateReuseStash(delegateFileSystem) : null);
  }

  private OutputReuseStash getOrCreateReuseStash(FileSystem fs) {
    OutputReuseStash stash = reuseStash;
    if (stash == null) {
      synchronized (this) {
        stash = reuseStash;
        if (stash == null) {
          Path stashDir =
              fs.getPath(directories.getOutputBase().asFragment()).getChild("remote_reuse_stash");
          stash = new OutputReuseStash(stashDir);
          reuseStash = stash;
        }
      }
    }
    return stash;
  }

  @Override
  public void updateActionFileSystemContext(
      ActionExecutionMetadata action,
      FileSystem actionFileSystem,
      InputMetadataProvider inputMetadataProvider,
      OutputMetadataStore outputMetadataStore,
      ImmutableMap<String, FileArtifactValue> staleOutputMetadata) {
    ((RemoteActionFileSystem) actionFileSystem).updateContext(action, staleOutputMetadata);
  }

  @Override
  public String getFileSystemName(String outputBaseFileSystemName) {
    return "remoteActionFS";
  }

  @Override
  public ModifiedFileSet startBuild(
      UUID buildId, String workspaceName, EventHandler eventHandler, boolean finalizeActions)
      throws AbruptExitException {
    // One of the responsibilities of OutputService.startBuild() is that it ensures the output path
    // is valid. If the previous OutputService redirected the output path to a remote location, we
    // must undo this.
    Path outputPath = directories.getOutputPath(workspaceName);
    try {
      if (outputPath.isSymbolicLink()) {
        outputPath.delete();
      }
    } catch (IOException e) {
      throw new AbruptExitException(
          DetailedExitCode.of(
              FailureDetail.newBuilder()
                  .setMessage(
                      String.format("Couldn't remove output path symlink: %s", e.getMessage()))
                  .setExecution(
                      Execution.newBuilder().setCode(Code.LOCAL_OUTPUT_DIRECTORY_SYMLINK_FAILURE))
                  .build()),
          e);
    }
    return ModifiedFileSet.EVERYTHING_MODIFIED;
  }

  @Override
  public void flushOutputTree() throws InterruptedException {
    if (actionInputFetcher != null) {
      actionInputFetcher.flushOutputTree();
    }
  }

  @Override
  public void finalizeBuild(boolean buildSuccessful) {
    OutputReuseStash stash = reuseStash;
    reuseStash = null;
    if (stash != null) {
      try {
        stash.discardAll();
      } catch (IOException e) {
        // Leftover stash entries are harmless temp data under the output base; failing to clean
        // them up must not fail the build.
        logger.atWarning().withCause(e).log("Failed to clean up the output reuse stash");
      }
    }
  }

  @Subscribe
  public void onExecutionPhaseCompleteEvent(ExecutionPhaseCompleteEvent event) {
    if (leaseService != null) {
      leaseService.finalizeExecution();
    }
  }

  @Override
  public void finalizeAction(Action action, OutputMetadataStore outputMetadataStore)
      throws IOException, LostInputsExecException, InterruptedException {
    if (actionInputFetcher != null) {
      actionInputFetcher.finalizeAction(action, outputMetadataStore);
    }

    if (leaseService != null) {
      leaseService.finalizeAction();
    }
  }

  @Override
  public boolean shouldStoreRemoteOutputMetadataInActionCache() {
    return true;
  }

  @Override
  public OutputChecker getOutputChecker() {
    return checkNotNull(remoteOutputChecker, "remoteOutputChecker must not be null");
  }

  @Nullable
  @Override
  public BatchStat getBatchStatter() {
    return null;
  }

  @Override
  public boolean canCreateSymlinkTree() {
    /* TODO(buchgr): Optimize symlink creation for remote execution */
    return false;
  }

  @Override
  public void createSymlinkTree(
      Map<PathFragment, PathFragment> symlinks, PathFragment symlinkTreeRoot) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clean() {
    // Intentionally left empty.
  }

  @Override
  public void checkActionFileSystemForLostInputs(FileSystem actionFileSystem, Action action)
      throws LostInputsActionExecutionException {
    if (actionFileSystem instanceof RemoteActionFileSystem remoteFileSystem) {
      remoteFileSystem.checkForLostInputs(action);
    }
  }

  @Override
  public RewoundActionSynchronizer getRewoundActionSynchronizer() {
    return rewoundActionSynchronizer;
  }
}
