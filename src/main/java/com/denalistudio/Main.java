package com.denalistudio;

import com.denalistudio.service.BackupWatcher;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Bean
    CommandLineRunner startBackupWatcher() {
        return args -> {
            BackupWatcher watcher = new BackupWatcher("/var/wordpress_backups");
            watcher.startWatching();
        };
    }
}