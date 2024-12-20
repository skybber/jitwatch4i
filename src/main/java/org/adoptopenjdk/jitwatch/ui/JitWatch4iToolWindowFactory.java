package org.adoptopenjdk.jitwatch.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.ContentManager;
import org.adoptopenjdk.jitwatch.logger.LoggerFactory;
import org.adoptopenjdk.jitwatch.ui.main.JITWatchUI;
import org.adoptopenjdk.jitwatch.util.IntelliJLoggerBackend;
import org.jetbrains.annotations.NotNull;

final class JitWatch4iToolWindowFactory implements ToolWindowFactory, DumbAware {

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    ContentManager contentManager = toolWindow.getContentManager();
    LoggerFactory.setLoggerBackend(new IntelliJLoggerBackend());
    JITWatchUI jitWatchUI = new JITWatchUI(project, contentManager);
    jitWatchUI.start();
  }
}
