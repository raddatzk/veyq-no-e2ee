import { sleep } from 'k6'
import { config, primaryUser, vuUser } from './lib/config.js'
import { ensureVuLogin, login } from './lib/http.js'
import {
  browsePrivateMemory,
  browsePublicShare,
  cleanupRunData,
  createRunData,
  editJournal,
  uploadPrivateMedia,
  uploadPublicMedia,
} from './lib/veyq.js'

export const options = profileOptions(config.profile)

export function setup() {
  login(primaryUser())
  return createRunData()
}

export default function (data) {
  ensureVuLogin(vuUser())

  if (config.profile === 'upload-focused') {
    browsePrivateMemory(data)
    uploadPrivateMedia(data)
    sleep(1)
    return
  }

  if (config.profile === 'smoke') {
    browsePrivateMemory(data)
    editJournal(data)
    uploadPrivateMedia(data)
    browsePublicShare(data)
    uploadPublicMedia(data)
    sleep(1)
    return
  }

  const selector = (__ITER + (__VU || 1)) % 10
  if (selector < 5) browsePrivateMemory(data)
  else if (selector < 7) editJournal(data)
  else if (selector < 9) browsePublicShare(data)
  else uploadPrivateMedia(data)
  sleep(1)
}

export function teardown(data) {
  login(primaryUser())
  cleanupRunData(data)
}

function profileOptions(profile) {
  const common = {
    thresholds: {
      http_req_failed: ['rate<0.01'],
      'http_req_duration{type:api}': ['p(95)<2000'],
      http_req_duration: ['p(95)<5000'],
    },
  }

  if (profile === 'capacity-step') {
    const step1 = Math.min(1, config.vusMax)
    const step3 = Math.min(3, config.vusMax)
    const step5 = Math.min(5, config.vusMax)
    const step10 = Math.min(10, config.vusMax)
    const step20 = Math.min(20, config.vusMax)
    return {
      ...common,
      stages: [
        { duration: '2m', target: step1 },
        { duration: '5m', target: step1 },
        { duration: '2m', target: step3 },
        { duration: '5m', target: step3 },
        { duration: '2m', target: step5 },
        { duration: '5m', target: step5 },
        { duration: '2m', target: step10 },
        { duration: '5m', target: step10 },
        { duration: '2m', target: step20 },
        { duration: '5m', target: step20 },
        { duration: '2m', target: config.vusMax },
        { duration: '5m', target: config.vusMax },
        { duration: '2m', target: 0 },
      ],
    }
  }

  if (profile === 'upload-focused') {
    return {
      ...common,
      scenarios: {
        upload_focused: {
          executor: 'constant-vus',
          vus: Math.min(5, config.vusMax),
          duration: '10m',
        },
      },
    }
  }

  return {
    ...common,
    scenarios: {
      smoke: {
        executor: 'constant-vus',
        vus: Math.min(2, config.vusMax),
        duration: '3m',
      },
    },
  }
}
