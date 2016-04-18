package com.google.devtools.build.lib.remote;

import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.analysis.config.BinTools;
import com.google.devtools.build.lib.rules.test.TestActionContext;
import com.google.devtools.build.lib.rules.test.TestResult;
import com.google.devtools.build.lib.rules.test.TestRunnerAction;
import com.google.devtools.build.lib.rules.test.TestStrategy;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import com.google.devtools.common.options.OptionsClassProvider;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.actions.ResourceManager.ResourceHandle;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.BaseSpawn;

import java.util.Map;

@ExecutionStrategy(contextType = TestActionContext.class, name = { "remote" })
public class RemoteTestStrategy extends TestStrategy {
  public RemoteTestStrategy(
      OptionsClassProvider requestOptions,
      BinTools binTools,
      Map<String, String> clientEnv) {
    super(requestOptions, binTools, clientEnv);
  }

  @Override
  public void exec(TestRunnerAction action, ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    System.out.println("Executing remote test action" + action);
    /*
      ResourceSet resources =
          action.getTestProperties().getLocalResourceUsage(executionOptions.usingLocalTestJobs());

    Artifact testSetup = action.getRuntimeArtifact(TEST_SETUP_BASENAME);
    Spawn spawn =
        new BaseSpawn(
            // Bazel lacks much of the tooling for coverage, so we don't attempt to pass a coverage
            // script here.
            getArgs(testSetup.getExecPathString(), "", action),
            env,
            info,
            new RunfilesSupplierImpl(
                runfilesDir.asFragment(), action.getExecutionSettings().getRunfiles()),
            action,
            action.getTestProperties().getLocalResourceUsage(executionOptions.usingLocalTestJobs()),
            ImmutableSet.of(resolvedPaths.getXmlOutputPath().relativeTo(execRoot)));

      try (FileOutErr fileOutErr =
              new FileOutErr(
                  action.getTestLog().getPath(),
                  action
                      .resolve(actionExecutionContext.getExecutor().getExecRoot())
                      .getTestStderr());
          ResourceHandle handle = ResourceManager.instance().acquireResources(action, resources)) {
        TestResultData data =
            execute(actionExecutionContext.withFileOutErr(fileOutErr), spawn, action);
        appendStderr(fileOutErr.getOutputFile(), fileOutErr.getErrorFile());
        finalizeTest(actionExecutionContext, action, data);
      }
    */
  }

  @Override
  public TestResult newCachedTestResult(
      Path execRoot, TestRunnerAction action, TestResultData data) {
    System.out.println("Returning cached result" + action);
    return new TestResult(action, data, /*cached*/ true);
  }
}
