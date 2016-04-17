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
  }

  @Override
  public TestResult newCachedTestResult(
      Path execRoot, TestRunnerAction action, TestResultData data) {
    System.out.println("Returning cached result" + action);
    return new TestResult(action, data, /*cached*/ true);
  }
}
