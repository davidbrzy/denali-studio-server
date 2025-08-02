package com.denalistudio.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import io.github.cdimascio.dotenv.Dotenv;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

public class BackupWatcher {
    OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build();

    private final Path backupDir;
    private final ExecutorService executorService;
    private final ScheduledExecutorService scheduler;
    private final String tempDir;
    private final String clickUpApiKey;
    private final String backupsList;
    private final String domainCustomField;

    public BackupWatcher(String backupDir) {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();
        this.backupDir = Paths.get(backupDir);
        this.executorService = Executors.newFixedThreadPool(4);
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.tempDir = dotenv.get("TEMP_DIR");
        this.clickUpApiKey = dotenv.get("CLICKUP_API_KEY");
        this.backupsList = dotenv.get("BACKUPS_LIST_ID");
        this.domainCustomField = dotenv.get("BACKUPS_DOMAIN_CUSTOM_FIELD_ID");
    }

    public void startWatching() {
        executorService.submit(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                registerAllSubdirectories(backupDir, watchService);
                System.out.println("Started watching directory: " + backupDir);

                while (true) {
                    WatchKey key;

                    try {
                        key = watchService.take();
                    } catch (InterruptedException ex) {
                        System.out.println("Watch service interrupted");
                        return;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path fileName = ev.context();
                        Path filePath = ((Path) key.watchable()).resolve(fileName);
                        System.out.println("Event kind: " + kind + ". File affected: " + filePath);
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            if (Files.isDirectory(filePath)) {
                                registerAllSubdirectories(filePath, watchService);
                            } else if (Files.isRegularFile(filePath) && (fileName.toString().endsWith(".zip") || fileName.toString().endsWith(".daf"))) {
                                executorService.submit(() -> handleNewBackup(filePath));
                            }
                        }
                    }

                    boolean valid = key.reset();
                    if (!valid) {
                        System.out.println("Watch key no longer valid");
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void registerAllSubdirectories(Path start, WatchService watchService) throws IOException {
        Files.walk(start)
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    try {
                        dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
    }

    private void handleNewBackup(Path filePath) {
        // Debug: Print the file path
        System.out.println("Handling file: " + filePath);

        // Check if the file exists
        if (!Files.exists(filePath)) {
            System.err.println("File does not exist: " + filePath);
            return;
        }

        // Check if the file is readable
        if (!Files.isReadable(filePath)) {
            System.err.println("File is not readable: " + filePath);
            return;
        }

        // Extract task ID and domain name from the directory name
        Path parentDir = filePath.getParent();
        if (parentDir == null) {
            System.out.println("Invalid file path: " + filePath);
            return;
        }

        String dirName = parentDir.getFileName().toString();
        String[] parts = dirName.split("_");
        if (parts.length < 3) {
            System.out.println("Invalid directory name format: " + dirName);
            return;
        }

        String taskId = parts[1];
        String domainName = dirName.substring(dirName.indexOf('_', dirName.indexOf('_') + 1) + 1).replace('_', '.');

        // Immediately create and link the ClickUp task
        String newTaskId = createAndLinkClickUpTask(taskId, domainName);

        // Wait for the file to stabilize (stop changing in size)
        long previousSize = -1;
        long currentSize = 0;
        int attempts = 0;
        int maxAttempts = 20; // Increased maximum number of attempts
        long delayBetweenAttempts = 30000; // Delay between attempts in milliseconds (30 seconds)

        while (attempts < maxAttempts) {
            try {
                currentSize = Files.size(filePath);
                System.out.println("Attempt " + (attempts + 1) + ": File size = " + currentSize + " bytes");

                // If the file size hasn't changed since the last attempt AND it meets the minimum threshold, the file is stable
                if (currentSize == previousSize && currentSize > 0) {
                    System.out.println("File size stabilized. Proceeding with processing...");
                    processFile(newTaskId, filePath, currentSize);
                    break;
                }

                // Update the previous size and wait before the next attempt
                previousSize = currentSize;
                attempts++;
                Thread.sleep(delayBetweenAttempts);
            } catch (IOException e) {
                System.err.println("Error checking file size: " + e.getMessage());
                e.printStackTrace();
                return;
            } catch (InterruptedException e) {
                System.err.println("Thread interrupted while waiting for file stability: " + e.getMessage());
                e.printStackTrace();
                return;
            }
        }

        // If the file size never stabilized, log an error and return
        if (attempts >= maxAttempts) {
            System.err.println("File size did not stabilize after " + maxAttempts + " attempts. Aborting processing.");
        }
    }

    private String createAndLinkClickUpTask(String domainTaskId, String domainName) {
        String date = java.time.LocalDate.now().toString();
        String name = "Backup - " + domainName + " - " + date;

        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("name", name);
        requestBodyMap.put("status", "in progress");

        Gson gson = new Gson();
        String json = gson.toJson(requestBodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/list/" + backupsList + "/task")
                .post(body)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Trying to create a new ClickUp task.");
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            assert response.body() != null;

            String responseBody = response.body().string();

            JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
            String newTaskId = jsonObject.get("id").getAsString();

            linkClickUpTaskToDomain(newTaskId, domainTaskId);

            return newTaskId;
        } catch (IOException e) {
            System.err.println("Error creating a new ClickUp task.");
            e.printStackTrace();
            return null;
        }
    }

    private void processFile(String taskId, Path filePath, long fileSize) throws IOException {
        long partSize = 900 * 1024L * 1024L; // 900 MB in bytes
        String uniqueId = UUID.randomUUID().toString();
        Path outputDir = Paths.get(tempDir + "/split_" + uniqueId);

        System.out.println("File size: " + fileSize + " bytes");
        System.out.println("Part size: " + partSize + " bytes");
        System.out.println("Should split the file? " + (fileSize > partSize));

        if (fileSize > partSize) {
            System.out.println("File size exceeds part size. Splitting file into parts.");
            ZipFileSplitter.splitFile(filePath, outputDir, partSize);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDir)) {
                List<Path> parts = new ArrayList<>();
                stream.forEach(part -> {
                    if (Files.isRegularFile(part)) {
                        parts.add(part);
                    }
                });

                // Create a thread pool for concurrent uploads
                ExecutorService uploadExecutor = Executors.newFixedThreadPool(3);
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                // Submit upload tasks
                for (Path part : parts) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            System.out.println("Attaching part: " + part);
                            attachBackupToClickUpTask(taskId, part);
                            Files.delete(part);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }, uploadExecutor);
                    futures.add(future);
                }

                // Wait for all uploads to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                uploadExecutor.shutdown();

                // Clean up the temporary directory
                Files.delete(outputDir);
            } catch (IOException e) {
                System.err.println("Error splitting or attaching file parts: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.out.println("File size is within the limit. Attaching the file directly.");
            attachBackupToClickUpTask(taskId, filePath);
        }

        // Mark the task as completed after successful processing
        markClickUpTaskAsCompleted(taskId);
    }

    private void linkClickUpTaskToDomain(String backupTaskId, String domainTaskId) {
        ArrayList<String> addTaskArray = new ArrayList<>();
        addTaskArray.add(backupTaskId);

        Map<String, Object> addTasksMap = new HashMap<>();
        addTasksMap.put("add", addTaskArray);

        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("value", addTasksMap);

        Gson gson = new Gson();
        String json = gson.toJson(requestBodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + domainTaskId + "/field/" + domainCustomField)
                .post(body)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Trying to link the ClickUp task to the domain task.");
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            assert response.body() != null;
        } catch (IOException e) {
            System.err.println("Error linking the ClickUp task to the domain task.");
            e.printStackTrace();
        }
    }

    private void attachBackupToClickUpTask(String taskId, Path filePath) {
        File file = filePath.toFile();
        RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("attachment", file.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId + "/attachment")
                .post(requestBody)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "multipart/form-data")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Trying to attach the file to the ClickUp task.");
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            assert response.body() != null;
            System.out.println("Response: " + response.body().string());
        } catch (IOException e) {
            System.err.println("Error attaching the file to the ClickUp task.");
            e.printStackTrace();
        }
    }

    private void markClickUpTaskAsCompleted(String taskId) {
        Map<String, Object> requestBodyMap = new HashMap<>();
        requestBodyMap.put("status", "complete");

        Gson gson = new Gson();
        String json = gson.toJson(requestBodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId)
                .put(body)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Trying to mark the ClickUp task as completed.");
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            assert response.body() != null;
        } catch (IOException e) {
            System.err.println("Error marking the ClickUp task as completed.");
            e.printStackTrace();
        }
    }

    public void stopWatching() {
        executorService.shutdownNow();
        scheduler.shutdownNow();
    }
}