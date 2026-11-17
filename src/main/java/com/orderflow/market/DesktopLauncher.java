package com.orderflow.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

@Component
public class DesktopLauncher {
    private final ConfigurableApplicationContext applicationContext;

    public DesktopLauncher(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Value("${orderflow.open-browser:true}")
    private boolean openBrowser;

    @EventListener(ApplicationReadyEvent.class)
    public void openTerminal() {
        if (!openBrowser || !Desktop.isDesktopSupported()) return;
        try {
            Desktop.getDesktop().browse(URI.create("http://127.0.0.1:8080"));
        } catch (Exception exception) {
            System.err.println("Could not open the OrderFlow browser terminal: " + exception.getMessage());
        }
    }

    public void stopApplication() {
        applicationContext.close();
    }
}
