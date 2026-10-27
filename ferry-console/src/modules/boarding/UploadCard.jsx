import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Field, Json, LinkButton, Table } from "../../kit/ui.jsx";

export default function UploadCard({ batch, onChanged }) {
  const { operator } = useFerry();
  const [model, setModel] = useState(null);
  const [profiles, setProfiles] = useState([]);
  const [recordType, setRecordType] = useState("");
  const [profileId, setProfileId] = useState("");
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    api.get("/api/boarding/model").then(setModel).catch(() => {});
    api.get("/api/boarding/profiles").then(setProfiles).catch((e) => setError(e.message));
  }, []);
  useEffect(() => { setResult(null); setError(""); }, [batch.batch_id]);

  const types = model ? Object.entries(model.recordTypes) : [];
  const usable = profiles.filter((p) => !recordType || p.recordType === recordType);

  const upload = async () => {
    setBusy(true); setError(""); setResult(null);
    const fd = new FormData();
    fd.append("file", file);
    const qs = new URLSearchParams();
    if (recordType) qs.set("recordType", recordType);
    if (recordType && profileId) qs.set("profileId", profileId);
    if (operator) qs.set("performedBy", operator);
    try {
      setResult(await api.upload(`/api/boarding/batches/${batch.batch_id}/files?${qs}`, fd));
      onChanged();
    } catch (e) { setError(e.message); }
    finally { setBusy(false); }
  };

  return (
    <Card icon="boarding" title="Load files"
      sub="Excel or CSV. Every value is read as text, so leading zeros on CIFs and account numbers are kept."
      actions={<LinkButton size="sm" icon="download" href={api.href("/api/boarding/templates/workbook.xlsx")}>Template workbook</LinkButton>}>
      <div className="row xs muted">
        One sheet per type:
        {types.map(([t, rt]) => (
          <a key={t} href={api.href(`/api/boarding/templates/${t}.xlsx`)} className="badge neutral">{rt.label}</a>
        ))}
      </div>

      <div className="row" style={{ alignItems: "flex-end" }}>
        <div style={{ flex: "1 1 220px" }}>
          <Field label="What the file holds" htmlFor="up-type">
            <select id="up-type" value={recordType} onChange={(e) => { setRecordType(e.target.value); setProfileId(""); }}>
              <option value="">Whole workbook (sheets named like the template)</option>
              {types.map(([t, rt]) => <option key={t} value={t}>{rt.label} only</option>)}
            </select>
          </Field>
        </div>
        <div style={{ flex: "1 1 220px" }}>
          <Field label="Mapping profile" htmlFor="up-profile">
            <select id="up-profile" value={profileId} onChange={(e) => setProfileId(e.target.value)} disabled={!recordType}>
              <option value="">{batch.source_system ? `Automatic for ${batch.source_system}` : "Template headers (none)"}</option>
              {usable.map((p) => <option key={p.id} value={p.id}>{p.id}{p.sourceSystem ? ` · ${p.sourceSystem}` : ""}</option>)}
            </select>
          </Field>
        </div>
        <div style={{ flex: "2 1 260px" }}>
          <Field label="File" htmlFor="up-file">
            <div className="file-drop">
              <input id="up-file" type="file" accept=".xlsx,.xls,.csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </div>
          </Field>
        </div>
        <Button icon="boarding" onClick={upload} disabled={!file || busy}>{busy ? "Loading…" : "Load into batch"}</Button>
      </div>
      <p className="xs muted">Loading a record type again replaces that type in the batch and sends the batch back to the start, so findings and the plan always match the data.</p>

      {error && <Banner tone="danger" title="The file was not loaded.">{error}</Banner>}

      {result && (
        <>
          <Banner tone="ok" title={`Loaded ${result.fileName}.`}>
            {result.staged.map((s) => `${s.rowsRead} ${s.recordType.toLowerCase().replace("_", " ")} rows`).join(", ")}.
            {result.sheetsIgnored.length > 0 && <> Sheets not used: {result.sheetsIgnored.join(", ")}.</>}
          </Banner>
          <Table
            columns={[
              { key: "recordType", label: "Record type" },
              { key: "sheetName", label: "Sheet", render: (s) => s.sheetName || "CSV" },
              { key: "profileId", label: "Profile", render: (s) => s.profileId || <span className="faint">template headers</span> },
              { key: "rowsRead", label: "Rows", num: true },
              { key: "mapped", label: "Columns mapped", num: true, render: (s) => Object.keys(s.resolvedColumns).length },
              { key: "unmappedColumns", label: "Ignored columns", render: (s) => s.unmappedColumns.length
                ? <Badge tone="warn">{s.unmappedColumns.join(", ")}</Badge> : <span className="faint">none</span> },
              { key: "missingRequiredFields", label: "Required, no column", render: (s) => s.missingRequiredFields.length
                ? <Badge tone="danger">{s.missingRequiredFields.join(", ")}</Badge> : <span className="faint">none</span> },
            ]}
            rows={result.staged.map((s) => ({ ...s, key: s.recordType }))}
          />
          <details>
            <summary>First rows as mapped</summary>
            <div style={{ marginTop: 8 }}><Json value={Object.fromEntries(result.staged.map((s) => [s.recordType, s.sample]))} /></div>
          </details>
        </>
      )}

      {batch.files?.length > 0 && (
        <details>
          <summary>Files loaded into this batch ({batch.files.length})</summary>
          <div style={{ marginTop: 8 }}>
            <Table
              columns={[
                { key: "record_type", label: "Type" }, { key: "file_name", label: "File" }, { key: "sheet_name", label: "Sheet" },
                { key: "profile_id", label: "Profile" }, { key: "rows_read", label: "Rows", num: true }, { key: "uploaded_by", label: "By" },
              ]}
              rows={batch.files.map((f, i) => ({ ...f, key: i }))}
            />
          </div>
        </details>
      )}
    </Card>
  );
}
