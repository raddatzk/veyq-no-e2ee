import http from 'k6/http'
import { check, fail } from 'k6'
import { config } from './config.js'

const sessions = {}

export function login(user) {
  const response = jsonRequest('POST', '/api/auth/login', {
    email: user.email,
    password: user.password,
  }, { name: 'POST /api/auth/login' })
  expectStatus(response, 200, 'login')
  return response.json()
}

export function ensureVuLogin(user) {
  const key = `${__VU || 0}:${user.email}`
  if (!sessions[key]) {
    sessions[key] = login(user)
  }
  return sessions[key]
}

export function get(path, params = {}) {
  return request('GET', path, null, params)
}

export function post(path, body, params = {}) {
  return body === null ? request('POST', path, null, params) : jsonRequest('POST', path, body, params)
}

export function put(path, body, params = {}) {
  return jsonRequest('PUT', path, body, params)
}

export function del(path, body = null, params = {}) {
  return body === null ? request('DELETE', path, null, params) : jsonRequest('DELETE', path, body, params)
}

export function putBytes(path, bytes, params = {}) {
  return request('PUT', path, bytes, {
    ...params,
    headers: {
      'Content-Type': 'application/octet-stream',
      ...(params.headers || {}),
    },
  })
}

export function jsonRequest(method, path, body, params = {}) {
  return request(method, path, JSON.stringify(body), {
    ...params,
    headers: {
      'Content-Type': 'application/json',
      ...(params.headers || {}),
    },
  })
}

export function request(method, path, body = null, params = {}) {
  const url = absoluteUrl(path)
  const response = http.request(method, url, body, requestParams(method, path, params))
  check(response, {
    [`${params.name || `${method} ${path}`} status < 500`]: (r) => r.status < 500,
  })
  return response
}

function requestParams(method, path, params) {
  const name = params.name || `${method} ${path}`
  return {
    ...params,
    tags: {
      type: 'api',
      name,
      ...(params.tags || {}),
    },
  }
}

export function expectStatus(response, status, label) {
  const ok = check(response, {
    [`${label} returned ${status}`]: (r) => r.status === status,
  })
  if (!ok) {
    fail(`${label} expected ${status}, got ${response.status}: ${response.body}`)
  }
}

export function acceptStatus(response, statuses, label) {
  return check(response, {
    [`${label} returned ${statuses.join(' or ')}`]: (r) => statuses.includes(r.status),
  })
}

export function absoluteUrl(path) {
  if (path.startsWith('http://') || path.startsWith('https://')) return path
  return `${config.baseUrl}${path.startsWith('/') ? '' : '/'}${path}`
}

export function encoded(value) {
  return encodeURIComponent(value)
}
