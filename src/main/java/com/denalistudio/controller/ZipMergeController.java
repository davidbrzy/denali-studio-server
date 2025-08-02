package com.denalistudio.controller;

import com.denalistudio.service.ZipFileMerger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.annotation.PreDestroy;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.*;

@RestController
@RequestMapping("/api")
public class ZipMergeController {
    private final String tempDirPath;
    private final String tempUrl;
    private final String backupsApiKey;
    private final String clickUpApiKey;
    private final OkHttpClient client = new OkHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ZipMergeController() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        // Load environment variables
        this.tempDirPath = dotenv.get("TEMP_DIR");
        this.tempUrl = dotenv.get("TEMP_URL");
        this.backupsApiKey = dotenv.get("BACKUPS_API_KEY");
        this.clickUpApiKey = dotenv.get("CLICKUP_API_KEY");
    }

    @PostMapping("/zip-merge")
    public String zipMerge(@RequestParam String apiKey, @RequestParam String taskId) {
        if (!backupsApiKey.equals(apiKey)) {
            return "Invalid API key";
        }

        // Create a unique directory name using UUID
        String uniqueId = UUID.randomUUID().toString();
        Path tempDir = Paths.get(tempDirPath + "/" + uniqueId);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            e.printStackTrace();
            return "Failed to create temporary directory";
        }

        System.out.println("Downloading attachments for task: " + taskId);
        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId)
                .addHeader("accept", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            String responseBody = response.body().string();
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode attachmentsNode = objectMapper.readTree(responseBody).get("attachments");

            if (attachmentsNode != null && attachmentsNode.isArray()) {
                List<Path> partFiles = new ArrayList<>();

                // Download each attachment
                for (JsonNode attachment : attachmentsNode) {
                    String fileUrl = attachment.get("url_w_host").asText();
                    Path partFile = downloadFile(fileUrl, tempDir);
                    if (partFile != null) {
                        partFiles.add(partFile);
                    }
                }

                // Merge the parts
                if (!partFiles.isEmpty()) {
                    Path firstPart = partFiles.get(0);
                    ZipFileMerger.mergeFile(firstPart);
                    Path mergedFile = tempDir.resolve(ZipFileMerger.getBaseName(firstPart.getFileName().toString()));
                    if (!Files.exists(mergedFile)) {
                        return "Error merging files";
                    }
                    String downloadUrl = tempUrl + "/" + uniqueId + "/" + mergedFile.getFileName();

                    Map<String, String> requestBodyMap = new HashMap<>();
                    requestBodyMap.put("comment_text", "Download link (valid for 1 hour): " + downloadUrl);

                    Gson gson = new Gson();
                    String json = gson.toJson(requestBodyMap);

                    RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

                    Request commentRequest = new Request.Builder()
                            .url("https://api.clickup.com/api/v2/task/" + taskId + "/comment")
                            .post(body)
                            .addHeader("accept", "application/json")
                            .addHeader("content-type", "application/json")
                            .addHeader("Authorization", clickUpApiKey)
                            .build();

                    try (Response commentResponse = client.newCall(commentRequest).execute()) {
                        if (!commentResponse.isSuccessful()) {
                            System.err.println("Failed to post comment: " + commentResponse);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Schedule cleanup
                    scheduler.schedule(() -> {
                        try {
                            Files.walk(tempDir)
                                    .sorted(Comparator.reverseOrder())
                                    .forEach(path -> {
                                        try {
                                            Files.delete(path);
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                    });
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }, 1, TimeUnit.HOURS);

                    // Clean up part files
                    for (Path partFile : partFiles) {
                        Files.deleteIfExists(partFile);
                    }

                    return "Zip merge completed. Download URL: " + downloadUrl;
                }
            }

            return "No attachments found";
        } catch (IOException e) {
            e.printStackTrace();
            return "Error processing attachments: " + e.getMessage();
        }
    }

    private Path downloadFile(String fileUrl, Path tempDir) throws IOException {
        Request request = new Request.Builder()
                .url(fileUrl)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to download file: " + response);
            }

            // Save the file to the temporary directory
            String fileName = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
            Path filePath = tempDir.resolve(fileName);
            Files.copy(response.body().byteStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            return filePath;
        }
    }

    @PreDestroy
    public void cleanup() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}