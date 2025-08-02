package com.denalistudio.service;

import java.io.*;
import java.nio.file.*;

public class ZipFileSplitter {
    public static void splitFile(Path source, Path outputDir, long partSize) throws IOException {
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        try (InputStream inputStream = Files.newInputStream(source)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            int partNumber = 1;

            while (true) {
                // Create the part file in the specified output directory
                Path partFile = outputDir.resolve(source.getFileName().toString() + ".part" + partNumber);
                try (OutputStream outputStream = Files.newOutputStream(partFile)) {
                    long bytesWritten = 0;
                    while (bytesWritten < partSize && (bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        bytesWritten += bytesRead;
                    }
                    if (bytesWritten == 0) {
                        Files.delete(partFile);
                        break;
                    }
                }
                partNumber++;
            }
        }
    }
}