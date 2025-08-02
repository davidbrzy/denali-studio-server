package com.denalistudio.controller;

import com.denalistudio.service.WebsiteAudit;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;

@RestController
@RequestMapping("/api")
public class WebsiteAuditController {
    private final String auditApiKey;

    public WebsiteAuditController() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        this.auditApiKey = dotenv.get("AUDIT_API_KEY");
    }

    @PostMapping("/audit")
    public ResponseEntity<WebsiteAudit.AuditResult> websiteAudit(@RequestParam String apiKey, @RequestParam String domain) {
        if (!auditApiKey.equals(apiKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Neplatný API klíč");
        }

        if (domain.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Doména je povinná");
        }

        // Generate audit result and return as JSON
        WebsiteAudit.AuditResult result = WebsiteAudit.generateWebsiteAudit(domain);
        return ResponseEntity.ok(result);
    }
}