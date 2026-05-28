package com.showdoc.plugin.openapi;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class OpenApiSettingsConfigurable implements Configurable {

    private JPanel mainPanel;
    private JTextField urlField;
    private JTextField apiKeyField;
    private JTextField apiTokenField;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "ShowDoc Open API";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        urlField = new JTextField();
        apiKeyField = new JTextField();
        apiTokenField = new JTextField();

        mainPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Open API URL: ", urlField)
                .addLabeledComponent("API Key: ", apiKeyField)
                .addLabeledComponent("API Token: ", apiTokenField)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        return mainPanel;
    }

    @Override
    public boolean isModified() {
        OpenApiSettings settings = OpenApiSettings.getInstance();
        boolean modified = !urlField.getText().equals(settings.openApiUrl);
        modified |= !apiKeyField.getText().equals(settings.openApiKey);
        modified |= !apiTokenField.getText().equals(settings.openApiToken);
        return modified;
    }

    @Override
    public void apply() throws ConfigurationException {
        OpenApiSettings settings = OpenApiSettings.getInstance();
        settings.openApiUrl = urlField.getText();
        settings.openApiKey = apiKeyField.getText();
        settings.openApiToken = apiTokenField.getText();
    }

    @Override
    public void reset() {
        OpenApiSettings settings = OpenApiSettings.getInstance();
        urlField.setText(settings.openApiUrl);
        apiKeyField.setText(settings.openApiKey);
        apiTokenField.setText(settings.openApiToken);
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        urlField = null;
        apiKeyField = null;
        apiTokenField = null;
    }
}
