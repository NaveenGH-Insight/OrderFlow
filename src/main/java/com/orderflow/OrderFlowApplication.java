package com.orderflow;

import com.orderflow.market.PackagedStartup;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OrderFlowApplication {
    public static void main(String[] args) {
        if (System.getProperty("jpackage.app-path") == null) {
            new SpringApplicationBuilder(OrderFlowApplication.class).headless(false).run(args);
            return;
        }
        System.setProperty("java.awt.headless", "false");
        try {
            PackagedStartup startup = PackagedStartup.claim();
            if (!startup.ownsInstance()) {
                try (startup) {
                    startup.openRunningInstance();
                }
                return;
            }
            try {
                var context = new SpringApplicationBuilder(OrderFlowApplication.class).headless(false).run(args);
                context.addApplicationListener(event -> {
                    if (event instanceof org.springframework.context.event.ContextClosedEvent) {
                        try {
                            startup.close();
                        } catch (java.io.IOException exception) {
                            exception.printStackTrace(System.err);
                        }
                    }
                });
            } catch (Exception exception) {
                startup.close();
                throw exception;
            }
        } catch (Exception exception) {
            PackagedStartup.showFailure(exception);
        }
    }
}
