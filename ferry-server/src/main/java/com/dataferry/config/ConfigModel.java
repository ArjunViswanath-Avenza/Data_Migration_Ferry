package com.dataferry.config;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.*;

/**
 * Typed shapes of the JSON files in config/. Every class rejects keys it does not know
 * (except notes starting with "_"), so a typo in config stops startup with a clear message
 * instead of being silently ignored.
 */
public final class ConfigModel {

    private ConfigModel() {}

    /**
     * Base for every config shape: unknown keys are an error, "_comment"-style notes are allowed
     * and kept, so writing a file back (assumptions.json) does not lose them.
     */
    public abstract static class Strict {
        private final Map<String, Object> notes = new LinkedHashMap<>();

        @JsonAnySetter
        public void unknown(String key, Object value) {
            if (key != null && key.startsWith("_")) { notes.put(key, value); return; }
            throw new IllegalArgumentException("Unknown key '" + key + "'");
        }

        @JsonAnyGetter
        public Map<String, Object> notes() { return notes; }
    }

    // ---------------- environments.json ----------------

    public static class Environments extends Strict {
        public String active;
        public List<TargetEnvironment> environments = new ArrayList<>();
    }

    public static class TargetEnvironment extends Strict {
        public String id;
        public String label;
        /** Which target adapter talks to this environment. Only "temenos-datamigration" exists today. */
        public String target = "temenos-datamigration";
        /** Fabric login address (…/login). */
        public String authUrl;
        /** API host; operations are called at hostUrl + basePath + path. */
        public String hostUrl;
        /** Used when a contract record does not carry its own legal entity. */
        public String defaultLegalEntityId;
        /** Master switch: nothing is ever sent to this environment while false. */
        public boolean liveCalls = false;
        /** Names of the environment variables holding the app key and secret (never the values). */
        public String appKeyVariable;
        public String appSecretVariable;
        public String notes;
    }

    // ---------------- reference-data.json ----------------

    public static class ReferenceData extends Strict {
        public List<ServiceDefinition> serviceDefinitions = new ArrayList<>();
        public List<Role> roles = new ArrayList<>();
        /** Named lookup tables for later steps, e.g. sourceTxnCode -> actionId. */
        public Map<String, Map<String, String>> codeMaps = new LinkedHashMap<>();
    }

    public static class ServiceDefinition extends Strict {
        /** The value source files use, e.g. RETAIL. */
        public String ref;
        /** The id the target system uses, e.g. a service definition UUID. */
        public String targetId;
        public String label;
        /** RETAIL or BUSINESS; business contracts get signatory groups and approval rules. */
        public String contractType = "RETAIL";
    }

    public static class Role extends Strict {
        public String ref;
        public String targetId;
        public String label;
    }

    // ---------------- assumptions.json ----------------

    public static class Assumptions extends Strict {
        public String version;
        public List<Assumption> assumptions = new ArrayList<>();
    }

    public static class Assumption extends Strict {
        public String id;
        public String title;
        public String question;
        /** Where the open question came from (document section, Postman request). */
        public String evidence;
        public String defaultChoice;
        public String choice;
        public List<Option> options = new ArrayList<>();
        /** Which steps or checks read this assumption. */
        public List<String> affects = new ArrayList<>();
        /** ASSUMED (our default) · CONFIRMED (answered by vendor or bank) · OVERRIDDEN (changed by us). */
        public String status = "ASSUMED";
        public String note;
    }

    public static class Option extends Strict {
        public String value;
        public String label;
    }

    // ---------------- validation-rules.json ----------------

    public static class ValidationRules extends Strict {
        public String version;
        public List<Rule> rules = new ArrayList<>();
    }

    public static class Rule extends Strict {
        public String id;
        public String recordType;
        /** required · allowed · regex · unique · length · date · conditional-required */
        public String type;
        /** ERROR blocks the record; WARN is reported only. */
        public String severity = "ERROR";
        public List<String> fields = new ArrayList<>();
        public String field;
        public String pattern;
        public Integer min;
        public Integer max;
        /** For unique: uniqueness is checked within each value of this field (e.g. per contractRef). */
        public String scope;
        public Map<String, String> when;
        public List<String> requires = new ArrayList<>();
        public String message;
    }

    // ---------------- target-temenos.json ----------------

    public static class TemenosTarget extends Strict {
        public String basePath = "/services/DataMigration";
        public Map<String, Operation> operations = new LinkedHashMap<>();
        public ResponseRules response = new ResponseRules();
        /** CreateVirtualUser fields the vendor document marks as mandatory. */
        public List<String> virtualUserMandatory = new ArrayList<>();
        /** Characters the vendor accepts in signatory group names (ERR_21023). */
        public String signatoryGroupNamePattern;
        public Map<String, String> errorCatalogue = new LinkedHashMap<>();
    }

    public static class Operation extends Strict {
        public String path;
        public String label;
    }

    public static class ResponseRules extends Strict {
        public String statusField = "opstatus";
        public List<String> successValues = List.of("0");
        public boolean requireStatusField = true;
        public List<String> errorFields = List.of("dbpErrCode", "dbpErrMsg", "errmsg");
        public List<String> messageFields = List.of("dbpErrMsg", "errmsg", "FailureReason");
    }
}
