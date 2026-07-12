// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CompilationMode;
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.FeatureConfiguration;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.regex.Pattern;
import net.starlark.java.eval.EvalException;

/**
 * Helper class for functionality shared by cpp related rules.
 *
 * <p>This class can be used only after the loading phase.
 */
public class CppHelper {

  static final PathFragment OBJS = PathFragment.create("_objs");
  static final PathFragment GN_STYLE_OBJS = PathFragment.create("obj");

  private CppHelper() {
    // prevents construction
  }

  /** Returns C++ toolchain, using toolchain resolution */
  public static CcToolchainProvider getToolchain(RuleContext ruleContext)
      throws RuleErrorException {
    ToolchainInfo toolchainInfo =
        ruleContext.getToolchainInfo(Label.parseCanonicalUnchecked("//tools/cpp:toolchain_type"));
    if (toolchainInfo == null) {
      toolchainInfo =
          ruleContext.getToolchainInfo(
              Label.parseCanonicalUnchecked("@bazel_tools//tools/cpp:toolchain_type"));
    }
    if (toolchainInfo == null) {
      throw ruleContext.throwWithRuleError(
          "Unable to find a CC toolchain using toolchain resolution. Did you properly set"
              + " --platforms?");
    }
    try {
      return CcToolchainProvider.wrap((Info) toolchainInfo.getValue("cc"));
    } catch (EvalException e) {
      // There is not actually any reason for toolchainInfo.getValue to throw an exception.
      throw ruleContext.throwWithRuleError(
          "Unexpected eval exception from toolchainInfo.getValue('cc')");
    }
  }

  /** Returns the directory where object files are created. */
  private static PathFragment getObjDirectory(Label ruleLabel) {
    return AnalysisUtils.getUniqueDirectory(ruleLabel, OBJS);
  }

  // gn2bazel splits a GN target's sources into helper sub-libraries: by language
  // (<target>__c / __cpp / __asm, since Bazel copts are per-target) and, when link
  // order must be preserved across a mixed-language target, into ordered chunks
  // (<target>__part0, __part1, …). GN compiles them all in the one target, so map
  // the helpers' objects back to GN's obj dir by stripping the suffix; this also
  // keeps the split-DWARF .dwo path (baked into .debug_str) byte-identical to GN.
  // Toolchain-variant suffixes (e.g. rust's __hosttc) are deliberately NOT stripped:
  // GN namespaces those by toolchain directory, not target name, so merging them
  // here would collide with the default-toolchain build.
  private static final Pattern GN_MIXED_LANG_SPLIT_SUFFIX =
      Pattern.compile("__(c|cpp|asm|part[0-9]+)$");

  private static PathFragment getGnStyleObjDirectory(RuleContext ruleContext, Label ruleLabel) {
    // gn2bazel stamps the exact GN obj dir for targets GN places outside the default
    // obj/<pkg>/<name> layout — notably non-default toolchains, which GN namespaces
    // under <toolchain>/obj/... (the toolchain name is gn2bazel/GN knowledge the fork
    // does not have). Honor it verbatim when present.
    for (String tag : ruleContext.attributes().get("tags", Types.STRING_LIST)) {
      if (tag.startsWith("gn_obj_dir=")) {
        return PathFragment.create(tag.substring("gn_obj_dir=".length()));
      }
    }
    String name = GN_MIXED_LANG_SPLIT_SUFFIX.matcher(ruleLabel.getName()).replaceFirst("");
    return GN_STYLE_OBJS
        .getRelative(ruleLabel.getPackageIdentifier().getPackagePath())
        .getRelative(name);
  }

  // LINT.IfChange
  /** Returns whether binaries must be compiled with position independent code. */
  public static boolean usePicForBinaries(
      CppConfiguration cppConfiguration, FeatureConfiguration featureConfiguration) {
    return cppConfiguration.forcePic()
        || (CcToolchainProvider.usePicForDynamicLibraries(cppConfiguration, featureConfiguration)
            && (cppConfiguration.getCompilationMode() != CompilationMode.OPT
                || featureConfiguration.isEnabled(CppRuleClasses.PREFER_PIC_FOR_OPT_BINARIES)));
  }

  // LINT.ThenChange(//src/main/starlark/builtins_bzl/common/cc/cc_helper_internal.bzl)

  static Artifact getCompileOutputArtifact(
      RuleContext ruleContext, Label label, String outputName, BuildConfigurationValue config) {
    boolean useGnStyleObjects = !config.getActionWorkingDirectory().isEmpty();
    PathFragment objectDir =
        useGnStyleObjects ? getGnStyleObjDirectory(ruleContext, label) : getObjDirectory(label);
    if (useGnStyleObjects) {
      return ruleContext.getShareableArtifact(
          objectDir.getRelative(outputName), config.getOutputDirectory());
    }
    return ruleContext.getDerivedArtifact(
        objectDir.getRelative(outputName), config.getBinDirectory());
  }
}
