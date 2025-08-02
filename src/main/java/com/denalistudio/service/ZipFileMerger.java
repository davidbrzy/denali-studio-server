package com.denalistudio.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class ZipFileMerger {
    public static void mergeFile(Path firstPartPath) throws IOException {
        // Get parent directory and base filename
        Path parentDir = firstPartPath.getParent();
        String originalFileName = getBaseName(firstPartPath.getFileName().toString());

        // Find all parts and sort them numerically
        List<Path> parts = Files.list(parentDir)
                .filter(p -> p.getFileName().toString().startsWith(originalFileName + ".part"))
                .sorted(Comparator.comparingInt(p -> extractPartNumber(p.getFileName().toString())))
                .collect(Collectors.toList());

        // Create merged output file
        Path mergedFile = parentDir.resolve(originalFileName);
        try (OutputStream out = Files.newOutputStream(mergedFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            // Concatenate all parts
            for (Path part : parts) {
                try (InputStream in = Files.newInputStream(part)) {
                    byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }
        }

        System.out.println("Successfully merged " + parts.size() + " parts into: " + mergedFile);
    }

    public static String getBaseName(String partName) {
        return partName.replaceAll("\\.part\\d+$", "");
    }

    private static int extractPartNumber(String filename) {
        return Integer.parseInt(filename.replaceAll(".*\\.part(\\d+)$", "$1"));
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java ZipFileMerger <path-to-first-part>");
            return;
        }

        try {
            mergeFile(Paths.get(args[0]));
        } catch (IOException e) {
            System.err.println("Error merging files: " + e.getMessage());
            e.printStackTrace();
        }
    }
}