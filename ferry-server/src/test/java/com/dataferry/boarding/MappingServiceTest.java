package com.dataferry.boarding;

import com.dataferry.boarding.MappingService.MappingProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MappingServiceTest {

    private final CanonicalModel model = newModel();
    private final MappingService mapping = new MappingService(null, model, new ObjectMapper());

    private static CanonicalModel newModel() {
        try { return new CanonicalModel(new ObjectMapper()); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static SheetReader.Sheet sheet(List<String> headers, List<List<String>> rows) {
        List<Map<String, String>> out = new ArrayList<>();
        for (List<String> r : rows) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) m.put(headers.get(i), r.get(i));
            out.add(m);
        }
        return new SheetReader.Sheet("Users", headers, out);
    }

    @Test
    void templateHeadersMatchWithoutAProfileAndUnknownColumnsAreReported() {
        var s = sheet(List.of("User Ref *", "contract_ref", "ROLE", "Shoe Size"), List.of(List.of("U-1", "C-1", "ADMIN", "42")));
        var m = mapping.map(s, "USER", null);
        StagedRecord r = m.records().get(0);
        assertEquals("U-1", r.get("userRef"));
        assertEquals("C-1", r.get("contractRef"));
        assertEquals("ADMIN", r.get("roleRef"));
        assertEquals(List.of("Shoe Size"), m.unmappedColumns());
        assertTrue(m.missingRequiredFields().isEmpty());
        assertEquals(2, r.rowNum, "row numbers match the spreadsheet, header being row 1");
        assertEquals("U-1", r.key);
        assertEquals("C-1", r.contractRef);
    }

    @Test
    void profileColumnsTransformsValueMapsAndDefaultsApplyInOrder() {
        MappingProfile p = new MappingProfile();
        p.id = "t"; p.recordType = "USER";
        p.columns.putAll(Map.of("LOGIN_ID", "userRef", "CONTRACT_NO", "contractRef", "ROLE_CODE", "roleRef",
                "USER_KIND", "userType", "MOBILE", "phone", "BIRTH_DT", "dateOfBirth"));
        p.transforms.putAll(Map.of("phone", "digits", "dateOfBirth", "date:dd/MM/yyyy|yyyy-MM-dd"));
        p.valueMaps.put("userType", Map.of("C", "LINKED", "N", "VIRTUAL"));
        p.valueMaps.put("roleRef", Map.of("a", "ADMIN"));
        p.defaults.put("phoneCountryCode", "95");

        var s = sheet(List.of("LOGIN_ID", "CONTRACT_NO", "ROLE_CODE", "USER_KIND", "MOBILE", "BIRTH_DT"),
                List.of(List.of("mya.win", "B-100", "A", "n", "9123 456 710", "12/04/1990"),
                        List.of("bad.date", "B-100", "A", "C", "", "31/02/1990")));
        var recs = mapping.map(s, "USER", p).records();
        assertEquals("VIRTUAL", recs.get(0).get("userType"), "value maps ignore case");
        assertEquals("ADMIN", recs.get(0).get("roleRef"));
        assertEquals("9123456710", recs.get(0).get("phone"));
        assertEquals("1990-04-12", recs.get(0).get("dateOfBirth"));
        assertEquals("95", recs.get(0).get("phoneCountryCode"));
        assertEquals("31/02/1990", recs.get(1).get("dateOfBirth"), "an impossible date is left for validation to report");
        assertNull(recs.get(1).get("phone"));
    }

    @Test
    void missingRequiredColumnsAreNamed() {
        var m = mapping.map(sheet(List.of("User Ref"), List.of(List.of("U-1"))), "USER", null);
        assertEquals(List.of("contractRef", "roleRef"), m.missingRequiredFields());
    }
}
