package com.dataferry.boarding;

import com.dataferry.config.ConfigModel.Strict;
import com.dataferry.config.ConfigStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;

/**
 * Translates source rows into canonical records using mapping profiles from config/mapping-profiles.
 *
 * Each source column resolves, in order, through: an explicit entry in the profile's columns map,
 * a header equal to a canonical field name, or a header equal to a canonical field label. Matching
 * ignores case, spaces and punctuation, so "Primary CIF *", "primary_cif" and "PRIMARYCIF" match.
 * Columns that resolve to nothing are reported, never silently dropped.
 *
 * Per field, in order: trim → transform (trim · upper · lower · digits · list · date:pattern|pattern)
 * → value map → default when still empty.
 */
@Service
public class MappingService {

    /** One file in config/mapping-profiles. Adding a source system = adding one of these. */
    public static class MappingProfile extends Strict {
        public String id;
        public String sourceSystem;
        public String recordType;
        public String description;
        /** Preferred sheet in a multi-sheet workbook. */
        public String sheet;
        /** source column -> canonical field */
        public Map<String, String> columns = new LinkedHashMap<>();
        /** canonical field -> value used when the source leaves it empty */
        public Map<String, String> defaults = new LinkedHashMap<>();
        /** canonical field -> transform */
        public Map<String, String> transforms = new LinkedHashMap<>();
        /** canonical field -> { source value -> canonical value }, compared without case */
        public Map<String, Map<String, String>> valueMaps = new LinkedHashMap<>();
    }

    public record Mapped(List<StagedRecord> records, Map<String, String> resolvedColumns,
                         List<String> unmappedColumns, List<String> missingRequiredFields) {}

    private final ConfigStore config;
    private final CanonicalModel model;
    private final ObjectMapper om;

    public MappingService(ConfigStore config, CanonicalModel model, ObjectMapper om) {
        this.config = config;
        this.model = model;
        this.om = om;
    }

    /** Read fresh from disk on every call, so a profile edit applies without a restart. */
    public List<MappingProfile> profiles() {
        Path dir = config.get().configDir().resolve("mapping-profiles");
        List<MappingProfile> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                MappingProfile p;
                try { p = om.readValue(f.toFile(), MappingProfile.class); }
                catch (Exception e) {
                    Throwable root = e;
                    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                    throw new IllegalStateException("Cannot read mapping profile " + f.getFileName() + ": " + root.getMessage());
                }
                if (p.id == null || p.recordType == null)
                    throw new IllegalStateException("Mapping profile " + f.getFileName() + " needs an id and a recordType");
                model.type(p.recordType);
                for (String target : p.columns.values()) requireField(p, target);
                for (String target : p.defaults.keySet()) requireField(p, target);
                for (String target : p.transforms.keySet()) requireField(p, target);
                for (String target : p.valueMaps.keySet()) requireField(p, target);
                out.add(p);
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list mapping profiles in " + dir + ": " + e.getMessage());
        }
        out.sort(Comparator.comparing(p -> p.id));
        return out;
    }

    private void requireField(MappingProfile p, String field) {
        if (model.field(p.recordType, field).isEmpty())
            throw new IllegalStateException("Mapping profile " + p.id + " refers to '" + field
                    + "', which is not a field of " + p.recordType);
    }

    /** Explicit profile wins; else one for this source system and record type; else header matching alone. */
    public Optional<MappingProfile> resolveProfile(String profileId, String recordType, String sourceSystem) {
        List<MappingProfile> all = profiles();
        if (profileId != null && !profileId.isBlank()) {
            MappingProfile p = all.stream().filter(x -> x.id.equals(profileId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown mapping profile '" + profileId + "'"));
            if (!p.recordType.equalsIgnoreCase(recordType))
                throw new IllegalArgumentException("Profile " + p.id + " maps " + p.recordType + ", not " + recordType);
            return Optional.of(p);
        }
        if (sourceSystem == null || sourceSystem.isBlank()) return Optional.empty();
        return all.stream().filter(p -> p.recordType.equalsIgnoreCase(recordType)
                && sourceSystem.equalsIgnoreCase(p.sourceSystem)).findFirst();
    }

    public static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public Mapped map(SheetReader.Sheet sheet, String recordType, MappingProfile profile) {
        String type = recordType.toUpperCase(Locale.ROOT);
        CanonicalModel.RecordType rt = model.type(type);
        Map<String, String> resolved = resolveColumns(sheet.headers(), rt, profile);
        List<String> unmapped = sheet.headers().stream().filter(h -> !resolved.containsKey(h)).toList();

        List<StagedRecord> records = new ArrayList<>();
        int rowNum = 1;
        for (Map<String, String> row : sheet.rows()) {
            StagedRecord rec = new StagedRecord(type, ++rowNum);   // row 1 is the header, as in the spreadsheet
            row.forEach((k, v) -> rec.raw.put(k, v));
            for (Map.Entry<String, String> e : resolved.entrySet()) {
                String value = row.get(e.getKey());
                if (value == null || value.isBlank()) continue;
                String field = e.getValue();
                rec.set(field, mapValue(field, transform(field, value, profile), profile));
            }
            if (profile != null) profile.defaults.forEach((f, v) -> { if (!rec.has(f)) rec.set(f, v); });
            rec.key = String.join(" / ", rt.keyFields.stream().map(f -> Objects.toString(rec.get(f), "?")).toList());
            rec.contractRef = rec.get(rt.contractField);
            records.add(rec);
        }

        List<String> missing = rt.fields.stream().filter(f -> f.required)
                .filter(f -> !resolved.containsValue(f.name) && (profile == null || !profile.defaults.containsKey(f.name)))
                .map(f -> f.name).toList();
        return new Mapped(records, resolved, unmapped, missing);
    }

    /** source header -> canonical field, for every header that can be placed. */
    public Map<String, String> resolveColumns(List<String> headers, CanonicalModel.RecordType rt, MappingProfile profile) {
        Map<String, String> byName = new HashMap<>(), byLabel = new HashMap<>(), explicit = new HashMap<>();
        for (CanonicalModel.Field f : rt.fields) {
            byName.put(norm(f.name), f.name);
            if (f.label != null) byLabel.put(norm(f.label), f.name);
        }
        if (profile != null) profile.columns.forEach((src, field) -> explicit.put(norm(src), field));

        Map<String, String> resolved = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (String h : headers) {
            String n = norm(h);
            String field = explicit.get(n);
            if (field == null) field = byName.get(n);
            if (field == null) field = byLabel.get(n);
            if (field != null && taken.add(field)) resolved.put(h, field);   // first column wins if two map to one field
        }
        return resolved;
    }

    String transform(String field, String value, MappingProfile profile) {
        String v = value.trim();
        String t = profile == null ? null : profile.transforms.get(field);
        if (t == null) return v;
        String kind = t.contains(":") ? t.substring(0, t.indexOf(':')) : t;
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "trim" -> v;
            case "upper" -> v.toUpperCase(Locale.ROOT);
            case "lower" -> v.toLowerCase(Locale.ROOT);
            case "digits" -> v.replaceAll("[^0-9]", "");
            case "list" -> String.join(",", Arrays.stream(v.split("[,;|]")).map(String::trim).filter(s -> !s.isEmpty()).toList());
            case "date" -> toIsoDate(v, t.contains(":") ? t.substring(t.indexOf(':') + 1) : "yyyy-MM-dd");
            default -> throw new IllegalStateException("Mapping profile " + profile.id + ": unknown transform '" + t
                    + "' for " + field + ". Use trim, upper, lower, digits, list or date:<pattern>.");
        };
    }

    /** Returns yyyy-MM-dd when one of the patterns fits; otherwise the value unchanged, for validation to report. */
    static String toIsoDate(String value, String patterns) {
        for (String p : patterns.split("\\|")) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(p.trim().replace("yyyy", "uuuu")).withResolverStyle(ResolverStyle.STRICT);
                return LocalDate.parse(value, f).toString();
            } catch (DateTimeParseException | IllegalArgumentException ignored) { /* try the next pattern */ }
        }
        return value;
    }

    private String mapValue(String field, String value, MappingProfile profile) {
        if (profile == null || value == null) return value;
        Map<String, String> vm = profile.valueMaps.get(field);
        if (vm == null) return value;
        for (Map.Entry<String, String> e : vm.entrySet())
            if (e.getKey().equalsIgnoreCase(value)) return e.getValue();
        return value;
    }
}
