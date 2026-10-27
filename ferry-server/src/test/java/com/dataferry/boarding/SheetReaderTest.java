package com.dataferry.boarding;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** Identifiers must arrive exactly as the source shows them. */
class SheetReaderTest {

    private final SheetReader reader = new SheetReader();

    @Test
    void csvKeepsLeadingZerosAndDropsTheByteOrderMark() throws Exception {
        String csv = "﻿Contract Ref,Primary CIF\nR-1,001000111\n,\n";
        var sheets = reader.readAll(csv.getBytes(StandardCharsets.UTF_8), "c.csv");
        var s = sheets.get("");
        assertEquals("Contract Ref", s.headers().get(0));
        assertEquals(1, s.rows().size(), "blank rows are skipped");
        assertEquals("001000111", s.rows().get(0).get("Primary CIF"));
    }

    @Test
    void workbookReadsEverySheetExceptInstructionsAndKeepsTextCells() throws Exception {
        byte[] xlsx;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet a = wb.createSheet("Accounts");
            a.createRow(0).createCell(0).setCellValue("Account ID");
            a.createRow(1).createCell(0).setCellValue("00110000011101");             // text cell
            Sheet c = wb.createSheet("Contracts");
            c.createRow(0).createCell(0).setCellValue("Primary CIF");
            c.createRow(1).createCell(0).setCellValue("045000100 ");            // non-breaking space from an export
            wb.createSheet("Instructions").createRow(0).createCell(0).setCellValue("ignore me");
            wb.write(out);
            xlsx = out.toByteArray();
        }
        LinkedHashMap<String, SheetReader.Sheet> sheets = reader.readAll(xlsx, "book.xlsx");
        assertEquals(java.util.List.of("Accounts", "Contracts"), java.util.List.copyOf(sheets.keySet()));
        assertEquals("00110000011101", sheets.get("Accounts").rows().get(0).get("Account ID"));
        assertEquals("045000100", sheets.get("Contracts").rows().get(0).get("Primary CIF"));
    }
}
