package com.dataferry.boarding;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Builds the Excel templates source teams fill in. Every data column is pre-formatted as Text, so
 * CIFs and account numbers keep their leading zeros even when pasted in. Required columns are
 * marked with " *"; header matching ignores the marker.
 */
@Service
public class TemplateService {

    private final CanonicalModel model;

    public TemplateService(CanonicalModel model) { this.model = model; }

    /** One record type on one sheet, plus an Instructions sheet. */
    public byte[] single(String recordType) throws Exception {
        return build(List.of(recordType.toUpperCase()));
    }

    /** Every record type on its own sheet: fill it in and upload it once. */
    public byte[] workbook() throws Exception {
        return build(List.copyOf(model.types()));
    }

    private byte[] build(List<String> types) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setDataFormat(wb.createDataFormat().getFormat("@"));

            for (String type : types) {
                CanonicalModel.RecordType rt = model.type(type);
                Sheet s = wb.createSheet(rt.sheet);
                Row h = s.createRow(0);
                for (int i = 0; i < rt.fields.size(); i++) {
                    CanonicalModel.Field f = rt.fields.get(i);
                    Cell c = h.createCell(i);
                    c.setCellValue(f.label + (f.required ? " *" : ""));
                    c.setCellStyle(head);
                    s.setDefaultColumnStyle(i, text);
                    s.setColumnWidth(i, Math.max(14, f.label.length() + 4) * 256);
                }
                s.createFreezePane(0, 1);
            }

            Sheet ins = wb.createSheet("Instructions");
            String[] cols = {"Sheet", "Column", "Field name", "Required", "Allowed values", "Notes"};
            Row ih = ins.createRow(0);
            for (int i = 0; i < cols.length; i++) {
                Cell c = ih.createCell(i);
                c.setCellValue(cols[i]);
                c.setCellStyle(head);
            }
            int r = 1;
            Row intro = ins.createRow(r++);
            intro.createCell(0).setCellValue("Fill one sheet per record type. Keep identifier columns as Text. Columns marked * are required. "
                    + "Sheets named Instructions, Readme or Notes are ignored on upload.");
            r++;
            for (String type : types) {
                CanonicalModel.RecordType rt = model.type(type);
                Row d = ins.createRow(r++);
                d.createCell(0).setCellValue(rt.sheet);
                d.createCell(5).setCellValue(rt.description == null ? "" : rt.description);
                for (CanonicalModel.Field f : rt.fields) {
                    Row fr = ins.createRow(r++);
                    fr.createCell(0).setCellValue(rt.sheet);
                    fr.createCell(1).setCellValue(f.label);
                    fr.createCell(2).setCellValue(f.name);
                    fr.createCell(3).setCellValue(f.required ? "Yes" : "");
                    fr.createCell(4).setCellValue(f.allowed == null ? "" : String.join(", ", f.allowed));
                    fr.createCell(5).setCellValue(f.note == null ? "" : f.note);
                }
                r++;
            }
            int[] widths = {18, 22, 22, 10, 18, 90};
            for (int i = 0; i < widths.length; i++) ins.setColumnWidth(i, widths[i] * 256);

            wb.write(out);
            return out.toByteArray();
        }
    }
}
