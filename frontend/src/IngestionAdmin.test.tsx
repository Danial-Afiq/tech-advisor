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
      <Route path="/admin/catalogue" element={<p>catalogue route</p>} />
      <Route path="/login" element={<p>login route</p>} />
    </Routes>
  </MemoryRouter>
)
const signInAsAdmin = () => setSession({ token: 'admin-token', email: 'admin@example.com', role: 'ADMIN' })

/** `searchapi/candidates`, `/runs`, `/sources`, `/schedule` fixture server. */
function searchApiServer(post: (init: RequestInit) => { ok: boolean; status?: number; data: unknown }, enabled = true) {
  return vi.fn(async (url: string, init: RequestInit = {}) => {
    const path = url.split('/api/admin/ingestion')[1]
    if (path === '/searchapi/candidates' && init.method === 'POST') return { ok: true, json: async () => [
      { externalProductId: 'iphone-128', title: 'Apple iPhone 16 Pro 128GB' },
      { externalProductId: 'iphone-256', title: 'Apple iPhone 16 Pro 256GB' },
    ] }
    if (path === '/runs' && init.method === 'POST') {
      const response = post(init)
      return { ...response, json: async () => response.data }
    }
    const data: Record<string, unknown> = {
      '/sources': [
        { sourceId: 'searchapi-google-product-reviews', enabled, simulation: false, nextAllowedAt: null },
        { sourceId: 'simulated-release', enabled: true, simulation: true, nextAllowedAt: null },
      ],
      '/schedule': { enabled: false, intervalHours: 336, activeRunId: null },
      '/runs': [{ runId: 'prior', status: 'SUCCESS', triggerType: 'MANUAL',
        requestedAt: '2026-09-24T00:00:00Z', finishedAt: '2026-09-24T00:00:02Z',
        product: { productId: 42, productName: 'Apple iPhone 16 Pro' }, processedPayloadCount: 1, errorStackCount: 0, sources: [] }],
    }
    return { ok: true, json: async () => data[path] }
  })
}

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

  it('navigates to the catalogue from the shared admin sidebar', async () => {
    signInAsAdmin()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})))

    withAppRoutes()
    await userEvent.click(screen.getByRole('button', { name: 'Catalogue' }))

    expect(await screen.findByText('catalogue route')).toBeInTheDocument()
  })

  it('clears the session when manual ingestion is forbidden', async () => {
    signInAsAdmin()
    vi.stubGlobal('fetch', searchApiServer(() => ({
      ok: false,
      status: 403,
      data: { error: 'Admin access required.' },
    })))

    withAppRoutes()
    await userEvent.click(await screen.findByRole('checkbox', { name: /simulated-release/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))

    expect(await screen.findByText('login route')).toBeInTheDocument()
    expect(getSession()).toBeNull()
  })

  it('clears the session when SearchAPI product lookup is unauthorized', async () => {
    signInAsAdmin()
    const successfulRequests = searchApiServer(() => ({ ok: true, data: {} }))
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
      if (url.endsWith('/searchapi/candidates')) {
        return { ok: false, status: 401, json: async () => ({ error: 'Session expired.' }) }
      }
      return successfulRequests(url, init)
    }))

    withAppRoutes()
    await userEvent.click(await screen.findByRole('checkbox', { name: 'SearchAPI customer reviews' }))
    await userEvent.type(screen.getByRole('textbox', { name: 'Smartphone name' }), 'Apple iPhone 16 Pro')
    await userEvent.click(screen.getByRole('button', { name: 'Find matching products' }))

    expect(await screen.findByText('login route')).toBeInTheDocument()
    expect(getSession()).toBeNull()
  })

  it('shows a SearchAPI lookup error without clearing a valid session', async () => {
    signInAsAdmin()
    const successfulRequests = searchApiServer(() => ({ ok: true, data: {} }))
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
      if (url.endsWith('/searchapi/candidates')) {
        return { ok: false, status: 502, json: async () => ({ error: 'SearchAPI is unavailable.' }) }
      }
      return successfulRequests(url, init)
    }))

    withAppRoutes()
    await userEvent.click(await screen.findByRole('checkbox', { name: 'SearchAPI customer reviews' }))
    await userEvent.type(screen.getByRole('textbox', { name: 'Smartphone name' }), 'Apple iPhone 16 Pro')
    await userEvent.click(screen.getByRole('button', { name: 'Find matching products' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('SearchAPI is unavailable.')
    expect(getSession()).toMatchObject({ role: 'ADMIN' })
  })

  it('sends the real bearer token and idempotency key, with no CSRF header', async () => {
    signInAsAdmin()
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

  it('requires a phone for SearchAPI, submits it, and shows the chosen product in history', async () => {
    signInAsAdmin()
    const fetcher = searchApiServer(() => ({ ok: true, data: { runId: 'new' } }))
    vi.stubGlobal('fetch', fetcher)
    withRouter(<IngestionAdmin />)
    expect(screen.queryByRole('textbox', { name: 'Smartphone name' })).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('checkbox', { name: 'SearchAPI customer reviews' }))
    expect(screen.getByRole('button', { name: 'Run now' })).toBeDisabled()
    await userEvent.type(screen.getByRole('textbox', { name: 'Smartphone name' }), '  Apple iPhone 16 Pro  ')
    await userEvent.click(screen.getByRole('button', { name: 'Find matching products' }))
    expect(await screen.findByRole('radio', { name: /Apple iPhone 16 Pro 128GB/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run now' })).toBeDisabled()
    await userEvent.click(screen.getByRole('radio', { name: /Apple iPhone 16 Pro 256GB/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([url, init]) => url.endsWith('/runs') && init?.method === 'POST')).toBe(true))
    const submitted = fetcher.mock.calls.find(([url, init]) => url.endsWith('/runs') && init?.method === 'POST')![1]!
    expect(JSON.parse(submitted.body as string)).toEqual({ sources: ['searchapi-google-product-reviews'],
      productName: 'Apple iPhone 16 Pro', externalProductId: 'iphone-256', reason: null })
    expect(submitted.headers).toMatchObject({ Authorization: 'Bearer admin-token', 'Idempotency-Key': expect.any(String) })
    expect(submitted.headers).not.toHaveProperty('X-CSRF-TOKEN')
    expect(screen.getByRole('cell', { name: 'Apple iPhone 16 Pro' })).toBeInTheDocument()
  })

  it('omits the phone when SearchAPI is unchecked and preserves ordinary source submissions', async () => {
    signInAsAdmin()
    const fetcher = searchApiServer(() => ({ ok: true, data: { runId: 'new' } }))
    vi.stubGlobal('fetch', fetcher)
    withRouter(<IngestionAdmin />)
    const checkbox = await screen.findByRole('checkbox', { name: 'SearchAPI customer reviews' })
    await userEvent.click(checkbox)
    await userEvent.type(screen.getByRole('textbox', { name: 'Smartphone name' }), 'Apple iPhone 16 Pro')
    await userEvent.click(checkbox)
    expect(screen.queryByRole('textbox', { name: 'Smartphone name' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: /simulated-release/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true))
    expect(JSON.parse(fetcher.mock.calls.find(([, init]) => init?.method === 'POST')![1]!.body as string))
      .toEqual({ sources: ['simulated-release'], reason: null })
  })

  it('shows product validation errors, retries with the same key, and changes it when the name changes', async () => {
    signInAsAdmin()
    const fetcher = searchApiServer(() => ({ ok: false, status: 400,
      data: { message: 'No verified smartphone matches that name.' } }))
    vi.stubGlobal('fetch', fetcher)
    withRouter(<IngestionAdmin />)
    await userEvent.click(await screen.findByRole('checkbox', { name: 'SearchAPI customer reviews' }))
    const input = screen.getByRole('textbox', { name: 'Smartphone name' })
    await userEvent.type(input, 'Unknown phone')
    await userEvent.click(screen.getByRole('button', { name: 'Find matching products' }))
    await userEvent.click(await screen.findByRole('radio', { name: /128GB/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No verified smartphone matches that name.')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run now' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run now' })).toBeEnabled())
    await userEvent.clear(input); await userEvent.type(input, 'Apple iPhone 16 Pro')
    expect(screen.getByRole('button', { name: 'Run now' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Find matching products' }))
    await userEvent.click(await screen.findByRole('radio', { name: /128GB/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(fetcher.mock.calls.filter(([url, init]) => url.endsWith('/runs') && init?.method === 'POST')).toHaveLength(3))
    const keys = fetcher.mock.calls.filter(([url, init]) => url.endsWith('/runs') && init?.method === 'POST')
      .map(([, init]) => (init!.headers as Record<string, string>)['Idempotency-Key'])
    expect(keys[0]).toBe(keys[1]); expect(keys[2]).not.toBe(keys[0])
  })

  it('keeps disabled SearchAPI unavailable', async () => {
    signInAsAdmin()
    vi.stubGlobal('fetch', searchApiServer(() => ({ ok: true, data: {} }), false))
    withRouter(<IngestionAdmin />)
    expect(await screen.findByRole('checkbox', { name: /SearchAPI customer reviews/ })).toBeDisabled()
    expect(screen.queryByRole('textbox', { name: 'Smartphone name' })).not.toBeInTheDocument()
  })
})
