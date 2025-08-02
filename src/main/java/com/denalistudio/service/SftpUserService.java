package com.denalistudio.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class SftpUserService {
    private void executeCommand(String[] command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();

        // Read both output and error streams
        String output = new String(process.getInputStream().readAllBytes());
        String errors = new String(process.getErrorStream().readAllBytes());

        int exitCode = process.waitFor();
        System.out.println("Command: " + String.join(" ", command));
        System.out.println("Exit code: " + exitCode);

        if (!output.isEmpty()) {
            System.out.println("Output: " + output);
        }

        if (!errors.isEmpty()) {
            System.out.println("Errors: " + errors);
        }

        if (exitCode != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", command));
        }
    }

    public Map<String, String> createUser(String username) throws IOException, InterruptedException, NoSuchAlgorithmException {
        String password = UUID.randomUUID().toString().replace("-", ""); // Random password
        password += password.substring(0, 4); // Append first 4 characters to the end
        String baseDir = "/var/wordpress_backups/" + username;

        try {
            // Commands to create and setup the user
            String[][] commands = {
                    {"/usr/bin/sudo", "/usr/sbin/useradd", "-N", "-m", "-d", baseDir, "-s", "/usr/sbin/nologin", username},
                    {"/usr/bin/sudo", "/usr/bin/chown", "root:root", baseDir},
                    {"/usr/bin/sudo", "/usr/bin/chmod", "755", baseDir},
                    {"/usr/bin/sudo", "/usr/sbin/usermod", "-aG", "wordpress_backups", username},
                    {"/usr/bin/sudo", "/usr/bin/chown", username + ":wordpress_backups", baseDir}
            };

            // Execute each command
            for (String[] command : commands) {
                executeCommand(command);
            }

            // Set password using chpasswd
            Process chpasswdProcess = new ProcessBuilder("sudo", "chpasswd").start();
            OutputStream os = chpasswdProcess.getOutputStream();
            os.write((username + ":" + password + "\n").getBytes());
            os.close();

            int exitCode = chpasswdProcess.waitFor();
            if (exitCode != 0) {
                System.out.println("Setting password failed.");
            } else {
                System.out.println("Password set successfully.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        Map<String, String> result = new HashMap<>();
        result.put("username", username);
        result.put("password", password);

        return result;
    }
}