import { render, screen, waitFor, cleanup } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactElement } from 'react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IngestionAdmin from './IngestionAdmin'
import { clearSession, getSession, setSession } from './api/session'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); clearSession() })

const withRouter = (element: ReactElement) => render(<MemoryRouter>{element}</MemoryRouter>)
const withAppRoutes = () => render(
  <MemoryRouter initialEntries={['/admin/ingestion']}>
    <Routes>
      <Route path="/admin/ingestion" element={<IngestionAdmin />} />
      <Route path="/login" element={<p>login route</p>} />
    </Routes>
  </MemoryRouter>
)

describe('Ingestion admin', () => {
  it('prompts to sign in rather than showing run controls when signed out', () => {
    withRouter(<IngestionAdmin />)
    expect(screen.getByText('Sign in required')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Go to login' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Run now' })).not.toBeInTheDocument()
  })

  it('prompts to sign in when the signed-in account is not an admin', () => {
    setSession({ token: 'user-token', email: 'user@example.com', role: 'USER' })
    withRouter(<IngestionAdmin />)
    expect(screen.getByText('Admin access required')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Run now' })).not.toBeInTheDocument()
  })

  it('clears the session and returns to login when the backend rejects the admin session', async () => {
    setSession({ token: 'stale-token', email: 'admin@example.com', role: 'ADMIN' })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }))
    withAppRoutes()
    expect(await screen.findByText('login route')).toBeInTheDocument()
    expect(getSession()).toBeNull()
  })

  it('signs out through the normal session abstraction and returns to login', async () => {
    setSession({ token: 'admin-token', email: 'admin@example.com', role: 'ADMIN' })
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))
    const user = userEvent.setup()

    withAppRoutes()
    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    expect(await screen.findByText('login route')).toBeInTheDocument()
    expect(getSession()).toBeNull()
  })

  it('sends the real bearer token and idempotency key, with no CSRF header', async () => {
    setSession({ token: 'admin-token', email: 'admin@example.com', role: 'ADMIN' })
    let submitted = false
    const fetcher = vi.fn(async (url: string, init: RequestInit = {}) => {
      const path = url.split('/api/admin/ingestion')[1]
      let data: unknown
      if (path === '/sources') data = [{ sourceId: 'simulated-release', enabled: true, simulation: true, nextAllowedAt: null }]
      if (path === '/schedule') data = { enabled: true, intervalHours: 24, nextScheduledAt: '2026-09-17T05:00:00Z', activeRunId: null }
      if (path === '/runs' && init.method === 'POST') { submitted = true; data = { runId: 'test-run' } }
      else if (path === '/runs') data = submitted ? [{ runId: 'test-run', status: 'SUCCESS', triggerType: 'MANUAL',
        requestedAt: '2026-09-16T00:00:00Z', finishedAt: '2026-09-16T00:00:02Z', processedPayloadCount: 3, errorStackCount: 0, sources: [] }] : []
      return { ok: true, json: async () => data }
    })
    vi.stubGlobal('fetch', fetcher)

    withRouter(<IngestionAdmin />)
    const run = await screen.findByRole('button', { name: 'Run now' })
    expect(run).toBeDisabled()
    await userEvent.click(screen.getByRole('checkbox'))
    await userEvent.click(run)
    await waitFor(() => expect(screen.getByText('SUCCESS')).toBeInTheDocument())

    const post = fetcher.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post?.[1]?.headers).toMatchObject({ Authorization: 'Bearer admin-token', 'Idempotency-Key': expect.any(String) })
    expect(post?.[1]?.headers).not.toHaveProperty('X-CSRF-TOKEN')
    expect(JSON.parse(post?.[1]?.body as string)).toEqual({ sources: ['simulated-release'], reason: null })
    expect(screen.getByText('Every 24 hours')).toBeInTheDocument()
  })
})
