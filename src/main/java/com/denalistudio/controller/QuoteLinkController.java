package com.denalistudio.controller;

import com.google.gson.Gson;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class QuoteLinkController {
    private final String quotesApiKey;
    private final String clickUpApiKey;
    private final String customFieldId;
    private final OkHttpClient client = new OkHttpClient();

    public QuoteLinkController() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        // Load environment variables
        this.quotesApiKey = dotenv.get("QUOTES_API_KEY");
        this.clickUpApiKey = dotenv.get("CLICKUP_API_KEY");
        this.customFieldId = dotenv.get("QUOTE_LINK_CUSTOM_FIELD_ID");
    }

    @PostMapping("/get-quote-link")
    public String getQuoteLink(@RequestParam String apiKey, @RequestParam String taskId) {
        if (!quotesApiKey.equals(apiKey)) {
            return "Invalid API key";
        }

        Map<String, String> requestBodyMap = new HashMap<>();
        requestBodyMap.put("value", "https://quotes.denali.cz/" + taskId);

        Gson gson = new Gson();
        String json = gson.toJson(requestBodyMap);

        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId + "/field/" + customFieldId)
                .post(body)
                .addHeader("accept", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Trying to paste quote link for task " + taskId);
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            return "Quote link pasted for task " + taskId;
        } catch (Exception e) {
            e.printStackTrace();
            return "Error pasting quote link for task " + taskId;
        }
    }
}
