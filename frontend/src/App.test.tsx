import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import App from './App'

function renderAt(path: string) {
  window.history.pushState({}, '', path)
  render(<App />)
}

afterEach(() => {
  window.history.pushState({}, '', '/')
})

describe('App routes', () => {
  it('renders the login page at /login', () => {
    renderAt('/login')

    expect(screen.getByLabelText('Email')).toBeInTheDocument()
  })

  it('renders nothing for an unknown path', () => {
    renderAt('/no-such-page')

    expect(screen.queryByRole('heading')).not.toBeInTheDocument()
  })
})
