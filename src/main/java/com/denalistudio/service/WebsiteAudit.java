package com.denalistudio.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.*;

public class WebsiteAudit {

    // Hlavní metoda pro generování auditu
    public static AuditResult generateWebsiteAudit(String domain) {
        String url = "https://" + domain;
        AuditResult result = new AuditResult();

        try {
            Document doc = Jsoup.connect(url).get();

            // 1. Extrakce textového obsahu s tagy
            result.setTextContent(extractTextContentWithTags(doc));

            // 2. Analýza img tagů
            result.setImageAnalysis(analyzeImgTags(doc));

            // 3. Analýza odkazů
            result.setLinkAnalysis(analyzeLinkHrefs(doc, url));

        } catch (IOException e) {
            result.setError("Nepodařilo se načíst stránku: " + e.getMessage());
        }

        return result;
    }

    private static List<TextItem> extractTextContentWithTags(Document doc) {
        List<TextItem> results = new ArrayList<>();
        Set<String> seenEntries = new HashSet<>();
        Set<String> formattingTags = Set.of("strong", "em", "b", "i", "small", "ins", "mark", "del", "sub", "sup");

        for (Element element : doc.select("*")) {
            // Přeskočit formátovací tagy
            if (formattingTags.contains(element.tagName())) {
                continue;
            }

            String ownText = element.ownText().trim();
            boolean hasDirectText = !ownText.isEmpty();

            int nonFormattingChildrenWithText = 0;
            for (Element child : element.children()) {
                if (!child.text().trim().isEmpty() && !formattingTags.contains(child.tagName())) {
                    nonFormattingChildrenWithText++;
                }
            }

            if (hasDirectText || (nonFormattingChildrenWithText == 0 && !element.text().trim().isEmpty())) {
                String tag = element.tagName();
                String fullText = element.text().trim();
                String entryKey = tag + "|" + fullText;

                if (!seenEntries.contains(entryKey)) {
                    seenEntries.add(entryKey);
                    results.add(new TextItem(tag, fullText));
                }
            }
        }

        return results;
    }

    private static ImageAnalysis analyzeImgTags(Document doc) {
        ImageAnalysis analysis = new ImageAnalysis();
        Elements imgs = doc.select("img");

        analysis.setTotalImages(imgs.size());
        int altsPresent = 0;

        for (Element img : imgs) {
            String src = img.attr("abs:src");
            String alt = img.attr("alt");

            if (!alt.isEmpty()) {
                altsPresent++;
            }
        }

        analysis.setImagesWithAlt(altsPresent);
        analysis.setImagesWithoutAlt(imgs.size() - altsPresent);
        analysis.setMajorityHasAlt(altsPresent > imgs.size() / 2);

        return analysis;
    }

    private static LinkAnalysis analyzeLinkHrefs(Document doc, String baseUrl) {
        LinkAnalysis analysis = new LinkAnalysis();
        Elements links = doc.select("a");

        List<LinkItem> linkItems = new ArrayList<>();
        List<String> nonHttpsLinks = new ArrayList<>();

        int totalLinks = 0;
        int filteredLinks = 0;
        int nonHttpsLinksCount = 0;
        int homepageLinks = 0;
        int emptyAnchors = 0;

        for (Element link : links) {
            String href = link.attr("abs:href").trim();
            String originalHref = link.attr("href").trim();
            String text = link.text().trim();

            if (!originalHref.isEmpty()) {
                totalLinks++;

                if (originalHref.startsWith("mailto:") || originalHref.startsWith("tel:")) {
                    filteredLinks++;
                } else if (originalHref.equals("#")) {
                    emptyAnchors++;
                } else if (originalHref.equals("/") || originalHref.equals(baseUrl) || originalHref.equals(baseUrl + "/")) {
                    homepageLinks++;
                } else {
                    LinkItem item = new LinkItem(originalHref, text);

                    if (href.startsWith("http:") || (!href.startsWith("https:") &&
                            !originalHref.startsWith("#") && !originalHref.startsWith("/"))) {
                        nonHttpsLinks.add(originalHref);
                        nonHttpsLinksCount++;
                        item.setSecure(false);
                    } else {
                        item.setSecure(true);
                    }

                    linkItems.add(item);
                }
            }
        }

        analysis.setTotalLinks(totalLinks);
        analysis.setFilteredLinks(filteredLinks);
        analysis.setNonHttpsLinks(nonHttpsLinksCount);
        analysis.setHomepageLinks(homepageLinks);
        analysis.setEmptyAnchors(emptyAnchors);
        analysis.setLinks(linkItems);
        analysis.setNonHttpsLinksList(nonHttpsLinks);

        return analysis;
    }

    // Třídy pro reprezentaci dat

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuditResult {
        private List<TextItem> textContent;
        private ImageAnalysis imageAnalysis;
        private LinkAnalysis linkAnalysis;
        private String error;

        // Gettery a settery
        public List<TextItem> getTextContent() { return textContent; }
        public void setTextContent(List<TextItem> textContent) { this.textContent = textContent; }
        public ImageAnalysis getImageAnalysis() { return imageAnalysis; }
        public void setImageAnalysis(ImageAnalysis imageAnalysis) { this.imageAnalysis = imageAnalysis; }
        public LinkAnalysis getLinkAnalysis() { return linkAnalysis; }
        public void setLinkAnalysis(LinkAnalysis linkAnalysis) { this.linkAnalysis = linkAnalysis; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    public static class TextItem {
        private String tag;
        private String text;

        public TextItem(String tag, String text) {
            this.tag = tag;
            this.text = text;
        }

        public String getTag() { return tag; }
        public String getText() { return text; }
    }

    public static class ImageAnalysis {
        private int totalImages;
        private int imagesWithAlt;
        private int imagesWithoutAlt;
        private boolean majorityHasAlt;
        private List<ImageItem> images;

        // Gettery a settery
        public int getTotalImages() { return totalImages; }
        public void setTotalImages(int totalImages) { this.totalImages = totalImages; }
        public int getImagesWithAlt() { return imagesWithAlt; }
        public void setImagesWithAlt(int imagesWithAlt) { this.imagesWithAlt = imagesWithAlt; }
        public int getImagesWithoutAlt() { return imagesWithoutAlt; }
        public void setImagesWithoutAlt(int imagesWithoutAlt) { this.imagesWithoutAlt = imagesWithoutAlt; }
        public boolean isMajorityHasAlt() { return majorityHasAlt; }
        public void setMajorityHasAlt(boolean majorityHasAlt) { this.majorityHasAlt = majorityHasAlt; }
        public List<ImageItem> getImages() { return images; }
        public void setImages(List<ImageItem> images) { this.images = images; }
    }

    public static class ImageItem {
        private String src;
        private String alt;

        public ImageItem(String src, String alt) {
            this.src = src;
            this.alt = alt;
        }

        public String getSrc() { return src; }
        public String getAlt() { return alt; }
    }

    public static class LinkAnalysis {
        private int totalLinks;
        private int filteredLinks;
        private int nonHttpsLinks;
        private int homepageLinks;
        private int emptyAnchors;
        private List<LinkItem> links;
        private List<String> nonHttpsLinksList;

        // Gettery a settery
        public int getTotalLinks() { return totalLinks; }
        public void setTotalLinks(int totalLinks) { this.totalLinks = totalLinks; }
        public int getFilteredLinks() { return filteredLinks; }
        public void setFilteredLinks(int filteredLinks) { this.filteredLinks = filteredLinks; }
        public int getNonHttpsLinks() { return nonHttpsLinks; }
        public void setNonHttpsLinks(int nonHttpsLinks) { this.nonHttpsLinks = nonHttpsLinks; }
        public int getHomepageLinks() { return homepageLinks; }
        public void setHomepageLinks(int homepageLinks) { this.homepageLinks = homepageLinks; }
        public int getEmptyAnchors() { return emptyAnchors; }
        public void setEmptyAnchors(int emptyAnchors) { this.emptyAnchors = emptyAnchors; }
        public List<LinkItem> getLinks() { return links; }
        public void setLinks(List<LinkItem> links) { this.links = links; }
        public List<String> getNonHttpsLinksList() { return nonHttpsLinksList; }
        public void setNonHttpsLinksList(List<String> nonHttpsLinksList) { this.nonHttpsLinksList = nonHttpsLinksList; }
    }

    public static class LinkItem {
        private String href;
        private String text;
        private boolean secure;

        public LinkItem(String href, String text) {
            this.href = href;
            this.text = text;
        }

        public String getHref() { return href; }
        public String getText() { return text; }
        public boolean isSecure() { return secure; }
        public void setSecure(boolean secure) { this.secure = secure; }
    }
}