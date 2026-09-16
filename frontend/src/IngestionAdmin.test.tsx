import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IngestionAdmin from './IngestionAdmin'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('Ingestion admin', () => {
  it('shows an authorization error without exposing controls', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }))
    render(<IngestionAdmin />)
    await userEvent.click(screen.getByRole('button', { name: 'Connect as admin' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Admin access is unavailable')
    expect(screen.queryByRole('button', { name: 'Run now' })).not.toBeInTheDocument()
  })

  it('submits selected sources with CSRF and idempotency and displays persisted results', async () => {
    let submitted = false
    const fetcher = vi.fn(async (url: string, init: RequestInit) => {
      const path = url.split('/api/admin/ingestion')[1]
      let data: unknown
      if (path === '/session') data = { username: 'admin', csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'test-token' }
      if (path === '/sources') data = [{ sourceId: 'simulated-release', enabled: true, simulation: true }]
      if (path === '/schedule') data = { enabled: true, intervalHours: 24, nextScheduledAt: '2026-09-17T05:00:00Z', activeRunId: null }
      if (path === '/runs' && init.method === 'POST') { submitted = true; data = { runId: 'test-run' } }
      else if (path === '/runs') data = submitted ? [{ runId: 'test-run', status: 'SUCCESS', triggerType: 'MANUAL',
        requestedAt: '2026-09-16T00:00:00Z', finishedAt: '2026-09-16T00:00:02Z', processedPayloadCount: 3, errorStackCount: 0, sources: [] }] : []
      return { ok: true, json: async () => data }
    })
    vi.stubGlobal('fetch', fetcher)
    render(<IngestionAdmin />)
    await userEvent.click(screen.getByRole('button', { name: 'Connect as admin' }))
    const run = await screen.findByRole('button', { name: 'Run now' })
    expect(run).toBeDisabled()
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(run)
    await waitFor(() => expect(screen.getByText('SUCCESS')).toBeInTheDocument())
    const post = fetcher.mock.calls.find(([, init]) => init.method === 'POST')
    expect(post?.[1].headers).toMatchObject({ 'X-CSRF-TOKEN': 'test-token', 'Idempotency-Key': expect.any(String) })
    expect(JSON.parse(post?.[1].body as string)).toEqual({ sources: ['simulated-release'], reason: null })
    expect(screen.getByText('Every 24 hours')).toBeInTheDocument()
  })
})
