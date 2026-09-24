import { render, screen, waitFor, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IngestionAdmin from './IngestionAdmin'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

async function connectAsAdmin() {
  const password = screen.queryByLabelText('Demo password')
  if (password) await userEvent.type(password, 'test-password-only')
  await userEvent.click(screen.getByRole('button', { name: 'Connect as admin' }))
}

function searchApiServer(post: (init: RequestInit) => { ok: boolean; status?: number; data: unknown }, enabled = true) {
  return vi.fn(async (url: string, init: RequestInit) => {
    const path = url.split('/api/admin/ingestion')[1]
    if (path === '/runs' && init.method === 'POST') {
      const response = post(init)
      return { ...response, json: async () => response.data }
    }
    const data: Record<string, unknown> = {
      '/session': { username: 'admin', csrfHeader: 'X-CSRF-TOKEN', csrfToken: 'test-token' },
      '/sources': [
        { sourceId: 'searchapi-google-product-reviews', enabled, simulation: false },
        { sourceId: 'simulated-release', enabled: true, simulation: true },
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
  it('requires a phone for SearchAPI, submits it with CSRF, and shows the chosen product in history', async () => {
    const fetcher = searchApiServer(() => ({ ok: true, data: { runId: 'new' } }))
    vi.stubGlobal('fetch', fetcher)
    render(<IngestionAdmin />)
    await connectAsAdmin()
    expect(screen.queryByRole('textbox', { name: 'Smartphone name' })).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('checkbox', { name: 'SearchAPI customer reviews' }))
    expect(screen.getByRole('button', { name: 'Run now' })).toBeDisabled()
    await userEvent.type(screen.getByRole('textbox', { name: 'Smartphone name' }), '  Apple iPhone 16 Pro  ')
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([, init]) => init.method === 'POST')).toBe(true))
    const submitted = fetcher.mock.calls.find(([, init]) => init.method === 'POST')![1]
    expect(JSON.parse(submitted.body as string)).toEqual({ sources: ['searchapi-google-product-reviews'],
      productName: 'Apple iPhone 16 Pro', reason: null })
    expect(submitted.headers).toMatchObject({ 'X-CSRF-TOKEN': 'test-token', 'Idempotency-Key': expect.any(String) })
    expect(screen.getByRole('cell', { name: 'Apple iPhone 16 Pro' })).toBeInTheDocument()
  })

  it('omits the phone when SearchAPI is unchecked and preserves ordinary source submissions', async () => {
    const fetcher = searchApiServer(() => ({ ok: true, data: { runId: 'new' } }))
    vi.stubGlobal('fetch', fetcher)
    render(<IngestionAdmin />)
    await connectAsAdmin()
    const checkbox = await screen.findByRole('checkbox', { name: 'SearchAPI customer reviews' })
    await userEvent.click(checkbox)
    await userEvent.type(screen.getByRole('textbox', { name: 'Smartphone name' }), 'Apple iPhone 16 Pro')
    await userEvent.click(checkbox)
    expect(screen.queryByRole('textbox', { name: 'Smartphone name' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: /simulated-release/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([, init]) => init.method === 'POST')).toBe(true))
    expect(JSON.parse(fetcher.mock.calls.find(([, init]) => init.method === 'POST')![1].body as string))
      .toEqual({ sources: ['simulated-release'], reason: null })
  })

  it('shows product validation errors, retries with the same key, and changes it when the name changes', async () => {
    const fetcher = searchApiServer(() => ({ ok: false, status: 400,
      data: { message: 'No verified smartphone matches that name.' } }))
    vi.stubGlobal('fetch', fetcher)
    render(<IngestionAdmin />)
    await connectAsAdmin()
    await userEvent.click(await screen.findByRole('checkbox', { name: 'SearchAPI customer reviews' }))
    const input = screen.getByRole('textbox', { name: 'Smartphone name' })
    await userEvent.type(input, 'Unknown phone')
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('No verified smartphone matches that name.')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run now' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Run now' })).toBeEnabled())
    await userEvent.clear(input); await userEvent.type(input, 'Apple iPhone 16 Pro')
    await userEvent.click(screen.getByRole('button', { name: 'Run now' }))
    await waitFor(() => expect(fetcher.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(3))
    const keys = fetcher.mock.calls.filter(([, init]) => init.method === 'POST')
      .map(([, init]) => (init.headers as Record<string, string>)['Idempotency-Key'])
    expect(keys[0]).toBe(keys[1]); expect(keys[2]).not.toBe(keys[0])
  })

  it('keeps disabled SearchAPI unavailable', async () => {
    vi.stubGlobal('fetch', searchApiServer(() => ({ ok: true, data: {} }), false))
    render(<IngestionAdmin />)
    await connectAsAdmin()
    expect(await screen.findByRole('checkbox', { name: /SearchAPI customer reviews/ })).toBeDisabled()
    expect(screen.queryByRole('textbox', { name: 'Smartphone name' })).not.toBeInTheDocument()
  })
  it('shows an authorization error without exposing controls', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }))
    render(<IngestionAdmin />)
    await connectAsAdmin()
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
    await connectAsAdmin()
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
