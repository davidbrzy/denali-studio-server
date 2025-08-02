package com.denalistudio.controller;

import com.denalistudio.service.WordPressInstallationService;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;

@RestController
@RequestMapping("/api")
public class WordPressInstallationController {
    private final String wpInstallApiKey;
    private final WordPressInstallationService installationService;

    public WordPressInstallationController() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        this.wpInstallApiKey = dotenv.get("WP_INSTALL_API_KEY");
        this.installationService = new WordPressInstallationService();
    }

    @PostMapping("/install-wp")
    public ResponseEntity<InstallationResponse> installWordPress(
            @RequestParam String apiKey,
            @RequestParam String taskId) {

        if (!wpInstallApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
        }

        try {
            String result = installationService.startWordPressInstallation(taskId);
            return ResponseEntity.ok(new InstallationResponse(true, result, null));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new InstallationResponse(false, null, e.getMessage()));
        }
    }

    public static class InstallationResponse {
        private boolean success;
        private String message;
        private String error;

        public InstallationResponse(boolean success, String message, String error) {
            this.success = success;
            this.message = message;
            this.error = error;
        }

        // Gettery
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getError() { return error; }
    }
}