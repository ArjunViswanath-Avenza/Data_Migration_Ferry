package com.dataferry.boarding;

import com.dataferry.config.ConfigModel.Strict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/**
 * The canonical model every source maps onto (canonical-model.json, shipped with the server).
 * Record types are kept in file order: that is the order of the template workbook and of planning.
 */
@Component
public class CanonicalModel {

    private static final Logger log = LoggerFactory.getLogger(CanonicalModel.class);

    public static class Model extends Strict {
        public String version;
        public LinkedHashMap<String, RecordType> recordTypes = new LinkedHashMap<>();
    }

    public static class RecordType extends Strict {
        public String label;
        public String sheet;
        public List<String> keyFields = new ArrayList<>();
        public String contractField;
        public String description;
        public List<Field> fields = new ArrayList<>();
    }

    public static class Field extends Strict {
        public String name;
        public String label;
        public boolean required;
        /** date: yyyy-MM-dd after mapping */
        public String format;
        public List<String> allowed;
        public String note;
    }

    private final Model model;

    public CanonicalModel(ObjectMapper om) throws IOException {
        this.model = om.readValue(new ClassPathResource("canonical-model.json").getInputStream(), Model.class);
        log.info("Canonical model v{}: {}", model.version, model.recordTypes.keySet());
    }

    public Model model() { return model; }

    public Set<String> types() { return model.recordTypes.keySet(); }

    public RecordType type(String recordType) {
        RecordType t = recordType == null ? null : model.recordTypes.get(recordType.toUpperCase(Locale.ROOT));
        if (t == null) throw new IllegalArgumentException("Unknown record type '" + recordType + "'. Known: " + types());
        return t;
    }

    public List<String> requiredFields(String recordType) {
        return type(recordType).fields.stream().filter(f -> f.required).map(f -> f.name).toList();
    }

    public Optional<Field> field(String recordType, String name) {
        return type(recordType).fields.stream().filter(f -> f.name.equals(name)).findFirst();
    }
}
