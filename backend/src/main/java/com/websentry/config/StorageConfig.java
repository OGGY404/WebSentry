package com.websentry.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.websentry.repository.InMemoryScanStore;
import com.websentry.repository.MongoScanStore;
import com.websentry.repository.ScanStore;

/** Chooses where scans are saved. */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public ScanStore scanStore(WebSentryProperties properties, ObjectProvider<MongoTemplate> mongoTemplate) {
        if (!properties.getMongodb().isEnabled()) {
            log.warn("MONGODB_URI is not set. Using the in-memory store: scans will NOT be saved.");
            return new InMemoryScanStore();
        }
        MongoTemplate template = mongoTemplate.getIfAvailable();
        if (template == null) {
            log.error("MongoDB is configured but no MongoTemplate was created. Falling back to in-memory.");
            return new InMemoryScanStore();
        }
        log.info("Using MongoDB for scan storage.");
        return new MongoScanStore(template);
    }
}
