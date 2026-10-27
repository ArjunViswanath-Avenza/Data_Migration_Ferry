package com.dataferry;

import com.dataferry.core.FerryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Data Migration Ferry: carries customer data from any source system into Temenos Digital
 * through the product's DataMigration APIs, and proves what arrived.
 *
 * Areas: Boarding (load, map, validate, plan) · Crossing (run) · Manifest (evidence)
 * · Return Crossing (rollback). Everything bank- or target-specific lives in config/.
 */
@SpringBootApplication
@EnableConfigurationProperties(FerryProperties.class)
public class DataFerryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataFerryApplication.class, args);
    }
}
