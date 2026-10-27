package com.dataferry.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/** The ferry.* settings from application.yml (all overridable by environment variables). */
@ConfigurationProperties(prefix = "ferry")
public class FerryProperties {

    private String version = "0.1.0";
    /** Folder holding environments.json, reference-data.json, mapping-profiles/ … */
    private String configDir = "../config";
    /** Folder for the embedded database. */
    private String dataDir = "./data";
    /** Target environment id; blank means "active" from environments.json. */
    private String environment = "";
    private String corsOrigin = "http://localhost:5190";

    public Path configPath() { return Path.of(configDir).toAbsolutePath().normalize(); }
    public Path dataPath() { return Path.of(dataDir).toAbsolutePath().normalize(); }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getConfigDir() { return configDir; }
    public void setConfigDir(String configDir) { this.configDir = configDir; }
    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getCorsOrigin() { return corsOrigin; }
    public void setCorsOrigin(String corsOrigin) { this.corsOrigin = corsOrigin; }
}
