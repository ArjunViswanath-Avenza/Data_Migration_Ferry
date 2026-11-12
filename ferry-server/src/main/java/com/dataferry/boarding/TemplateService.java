package com.dataferry.boarding;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Builds the Excel template a bank's teams fill in: the standard data format for a migration, generated
 * from the canonical model so the two can't drift apart. Every data column is pre-formatted as Text, so
 * CIFs and account numbers keep their leading zeros even when pasted in. Required columns are marked
 * with " *"; header matching ignores the marker. Examples stay on the Instructions sheet, because any
 * row on a data sheet would load as data.
 */
@Service
public class TemplateService {

    private static final int LAST_ROW = 1_048_575;
    private static final String FONT = "Arial";

    private final CanonicalModel model;

    public TemplateService(CanonicalModel model) { this.model = model; }

    /** One record type on one sheet, plus the Readme and Instructions sheets. */
    public byte[] single(String recordType) throws Exception {
        return build(List.of(recordType.toUpperCase()));
    }

    /** Every record type on its own sheet: fill it in and upload it once. */
    public byte[] workbook() throws Exception {
        return build(List.copyOf(model.types()));
    }

    private record Styles(CellStyle text, CellStyle head, CellStyle title, CellStyle bold, CellStyle wrap, CellStyle cell) {}

    private byte[] build(List<String> types) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = styles(wb);
            readme(wb, st, types);
            instructions(wb, st, types);
            for (String type : types) dataSheet(wb, st, model.type(type));
            for (Sheet s : wb) {
                s.setFitToPage(true);
                s.getPrintSetup().setLandscape(true);
                s.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
                s.getPrintSetup().setFitWidth((short) 1);
                s.getPrintSetup().setFitHeight((short) 0);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private Styles styles(Workbook wb) {
        DataFormat fmt = wb.createDataFormat();
        Font plain = font(wb, 10, false, IndexedColors.BLACK);

        CellStyle text = wb.createCellStyle();
        text.setDataFormat(fmt.getFormat("@"));
        text.setFont(plain);

        CellStyle head = wb.createCellStyle();
        head.setFont(font(wb, 10, true, IndexedColors.WHITE));
        head.setFillForegroundColor(IndexedColors.BLACK.getIndex());
        head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        head.setDataFormat(fmt.getFormat("@"));
        head.setVerticalAlignment(VerticalAlignment.CENTER);
        head.setWrapText(true);
        box(head);

        CellStyle title = wb.createCellStyle();
        title.setFont(font(wb, 14, true, IndexedColors.BLACK));

        CellStyle bold = wb.createCellStyle();
        bold.setFont(font(wb, 10, true, IndexedColors.BLACK));
        bold.setVerticalAlignment(VerticalAlignment.TOP);
        bold.setWrapText(true);

        CellStyle wrap = wb.createCellStyle();
        wrap.setFont(plain);
        wrap.setWrapText(true);
        wrap.setVerticalAlignment(VerticalAlignment.TOP);

        CellStyle cell = wb.createCellStyle();
        cell.cloneStyleFrom(wrap);
        cell.setDataFormat(fmt.getFormat("@"));
        box(cell);
        return new Styles(text, head, title, bold, wrap, cell);
    }

    private static Font font(Workbook wb, int size, boolean bold, IndexedColors color) {
        Font f = wb.createFont();
        f.setFontName(FONT);
        f.setFontHeightInPoints((short) size);
        f.setBold(bold);
        f.setColor(color.getIndex());
        return f;
    }

    private static void box(CellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
    }

    // ---------------- Readme ----------------

    private void readme(Workbook wb, Styles st, List<String> types) {
        Sheet s = wb.createSheet("Readme");
        s.setDisplayGridlines(false);
        int[] widths = {26, 62, 42, 28};
        for (int i = 0; i < widths.length; i++) s.setColumnWidth(i, widths[i] * 256);

        int r = 0;
        Row t = s.createRow(r++);
        t.createCell(0).setCellValue("Data Migration Template");
        t.getCell(0).setCellStyle(st.title);
        r++;
        String[][] rows = {
                {"What this is", "The standard format for the data needed to migrate customers to Temenos Digital. It works for any core banking system. Your teams extract the data, fill the sheets, and send the file back."},
                {"Who fills it", "The core banking team holds customer numbers and accounts. Users, approval rules, limits and payees usually sit in the current online banking system, so that team is needed too. See the table below."},
                {"How to fill it", "One row per item, starting on row 2. Don't rename sheets or columns, and don't add rows above the headers. Extra columns are allowed and are reported as ignored. Leave a sheet empty if it doesn't apply."},
                {"Columns marked *", "Required on every row. The Instructions sheet shows other columns that are needed only in some cases, the format of each column, and an example value."},
                {"Numbers and IDs", "Every column is set to Text. Keep leading zeros on customer and account numbers. If you paste data, use Paste Values so the Text format stays."},
                {"Formats", "Dates as yyyy-MM-dd. Amounts as plain numbers, such as 1000.50. Phone numbers as digits only."},
                {"Your own codes", "Use your system's own codes for service types, roles, transaction types and limits. Send the list of codes with their meanings, so they can be matched to the new system's codes."},
                {"Large volumes", "You can send one CSV file per sheet instead of this workbook. Use the same column names, and save as UTF-8."},
                {"Your own format", "If an extract can't match these columns, send a sample of your own layout. It can be mapped once without changing your extract."},
                {"Sending the data", "This file will contain customer data. Send it only through the secure channel agreed with the migration team, never by ordinary email."},
        };
        for (String[] row : rows) {
            Row x = s.createRow(r);
            x.createCell(0).setCellValue(row[0]);
            x.getCell(0).setCellStyle(st.bold);
            x.createCell(1).setCellValue(row[1]);
            x.getCell(1).setCellStyle(st.wrap);
            s.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(r, r, 1, 3));
            x.setHeightInPoints(13f * Math.max(1, (int) Math.ceil(row[1].length() / 125.0)) + 4);
            r++;
        }
        r++;
        Row h = s.createRow(r++);
        String[] heads = {"Sheet", "What it holds", "Usually comes from", "When to fill it"};
        for (int i = 0; i < heads.length; i++) {
            h.createCell(i).setCellValue(heads[i]);
            h.getCell(i).setCellStyle(st.head);
        }
        h.setHeightInPoints(20);
        for (String type : types) {
            CanonicalModel.RecordType rt = model.type(type);
            String[] v = {rt.sheet, rt.description, rt.source, rt.needed};
            Row x = s.createRow(r++);
            for (int i = 0; i < v.length; i++) {
                x.createCell(i).setCellValue(v[i] == null ? "" : v[i]);
                x.getCell(i).setCellStyle(st.cell);
            }
            int lines = (int) Math.max(Math.ceil(String.valueOf(rt.description).length() / 62.0), Math.ceil(String.valueOf(rt.source).length() / 40.0));
            x.setHeightInPoints(13f * Math.max(1, lines) + 4);
        }
    }

    // ---------------- Instructions: one row per column ----------------

    private void instructions(Workbook wb, Styles st, List<String> types) {
        Sheet s = wb.createSheet("Instructions");
        s.setDisplayGridlines(false);
        String[] cols = {"Sheet", "Column", "Required", "Allowed values", "Example", "Notes"};
        int[] widths = {22, 22, 24, 24, 30, 72};
        Row h = s.createRow(0);
        for (int i = 0; i < cols.length; i++) {
            h.createCell(i).setCellValue(cols[i]);
            h.getCell(i).setCellStyle(st.head);
            s.setColumnWidth(i, widths[i] * 256);
        }
        h.setHeightInPoints(20);
        s.createFreezePane(0, 1);
        int r = 1;
        for (String type : types) {
            CanonicalModel.RecordType rt = model.type(type);
            for (CanonicalModel.Field f : rt.fields) {
                String required = f.required ? "Yes" : f.requiredWhen != null ? f.requiredWhen : "";
                String[] v = {rt.sheet, f.label, required, f.allowed == null ? "" : String.join(", ", f.allowed),
                        f.example == null ? "" : f.example, f.note == null ? "" : f.note};
                Row x = s.createRow(r++);
                int lines = 1;
                for (int i = 0; i < v.length; i++) {
                    x.createCell(i).setCellValue(v[i]);
                    x.getCell(i).setCellStyle(st.cell);
                    lines = Math.max(lines, (int) Math.ceil(v[i].length() / (double) widths[i]));
                }
                x.setHeightInPoints(13f * lines + 4);
            }
        }
        s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, r - 1, 0, cols.length - 1));
    }

    // ---------------- Data sheets: headers only ----------------

    private void dataSheet(Workbook wb, Styles st, CanonicalModel.RecordType rt) {
        Sheet s = wb.createSheet(rt.sheet);
        Row h = s.createRow(0);
        h.setHeightInPoints(30);
        DataValidationHelper dv = s.getDataValidationHelper();
        for (int i = 0; i < rt.fields.size(); i++) {
            CanonicalModel.Field f = rt.fields.get(i);
            Cell c = h.createCell(i);
            c.setCellValue(f.label + (f.required ? " *" : ""));
            c.setCellStyle(st.head);
            s.setDefaultColumnStyle(i, st.text);
            s.setColumnWidth(i, Math.max(16, f.label.length() + 6) * 256);
            if (f.allowed != null) {
                DataValidation v = dv.createValidation(dv.createExplicitListConstraint(f.allowed.toArray(String[]::new)),
                        new CellRangeAddressList(1, LAST_ROW, i, i));
                v.setShowErrorBox(true);
                v.createErrorBox(f.label, "Choose one of: " + String.join(", ", f.allowed));
                s.addValidationData(v);
            }
        }
        s.createFreezePane(0, 1);
    }
}
