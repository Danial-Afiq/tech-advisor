import { useCallback, useEffect, useRef, useState } from 'react'
import { API_BASE_URL } from './config'
import './IngestionAdmin.css'

type Source = { sourceId: string; enabled: boolean; simulation: boolean; nextAllowedAt: string | null }
type Run = { runId: string; status: string; triggerType: string; requestedAt: string; startedAt: string | null;
  product?: { productId: number; productName: string } | null;
  finishedAt: string | null; processedPayloadCount: number; errorStackCount: number;
  sources: { sourceId: string; status: string; processedPayloadCount: number; errorStackCount: number }[] }
type Schedule = { enabled: boolean; intervalHours: number; nextScheduledAt: string | null; activeRunId: string | null }
type Session = { username: string; csrfHeader: string; csrfToken: string }
const SEARCHAPI_SOURCE = 'searchapi-google-product-reviews'
const sourceLabel = (id: string) => id === SEARCHAPI_SOURCE ? 'SearchAPI customer reviews' : id
const when = (value: string | null) => value ? new Date(value).toLocaleString() : '—'

export default function IngestionAdmin() {
  const [password, setPassword] = useState('')
  const auth = useRef('')
  const pending = useRef<{ key: string; body: string } | null>(null)
  const [session, setSession] = useState<Session | null>(null)
  const [sources, setSources] = useState<Source[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [runs, setRuns] = useState<Run[]>([])
  const [schedule, setSchedule] = useState<Schedule | null>(null)
  const [reason, setReason] = useState('')
  const [productName, setProductName] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const demo = import.meta.env.VITE_INGESTION_DEMO === 'true'
  const searchApiSelected = selected.includes(SEARCHAPI_SOURCE)

  const request = useCallback(async (path: string, init: RequestInit = {}) => {
    const response = await fetch(`${API_BASE_URL}/api/admin/ingestion${path}`, {
      ...init, credentials: 'include', headers: { ...(auth.current ? { Authorization: auth.current } : {}), ...init.headers },
    })
    if (!response.ok) {
      if (response.status === 400) {
        const body = await response.json().catch(() => null)
        throw new Error(typeof body?.message === 'string' ? body.message : 'Check your source selection and product name.')
      }
      const descriptions: Record<number, string> = { 401: 'Sign in with an admin account to continue.',
        403: 'Admin access is unavailable or your session has expired.', 409: 'A run is already active, or this request conflicts with an earlier submission.',
        503: 'Run storage is unavailable. Retry the same request when it recovers.' }
      throw new Error(descriptions[response.status] ?? `Request failed (${response.status}).`)
    }
    return response.json()
  }, [])

  const refresh = useCallback(async () => {
    const [nextSources, nextRuns, nextSchedule] = await Promise.all([request('/sources'), request('/runs'), request('/schedule')])
    setSources(nextSources); setRuns(nextRuns); setSchedule(nextSchedule)
  }, [request])

  useEffect(() => {
    if (!session) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try { await refresh() } catch (e) { if (!stopped) setError((e as Error).message) }
      if (!stopped) timer = setTimeout(poll, 2000)
    }
    timer = setTimeout(poll, 2000)
    return () => { stopped = true; clearTimeout(timer) }
  }, [session, refresh])

  async function connect() {
    setSubmitting(true); setError('')
    if (demo) auth.current = `Basic ${btoa(`demo-admin:${password}`)}`
    try {
      const identity = await request('/session')
      await refresh(); setSession(identity); setPassword('')
    } catch (e) { setError((e as Error).message); auth.current = '' }
    finally { setSubmitting(false) }
  }

  async function start() {
    if (!session) return
    if (searchApiSelected && !productName.trim()) {
      setError('Enter the smartphone name to import its customer reviews.'); return
    }
    setSubmitting(true); setError('')
    const body = JSON.stringify({ sources: [...selected].sort(), reason: reason.trim() || null,
      ...(searchApiSelected ? { productName: productName.trim() } : {}) })
    if (!pending.current || pending.current.body !== body) pending.current = { body, key: crypto.randomUUID() }
    try {
      await request('/runs', { method: 'POST', headers: { 'Content-Type': 'application/json',
        'Idempotency-Key': pending.current.key, [session.csrfHeader]: session.csrfToken }, body })
      pending.current = null
      await refresh()
    } catch (e) { setError((e as Error).message); await refresh().catch(() => {}) }
    finally { setSubmitting(false) }
  }

  return <main className="ingestion-admin">
    <header><p className="eyebrow">TECH ADVISOR · ADMIN</p><h1>Market data ingestion</h1>
      <p>Import market updates and customer reviews for your catalogue.</p></header>
    {error && <p className="ingestion-error" role="alert">{error}</p>}
    {!session ? <section className="ingestion-card"><h2>Admin access</h2>
      <p>{demo ? 'Local admin access. Enter the configured demo admin password.' : 'Connect using your authenticated admin session.'}</p>
      <form onSubmit={e => { e.preventDefault(); void connect() }}>
        {demo && <label>Demo password<input type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} required /></label>}
        <button disabled={submitting} type="submit">{submitting ? 'Connecting…' : 'Connect as admin'}</button>
      </form></section> : <>
      <section className="ingestion-summary" aria-label="Schedule">
        <div><span>Recurring cycle</span><strong>Every {schedule?.intervalHours ?? 336} hours</strong></div>
        <div><span>Next scheduled run</span><strong>{schedule?.enabled ? when(schedule.nextScheduledAt) : 'Scheduling disabled'}</strong></div>
        <div><span>Pipeline</span><strong>{schedule?.activeRunId ? 'Running' : 'Ready'}</strong></div>
      </section>
      <section className="ingestion-card"><h2>Run a market update</h2><p>Manual runs leave the recurring schedule unchanged.</p>
        <form onSubmit={e => { e.preventDefault(); void start() }}>
          <fieldset disabled={submitting || !!schedule?.activeRunId}><legend>Sources</legend>
            {sources.length === 0 && <p>No sources have been registered.</p>}
            {sources.map(source => <label className="source-choice" key={source.sourceId}>
              <input type="checkbox" disabled={!source.enabled} checked={selected.includes(source.sourceId)}
                onChange={e => setSelected(old => e.target.checked ? [...old, source.sourceId] : old.filter(id => id !== source.sourceId))} />
              <span>{sourceLabel(source.sourceId)}{source.simulation && <small>Simulation</small>}
                {!source.enabled && <small>Disabled</small>}
                {source.sourceId === SEARCHAPI_SOURCE && !source.enabled && <small>SearchAPI ingestion must be enabled on the server.</small>}
                {source.nextAllowedAt && new Date(source.nextAllowedAt) > new Date() && <small>Available after {when(source.nextAllowedAt)}</small>}</span>
            </label>)}
          </fieldset>
          {searchApiSelected && <div className="ingestion-product">
            <label htmlFor="ingestion-product-name">Smartphone name
              <input id="ingestion-product-name" value={productName} maxLength={200} required
                disabled={submitting || !!schedule?.activeRunId} aria-describedby="ingestion-product-help"
                onChange={e => setProductName(e.target.value)} placeholder="For example, Apple iPhone 16 Pro" />
            </label>
            <small id="ingestion-product-help">Enter the exact model name of an existing verified smartphone. Only this phone’s customer reviews will be imported.</small>
          </div>}
          <label>Reason (optional)<input value={reason} maxLength={500} onChange={e => setReason(e.target.value)} placeholder="For example, a major mid-cycle phone release" /></label>
          <button type="submit" disabled={submitting || !!schedule?.activeRunId || selected.length === 0 || (searchApiSelected && !productName.trim())}>
            {submitting ? 'Submitting…' : schedule?.activeRunId ? 'Run in progress' : 'Run now'}</button>
        </form>
      </section>
      <section className="ingestion-card" aria-live="polite"><h2>Recent runs</h2>
        {runs.length === 0 ? <p>No runs yet. Select a source to start your first update.</p> : <div className="ingestion-table"><table>
          <thead><tr><th>Requested</th><th>Trigger</th><th>Product</th><th>Status</th><th>Processed</th><th>Error stacks</th><th>Source results</th></tr></thead>
          <tbody>{runs.map(run => <tr key={run.runId}><td title={run.runId}>{when(run.requestedAt)}</td><td>{run.triggerType}</td>
            <td>{run.product?.productName ?? 'Source defaults'}</td>
            <td>{run.status}<small>{run.finishedAt ? `Finished ${when(run.finishedAt)}` : 'Awaiting completion'}</small></td>
            <td>{run.processedPayloadCount}</td><td>{run.errorStackCount}</td>
            <td>{run.sources.map(source => <small key={source.sourceId}>{sourceLabel(source.sourceId)}: {source.status} ({source.processedPayloadCount} processed)</small>)}</td></tr>)}</tbody>
        </table></div>}
      </section>
    </>}
  </main>
}
