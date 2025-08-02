package com.denalistudio.controller;

import com.denalistudio.service.SftpUserService;
import com.google.gson.Gson;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import okhttp3.*;
import okhttp3.RequestBody;

@RestController
@RequestMapping("/api")
public class UserController {
    OkHttpClient client = new OkHttpClient();

    @Autowired
    private SftpUserService sftpUserService;

    private final String backupsApiKey;
    private final String clickUpApiKey;
    private final String backupsPathCustomFieldId;
    private final String backupsUsernameCustomFieldId;
    private final String backupsPasswordCustomFieldId;

    public UserController() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        // Load environment variables
        this.backupsApiKey = dotenv.get("BACKUPS_API_KEY");
        this.clickUpApiKey = dotenv.get("CLICKUP_API_KEY");
        this.backupsPathCustomFieldId = dotenv.get("BACKUPS_PATH_CUSTOM_FIELD_ID");
        this.backupsUsernameCustomFieldId = dotenv.get("BACKUPS_USERNAME_CUSTOM_FIELD_ID");
        this.backupsPasswordCustomFieldId = dotenv.get("BACKUPS_PASSWORD_CUSTOM_FIELD_ID");
    }

    @PostMapping("/create-sftp-user")
    public String createSftpUser(@RequestParam String apiKey, @RequestParam String taskId, @RequestParam String domainName) {
        if (!backupsApiKey.equals(apiKey)) {
            return "Invalid API key";
        }

        String sanitizedDomainName = domainName.replace(".", "_");

        try {
            Map<String, String> userDetails = sftpUserService.createUser("backup_" + taskId + "_" + sanitizedDomainName);
            String username = userDetails.get("username");
            String password = userDetails.get("password");

            savePathToClickUp(taskId, "/" + username);
            saveUsernameToClickUp(taskId, username);
            savePasswordToClickUp(taskId, password);

            return "User created successfully";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private void savePathToClickUp(String taskId, String path) {
        Map<String, String> requestBodyMap = new HashMap<>();
        requestBodyMap.put("value", path);

        Gson gson = new Gson();
        String json = gson.toJson(requestBodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId + "/field/" + backupsPathCustomFieldId)
                .post(body)
                .addHeader("Authorization", clickUpApiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            assert response.body() != null;
            System.out.println(response.body().string());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveUsernameToClickUp(String taskId, String username) {
        Map<String, String> requestBodyMap = new HashMap<>();
        requestBodyMap.put("value", username);

        Gson gson = new Gson();
        String json = gson.toJson(requestBodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId + "/field/" + backupsUsernameCustomFieldId)
                .post(body)
                .addHeader("Authorization", clickUpApiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            assert response.body() != null;
            System.out.println(response.body().string());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void savePasswordToClickUp(String taskId, String password) {
        Map<String, String> requestBodyMap = new HashMap<>();
        requestBodyMap.put("value", password);

        Gson gson = new Gson();
        String json = gson.toJson(requestBodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId + "/field/" + backupsPasswordCustomFieldId)
                .post(body)
                .addHeader("Authorization", clickUpApiKey)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            assert response.body() != null;
            System.out.println(response.body().string());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}