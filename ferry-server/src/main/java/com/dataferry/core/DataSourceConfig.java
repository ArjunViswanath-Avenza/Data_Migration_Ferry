package com.dataferry.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * The embedded store (run history, ID register, staged batches). The database file always lives
 * in the absolute data folder, whatever relative path FERRY_DATA_DIR was given as; H2 refuses
 * implicitly relative paths.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource(FerryProperties props) {
        Path db = props.dataPath().resolve("ferry");
        String url = "jdbc:h2:file:" + db.toString().replace('\\', '/')
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        log.info("Run store: {}", db);
        return DataSourceBuilder.create().driverClassName("org.h2.Driver").url(url)
                .username("ferry").password("").build();
    }
}
