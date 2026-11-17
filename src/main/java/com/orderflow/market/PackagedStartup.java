package com.orderflow.market;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

public final class PackagedStartup implements AutoCloseable {
    private static final URI TERMINAL = URI.create("http://127.0.0.1:8080");
    private final FileChannel channel;
    private final FileLock lock;

    private PackagedStartup(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static PackagedStartup claim() throws IOException {
        Path directory = Path.of(System.getProperty("user.home"), ".orderflow");
        Files.createDirectories(directory);
        FileChannel channel = FileChannel.open(directory.resolve("desktop.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
        return new PackagedStartup(channel, lock);
    }

    public boolean ownsInstance() { return lock != null; }

    public void openRunningInstance() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        HttpRequest request = HttpRequest.newBuilder(TERMINAL.resolve("/api/health"))
                .timeout(Duration.ofSeconds(1)).GET().build();
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"application\":\"OrderFlow\"")) {
                    if (!Desktop.isDesktopSupported()) throw new IOException("Windows desktop browsing is unavailable.");
                    Desktop.getDesktop().browse(TERMINAL);
                    return;
                }
                lastFailure = new IOException("The service on port 8080 did not identify itself as OrderFlow.");
            } catch (IOException exception) {
                lastFailure = exception;
            }
            Thread.sleep(1_000);
        }
        throw new IOException("Another OrderFlow process holds the desktop lock but its terminal is not ready.", lastFailure);
    }

    public static void showFailure(Exception exception) {
        exception.printStackTrace(System.err);
        JOptionPane.showMessageDialog(null,
                "OrderFlow could not start: " + exception.getMessage()
                        + "\n\nCheck " + Path.of(System.getProperty("user.home"), ".orderflow", "logs", "orderflow.log")
                        + "\nPort 8080 must be available. Quit an older OrderFlow instance before upgrading.",
                "OrderFlow startup problem", JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void close() throws IOException {
        if (lock != null && lock.isValid()) lock.release();
        channel.close();
    }
}
