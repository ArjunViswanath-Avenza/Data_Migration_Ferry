import { useEffect, useState } from "react";
import { api } from "../../api.js";
import { useFerry } from "../../state.jsx";
import { Badge, Banner, Button, Card, Field, Json, Table } from "../../ui.jsx";

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
    <Card title="1 · Load files" sub="Excel or CSV. Every value is read as text, so leading zeros on CIFs and account numbers are kept.">
      <div className="row" style={{ marginBottom: 12 }}>
        <span className="small muted">Templates:</span>
        <a className="btn ghost small" href={api.href("/api/boarding/templates/workbook.xlsx")}>All sheets (one workbook)</a>
        {types.map(([t, rt]) => (
          <a key={t} className="btn ghost small" href={api.href(`/api/boarding/templates/${t}.xlsx`)}>{rt.label}</a>
        ))}
      </div>

      <div className="row" style={{ alignItems: "flex-end" }}>
        <div style={{ flex: "1 1 200px" }}>
          <Field label="What the file holds" htmlFor="up-type">
            <select id="up-type" value={recordType} onChange={(e) => { setRecordType(e.target.value); setProfileId(""); }}>
              <option value="">Whole workbook (sheets named like the template)</option>
              {types.map(([t, rt]) => <option key={t} value={t}>{rt.label} only</option>)}
            </select>
          </Field>
        </div>
        <div style={{ flex: "1 1 200px" }}>
          <Field label="Mapping profile" htmlFor="up-profile">
            <select id="up-profile" value={profileId} onChange={(e) => setProfileId(e.target.value)} disabled={!recordType}>
              <option value="">{batch.source_system ? `Automatic for ${batch.source_system}` : "Template headers (none)"}</option>
              {usable.map((p) => <option key={p.id} value={p.id}>{p.id}{p.sourceSystem ? ` · ${p.sourceSystem}` : ""}</option>)}
            </select>
          </Field>
        </div>
        <div style={{ flex: "2 1 240px" }}>
          <Field label="File" htmlFor="up-file">
            <input id="up-file" type="file" accept=".xlsx,.xls,.csv" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
          </Field>
        </div>
        <Button onClick={upload} disabled={!file || busy}>{busy ? "Loading…" : "Load into batch"}</Button>
      </div>
      <p className="muted small" style={{ margin: "8px 0 0" }}>
        Loading a record type again replaces that type in the batch and sends the batch back to the start, so findings and the plan always match the data.
      </p>

      {error && <div style={{ marginTop: 12 }}><Banner tone="danger" title="The file was not loaded.">{error}</Banner></div>}

      {result && (
        <div style={{ marginTop: 14, display: "flex", flexDirection: "column", gap: 10 }}>
          <Banner tone="info">
            Loaded <b>{result.fileName}</b>: {result.staged.map((s) => `${s.rowsRead} ${s.recordType.toLowerCase()} rows`).join(", ")}.
            {result.sheetsIgnored.length > 0 && <> Sheets not used: {result.sheetsIgnored.join(", ")}.</>}
          </Banner>
          <Table
            columns={[
              { key: "recordType", label: "Record type" },
              { key: "sheetName", label: "Sheet", render: (s) => s.sheetName || "CSV" },
              { key: "profileId", label: "Profile", render: (s) => s.profileId || <span className="muted">template headers</span> },
              { key: "rowsRead", label: "Rows", num: true },
              { key: "mapped", label: "Columns mapped", num: true, render: (s) => Object.keys(s.resolvedColumns).length },
              { key: "unmappedColumns", label: "Ignored columns", render: (s) => s.unmappedColumns.length
                ? <Badge tone="warn">{s.unmappedColumns.join(", ")}</Badge> : <span className="muted">none</span> },
              { key: "missingRequiredFields", label: "Required with no column", render: (s) => s.missingRequiredFields.length
                ? <Badge tone="danger">{s.missingRequiredFields.join(", ")}</Badge> : <span className="muted">none</span> },
            ]}
            rows={result.staged.map((s) => ({ ...s, key: s.recordType }))}
          />
          <details>
            <summary className="small">First rows as mapped</summary>
            <Json value={Object.fromEntries(result.staged.map((s) => [s.recordType, s.sample]))} />
          </details>
        </div>
      )}

      {batch.files?.length > 0 && (
        <details style={{ marginTop: 12 }}>
          <summary className="small">Files loaded into this batch ({batch.files.length})</summary>
          <div style={{ marginTop: 8 }}>
            <Table
              columns={[
                { key: "record_type", label: "Type" }, { key: "file_name", label: "File" }, { key: "sheet_name", label: "Sheet" },
                { key: "profile_id", label: "Profile" }, { key: "rows_read", label: "Rows", num: true },
                { key: "uploaded_by", label: "By" },
              ]}
              rows={batch.files.map((f, i) => ({ ...f, key: i }))}
            />
          </div>
        </details>
      )}
    </Card>
  );
}
