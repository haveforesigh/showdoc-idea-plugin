package com.showdoc.plugin.openapi;

import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 独立的 HTTP 客户端，用于调用 ShowDoc Open API (/api/item/updateByApi)。
 * 不复用现有 ShowDocHttpClient。
 */
public class OpenApiHttpClient {

    private static final Logger LOG = Logger.getInstance(OpenApiHttpClient.class);
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    /**
     * 调用 ShowDoc Open API 创建或更新文档页面。
     *
     * @param apiUrl    ShowDoc Open API 地址 (/api/item/updateByApi)
     * @param apiKey    项目 API Key
     * @param apiToken  项目 API Token
     * @param pageTitle 页面标题
     * @param pageContent 页面内容（Markdown）
     * @param catName   目录名称，支持 "/" 分隔多级目录
     * @return "SUCCESS" 或 错误信息
     */
    public static String createOrUpdatePage(String apiUrl, String apiKey, String apiToken,
                                             String pageTitle, String pageContent, String catName) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);

            StringBuilder params = new StringBuilder();
            params.append("api_key=").append(URLEncoder.encode(apiKey, "UTF-8"));
            params.append("&api_token=").append(URLEncoder.encode(apiToken, "UTF-8"));
            params.append("&page_title=").append(URLEncoder.encode(pageTitle, "UTF-8"));
            params.append("&page_content=").append(URLEncoder.encode(pageContent, "UTF-8"));
            if (catName != null && !catName.isEmpty()) {
                params.append("&cat_name=").append(URLEncoder.encode(catName, "UTF-8"));
            }

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = params.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int httpCode = conn.getResponseCode();
            LOG.info("ShowDoc Open API responded with HTTP code: " + httpCode);

            if (httpCode != 200) {
                return "HTTP " + httpCode;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line.trim());
                }
            }

            String jsonStr = response.toString().trim();
            LOG.info("ShowDoc Open API Response: " + jsonStr);

            try {
                JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                if (json.has("error_code") && json.get("error_code").getAsInt() == 0) {
                    return "SUCCESS";
                } else if (json.has("error_message")) {
                    return json.get("error_message").getAsString();
                }
            } catch (Exception ex) {
                LOG.warn("Failed to parse JSON response: " + jsonStr, ex);
                return "Invalid JSON: " + jsonStr;
            }

            return jsonStr;
        } catch (Exception e) {
            LOG.error("Failed to call ShowDoc Open API", e);
            return "Exception: " + e.getMessage();
        }
    }
}
