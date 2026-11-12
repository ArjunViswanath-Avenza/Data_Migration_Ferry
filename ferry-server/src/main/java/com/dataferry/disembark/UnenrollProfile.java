package com.dataferry.disembark;

import com.dataferry.config.ConfigModel.Strict;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * config/unenroll.json: everything Disembark knows about the target schema. The engine holds no
 * table or column names; it runs whatever this file lists, in its order. Parsed strictly, so a
 * misspelt key stops with a message instead of being silently ignored (the old module's
 * missing-"note" bug).
 */
public class UnenrollProfile extends Strict {

    static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    public String schema = "dbxdb";
    public String keyLabel = "Core customer ID (CIF)";
    public String keyPattern = "^[A-Za-z0-9_-]{1,64}$";
    public long maxRowsPerTableGuard = 5000;
    /** Master switch for deleting directly from the database. Off: only previews and scripts. */
    public boolean allowExecute = false;
    public List<ResolveStep> resolveSteps = new ArrayList<>();
    public List<Check> checks = new ArrayList<>();
    public List<DeleteStep> deleteSteps = new ArrayList<>();

    public static class ResolveStep extends Strict {
        public String name;
        /** One column; ":cif" is the key. */
        public String sql;
        public boolean confirmed = true;
        public String note;
    }

    /** A query that returns (item, others) rows when the customer shares something with other CIFs. */
    public static class Check extends Strict {
        public String id;
        public String label;
        /** May use :cif and :<resolve name> (a list). Returns columns item, others. */
        public String sql;
        public String message;
    }

    public static class DeleteStep extends Strict {
        public String group;
        public String table;
        public String column;
        /** "cif" or the name of a resolve step. */
        public String using;
        public boolean confirmed = true;
        public String note;
    }

    static UnenrollProfile load(Path file, ObjectMapper om) {
        if (!Files.exists(file)) throw new IllegalStateException("Disembark needs " + file + ", which is missing.");
        UnenrollProfile p;
        try { p = om.readValue(file.toFile(), UnenrollProfile.class); }
        catch (Exception e) { throw new IllegalStateException(file.getFileName() + ": " + rootMessage(e)); }
        p.validate();
        return p;
    }

    void validate() {
        List<String> problems = new ArrayList<>();
        if (!IDENT.matcher(String.valueOf(schema)).matches()) problems.add("schema '" + schema + "' is not a plain name");
        try { Pattern.compile(keyPattern); } catch (Exception e) { problems.add("keyPattern is not a valid pattern"); }
        Set<String> names = new HashSet<>(Set.of("cif"));
        for (ResolveStep r : resolveSteps) {
            if (r.name == null || !IDENT.matcher(r.name).matches()) problems.add("resolve step name '" + r.name + "' is not a plain name");
            else if (!names.add(r.name)) problems.add("resolve step '" + r.name + "' appears twice");
            if (r.sql == null || !r.sql.contains(":cif")) problems.add("resolve step '" + r.name + "' must use :cif");
        }
        for (DeleteStep d : deleteSteps) {
            String where = "delete step " + d.table;
            if (d.table == null || !IDENT.matcher(d.table).matches()) problems.add(where + ": table is not a plain name");
            if (d.column == null || !IDENT.matcher(d.column).matches()) problems.add(where + ": column is not a plain name");
            if (!names.contains(d.using)) problems.add(where + ": uses '" + d.using + "', which no resolve step defines");
            if (d.group == null || d.group.isBlank()) problems.add(where + ": group is missing");
        }
        Set<String> ids = new HashSet<>();
        for (Check c : checks) {
            if (c.id == null || !ids.add(c.id)) problems.add("check id '" + c.id + "' is missing or repeated");
            if (c.sql == null) problems.add("check " + c.id + " has no sql");
        }
        if (!problems.isEmpty()) throw new IllegalStateException("config/unenroll.json: " + String.join("; ", problems));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }
}
