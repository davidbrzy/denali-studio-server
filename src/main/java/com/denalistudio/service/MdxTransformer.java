package com.denalistudio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

public class MdxTransformer {
    private final String clickUpApiKey;
    private final String listId;
    private final String idCfId;
    private final String iconTypeCfId;
    private final String iconCfId;
    private final String textCfId;
    private final String priceCfId;
    private final OkHttpClient client = new OkHttpClient();

    public MdxTransformer() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        // Load environment variables
        this.clickUpApiKey = dotenv.get("CLICKUP_API_KEY");
        this.listId = dotenv.get("CLICKUP_ITEMCARDS_LIST_ID");
        this.idCfId = dotenv.get("CLICKUP_ITEMCARDS_ID_CUSTOM_FIELD_ID");
        this.iconTypeCfId = dotenv.get("CLICKUP_ITEMCARDS_ICON_TYPE_CUSTOM_FIELD_ID");
        this.iconCfId = dotenv.get("CLICKUP_ITEMCARDS_ICON_CUSTOM_FIELD_ID");
        this.textCfId = dotenv.get("CLICKUP_ITEMCARDS_TEXT_CUSTOM_FIELD_ID");
        this.priceCfId = dotenv.get("CLICKUP_ITEMCARDS_PRICE_CUSTOM_FIELD_ID");
    }

    public String transformToMdx(String markdown, ArrayList<String> assignees) {
        String mdx = transformContent(markdown, assignees);
        mdx = transformHover(mdx);
        mdx = convertDividers(mdx);
        mdx = convertImages(mdx);
        mdx = convertCards(mdx);
        mdx = convertItemCards(mdx);
        mdx = convertPriceSection(mdx);
        mdx = convertButtonGroups(mdx);
        return mdx;
    }

    public String transformContent(String input, ArrayList<String> assignees) {
        // Split on '* * *'
        String[] parts = input.split("\\* \\* \\*");
        StringBuilder sb = new StringBuilder();

        // First part -> Intro
        if (parts.length > 0) {
            // Wrap paragraphs in <IntroParagraph>
            String[] paragraphs = parts[0].split("\\r?\\n");
            sb.append("<Intro>\n")
                    .append("<IntroProfiles assignees={[")
                    .append(assignees.stream()
                            .map(initial -> "\"" + initial + "\"")
                            .collect(Collectors.joining(", ")))
                    .append("]} />\n")
                    .append("<IntroText>\n");
            for (String p : paragraphs) {
                if (!p.trim().isEmpty()) {
                    sb.append(p.trim())
                            .append("\n\n");
                }
            }
            sb.append("</IntroText>\n");
            sb.append("</Intro>\n");
        }

        // Subsequent parts -> Section blocks
        for (int i = 1; i < parts.length; i++) {
            String content = parts[i].trim();
            sb.append("<Section>\n").append(content);
            if (content.startsWith("## Průběh")) {
                sb.append("\n<ReferencesAndOurApproach />");
            }
            sb.append("\n</Section>\n");
        }
        return sb.toString();
    }

    private String convertDividers(String input) {
        // Remove spaced triple asterisks
        return input.replaceAll("\\* \\* \\*", "");
    }

    private String convertImages(String input) {
        Pattern pattern = Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
        Matcher matcher = pattern.matcher(input);
        return matcher.replaceAll("<Pic src=\"$2\" />");
    }

    private String transformHover(String input) {
        input = input.replace("\\[", "[").replace("\\]", "]");
        Pattern pattern = Pattern.compile("\\[begin\\s+Hover\\s+text=\"([^\"]*)\"\\](.*?)\\[end\\s+Hover\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(input);
        return matcher.replaceAll("<Hover text=\"$1\">$2</Hover>");
    }

    private String convertCards(String input) {
        // Cards conversion
        Pattern pattern = Pattern.compile("\\[begin cards\\](.*?)\\[end cards\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String cardsBlock = matcher.group(1);
            String replacement = "<Cards>\n" + buildCardsArray(cardsBlock) + "</Cards>";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String buildCardsArray(String cardsBlock) {
        String[] lines = cardsBlock.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();

        for (String line : lines) {
            if (line.contains("[card ")) {
                String icon = line.replaceAll(".*icon=\"(.*?)\".*", "$1");
                if ("used".equals(icon)) {
                    icon = "users";
                }
                String text = line.replaceAll(".*text=\"(.*?)\".*", "$1");
                sb.append("<Card icon=\"" + icon + "\" text=\"" + text + "\" />\n");
            }
        }

        return sb.toString();
    }

    private String convertItemCards(String input) {
        // First unescape the input
        input = input.replace("\\[", "[").replace("\\]", "]");
        Pattern pattern = Pattern.compile("\\[begin itemCards\\](.*?)\\[end itemCards\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String itemCardsBlock = matcher.group(1).trim();
            StringBuilder replacement = new StringBuilder("<ItemCards>\n");

            // Split into individual cards and process each
            String[] cards = itemCardsBlock.split("\\n\\n");
            for (String card : cards) {
                if (card.contains("[itemCard")) {
                    Map <String, Object> itemCardAttributes = new HashMap<>();

                    // Extract library if present
                    Pattern libPattern = Pattern.compile("library=\"([^\"]+)\"");
                    Matcher libMatcher = libPattern.matcher(card);
                    if (libMatcher.find()) {
                        String libraryName = libMatcher.group(1);
                        Map<String, String> libraryData = getItemCardAttributes(libraryName);
                        itemCardAttributes.putAll(libraryData);
                    }

                    // Extract all key="value" pairs
                    Pattern attrPattern = Pattern.compile("(\\w+)=\"([^\"]+)\"");
                    Matcher attrMatcher = attrPattern.matcher(card);
                    while (attrMatcher.find()) {
                        String key = attrMatcher.group(1);
                        String value = attrMatcher.group(2);
                        itemCardAttributes.put(key, value); // overrides if already set
                    }

                    // Render the collected attributes
                    replacement.append("<ItemCard");
                    for (Map.Entry<String, Object> entry : itemCardAttributes.entrySet()) {
                        replacement
                                .append(" ")
                                .append(entry.getKey())
                                .append("=\"")
                                .append(entry.getValue())
                                .append("\"");
                    }
                    replacement.append(" />\n");
                }
            }
            replacement.append("</ItemCards>");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Map<String, String> getItemCardAttributes(String library) {
        Map<String, String> result = new HashMap<>();
        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/list/" + listId + "/task")
                .addHeader("accept", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            String responseBody = response.body().string();
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode tasks = objectMapper.readTree(responseBody).get("tasks");

            if (tasks != null && tasks.isArray()) {
                for (JsonNode t : tasks) {
                    JsonNode customFields = t.get("custom_fields");
                    if (customFields != null && customFields.isArray()) {
                        boolean matchFound = false;
                        // Check if this task's custom field with \`idCfId\` has a value matching \`library\`
                        for (JsonNode cf : customFields) {
                            if (cf.has("id")
                                    && idCfId.equals(cf.get("id").asText())
                                    && cf.has("value")
                                    && library.equals(cf.get("value").asText())) {
                                matchFound = true;
                                break;
                            }
                        }
                        // If matched, gather other custom fields
                        if (matchFound) {
                            String heading = t.get("name").asText();
                            result.put("heading", heading);
                            for (JsonNode cf : customFields) {
                                if (cf.has("id") && cf.has("value") && !cf.get("value").isNull()) {
                                    String fieldId = cf.get("id").asText();
                                    String fieldValue = cf.get("value").asText();
                                    if (iconTypeCfId.equals(fieldId)) {
                                        if (Objects.equals(fieldValue, "0")) {
                                            fieldValue = "fa";
                                        } else if (Objects.equals(fieldValue, "1")) {
                                            fieldValue = "custom";
                                        }
                                        result.put("iconType", fieldValue);
                                    } else if (iconCfId.equals(fieldId)) {
                                        result.put("icon", fieldValue);
                                    } else if (textCfId.equals(fieldId)) {
                                        result.put("text", fieldValue);
                                    } else if (priceCfId.equals(fieldId)) {
                                        result.put("price", fieldValue);
                                    }
                                }
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private String convertPriceSection(String input) {
        // First unescape the input
        input = input.replace("\\[", "[").replace("\\]", "]");

        Pattern sectionPattern = Pattern.compile("\\[begin PriceSection\\](.*?)\\[end PriceSection\\]", Pattern.DOTALL);
        Matcher sectionMatcher = sectionPattern.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (sectionMatcher.find()) {
            String sectionContent = sectionMatcher.group(1).trim();
            StringBuilder replacement = new StringBuilder("<PriceSection>\n");

            // Modified pattern to better separate attributes from content
            Pattern priceBoxPattern = Pattern.compile("\\[begin PriceBox([^\\n]*?)\\n(.*?)\\n\\s*\\[end PriceBox\\]", Pattern.DOTALL);
            Matcher priceBoxMatcher = priceBoxPattern.matcher(sectionContent);

            if (priceBoxMatcher.find()) {
                String priceAttributes = priceBoxMatcher.group(1).trim();

                // Remove trailing bracket if present
                if (priceAttributes.endsWith("]")) {
                    priceAttributes = priceAttributes.substring(0, priceAttributes.length() - 1);
                }

                String priceBoxContent = priceBoxMatcher.group(2).trim();

                // Process markdown link in attributes
                Pattern linkPattern = Pattern.compile("link\\s*=\\s*\"\\[([^\\]]+)\\]\\(([^)]+)\\)\"");
                Matcher linkMatcher = linkPattern.matcher(priceAttributes);

                if (linkMatcher.find()) {
                    String url = linkMatcher.group(2);
                    priceAttributes = linkMatcher.replaceAll("link=\"" + url + "\"");
                }

                replacement.append("<PriceBox ").append(priceAttributes).append(">\n")
                        .append(priceBoxContent).append("\n")
                        .append("</PriceBox>\n");

                sectionContent = priceBoxMatcher.replaceFirst("");
            }

            // Process PriceContent block
            Pattern contentPattern = Pattern.compile("\\[begin PriceContent\\](.*?)\\[end PriceContent\\]", Pattern.DOTALL);
            Matcher contentMatcher = contentPattern.matcher(sectionContent);
            if (contentMatcher.find()) {
                String priceContent = contentMatcher.group(1).trim();
                replacement.append("<PriceContent>\n")
                        .append(priceContent).append("\n")
                        .append("</PriceContent>\n");
            }

            replacement.append("</PriceSection>");
            sectionMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }

        sectionMatcher.appendTail(sb);
        return sb.toString();
    }

    private String convertButtonGroups(String input) {
        // First unescape the input
        input = input.replace("\\[", "[").replace("\\]", "]");

        Pattern pattern = Pattern.compile("\\[begin buttonGroup\\](.*?)\\[end buttonGroup\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(input);

        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String buttonGroupBlock = matcher.group(1).trim();
            StringBuilder replacement = new StringBuilder("<ButtonGroup\n    buttons={[");

            // Split into individual buttons and process each
            String[] buttons = buttonGroupBlock.split("\\n\\n");
            for (String button : buttons) {
                if (button.contains("[button")) {
                    Pattern btnPattern = Pattern.compile("\\[button\\s+hierarchy=\"([^\"]+)\"\\s+icon=\"([^\"]+)\"\\s+text=\"([^\"]+)\"\\s+href=\"([^\"]+)\"\\]");
                    Matcher btnMatcher = btnPattern.matcher(button);
                    if (btnMatcher.find()) {
                        String hierarchy = btnMatcher.group(1);
                        String icon = btnMatcher.group(2);
                        String text = btnMatcher.group(3);
                        String href = btnMatcher.group(4).replaceAll("\\[|\\]\\([^)]*\\)", ""); // Clean up markdown links

                        replacement.append("\n{\n")
                                .append("    hierarchy: '").append(hierarchy).append("',\n")
                                .append("    icon: '").append(icon).append("',\n")
                                .append("    text: '").append(text).append("',\n")
                                .append("    href: \"").append(href).append("\"\n")
                                .append("},");
                    }
                }
            }
            // Remove last comma and close the array
            if (replacement.charAt(replacement.length() - 1) == ',') {
                replacement.setLength(replacement.length() - 1);
            }
            replacement.append("\n    ]}\n/>");

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}