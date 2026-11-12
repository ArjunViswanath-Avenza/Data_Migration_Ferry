"""
Regenerates the bank data template files in this folder from a running Ferry server, so they always
match the server's data model (ferry-server/src/main/resources/canonical-model.json).

    python build_templates.py

Needs openpyxl and a server on http://localhost:8095. All example data is invented.
"""
import csv
import json
import urllib.request
from pathlib import Path

from openpyxl import load_workbook

SERVER = "http://localhost:8095"
HERE = Path(__file__).resolve().parent
BLANK = HERE / "Data_Migration_Template.xlsx"
EXAMPLE = HERE / "Data_Migration_Template_EXAMPLE.xlsx"
CSV_DIR = HERE / "csv"

# One retail and one business customer, showing every kind of row. Keys are field names.
EXAMPLE_ROWS = {
    "CONTRACT": [
        {"contractRef": "R-2001", "serviceDefinitionRef": "RETAIL", "primaryCif": "001000111", "email": "anna.lee@example.test",
         "phoneCountryCode": "44", "phoneNumber": "7700900101", "addressLine1": "12 Harbour Road", "city": "Port Town", "country": "GB"},
        {"contractRef": "B-1001", "serviceDefinitionRef": "BUSINESS", "primaryCif": "045000100", "email": "finance@acme.example.test",
         "phoneCountryCode": "44", "phoneNumber": "2071234567", "addressLine1": "1 Dock Street", "city": "Port Town", "country": "GB"},
    ],
    "ACCOUNT": [
        {"contractRef": "R-2001", "cif": "001000111", "accountId": "00110000011101", "accountName": "Savings", "accountType": "S", "currency": "USD", "status": "Open"},
        {"contractRef": "B-1001", "cif": "045000100", "accountId": "04510000010001", "accountName": "Operating", "accountType": "C", "currency": "USD", "status": "Open"},
        {"contractRef": "B-1001", "cif": "045000100", "accountId": "04510000010002", "accountName": "Payroll", "accountType": "C", "currency": "EUR", "status": "Open"},
    ],
    "USER": [
        {"userRef": "anna.lee", "contractRef": "R-2001", "roleRef": "USER", "userType": "LINKED", "cif": "001000111", "legacyUserName": "anna.lee",
         "firstName": "Anna", "lastName": "Lee", "email": "anna.lee@example.test", "phoneCountryCode": "44", "phone": "7700900101", "dateOfBirth": "1985-03-02"},
        {"userRef": "acme.admin", "contractRef": "B-1001", "roleRef": "ADMIN", "userType": "LINKED", "cif": "045000100", "legacyUserName": "acme.admin",
         "firstName": "Sam", "lastName": "Reyes", "email": "sam@acme.example.test", "phoneCountryCode": "44", "phone": "7700900102", "dateOfBirth": "1975-06-01"},
        {"userRef": "acme.maker", "contractRef": "B-1001", "roleRef": "ACCOUNTANT", "userType": "VIRTUAL", "legacyUserName": "acme.maker",
         "firstName": "Maya", "middleName": "Rose", "lastName": "Hart", "email": "maya@acme.example.test", "phoneCountryCode": "44", "phone": "7700900103",
         "dateOfBirth": "1990-04-12", "taxId": "AB1234567", "accountIds": "04510000010001"},
        {"userRef": "acme.checker", "contractRef": "B-1001", "roleRef": "ACCOUNTANT", "userType": "VIRTUAL", "legacyUserName": "acme.checker",
         "firstName": "Leo", "middleName": "Ray", "lastName": "Costa", "email": "leo@acme.example.test", "phoneCountryCode": "44", "phone": "7700900104",
         "dateOfBirth": "1988-01-30", "taxId": "CD7654321"},
    ],
    "CONTRACT_LIMIT": [
        {"contractRef": "B-1001", "cif": "045000100", "featureRef": "ACH_PAYMENT", "actionRef": "ACH_PAYMENT_CREATE", "limitTypeRef": "MAX_TRANSACTION_LIMIT", "value": "1200.00"},
        {"contractRef": "B-1001", "cif": "045000100", "featureRef": "ACH_PAYMENT", "actionRef": "ACH_PAYMENT_CREATE", "limitTypeRef": "DAILY_LIMIT", "value": "5000.00"},
    ],
    "USER_LIMIT": [
        {"userRef": "acme.maker", "contractRef": "B-1001", "cif": "045000100", "accountId": "04510000010001", "featureRef": "BULK_PAYMENT_REQUEST",
         "actionRef": "BULK_PAYMENT_SINGLE_SUBMIT", "limitGroupRef": "BULK_PAYMENT", "limitTypeRef": "PRE_APPROVED_DAILY_LIMIT", "value": "400.00"},
    ],
    "SRC_RULE": [
        {"ruleRef": "9001", "contractRef": "B-1001", "txnType": "*", "currency": "USD", "fromAmount": "1000", "toAmount": "9999999999",
         "makerRef": "acme.maker", "requiredApprovals": "2", "selfApprove": "N", "sequential": "N"},
        {"ruleRef": "9002", "contractRef": "B-1001", "txnType": "*", "currency": "USD", "fromAmount": "0", "toAmount": "1000",
         "makerRef": "acme.admin", "requiredApprovals": "0", "selfApprove": "Y", "sequential": "N"},
    ],
    "SRC_RULE_CHECKER": [
        {"contractRef": "B-1001", "ruleRef": "9001", "position": "1", "checkerRef": "SENIOR", "isGroup": "Y"},
    ],
    "SRC_GROUP_MEMBER": [
        {"contractRef": "B-1001", "groupRef": "SENIOR", "groupKind": "CHECKER", "userRef": "acme.admin"},
        {"contractRef": "B-1001", "groupRef": "SENIOR", "groupKind": "CHECKER", "userRef": "acme.checker"},
    ],
    "PAYEE": [
        {"payeeRef": "P-1", "contractRef": "B-1001", "userRef": "acme.maker", "payeeType": "DOMESTIC", "payeeName": "XYZ Consulting", "accountId": "0987987",
         "iban": "GB29NWBK60161331926819", "swiftCode": "NWBKGB2L", "bankName": "Example Bank"},
        {"payeeRef": "P-2", "contractRef": "R-2001", "userRef": "anna.lee", "payeeType": "P2P", "payeeName": "Ben Hart", "nickName": "Ben", "phone": "7700900105", "primaryContact": "7700900105"},
        {"payeeRef": "P-3", "contractRef": "R-2001", "userRef": "anna.lee", "payeeType": "BILLPAY", "payeeName": "City Utilities", "nickName": "Power", "accountId": "123006",
         "addressLine1": "44 Market Street", "city": "Port Town", "companyName": "City Utilities", "nameOnBill": "Anna Lee"},
    ],
}

model = json.load(urllib.request.urlopen(f"{SERVER}/api/boarding/model"))
BLANK.write_bytes(urllib.request.urlopen(f"{SERVER}/api/boarding/templates/workbook.xlsx").read())

CSV_DIR.mkdir(exist_ok=True)
for rt in model["recordTypes"].values():
    name = rt["sheet"].lower().replace(" ", "_") + ".csv"
    with open(CSV_DIR / name, "w", newline="", encoding="utf-8") as f:
        csv.writer(f).writerow([field["label"] for field in rt["fields"]])

wb = load_workbook(BLANK)
wb["Readme"]["A1"] = "Data Migration Template: worked example (all data invented)"
for type_name, rows in EXAMPLE_ROWS.items():
    rt = model["recordTypes"][type_name]
    ws = wb[rt["sheet"]]
    columns = [field["name"] for field in rt["fields"]]
    for r, row in enumerate(rows, 2):
        unknown = set(row) - set(columns)
        assert not unknown, (type_name, unknown)
        for c, name in enumerate(columns, 1):
            if row.get(name):
                cell = ws.cell(row=r, column=c, value=row[name])
                cell.number_format = "@"
wb.save(EXAMPLE)
print("written:", BLANK.name, EXAMPLE.name, "csv files:", len(list(CSV_DIR.glob("*.csv"))))
