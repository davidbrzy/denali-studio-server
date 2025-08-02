package com.denalistudio.controller;

import com.denalistudio.service.MdxTransformer;
import com.denalistudio.service.PdfTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.*;
import okhttp3.RequestBody;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class QuoteGenerationController {
    private final String tempDirPath;
    private final String quotesApiKey;
    private final String clickUpApiKey;
    private final String quoteSubtitleCustomFieldId;
    private final String quoteCompanyCustomFieldId;
    private final String companyLegalNameCustomFieldId;
    private final OkHttpClient client = new OkHttpClient();
    boolean isMdxUploaded = false;
    boolean isPdfUploaded = false;

    public QuoteGenerationController() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        // Load environment variables
        this.tempDirPath = dotenv.get("TEMP_DIR");
        this.quotesApiKey = dotenv.get("QUOTES_API_KEY");
        this.clickUpApiKey = dotenv.get("CLICKUP_API_KEY");
        this.quoteSubtitleCustomFieldId = dotenv.get("CLICKUP_QUOTES_SUBTITLE_CUSTOM_FIELD_ID");
        this.quoteCompanyCustomFieldId = dotenv.get("CLICKUP_QUOTES_COMPANY_CUSTOM_FIELD_ID");
        this.companyLegalNameCustomFieldId = dotenv.get("CLICKUP_COMPANY_LEGAL_NAME_CUSTOM_FIELD_ID");
    }

    @PostMapping("/generate-quote")
    public String generateQuote(@RequestParam String apiKey, @RequestParam String taskId) {
        if (!quotesApiKey.equals(apiKey)) {
            return "Invalid API key";
        }

        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId + "?include_markdown_description=true")
                .addHeader("accept", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            System.out.println("Request sent to ClickUp API to gather task data for task ID " + taskId);
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            String responseBody = response.body().string();
            ObjectMapper objectMapper = new ObjectMapper();
            String title = objectMapper.readTree(responseBody).get("name").asText();
            JsonNode assigneesNode = objectMapper.readTree(responseBody).get("assignees");
            JsonNode markdownDescription = objectMapper.readTree(responseBody).get("markdown_description");
            JsonNode customFieldsNode = objectMapper.readTree(responseBody).get("custom_fields");

            ArrayList<String> assignees = new ArrayList<>();

            if (assigneesNode != null && assigneesNode.isArray()) {
                for (JsonNode assignee : assigneesNode) {
                    JsonNode initials = assignee.get("initials");
                    if (initials != null) {
                        assignees.add(initials.asText().toLowerCase());
                    }
                }
            }

            String subtitle = "";
            for (JsonNode customField : customFieldsNode) {
                if (customField.get("id").asText().equals(quoteSubtitleCustomFieldId) && customField.get("value") != null) {
                    subtitle = customField.get("value").asText();
                    break;
                }
            }

            if (subtitle.isEmpty()) {
                for (JsonNode customField : customFieldsNode) {
                    if (customField.get("id").asText().equals(quoteCompanyCustomFieldId) &&
                            customField.has("value") &&
                            !customField.get("value").isNull() &&
                            customField.get("value").isArray() &&
                            customField.get("value").get(0).has("id")) {
                        String companyId = customField.get("value").get(0).get("id").asText();
                        String legalName = getCompanyLegalName(companyId);
                        if (!legalName.isEmpty()) {
                            subtitle = "Nabídka pro " + legalName;
                            break;
                        }
                    }
                }
            }

            if (markdownDescription != null && !markdownDescription.isNull()) {
                MdxTransformer mdxTransformer = new MdxTransformer();
                PdfTransformer pdfTransformer = new PdfTransformer();

                // Convert the markdown description to text
                String mdAsText = markdownDescription.asText();

                // Create the MDX file
                String mdx = mdxTransformer.transformToMdx(mdAsText, assignees);
                createMdxFile(taskId, mdx);

                // Create the PDF file
                byte[] pdfBytes = pdfTransformer.generatePdf(taskId, title, subtitle, mdAsText, assignees);
                createPdfFile(taskId, pdfBytes);

                // Return success message
                return "MDX and PDF quotes generated for task " + taskId;
            }

            return "No markdown description found";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String getCompanyLegalName(String taskId) {
        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/task/" + taskId)
                .addHeader("accept", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            System.out.println("Request sent to ClickUp API to gather company legal name for task ID " + taskId);

            String responseBody = response.body().string();
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode customFieldsNode = objectMapper.readTree(responseBody).get("custom_fields");

            for (JsonNode customField : customFieldsNode) {
                if (customField.get("id").asText().equals(companyLegalNameCustomFieldId) && customField.get("value") != null) {
                    return customField.get("value").asText();
                }
            }
            return "";
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return "";
        }
    }

    private void createMdxFile(String taskId, String mdx) {
        try {
            Path tempDir = Paths.get(tempDirPath);
            Files.createDirectories(tempDir);

            // Create a temporary MDX file
            Path mdxFile = tempDir.resolve("quote_" + taskId + ".mdx");
            Files.writeString(mdxFile, mdx, StandardCharsets.UTF_8);

            // Attach the file to the task
            String result = attachFileToTask(taskId, mdxFile);  // This is correct - passing the Path
            System.out.println(result);  // Log the result

            // Clean up - delete the temporary file
            Files.deleteIfExists(mdxFile);
        } catch (IOException e) {
            System.err.println("Error creating file: " + e.getMessage());
        }
    }

    private void createPdfFile(String taskId, byte[] pdfBytes) {
        try {
            Path tempDir = Paths.get(tempDirPath);
            Files.createDirectories(tempDir);

            Path pdfFile = tempDir.resolve("nabidka_" + taskId + ".pdf");
            // Write the PDF bytes to the temporary file
            Files.write(pdfFile, pdfBytes);

            // Attach the PDF file to the ClickUp task
            String result = attachFileToTask(taskId, pdfFile);
            System.out.println(result);  // Log the result

            // Delete the temporary file
            Files.deleteIfExists(pdfFile);
        } catch (IOException e) {
            System.err.println("Error creating PDF file: " + e.getMessage());
        }
    }

    private String attachFileToTask(String taskId, Path filePath) {
        File file = filePath.toFile();
        okhttp3.RequestBody fileBody = RequestBody.create(file, MediaType.parse("application/octet-stream"));

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
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            // Check the file format
            String fileName = file.getName();
            if (fileName.endsWith(".mdx")) {
                isMdxUploaded = true;
            } else if (fileName.endsWith(".pdf")) {
                isPdfUploaded = true;
            }

            // Check if both files are uploaded and uncheck the custom field
            if (isMdxUploaded && isPdfUploaded) {
                return "File attached to task " + taskId;
            } else {
                return "File attached to task " + taskId;
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return "Error attaching file to task " + taskId;
        }
    }
}