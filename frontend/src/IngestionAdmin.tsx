import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { signOut } from './api/auth'
import { apiFetch, ApiError } from './api/client'
import { getSession, clearSession } from './api/session'
import type { Session } from './api/session'
import { ThemeRoot } from './components/layout/ThemeRoot'
import { Button } from './components/ui/Button'
import { Card } from './components/ui/Card'
import { Callout } from './components/ui/Callout'
import { EmptyState } from './components/ui/EmptyState'
import { Eyebrow } from './components/ui/Eyebrow'
import { Field, FormError, Input } from './components/ui/Form'
import { LoadingBlock } from './components/ui/LoadingBlock'
import { PageHeader } from './components/ui/PageHeader'
import { Tag } from './components/ui/Tag'

type Source = { sourceId: string; enabled: boolean; simulation: boolean; nextAllowedAt: string | null }
type Run = { runId: string; status: string; triggerType: string; requestedAt: string; startedAt: string | null;
  finishedAt: string | null; processedPayloadCount: number; errorStackCount: number;
  sources: { sourceId: string; status: string; processedPayloadCount: number; errorStackCount: number }[] }
type Schedule = { enabled: boolean; intervalHours: number; nextScheduledAt: string | null; activeRunId: string | null }
const when = (value: string | null) => value ? new Date(value).toLocaleString() : '—'

/**
 * Market data ingestion admin panel. Authenticates with the app's normal
 * signed-in session (the same `ROLE_ADMIN` JWT used everywhere else, from
 * `frontend/src/api/session.ts`) - there is no separate ingestion-only
 * credential to enter here. Signed out, or signed in without the ADMIN
 * role, shows a link to `/login` instead of the run controls.
 */
export default function IngestionAdmin() {
  const navigate = useNavigate()
  const [account, setAccount] = useState<Session | null>(getSession)
  const [ready, setReady] = useState(false)
  const pending = useRef<{ key: string; body: string } | null>(null)
  const [sources, setSources] = useState<Source[]>([])
  const [selected, setSelected] = useState<string[]>([])
  const [runs, setRuns] = useState<Run[]>([])
  const [schedule, setSchedule] = useState<Schedule | null>(null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const request = useCallback(<T,>(path: string, init?: { method?: string; body?: unknown; headers?: Record<string, string> }) =>
    apiFetch<T>(`/api/admin/ingestion${path}`, init), [])

  const refresh = useCallback(async () => {
    const [nextSources, nextRuns, nextSchedule] = await Promise.all([
      request<Source[]>('/sources'), request<Run[]>('/runs'), request<Schedule>('/schedule'),
    ])
    setSources(nextSources); setRuns(nextRuns); setSchedule(nextSchedule)
  }, [request])

  useEffect(() => {
    // `ready` gates the loading spinner on the signed-in-as-admin render path only - the
    // signed-out/non-admin path below never reads it, so there's nothing to set here.
    if (!account || account.role !== 'ADMIN') return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try { await refresh(); setError('') }
      catch (e) {
        if (stopped) return
        if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
          clearSession()
          setAccount(null)
          navigate('/login', { replace: true })
        }
        else setError((e as Error).message)
      }
      finally { if (!stopped) { setReady(true); timer = setTimeout(poll, 2000) } }
    }
    void poll()
    return () => { stopped = true; clearTimeout(timer) }
  }, [account, navigate, refresh])

  const handleSignOut = () => {
    signOut()
    navigate('/login', { replace: true })
  }

  async function start() {
    if (!account) return
    setSubmitting(true); setError('')
    const payload = { sources: [...selected].sort((a, b) => a.localeCompare(b)), reason: reason.trim() || null }
    const body = JSON.stringify(payload)
    if (!pending.current || pending.current.body !== body) pending.current = { body, key: crypto.randomUUID() }
    try {
      await request('/runs', { method: 'POST', headers: { 'Idempotency-Key': pending.current.key }, body: payload })
      pending.current = null
      await refresh()
    } catch (e) {
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        clearSession()
        setAccount(null)
        navigate('/login', { replace: true })
        return
      }
      setError((e as Error).message)
      await refresh().catch(() => {})
    }
    finally { setSubmitting(false) }
  }

  if (!account || account.role !== 'ADMIN') {
    return (
      <ThemeRoot>
        <div className="mx-auto max-w-[520px] p-7">
          <PageHeader eyebrow="Tech Advisor · Admin" title="Market data ingestion" />
          <EmptyState
            icon="🔒"
            title={account ? 'Admin access required' : 'Sign in required'}
            description={account
              ? 'Your signed-in account does not have the ADMIN role.'
              : 'Sign in with an administrator account to manage data ingestion.'}
            action={<Button variant="primary" onClick={() => navigate('/login')}>Go to login</Button>}
          />
        </div>
      </ThemeRoot>
    )
  }

  return (
    <ThemeRoot>
      <div className="mx-auto max-w-[1100px] p-7 max-[620px]:p-4">
        <PageHeader eyebrow="Tech Advisor · Admin" title="Market data ingestion"
          description="Keep the catalogue current, on schedule or when a major release arrives."
          action={<Button size="sm" variant="ghost" onClick={handleSignOut}>Sign out</Button>} />
        <FormError message={error} />
        {!ready ? <LoadingBlock label="Loading ingestion status…" /> : <>
          <div className="grid grid-cols-3 gap-4 max-[700px]:grid-cols-1">
            <Card><div className="card-body gap-1 p-[18px]">
              <Eyebrow>Recurring cycle</Eyebrow>
              <strong className="text-[17px] text-[#eef5ff]">Every {schedule?.intervalHours ?? 24} hours</strong>
            </div></Card>
            <Card><div className="card-body gap-1 p-[18px]">
              <Eyebrow>Next scheduled run</Eyebrow>
              <strong className="text-[17px] text-[#eef5ff]">
                {schedule?.enabled ? when(schedule.nextScheduledAt) : 'Scheduling disabled'}</strong>
            </div></Card>
            <Card><div className="card-body gap-1 p-[18px]">
              <Eyebrow>Pipeline</Eyebrow>
              <strong className="text-[17px] text-[#eef5ff]">{schedule?.activeRunId ? 'Running' : 'Ready'}</strong>
            </div></Card>
          </div>

          <Card className="mt-6"><div className="card-body p-[22px]">
            <h2 className="m-0 text-[19px] font-bold text-[#eef5ff]">Run a market update</h2>
            <p className="m-0 text-[14px] text-[#8fa0b8]">Manual runs leave the recurring schedule unchanged.</p>
            <form className="mt-4 grid gap-4" onSubmit={e => { e.preventDefault(); void start() }}>
              <fieldset disabled={submitting || !!schedule?.activeRunId}
                className="grid gap-3 rounded-[12px] border border-white/[0.09] p-4">
                <legend className="px-1 text-[13px] font-semibold text-[#c6d1df]">Sources</legend>
                {sources.length === 0 && <p className="m-0 text-[14px] text-[#8fa0b8]">No sources have been registered.</p>}
                {sources.map(source => (
                  <label key={source.sourceId} className="flex items-start gap-3">
                    <input type="checkbox" className="checkbox checkbox-sm mt-[2px]" disabled={!source.enabled}
                      checked={selected.includes(source.sourceId)}
                      onChange={e => setSelected(old => e.target.checked ? [...old, source.sourceId] : old.filter(id => id !== source.sourceId))} />
                    <span className="flex flex-col gap-1">
                      <span className="text-[14px] text-[#eef5ff]">
                        {source.sourceId}{' '}
                        {source.simulation && <Tag>Simulation</Tag>}{' '}
                        {!source.enabled && <Tag>Disabled</Tag>}
                      </span>
                      {source.nextAllowedAt && new Date(source.nextAllowedAt) > new Date() &&
                        <small className="text-[12px] text-[#8fa0b8]">Available after {when(source.nextAllowedAt)}</small>}
                    </span>
                  </label>
                ))}
              </fieldset>
              <Field label="Reason (optional)">
                <Input value={reason} maxLength={500} onChange={e => setReason(e.target.value)}
                  placeholder="For example, a major mid-cycle phone release" />
              </Field>
              <Button type="submit" variant="primary" loading={submitting} className="justify-self-start"
                disabled={submitting || !!schedule?.activeRunId || selected.length === 0}>
                {schedule?.activeRunId ? 'Run in progress' : 'Run now'}
              </Button>
            </form>
          </div></Card>

          <Card className="mt-6" aria-live="polite"><div className="card-body p-[22px]">
            <h2 className="m-0 text-[19px] font-bold text-[#eef5ff]">Recent runs</h2>
            {runs.length === 0 ? (
              <Callout className="mt-3">No runs yet. Select a source above to start your first update.</Callout>
            ) : (
              <div className="mt-3 overflow-x-auto">
                <table className="w-full text-[13px]">
                  <thead className="text-[#8fa0b8]">
                    <tr>
                      <th className="border-b border-white/[0.09] px-2 py-3 text-left">Requested</th>
                      <th className="border-b border-white/[0.09] px-2 py-3 text-left">Trigger</th>
                      <th className="border-b border-white/[0.09] px-2 py-3 text-left">Status</th>
                      <th className="border-b border-white/[0.09] px-2 py-3 text-left">Processed</th>
                      <th className="border-b border-white/[0.09] px-2 py-3 text-left">Error stacks</th>
                      <th className="border-b border-white/[0.09] px-2 py-3 text-left">Source results</th>
                    </tr>
                  </thead>
                  <tbody>
                    {runs.map(run => (
                      <tr key={run.runId}>
                        <td className="border-b border-white/[0.06] px-2 py-3 align-top" title={run.runId}>{when(run.requestedAt)}</td>
                        <td className="border-b border-white/[0.06] px-2 py-3 align-top">{run.triggerType}</td>
                        <td className="border-b border-white/[0.06] px-2 py-3 align-top">
                          {run.status}
                          <small className="block text-[12px] text-[#8fa0b8]">
                            {run.finishedAt ? `Finished ${when(run.finishedAt)}` : 'Awaiting completion'}</small>
                        </td>
                        <td className="border-b border-white/[0.06] px-2 py-3 align-top">{run.processedPayloadCount}</td>
                        <td className="border-b border-white/[0.06] px-2 py-3 align-top">{run.errorStackCount}</td>
                        <td className="border-b border-white/[0.06] px-2 py-3 align-top">
                          {run.sources.map(source => (
                            <small key={source.sourceId} className="block text-[12px] text-[#8fa0b8]">
                              {source.sourceId}: {source.status} ({source.processedPayloadCount} processed)</small>
                          ))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div></Card>
        </>}
      </div>
    </ThemeRoot>
  )
}
