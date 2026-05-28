package com.showdoc.plugin.http;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

public class TestShowDoc {
    public static void main(String[] args) {
         String url = "http://172.23.5.136:4999/server/index.php?s=/api/open/fromComments";
         String apiKey = "7927cf0fc607afbc69540df0e1ede89a261369091";
         String apiToken = "f3a369bef040cd62b0da8c03278d993c991133567";
        
        String dummyContent = "/**\n" +
                " * showdoc\n" +
                " * @catalog 测试文档/API测试\n" +
                " * @title Java直传测试\n" +
                " * @description 这是从Java直接发送的测试\n" +
                " * @method post\n" +
                " * @url /api/test/java\n" +
                " * @param test 必选 string 测试参数\n" +
                " * @return {aaa}" +
                "*/";

        try {
            System.out.println("====== 测试 1: 使用 URLEncoder (当前插件方式) ======");
            sendAndPrint(url, apiKey, apiToken, dummyContent, true);
            
            System.out.println("\n====== 测试 2: 不使用 URLEncoder，原生字符串 (Bash Shell 方式) ======");
            sendAndPrint(url, apiKey, apiToken, dummyContent, false);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendAndPrint(String showdocUrl, String apiKey, String apiToken, String content, boolean useUrlEncode) throws Exception {
        URL url = new URL(showdocUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setDoOutput(true);

        String modifiedContent = content.replace("&", "_this_and_change_");
        String finalContent = useUrlEncode ? URLEncoder.encode(modifiedContent, "UTF-8") : modifiedContent;
        
        String params = "from=shell&api_key=" + apiKey +
                "&api_token=" + apiToken +
                "&content=" + finalContent;

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = params.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int code = conn.getResponseCode();
        System.out.println("HTTP 状态码: " + code);
        
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), "utf-8"))) {
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            System.out.println("服务器返回正文: " + response.toString());
        }
    }
}
