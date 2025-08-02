package com.denalistudio.service;

import com.jcraft.jsch.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Vector;

public class SftpService {

    public String uploadWordPress(WordPressInstallationService.TaskData taskData) throws Exception {
        Session session = null;
        ChannelSftp sftpChannel = null;

        try {
            // Connect to SFTP server
            JSch jsch = new JSch();
            session = jsch.getSession(taskData.getUsername(), taskData.getServerUrl(), 22);
            session.setPassword(taskData.getPassword());
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect();

            Channel channel = session.openChannel("sftp");
            channel.connect();
            sftpChannel = (ChannelSftp) channel;

            // Chec whether there is any directory in /www/domains
            String domainsPath = "/www/domains";

            System.out.println("Checking if any directory exists in: " + domainsPath);

            if (anyDirectoryExists(sftpChannel, domainsPath)) {
                String message = "Directory already exists in /www/domains, skipping WordPress installation";
                System.out.println("SKIPPED: " + message);
                return message;
            }

            System.out.println("No directories found in /www/domains, proceeding with installation...");

            // Create the target directory for the task
            String targetPath = domainsPath + "/" + sanitizeTaskName(taskData.getTaskName());
            createDirectoryIfNotExists(sftpChannel, targetPath);

            System.out.println("Downloading WordPress...");
            String wordPressPath = downloadWordPress();

            System.out.println("Uploading WordPress files...");
            uploadDirectory(sftpChannel, wordPressPath, targetPath);

            System.out.println("Cleaning up temporary files...");
            deleteDirectory(Paths.get(wordPressPath));

            String successMessage = "WordPress was successfully uploaded to: " + targetPath;
            System.out.println("COMPLETED: " + successMessage);
            return successMessage;

        } finally {
            if (sftpChannel != null) sftpChannel.disconnect();
            if (session != null) session.disconnect();
        }
    }

    private void createDirectoryIfNotExists(ChannelSftp sftp, String path) throws SftpException {
        try {
            sftp.stat(path);
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                // Adresář neexistuje, vytvoříme ho
                String[] pathParts = path.split("/");
                String currentPath = "";

                for (String part : pathParts) {
                    if (!part.isEmpty()) {
                        currentPath += "/" + part;
                        try {
                            sftp.stat(currentPath);
                        } catch (SftpException ex) {
                            if (ex.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                                sftp.mkdir(currentPath);
                            }
                        }
                    }
                }
            } else {
                throw e;
            }
        }
    }

    private String downloadWordPress() throws IOException {
        // Create a temporary directory for downloading and extracting WordPress
        Path tempDir = Files.createTempDirectory("wordpress");
        Path zipFile = tempDir.resolve("wordpress.zip");
        Path extractDir = tempDir.resolve("extracted");

        try {
            // Download the latest WordPress ZIP file
            java.net.URL url = new java.net.URL("https://cs.wordpress.org/latest-cs_CZ.zip");
            try (InputStream in = url.openStream()) {
                Files.copy(in, zipFile);
            }

            // Extract the ZIP file
            extractZip(zipFile.toString(), extractDir.toString());

            // WordPress will extract to "wordpress" subdirectory
            Path wordpressDir = extractDir.resolve("wordpress");
            if (Files.exists(wordpressDir)) {
                return wordpressDir.toString();
            } else {
                return extractDir.toString();
            }

        } catch (Exception e) {
            throw new IOException("Error while downloading WordPress: " + e.getMessage(), e);
        }
    }

    private void uploadDirectory(ChannelSftp sftp, String localPath, String remotePath) throws SftpException, IOException {
        File localDir = new File(localPath);

        for (File file : localDir.listFiles()) {
            String remoteFilePath = remotePath + "/" + file.getName();

            if (file.isDirectory()) {
                try {
                    sftp.mkdir(remoteFilePath);
                } catch (SftpException e) {
                    // Directory already exists, we can ignore this error
                }
                uploadDirectory(sftp, file.getAbsolutePath(), remoteFilePath);
            } else {
                sftp.put(file.getAbsolutePath(), remoteFilePath);
            }
        }
    }

    private String sanitizeTaskName(String taskName) {
        // Keep dots and dashes, remove only problematic characters
        return taskName.replaceAll("[^a-zA-Z0-9._-]", "").toLowerCase();
    }

    private void extractZip(String zipFilePath, String destDir) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new FileInputStream(zipFilePath))) {

            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path destPath = Paths.get(destDir, entry.getName());

                if (!destPath.normalize().startsWith(Paths.get(destDir).normalize())) {
                    throw new IOException("Invalid path in the ZIP archive: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(destPath);
                } else {
                    Files.createDirectories(destPath.getParent());
                    try (OutputStream out = Files.newOutputStream(destPath)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            out.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // Ignore errors during deletion
                        }
                    });
        }
    }

    private boolean anyDirectoryExists(ChannelSftp sftp, String parentPath) {
        try {
            Vector<ChannelSftp.LsEntry> entries = sftp.ls(parentPath);

            for (ChannelSftp.LsEntry entry : entries) {
                String filename = entry.getFilename();

                // Skip "." and ".."
                if (!".".equals(filename) && !"..".equals(filename)) {
                    // Check if the entry is a directory
                    if (entry.getAttrs().isDir()) {
                        System.out.println("Found existing directory: " + parentPath + "/" + filename);
                        return true;
                    }
                }
            }

            return false;
        } catch (SftpException e) {
            System.out.println("Could not check directory: " + parentPath + " - " + e.getMessage());
            return false;
        }
    }
}