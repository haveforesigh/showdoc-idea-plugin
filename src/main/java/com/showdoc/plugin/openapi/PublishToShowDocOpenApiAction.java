package com.showdoc.plugin.openapi;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立的右键菜单 Action，用于通过 ShowDoc Open API 批量发布 Java Spring API。
 */
public class PublishToShowDocOpenApiAction extends AnAction {

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        e.getPresentation().setEnabledAndVisible(project != null && vFile != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        VirtualFile vFile = e.getData(CommonDataKeys.VIRTUAL_FILE);

        if (project == null || vFile == null) {
            return;
        }

        OpenApiSettings settings = OpenApiSettings.getInstance();
        if (!settings.isConfigured()) {
            Messages.showErrorDialog(
                    "Please configure ShowDoc Open API URL, API Key, and API Token in settings under Tools -> ShowDoc Open API.",
                    "ShowDoc Open API Configuration Missing"
            );
            return;
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Publishing to ShowDoc (Open API)", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                List<VirtualFile> targetFiles = new ArrayList<>();
                collectJavaFiles(vFile, targetFiles);

                if (targetFiles.isEmpty()) {
                    showDialogOnEdt("No Java files found in the selected path.", "ShowDoc Open API Publish", true);
                    return;
                }

                int pageSuccessCount = 0;
                int pageFailCount = 0;
                int skipFileCount = 0;
                StringBuilder errorMsg = new StringBuilder();

                for (int i = 0; i < targetFiles.size(); i++) {
                    VirtualFile file = targetFiles.get(i);
                    indicator.checkCanceled();
                    indicator.setFraction((double) i / targetFiles.size());
                    indicator.setText("Parsing: " + file.getName());

                    List<OpenApiDocParser.DocPageInfo> pages = ReadAction.compute(() -> {
                        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
                        if (psiFile instanceof PsiJavaFile) {
                            return OpenApiDocParser.parse((PsiJavaFile) psiFile);
                        }
                        return null;
                    });

                    if (pages == null || pages.isEmpty()) {
                        skipFileCount++;
                        continue;
                    }

                    for (int j = 0; j < pages.size(); j++) {
                        OpenApiDocParser.DocPageInfo page = pages.get(j);
                        indicator.checkCanceled();
                        indicator.setText(String.format("Publishing: %s -> %s", file.getName(), page.title));

                        String response = OpenApiHttpClient.createOrUpdatePage(
                                settings.openApiUrl,
                                settings.openApiKey,
                                settings.openApiToken,
                                page.title,
                                page.content,
                                page.catalog
                        );

                        if ("SUCCESS".equals(response)) {
                            pageSuccessCount++;
                        } else {
                            pageFailCount++;
                            if (errorMsg.length() < 500) {
                                errorMsg.append(file.getName()).append(" (")
                                        .append(page.title).append("): ")
                                        .append(response).append("\n");
                            }
                        }
                    }
                }

                String finalMessage = String.format(
                        "Publish completed.\n" +
                                "Successfully Published Pages: %d\n" +
                                "Failed Pages: %d\n" +
                                "Skipped Files (No Controllers): %d",
                        pageSuccessCount, pageFailCount, skipFileCount
                );

                if (pageFailCount > 0) {
                    finalMessage += "\n\nErrors:\n" + errorMsg.toString();
                    showDialogOnEdt(finalMessage, "ShowDoc Open API Publish Finished with Errors", false);
                } else if (pageSuccessCount > 0) {
                    showDialogOnEdt(finalMessage, "ShowDoc Open API Publish Success", true);
                } else {
                    showDialogOnEdt("No documents/controllers were found to export.", "ShowDoc Open API Publish", true);
                }
            }
        });
    }

    private void collectJavaFiles(VirtualFile file, List<VirtualFile> targetFiles) {
        if (file.isDirectory()) {
            for (VirtualFile child : file.getChildren()) {
                collectJavaFiles(child, targetFiles);
            }
        } else {
            if (file.getName().endsWith(".java")) {
                targetFiles.add(file);
            }
        }
    }

    private void showDialogOnEdt(String message, String title, boolean isInfo) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isInfo) {
                Messages.showInfoMessage(message, title);
            } else {
                Messages.showErrorDialog(message, title);
            }
        });
    }
}
