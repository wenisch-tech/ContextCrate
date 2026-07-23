package tech.wenisch.harvex.service;

import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class RunLogger {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public void log(UUID runId, String message) {
        try {
            Path logFile = Paths.get("logs", "run_" + runId + ".log");
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String logLine = "[" + timestamp + "] " + message;

            // Create parent directories if they don't exist
            if (!Files.exists(logFile.getParent())) {
                Files.createDirectories(logFile.getParent());
            }

            // Append to log file
            Files.writeString(logFile, logLine + System.lineSeparator(),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Fallback to console logging if file logging fails
            System.err.println("Failed to write to run log: " + e.getMessage());
        }
    }

    public void log(UUID runId, String level, String message) {
        log(runId, "[" + level + "] " + message);
    }
}