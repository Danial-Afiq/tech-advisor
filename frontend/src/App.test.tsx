import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearSession, setSession } from './api/session'
import App from './App'

vi.mock('./pages/Login', () => ({
  default: () => <h1>Login route</h1>,
}))

vi.mock("./pages/DashboardPage", () => ({
  default: () => <h1>Dashboard route</h1>,
}));

vi.mock('./pages/DevicesPageTest', () => ({
  default: () => <h1>Devices route</h1>,
}))

vi.mock('./IngestionAdmin', () => ({
  default: () => <h1>Admin ingestion route</h1>,
}))

vi.mock('./pages/AdminCatalogue', () => ({
  default: () => <h1>Admin catalogue route</h1>,
}))

function renderAt(path: string) {
  window.history.replaceState({}, '', path)
  return render(<App />)
}

async function expectRoute(heading: string, path: string) {
  expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument()
  expect(window.location.pathname).toBe(path)
}

afterEach(() => {
  clearSession()
  window.history.replaceState({}, '', '/')
})

describe('authenticated App routes', () => {
  it('redirects signed-out visitors from / to /login', async () => {
    renderAt('/')
    await expectRoute('Login route', '/login')
  })

it("redirects USER visitors from / to /dashboard", async () => {
    setSession({
      token: "user-token",
      email: "user@example.com",
      role: "USER",
    });

    renderAt("/");

    await expectRoute("Dashboard route", "/dashboard");
  });

  it('redirects ADMIN visitors from / to /admin/ingestion', async () => {
    setSession({ token: 'admin-token', email: 'admin@example.com', role: 'ADMIN' })
    renderAt('/')
    await expectRoute('Admin ingestion route', '/admin/ingestion')
  })

  it('redirects an authenticated USER away from /login', async () => {
    setSession({ token: 'user-token', email: 'user@example.com', role: 'USER' })
    renderAt('/login')
    await expectRoute("Dashboard route", "/dashboard");
  })

  it('redirects an authenticated ADMIN away from /login', async () => {
    setSession({ token: 'admin-token', email: 'admin@example.com', role: 'ADMIN' })
    renderAt('/login')
    await expectRoute('Admin ingestion route', '/admin/ingestion')
  })

  it('redirects signed-out access to /devices to /login', async () => {
    renderAt('/devices')
    await expectRoute('Login route', '/login')
  })

  it('redirects signed-out access to /admin/ingestion to /login', async () => {
    renderAt('/admin/ingestion')
    await expectRoute('Login route', '/login')
  })

  it('redirects a USER away from /admin/ingestion', async () => {
    setSession({ token: 'user-token', email: 'user@example.com', role: 'USER' })
    renderAt('/admin/ingestion')
    await expectRoute("Dashboard route", "/dashboard");
  })

  it('allows an ADMIN to access /admin/catalogue', async () => {
    setSession({ token: 'admin-token', email: 'admin@example.com', role: 'ADMIN' })
    renderAt('/admin/catalogue')
    await expectRoute('Admin catalogue route', '/admin/catalogue')
  })

  it('redirects signed-out access to /admin/catalogue to /login', async () => {
    renderAt('/admin/catalogue')
    await expectRoute('Login route', '/login')
  })

  it('redirects a USER away from /admin/catalogue', async () => {
    setSession({ token: 'user-token', email: 'user@example.com', role: 'USER' })
    renderAt('/admin/catalogue')
    await expectRoute('Dashboard route', '/dashboard')
  })

  it('redirects an ADMIN away from /devices', async () => {
    setSession({ token: 'admin-token', email: 'admin@example.com', role: 'ADMIN' })
    renderAt('/devices')
    await expectRoute('Admin ingestion route', '/admin/ingestion')
  })

  it('redirects the old /DevicesPageTest URL to /devices', async () => {
    setSession({ token: 'user-token', email: 'user@example.com', role: 'USER' })
    renderAt('/DevicesPageTest')
    await expectRoute('Devices route', '/devices')
  })

  it('redirects the old /IngestionAdmin URL to /admin/ingestion', async () => {
    setSession({ token: 'admin-token', email: 'admin@example.com', role: 'ADMIN' })
    renderAt('/IngestionAdmin')
    await expectRoute('Admin ingestion route', '/admin/ingestion')
  })

  it('still leaves unrelated unknown paths untouched', () => {
    renderAt('/no-such-page')
    expect(screen.queryByRole('heading')).not.toBeInTheDocument()
  })
})
