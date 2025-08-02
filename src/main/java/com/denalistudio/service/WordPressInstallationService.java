package com.denalistudio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class WordPressInstallationService {
    private final String clickUpApiKey;
    private final String targetServerUrl;
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SftpService sftpService = new SftpService();

    public WordPressInstallationService() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        this.clickUpApiKey = dotenv.get("CLICKUP_API_KEY");
        this.targetServerUrl = dotenv.get("WP_SERVER_URL");
    }

    public String startWordPressInstallation(String taskId) throws Exception {
        System.out.println("=== WordPress Installation Started ===");
        System.out.println("Task ID: " + taskId);

        // Quick validation - fetch task data from ClickUp
        System.out.println("Fetching task data from ClickUp...");
        TaskData taskData = getTaskData(taskId);

        System.out.println("Task Name: " + taskData.getTaskName());
        System.out.println("Protocol: " + taskData.getType());
        System.out.println("Server: " + taskData.getServerUrl());
        System.out.println("Username: " + taskData.getUsername());

        // Basic validation
        if (!"SFTP".equals(taskData.getType())) {
            System.err.println("ERROR: Only SFTP protocol is supported, got: " + taskData.getType());
            throw new IllegalArgumentException("Only SFTP is allowed");
        }

        if (!isValidServerUrl(taskData.getServerUrl())) {
            System.err.println("ERROR: Invalid server URL: " + taskData.getServerUrl());
            throw new IllegalArgumentException("Invalid server URL");
        }

        System.out.println("Validation passed, starting async upload...");

        // Start async upload process
        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("=== Background Upload Process Started ===");
                System.out.println("Downloading and uploading WordPress for: " + taskData.getTaskName());

                String result = sftpService.uploadWordPress(taskData);

                // Check if the installation was skipped
                if (result.contains("already exists") || result.contains("skipping")) {
                    System.out.println("SKIPPED: " + result);
                    postSkipComment(taskId, result);
                    System.out.println("Skip comment posted to task: " + taskId);
                } else {
                    System.out.println("SUCCESS: " + result);
                    postSuccessComment(taskId, result);
                    System.out.println("Success comment posted to task: " + taskId);
                }

            } catch (Exception e) {
                System.err.println("FAILED: WordPress installation failed for task " + taskId);
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace();

                postErrorComment(taskId, e.getMessage());
                System.out.println("Error comment posted to task: " + taskId);
            }
        });

        String responseMessage = "WordPress installation process started for task: " + taskData.getTaskName();
        System.out.println("Response sent: " + responseMessage);
        System.out.println("=== API Response Completed ===\n");

        return responseMessage;
    }

    private void postSuccessComment(String taskId, String result) {
        try {
            String commentText = "WordPress upload was completed successfully!\n\n" + result;
            postComment(taskId, commentText);
            System.out.println("Success comment posted successfully");
        } catch (IOException e) {
            System.err.println("Failed to post success comment: " + e.getMessage());
        }
    }

    private void postErrorComment(String taskId, String error) {
        try {
            String commentText = "WordPress upload failed!\n\nError: " + error;
            postComment(taskId, commentText);
            System.out.println("Error comment posted successfully");
        } catch (IOException e) {
            System.err.println("Failed to post error comment: " + e.getMessage());
        }
    }

    private void postSkipComment(String taskId, String result) {
        try {
            String commentText = "WordPress upload was not performed!\n\n" +
                    "Reason: A directory already exists in /www/domains\n\n" +
                    "Installation is not allowed when any directory already exists.";
            postComment(taskId, commentText);
            System.out.println("Skip comment posted successfully");
        } catch (IOException e) {
            System.err.println("Failed to post skip comment: " + e.getMessage());
        }
    }

    private void postComment(String taskId, String commentText) throws IOException {
        String json = objectMapper.writeValueAsString(Map.of("comment_text", commentText));

        RequestBody body = RequestBody.create(
                MediaType.get("application/json; charset=utf-8"),
                json
        );

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId + "/comment")
                .addHeader("Authorization", clickUpApiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to post comment: " + response.code());
            }
        }
    }

    private TaskData getTaskData(String taskId) throws IOException {
        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId)
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("ClickUp API error: " + response.code());
            }

            JsonNode taskJson = objectMapper.readTree(response.body().string());

            // Extract custom fields from the task JSON
            Map<String, String> customFields = new HashMap<>();
            JsonNode customFieldsArray = taskJson.get("custom_fields");

            if (customFieldsArray != null && customFieldsArray.isArray()) {
                for (JsonNode field : customFieldsArray) {
                    String name = field.get("name").asText();

                    if ("Protocol".equals(name)) {
                        // Protocol is stored as dropdown field
                        int valueIndex = field.get("value").asInt();
                        JsonNode options = field.get("type_config").get("options");

                        if (options != null && options.isArray()) {
                            for (JsonNode option : options) {
                                if (option.get("orderindex").asInt() == valueIndex) {
                                    customFields.put("Protocol", option.get("name").asText());
                                    break;
                                }
                            }
                        }
                    } else {
                        JsonNode valueNode = field.get("value");
                        String value = valueNode != null ? valueNode.asText() : "";
                        customFields.put(name, value);
                    }
                }
            }

            String taskName = taskJson.get("name").asText();

            return new TaskData(
                    taskName,
                    customFields.get("Protocol"),
                    customFields.get("Server"),
                    customFields.get("Username"),
                    customFields.get("Password")
            );
        }
    }

    private boolean isValidServerUrl(String serverUrl) {
        return serverUrl != null && !serverUrl.isEmpty() &&
                (serverUrl.contains(targetServerUrl) ||
                        serverUrl.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"));
    }

    public static class TaskData {
        private String taskName;
        private String type;
        private String serverUrl;
        private String username;
        private String password;

        public TaskData(String taskName, String type, String serverUrl, String username, String password) {
            this.taskName = taskName;
            this.type = type;
            this.serverUrl = serverUrl;
            this.username = username;
            this.password = password;
        }

        public String getTaskName() { return taskName; }
        public String getType() { return type; }
        public String getServerUrl() { return serverUrl; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
    }
}