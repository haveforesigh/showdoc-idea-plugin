package com.showdoc.plugin.openapi;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(
        name = "com.showdoc.plugin.openapi.OpenApiSettings",
        storages = {@Storage("ShowDocOpenApiSettings.xml")}
)
public class OpenApiSettings implements PersistentStateComponent<OpenApiSettings> {

    public String openApiUrl = "http://192.168.0.85:4999/server/index.php?s=/api/item/updateByApi";
    public String openApiKey = "91e056bd93cff1617c457d4ebf8b197c19622230";
    public String openApiToken = "1157d3bb70e0a70f5b73053279ec32cb370388163";

    public static OpenApiSettings getInstance() {
        return ApplicationManager.getApplication().getService(OpenApiSettings.class);
    }

    @Nullable
    @Override
    public OpenApiSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull OpenApiSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }

    public boolean isConfigured() {
        return openApiUrl != null && !openApiUrl.isEmpty()
                && openApiKey != null && !openApiKey.isEmpty()
                && openApiToken != null && !openApiToken.isEmpty();
    }
}
