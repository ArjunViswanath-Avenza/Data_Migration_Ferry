package com.dataferry.core;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets the console call the API directly when it is not going through the Vite proxy. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FerryProperties props;

    public WebConfig(FerryProperties props) { this.props = props; }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.getCorsOrigin())
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .exposedHeaders("Content-Disposition");
    }
}
