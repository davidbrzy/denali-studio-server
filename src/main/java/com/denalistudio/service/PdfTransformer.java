package com.denalistudio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.itextpdf.html2pdf.HtmlConverter;
import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.resolver.font.DefaultFontProvider;
import com.itextpdf.layout.font.FontProvider;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import io.github.cdimascio.dotenv.Dotenv;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PdfTransformer {
    private final String tempDirPath;
    private final String clickUpApiKey;
    private final String itemCardsListId;
    private final String itemCardsIdCfId;
    private final String itemCardsIconTypeCfId;
    private final String itemCardsIconCfId;
    private final String itemCardsTextCfId;
    private final String itemCardsPriceCfId;
    private final String iconsListId;
    private final String iconsIconCfId;
    private final String contactsListId;
    private final String contactsInitialsCfId;
    private final String contactsPositionCfId;
    private final String contactsEmailCfId;
    private final String contactsPhoneCfId;
    private final OkHttpClient client = new OkHttpClient();

    public PdfTransformer() {
        Dotenv dotenv;
        String envPath = System.getenv("DS_SERVER_ENV_PATH");

        if (envPath == null) {
            throw new RuntimeException("DS_SERVER_ENV_PATH environment variable not set.");
        }

        dotenv = Dotenv.configure().directory(envPath).load();

        // Load environment variables
        this.tempDirPath = dotenv.get("TEMP_DIR");
        this.clickUpApiKey = dotenv.get("CLICKUP_API_KEY");
        this.itemCardsListId = dotenv.get("CLICKUP_ITEMCARDS_LIST_ID");
        this.itemCardsIdCfId = dotenv.get("CLICKUP_ITEMCARDS_ID_CUSTOM_FIELD_ID");
        this.itemCardsIconTypeCfId = dotenv.get("CLICKUP_ITEMCARDS_ICON_TYPE_CUSTOM_FIELD_ID");
        this.itemCardsIconCfId = dotenv.get("CLICKUP_ITEMCARDS_ICON_CUSTOM_FIELD_ID");
        this.itemCardsTextCfId = dotenv.get("CLICKUP_ITEMCARDS_TEXT_CUSTOM_FIELD_ID");
        this.itemCardsPriceCfId = dotenv.get("CLICKUP_ITEMCARDS_PRICE_CUSTOM_FIELD_ID");
        this.iconsListId = dotenv.get("CLICKUP_ICONS_LIST_ID");
        this.iconsIconCfId = dotenv.get("CLICKUP_ICONS_ICON_CUSTOM_FIELD_ID");
        this.contactsListId = dotenv.get("CLICKUP_CONTACTS_LIST_ID");
        this.contactsInitialsCfId = dotenv.get("CLICKUP_CONTACTS_INITIALS_CUSTOM_FIELD_ID");
        this.contactsPositionCfId = dotenv.get("CLICKUP_CONTACTS_POSITION_CUSTOM_FIELD_ID");
        this.contactsEmailCfId = dotenv.get("CLICKUP_CONTACTS_EMAIL_CUSTOM_FIELD_ID");
        this.contactsPhoneCfId = dotenv.get("CLICKUP_CONTACTS_PHONE_CUSTOM_FIELD_ID");
    }

    public byte[] generatePdf(String id, String title, String subtitle, String markdownContent, ArrayList<String> assignees) {
        String quoteLink = "https://quotes.denali.cz/" + id + "?accept=true";

        try {
            // Convert markdown to HTML
            String html = convertMarkdownToHtml(quoteLink, title, subtitle, markdownContent, assignees);

            // Get the resources path from system property or use default
            String resourcePath = System.getProperty("resources.path", "resources");
            String fontsPath = resourcePath + "/fonts/";

            // Fallback to development path if fonts directory does not exist
            File fontsDir = new File(fontsPath);
            if (!fontsDir.exists()) {
                fontsPath = "src/main/resources/fonts/";
            }

            // Configure font provider with explicit registration
            FontProvider fontProvider = new DefaultFontProvider(false, false, false);
            fontProvider.addFont(fontsPath + "inter-regular.ttf");
            fontProvider.addFont(fontsPath + "inter-semibold.ttf");
            fontProvider.addFont(fontsPath + "inter-bold.ttf");

            // Convert HTML to PDF with custom fonts
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ConverterProperties properties = new ConverterProperties();
            properties.setFontProvider(fontProvider);

            String baseUri = new File(resourcePath).toURI().toString();
            properties.setBaseUri(baseUri);

            HtmlConverter.convertToPdf(
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                    outputStream,
                    properties
            );

            byte[] mainPdfContent = outputStream.toByteArray();

            // Extract PDF links from the original markdown content
            List<String> pdfLinks = extractPdfLinks(markdownContent);

            // If there are any links, download and append the PDF documents
            if (!pdfLinks.isEmpty()) {
                mainPdfContent = appendPdfAttachments(mainPdfContent, pdfLinks);
            }

            return mainPdfContent;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate PDF", e);
        }
    }

    private byte[] appendPdfAttachments(byte[] mainPdfContent, List<String> pdfLinks) {
        try {
            File tempDir = new File("temp");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            // Create a list to hold temporary files
            List<File> tempFiles = new ArrayList<>();

            // Firstly save the main PDF
            File mainPdfFile = File.createTempFile("main_pdf_", ".pdf", tempDir);
            try (FileOutputStream fos = new FileOutputStream(mainPdfFile)) {
                fos.write(mainPdfContent);
            }
            tempFiles.add(mainPdfFile);

            // Download all PDFs from the list of links
            for (String pdfUrl : pdfLinks) {
                try {
                    File tempFile = downloadPdfFromUrl(pdfUrl, tempDir);
                    if (tempFile != null) {
                        tempFiles.add(tempFile);
                    }
                } catch (Exception e) {
                    System.err.println("Error downloading PDF from " + pdfUrl + ": " + e.getMessage());
                }
            }

            // Merge the PDFs
            if (tempFiles.size() > 1) {
                ByteArrayOutputStream mergedPdfStream = new ByteArrayOutputStream();
                PdfDocument pdfDoc = new PdfDocument(new PdfWriter(mergedPdfStream));

                for (File pdfFile : tempFiles) {
                    PdfDocument sourcePdf = new PdfDocument(new PdfReader(pdfFile));
                    sourcePdf.copyPagesTo(1, sourcePdf.getNumberOfPages(), pdfDoc);
                    sourcePdf.close();
                }

                pdfDoc.close();

                // Delete the temporary files
                for (File tempFile : tempFiles) {
                    tempFile.delete();
                }

                return mergedPdfStream.toByteArray();
            } else {
                // If there is nothing to merge, return the original PDF
                return mainPdfContent;
            }
        } catch (Exception e) {
            System.err.println("Error appending PDF attachments: " + e.getMessage());
            return mainPdfContent; // Return the original PDF in case of an error
        }
    }

    private File downloadPdfFromUrl(String pdfUrl, File tempDir) throws Exception {
        try {
            // The URL was already cleaned during extraction
            URL url = new URL(pdfUrl);

            // Initialize HTTP connection
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Accept", "*/*");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            // Debug
            System.out.println("Connecting to URL: " + pdfUrl);

            // Check the response
            int responseCode = connection.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK) {
                System.err.println("Server returned code: " + responseCode + " for URL: " + pdfUrl);
                connection.disconnect();
                return null;
            }

            // Download the file regardless of the content-type
            File tempFile = File.createTempFile("attachment_", ".pdf", tempDir);
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            // Close the connection
            connection.disconnect();

            // Check the filesize
            if (tempFile.length() < 10) {
                System.err.println("Downloaded file is too small: " + tempFile.length() + " bytes");
                tempFile.delete();
                return null;
            }

            System.out.println("PDF successfully downloaded: " + tempFile.getAbsolutePath());
            return tempFile;
        } catch (Exception e) {
            System.err.println("Error while downloading PDF: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private List<String> extractPdfLinks(String markdownContent) {
        List<String> pdfLinks = new ArrayList<>();

        // Pattern for extracting URLs from PriceBox format link="[text](url)"
        Pattern priceBoxPattern = Pattern.compile("\\[begin PriceBox[^\\]]*link=\"\\[([^\\]]*)\\]\\(([^)]*)\\)\"[^\\]]*\\]", Pattern.DOTALL);
        Matcher priceBoxMatcher = priceBoxPattern.matcher(markdownContent);

        while (priceBoxMatcher.find()) {
            String linkText = priceBoxMatcher.group(1); // Link display text
            String linkUrl = priceBoxMatcher.group(2);  // Link URL

            if (linkUrl != null && !linkUrl.isEmpty()) {
                try {
                    // Remove escape symbols from the URL
                    linkUrl = linkUrl.replace("\\_", "_")
                            .replace("\\-", "-")
                            .replace("\\.", ".")
                            .replace("\\~", "~")
                            .replace("\\*", "*")
                            .replace("\\(", "(")
                            .replace("\\)", ")")
                            .replace("\\[", "[")
                            .replace("\\]", "]");

                    // Remove query string ?view=open if exists
                    if (linkUrl.endsWith("?view=open")) {
                        linkUrl = linkUrl.substring(0, linkUrl.length() - 10);
                    }

                    // Validate that the URL has a protocol
                    if (!linkUrl.startsWith("http://") && !linkUrl.startsWith("https://")) {
                        System.out.println("URL does not have a protocol: " + linkUrl);
                        continue;
                    }

                    // Validate the URL
                    new URL(linkUrl);

                    pdfLinks.add(linkUrl);
                    System.out.println("Extracted URL: " + linkUrl + " (text: " + linkText + ")");
                } catch (MalformedURLException e) {
                    System.err.println("Invalid URL: " + linkUrl + " - " + e.getMessage());
                }
            }
        }

        return pdfLinks;
    }

    private String convertMarkdownToHtml(String quoteLink, String title, String subtitle, String markdownContent, ArrayList<String> assignees) {
        // Use system property for resources path
        String resourcePath = System.getProperty("resources.path", "resources");
        String logoPath =  "static/logo.png";
        String cssPath =  "static/pdf-quote-style.css";

        // Fallback to the development path
        File logoFile = new File(resourcePath, logoPath);
        if (!logoFile.exists()) {
            logoPath = "src/main/resources/static/logo.png";
        }

        File cssFile = new File(resourcePath, cssPath);
        if (!cssFile.exists()) {
            cssPath = "src/main/resources/static/pdf-quote-style.css";
        }

        // Extract PDF links from PriceBox for later attachment
        List<String> pdfLinksToAttach = new ArrayList<>();
        Pattern pdfLinkPattern = Pattern.compile("\\[begin PriceBox[^\\]]*link=\"\\[([^\\]]*)\\]\\(([^)]*)\\)\"[^\\]]*\\]", Pattern.DOTALL);
        Matcher priceBoxMatcher = pdfLinkPattern.matcher(markdownContent);
        while (priceBoxMatcher.find()) {
            String pdfUrl = priceBoxMatcher.group(2);
            if (pdfUrl != null && !pdfUrl.isEmpty()) {
                pdfLinksToAttach.add(pdfUrl);
            }
        }

        // Firstly convert markdown to HTML
        MutableDataSet options = new MutableDataSet();
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();

        Node document = parser.parse(markdownContent);
        String contentHtml = renderer.render(document);

        // Process the PriceBox element in HTML
        String priceBoxHtmlPattern = "<p>\\s*\\[begin PriceBox[\\s\\S]*?price=&quot;([^&]*?)&quot;[\\s\\S]*?link=&quot;([^&]*?)&quot;[\\s\\S]*?\\]([\\s\\S]*?)\\[end PriceBox\\]\\s*</p>";
        Pattern pattern = Pattern.compile(priceBoxHtmlPattern);
        Matcher matcher = pattern.matcher(contentHtml);

        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String price = matcher.group(1);
            String link = matcher.group(2);
            String content = matcher.group(3).trim();

            String replacement = "<td class=\"price-box\"><div class=\"wrapper\"><p class=\"price\">" + price +
                    "</p><div>" + content + "</div></div></td>";

            // Extract the link for later processing
            if (link != null && !link.isEmpty()) {
                pdfLinksToAttach.add(link.replaceAll("\\s+", "").trim());
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        contentHtml = sb.toString();

        // Remove unwanted HTML tags
        contentHtml = contentHtml.replaceAll("<hr\\s*/?>", "");
        contentHtml = contentHtml.replaceAll("<p>\\s*\\[begin cards\\]\\s*</p>", "<div class='cards-section'>");
        contentHtml = contentHtml.replaceAll("<p>\\s*\\[end cards\\]\\s*</p>", "</div>");
        contentHtml = contentHtml.replaceAll("<p>\\s*\\[begin PriceSection\\]\\s*</p>", "<table class=\"pricing\"><tr>");
        contentHtml = contentHtml.replaceAll("<p>\\s*\\[end PriceSection\\]\\s*</p>", "</tr></table>");
        contentHtml = contentHtml.replaceAll("<p>\\s*\\[begin PriceContent\\]\\s*</p>", "<td class=\"price-content\"><h4>Obsah služby:</h4>");
        contentHtml = contentHtml.replaceAll("<p>\\s*\\[end PriceContent\\]\\s*</p>", "</td>");
        contentHtml = contentHtml.replaceAll("<p>\\s*\\[begin itemCards\\]\\s*</p>", "<table class=\"item-cards\">");
        contentHtml = contentHtml.replaceAll("<p>\\s*\\[end itemCards\\]\\s*</p>", "</table>");

        // Process each card section
        Pattern sectionPattern = Pattern.compile("<div class='cards-section'>(.*?)</div>", Pattern.DOTALL);
        Matcher sectionMatcher = sectionPattern.matcher(contentHtml);
        StringBuffer completeSb = new StringBuffer();

        while (sectionMatcher.find()) {
            String sectionContent = sectionMatcher.group(1);

            // Cards processing within the card section
            Pattern cardPattern = Pattern.compile("<p>\\s*\\[card\\s+([^\\]]*)\\]\\s*</p>");
            Matcher cardMatcher = cardPattern.matcher(sectionContent);

            List<String> cardsHtml = new ArrayList<>();
            while (cardMatcher.find()) {
                String cardAttributes = cardMatcher.group(1);

                // Card attributes processing
                String icon = "";
                Pattern iconPattern = Pattern.compile("icon=&quot;(.*?)&quot;");
                Matcher iconMatcher = iconPattern.matcher(cardAttributes);
                if (iconMatcher.find()) {
                    icon = iconMatcher.group(1);
                }

                // Extract text attribute with HTML entity quotes
                String text = "";
                Pattern textPattern = Pattern.compile("text=&quot;(.*?)&quot;");
                Matcher textMatcher = textPattern.matcher(cardAttributes);
                if (textMatcher.find()) {
                    text = textMatcher.group(1);
                }

                String svgContent = getSvgContent(icon);
                String cardHtml = "<td class=\"card\"><div class=\"card-content\"><div class=\"icon\">"
                        + svgContent + "</div><div class=\"text\"><p>" + text + "</p></div></div></td>";
                cardsHtml.add(cardHtml);
            }

            // Build HTML table with cards, 3 cards per row
            StringBuilder cardsTableHtml = new StringBuilder("<table class='cards'>");

            for (int i = 0; i < cardsHtml.size(); i += 3) {
                cardsTableHtml.append("<tr>");

                // Adding 3 cells in each row, even empty ones
                for (int j = 0; j < 3; j++) {
                    int index = i + j;
                    if (index < cardsHtml.size()) {
                        cardsTableHtml.append(cardsHtml.get(index));
                    } else {
                        // Empty cell to maintain the layout
                        cardsTableHtml.append("<td></td>");
                    }
                }

                cardsTableHtml.append("</tr>");
            }

            cardsTableHtml.append("</table>");

            // Replace the entire section with the new table
            sectionMatcher.appendReplacement(completeSb, Matcher.quoteReplacement(cardsTableHtml.toString()));
        }

        sectionMatcher.appendTail(completeSb);
        contentHtml = completeSb.toString();

        // Item Card sections processing
        Pattern itemCardsPattern = Pattern.compile("<table class=\"item-cards\">(.*?)</table>", Pattern.DOTALL);
        Matcher itemCardsMatcher = itemCardsPattern.matcher(contentHtml);
        StringBuffer itemCardsSb = new StringBuffer();

        while (itemCardsMatcher.find()) {
            String itemCardsContent = itemCardsMatcher.group(1);

            // Nejprve sbíráme všechny karty
            List<String> itemCardsList = new ArrayList<>();
            Pattern itemCardPattern = Pattern.compile("<p>\\s*\\[itemCard\\s+([^\\]]*)\\]\\s*</p>");
            Matcher itemCardMatcher = itemCardPattern.matcher(itemCardsContent);

            while (itemCardMatcher.find()) {
                String cardAttributes = itemCardMatcher.group(1);
                Map<String, String> attributes = new HashMap<>();

                // Extract firstYearFree attribute with HTML entity quotes
                String firstYearFree = "false";
                Pattern fyfPattern = Pattern.compile("firstYearFree=&quot;(true|false)&quot;");
                Matcher fyfMatcher = fyfPattern.matcher(cardAttributes);
                if (fyfMatcher.find()) {
                    firstYearFree = fyfMatcher.group(1);
                }

                // Regex pattern for the library type
                Pattern libPattern = Pattern.compile("library=&quot;([^&]+)&quot;");
                Matcher libMatcher = libPattern.matcher(cardAttributes);
                if (libMatcher.find()) {
                    // Library type: Getting data from ClickUp
                    String libraryName = libMatcher.group(1);
                    attributes = getItemCardAttributes(libraryName);
                    attributes.put("firstYearFree", firstYearFree);
                } else {
                    // Custom type: Getting the data from the attributes
                    Pattern attrPattern = Pattern.compile("(\\w+)=&quot;([^&]+)&quot;");
                    Matcher attrMatcher = attrPattern.matcher(cardAttributes);
                    while (attrMatcher.find()) {
                        String key = attrMatcher.group(1);
                        String value = attrMatcher.group(2);
                        attributes.put(key, value);
                    }
                }

                // Building HTML for itemCard
                String iconType = attributes.getOrDefault("iconType", "");
                String icon = attributes.getOrDefault("icon", "");
                String heading = attributes.getOrDefault("heading", "");
                String text = attributes.getOrDefault("text", "");
                String price = attributes.getOrDefault("price", "");

                String svgContent = "";
                if ("fa".equals(iconType)) {
                    svgContent = getSvgContent(icon);
                } else if ("custom".equals(iconType)) {
                    File iconFile = getCustomIcon(icon);
                    if (iconFile != null && iconFile.exists()) {
                        try {
                            svgContent = new String(java.nio.file.Files.readAllBytes(iconFile.toPath()), StandardCharsets.UTF_8);
                            if (!svgContent.contains(" width=") && !svgContent.contains(" height=")) {
                                svgContent = svgContent.replaceFirst("<svg", "<svg width=\"28\" height=\"28\"");
                            }
                            // Delete the file after use
                            boolean deleted = iconFile.delete();
                            if (!deleted) {
                                System.out.println("Nepodařilo se smazat dočasný soubor ikony: " + iconFile.getAbsolutePath());
                                // Registering the file for deletion on exit
                                iconFile.deleteOnExit();
                            }
                        } catch (IOException e) {
                            System.err.println("Error while loading SVG: " + e.getMessage());
                        }
                    } else {
                        System.err.println("Icon not found: " + icon);
                    }
                }

                // Building HTML for price with consideration of firstYearFree attribute
                String priceHtml;
                if ("true".equalsIgnoreCase(firstYearFree)) {
                    priceHtml = "<p class=\"price\"><s>" + price + "</s></p><p class=\"price\" style=\"margin-bottom: 0;\">Na první rok k webu zdarma</p>";
                } else {
                    priceHtml = "<p class=\"price\">" + price + "</p>";
                }

                String itemCardHtml = "<td class=\"item-card\">" +
                        "<div class=\"icon\">" + svgContent + "</div>" +
                        "<h4 class=\"heading\">" + heading + "</h4>" +
                        "<p class=\"text\">" + text + "</p>" +
                        priceHtml +
                        "</td>";

                itemCardsList.add(itemCardHtml);
            }

            // Create table with rows of 2 cells each
            StringBuilder processedItemCards = new StringBuilder();

            for (int i = 0; i < itemCardsList.size(); i += 2) {
                processedItemCards.append("<tr>");

                // Adding 2 cells in each row, even empty ones
                for (int j = 0; j < 2; j++) {
                    int index = i + j;
                    if (index < itemCardsList.size()) {
                        processedItemCards.append(itemCardsList.get(index));
                    } else {
                        // Empty cell to maintain the layout
                        processedItemCards.append("<td></td>");
                    }
                }

                processedItemCards.append("</tr>");
            }

            // Wrapping the processed item cards in a table
            String finalItemCardsHtml = "<table class=\"item-cards\">" + processedItemCards + "</table>";

            itemCardsMatcher.appendReplacement(itemCardsSb, Matcher.quoteReplacement(finalItemCardsHtml));
        }

        itemCardsMatcher.appendTail(itemCardsSb);
        contentHtml = itemCardsSb.toString();

        // Wrap headings followed immediately by tables in a div
        contentHtml = contentHtml.replaceAll("(<h[1-6][^>]*>.*?</h[1-6]>)\\s*(<table class='cards')", "<div class='heading-with-table'>$1$2");
        contentHtml = contentHtml.replaceAll("(</table>)\\s*(<h[1-6][^>]*>)", "$1</div>$2");

        // Add PDF links to metadata for further processing
        StringBuilder pdfAttachmentsMetadata = new StringBuilder();
        if (!pdfLinksToAttach.isEmpty()) {
            pdfAttachmentsMetadata.append("<div id=\"pdf-attachments\" style=\"display:none;\">");
            for (String url : pdfLinksToAttach) {
                pdfAttachmentsMetadata.append("<a href=\"").append(escapeHtml(url)).append("\">").append(escapeHtml(url)).append("</a>");
            }
            pdfAttachmentsMetadata.append("</div>");
        }

        String contactsHtml = generateContactsHtml(assignees);

        String qrImageBase64 = generateQrCodeBase64(quoteLink);

        // Create complete HTML with styling
        return "<!DOCTYPE html><html><head>" +
                "<link rel=\"stylesheet\" href=\"" + cssPath + "\">" +
                "</head><body>" +
                "<img id=\"logo\" src=\"" + logoPath + "\" alt=\"Logo Denali studio\">" +
                "<h3 id=\"subtitle\">" + escapeHtml(subtitle) + "</h3>" +
                "<h1 id=\"title\">" + escapeHtml(title) + "</h1>" +
                "<div class=\"content\">" + contentHtml + "</div>" +
                "<h2>QR kód pro přijetí nabídky</h2><div><a href=\"" + quoteLink + "\"><img src=\"data:image/png;base64," + qrImageBase64 + "\"></a></div>" +
                contactsHtml +
                pdfAttachmentsMetadata +
                "</body></html>";
    }

    private String getSvgContent(String iconName) {
        try {
            String resourcePath = System.getProperty("resources.path", "resources");
            File svgFile = new File(resourcePath + "/static/fa-regular/" + iconName + ".svg");

            if (!svgFile.exists()) {
                // Fallback to the development path
                svgFile = new File("src/main/resources/static/fa-regular/" + iconName + ".svg");
                if (!svgFile.exists()) {
                    System.out.println("SVG file not found: " + svgFile.getAbsolutePath());
                    return "";
                }
            }

            // Read SVG file content
            java.nio.file.Path path = svgFile.toPath();
            String svgContent = new String(java.nio.file.Files.readAllBytes(path), StandardCharsets.UTF_8);

            // Add width and height attributes to the SVG tag if they don't already exist
            if (!svgContent.contains(" width=") && !svgContent.contains(" height=")) {
                svgContent = svgContent.replaceFirst("<svg", "<svg width=\"28\" height=\"28\"");
            }

            return svgContent;
        } catch (Exception e) {
            System.err.println("Error loading SVG: " + e.getMessage());
            return "";
        }
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private Map<String, String> getItemCardAttributes(String library) {
        Map<String, String> result = new HashMap<>();
        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/list/" + itemCardsListId + "/task")
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
                                    && itemCardsIdCfId.equals(cf.get("id").asText())
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
                                    if (itemCardsIconTypeCfId.equals(fieldId)) {
                                        if (Objects.equals(fieldValue, "0")) {
                                            fieldValue = "fa";
                                        } else if (Objects.equals(fieldValue, "1")) {
                                            fieldValue = "custom";
                                        }
                                        result.put("iconType", fieldValue);
                                    } else if (itemCardsIconCfId.equals(fieldId)) {
                                        result.put("icon", fieldValue);
                                    } else if (itemCardsTextCfId.equals(fieldId)) {
                                        result.put("text", fieldValue);
                                    } else if (itemCardsPriceCfId.equals(fieldId)) {
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

    private File getCustomIcon(String iconName) {
        Request request = new Request.Builder()
                .url("https://api.clickup.com/api/v2/list/" + iconsListId + "/task")
                .addHeader("accept", "application/json")
                .addHeader("Authorization", clickUpApiKey)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }

            String responseBody = response.body().string();
            System.out.println("Hledám ikonu: " + iconName);

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode tasks = rootNode.get("tasks");

            if (tasks != null && tasks.isArray()) {
                for (JsonNode task : tasks) {
                    // Check if the task name matches the icon name
                    String taskName = task.get("name").asText().trim();
                    System.out.println("Checking icon: \"" + taskName + "\" vs \"" + iconName + "\"");

                    if (taskName.equalsIgnoreCase(iconName)) {
                        System.out.println("Found the icon: " + taskName);

                        // The task found, now we look for the custom field with the icon
                        JsonNode customFields = task.get("custom_fields");
                        if (customFields != null && customFields.isArray()) {
                            for (JsonNode cf : customFields) {
                                if (cf.has("id") && iconsIconCfId.equals(cf.get("id").asText())) {
                                    // Check if a custom field named "value" exists and contains data
                                    if (cf.has("value") && !cf.get("value").isNull() && cf.get("value").isArray()) {
                                        JsonNode attachmentInfo = cf.get("value").get(0);

                                        // Get the URL to download the icon
                                        if (attachmentInfo.has("url")) {
                                            String iconUrl = attachmentInfo.get("url").asText();
                                            System.out.println("Found the icon URL: " + iconUrl);

                                            // Download the icon and create an SVG file
                                            return downloadIcon(iconUrl, iconName);
                                        }
                                    }
                                }
                            }
                        }
                        break; // Break after finding the first match
                    }
                }
            }

            System.err.println("Icon not found: " + iconName);
            return null;
        } catch (Exception e) {
            System.err.println("Error while loading the icon " + iconName + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private File downloadIcon(String iconUrl, String iconName) {
        try {
            Path tempDir = Paths.get(tempDirPath);
            Files.createDirectories(tempDir);

            // Temporary file for the icon
            File iconFile = tempDir.resolve(iconName + ".svg").toFile();

            // Downloading the icon from the URL
            Request request = new Request.Builder()
                    .url(iconUrl)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Error while downloading the icon, code: " + response);
                }

                // Save the icon to the temporary file
                try (InputStream inputStream = response.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(iconFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }

                return iconFile;
            }
        } catch (Exception e) {
            System.err.println("Error while downloading icon " + iconName + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private String generateContactsHtml(ArrayList<String> assignees) {
        if (assignees == null || assignees.isEmpty()) {
            return ""; // No contacts to display
        }

        Map<String, Map<String, String>> contacts = new HashMap<>();
        StringBuilder output = new StringBuilder();

        try {
            Request request = new Request.Builder()
                    .url("https://api.clickup.com/api/v2/list/" + contactsListId + "/task")
                    .addHeader("accept", "application/json")
                    .addHeader("Authorization", clickUpApiKey)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }

                String responseBody = response.body().string();
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(responseBody);
                JsonNode tasks = rootNode.get("tasks");

                if (tasks != null && tasks.isArray()) {
                    for (JsonNode task : tasks) {
                        // Get the initals from the custom field
                        String initials = null;
                        String position = null;
                        String phoneNumber = null;
                        String email = null;
                        String fullName = task.get("name").asText();

                        JsonNode customFields = task.get("custom_fields");
                        if (customFields != null && customFields.isArray()) {
                            for (JsonNode customField : customFields) {
                                String fieldId = customField.get("id").asText();
                                JsonNode value = customField.get("value");

                                if (value != null && !value.isNull()) {
                                    if (fieldId.equals(contactsInitialsCfId)) {
                                        initials = value.asText();
                                    } else if (fieldId.equals(contactsPositionCfId)) {
                                        position = value.asText();
                                    }else if (fieldId.equals(contactsPhoneCfId)) {
                                        phoneNumber = value.asText();
                                    } else if (fieldId.equals(contactsEmailCfId)) {
                                        email = value.asText();
                                    }
                                }
                            }
                        }

                        // If we found initials and they are in the assignees list, we save the contact
                        if (initials != null && assignees.contains(initials)) {
                            Map<String, String> contactInfo = new HashMap<>();
                            contactInfo.put("name", fullName);
                            contactInfo.put("position", position);
                            contactInfo.put("phone", phoneNumber);
                            contactInfo.put("email", email);
                            contacts.put(initials, contactInfo);
                        }
                    }
                }

                // Number of contacts to display
                int foundContacts = 0;
                for (String initials : assignees) {
                    if (contacts.containsKey(initials)) {
                        foundContacts++;
                    }
                }

                // Dynamic heading based on the number of contacts
                if (foundContacts == 0) {
                    return ""; // No contacts to display
                } else if (foundContacts == 1) {
                    output.append("<h2>Kontaktní osoba</h2>");
                } else {
                    output.append("<h2>Kontaktní osoby</h2>");
                }

                // Generate HTML for contacts
                output.append("<table class=\"contacts\">");

                // Create contact list from assignees
                List<Map<String, String>> contactList = new ArrayList<>();
                for (String initials : assignees) {
                    Map<String, String> contactInfo = contacts.get(initials);
                    if (contactInfo != null) {
                        contactList.add(contactInfo);
                    }
                }

                // Loop contacts in groups of three
                for (int i = 0; i < contactList.size(); i += 3) {
                    output.append("<tr>");

                    // Always generate three cells per row - even empty ones
                    for (int j = 0; j < 3; j++) {
                        int index = i + j;

                        if (index < contactList.size()) {
                            Map<String, String> contactInfo = contactList.get(index);
                            output.append("<td class=\"contact\">");
                            output.append("<h4>").append(escapeHtml(contactInfo.get("name"))).append("</h4>");

                            String position = contactInfo.get("position");
                            if (position != null && !position.isEmpty()) {
                                output.append("<p class=\"position\">").append(escapeHtml(position)).append("</p>");
                            }

                            String phone = contactInfo.get("phone");
                            if (phone != null && !phone.isEmpty()) {
                                String cleanPhone = phone.replaceAll("\\s+", "").replaceAll("[()\\-–]", "");
                                output.append("<p class=\"link\"><a href=\"tel:").append(cleanPhone).append("\">").append(escapeHtml(phone)).append("</a></p>");
                            }

                            String email = contactInfo.get("email");
                            if (email != null && !email.isEmpty()) {
                                output.append("<p class=\"link\"><a href=\"mailto:").append(escapeHtml(email)).append("\">").append(escapeHtml(email)).append("</a></p>");
                            }
                            output.append("</td>");
                        } else {
                            // Empty cell to maintain the layout
                            output.append("<td></td>");
                        }
                    }

                    output.append("</tr>");
                }

                output.append("</table>");
            }
        } catch (Exception e) {
            System.err.println("Error generating contacts HTML: " + e.getMessage());
            e.printStackTrace();
            return ""; // Return empty string in case of error
        }

        return output.toString();
    }

    private String generateQrCodeBase64(String url) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0); // 0 = žádný okraj

            BitMatrix bitMatrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 200, 200, hints);

            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(qrImage, "PNG", outputStream);
            byte[] imageBytes = outputStream.toByteArray();

            return Base64.getEncoder().encodeToString(imageBytes);
        } catch (Exception e) {
            System.err.println("Error while generating QR code: " + e.getMessage());
            return "";
        }
    }
}