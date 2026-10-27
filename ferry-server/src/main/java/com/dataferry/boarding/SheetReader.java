package com.dataferry.boarding;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Reads .xlsx, .xls and .csv into header/row maps. Everything is read as text, exactly as the
 * cell displays it: letting a spreadsheet library guess types is how leading zeros on CIFs and
 * account numbers get destroyed. Typing and checks are left to validation, which reports
 * problems instead of hiding them.
 */
@Component
public class SheetReader {

    /** One sheet: its name (null for CSV), header names in column order, and the non-blank rows. */
    public record Sheet(String name, List<String> headers, List<Map<String, String>> rows) {}

    /** Every data sheet in the file, in workbook order. Sheets named like instructions or notes are skipped. */
    public LinkedHashMap<String, Sheet> readAll(byte[] data, String fileName) throws Exception {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        LinkedHashMap<String, Sheet> out = new LinkedHashMap<>();
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            out.put("", readCsv(data));
            return out;
        }
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            DataFormatter fmt = new DataFormatter();
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                org.apache.poi.ss.usermodel.Sheet s = wb.getSheetAt(i);
                String sn = s.getSheetName();
                String lower = sn.toLowerCase(Locale.ROOT);
                if (lower.contains("instruction") || lower.contains("readme") || lower.contains("notes")) continue;
                if (s.getPhysicalNumberOfRows() == 0) continue;
                out.put(sn, readSheet(s, fmt, eval));
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("The file has no readable data sheet.");
        return out;
    }

    private Sheet readCsv(byte[] data) throws Exception {
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);      // Excel's UTF-8 byte-order mark
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setAllowMissingColumnNames(true).build();
        try (CSVParser parser = CSVParser.parse(new java.io.StringReader(text), fmt)) {
            List<String> headers = new ArrayList<>();
            int c = 1;
            for (String h : parser.getHeaderNames()) headers.add(h == null || h.isBlank() ? "column" + c++ : h.trim());
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord r : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), i < r.size() ? clean(r.get(i)) : null);
                if (!blank(row)) rows.add(row);
            }
            return new Sheet(null, headers, rows);
        }
    }

    private Sheet readSheet(org.apache.poi.ss.usermodel.Sheet sheet, DataFormatter fmt, FormulaEvaluator eval) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        List<String> headers = new ArrayList<>();
        if (headerRow != null) {
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                String h = clean(fmt.formatCellValue(headerRow.getCell(c), eval));
                headers.add(h == null ? "column" + (c + 1) : h);
            }
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) m.put(headers.get(c), clean(fmt.formatCellValue(row.getCell(c), eval)));
            if (!blank(m)) rows.add(m);
        }
        return new Sheet(sheet.getSheetName(), headers, rows);
    }

    /** Trims, and turns non-breaking spaces (common in bank exports) into plain spaces. */
    private static String clean(String v) {
        if (v == null) return null;
        String t = v.replace(' ', ' ').trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean blank(Map<String, String> row) {
        return row.values().stream().allMatch(v -> v == null || v.isBlank());
    }
}
