import * as Y from 'yjs'

export function createDocumentRegistry({
  backend,
  debounceMs = 750,
  idleEvictionMs = 300000,
  setTimer = setTimeout,
  clearTimer = clearTimeout,
  now = Date.now,
  logger = console
}) {
  const sessions = new Map()
  const documents = new Map()

  function sessionKey(token, documentId) {
    return `${documentId}\n${token}`
  }

  function markdownFromDoc(document) {
    return document.getText('markdown').toString()
  }

  function ensureInitialMarkdown(document, markdown) {
    const text = document.getText('markdown')
    if (text.length === 0 && markdown.length > 0) {
      text.insert(0, markdown)
    }
  }

  function sessionExpired(session) {
    return session?.expiresAt && Date.parse(session.expiresAt) <= now()
  }

  function cleanupSessions() {
    for (const [key, session] of sessions.entries()) {
      if (sessionExpired(session)) sessions.delete(key)
    }
  }

  function documentState(documentName) {
    let state = documents.get(documentName)
    if (!state) {
      state = {
        document: new Y.Doc(),
        connections: 0,
        initialized: false,
        idleTimer: null,
        snapshotTimer: null,
        persistChain: Promise.resolve(),
        lastPersistedMarkdown: null,
        token: ''
      }
      documents.set(documentName, state)
    }
    return state
  }

  function scheduleIdleEviction(documentName, state) {
    if (state.idleTimer) clearTimer(state.idleTimer)
    if (state.connections > 0) return
    state.idleTimer = setTimer(() => {
      if (state.connections > 0) return
      if (state.snapshotTimer) clearTimer(state.snapshotTimer)
      state.document.destroy()
      documents.delete(documentName)
    }, idleEvictionMs)
  }

  function persistSnapshot(documentName, state, token) {
    const markdown = markdownFromDoc(state.document)
    if (markdown === state.lastPersistedMarkdown) return state.persistChain
    state.token = token || state.token
    const persistToken = state.token
    if (!persistToken) return state.persistChain

    state.persistChain = state.persistChain
      .catch(() => undefined)
      .then(async () => {
        await backend.persistSnapshot({
          token: persistToken,
          documentId: documentName,
          markdown
        })
        state.lastPersistedMarkdown = markdown
      })
    return state.persistChain
  }

  async function authenticate({ token, documentName }) {
    cleanupSessions()
    if (!token) throw new Error('Missing journal live token')
    const session = await backend.validateSession({ token, documentId: documentName })
    sessions.set(sessionKey(token, documentName), session)
    documentState(documentName).token = token
    return { ...session, token }
  }

  function connected({ documentName }) {
    const state = documentState(documentName)
    if (state.idleTimer) {
      clearTimer(state.idleTimer)
      state.idleTimer = null
    }
    state.connections += 1
  }

  async function loadDocument({ documentName, context }) {
    const key = sessionKey(context.token, documentName)
    const cachedSession = sessions.get(key)
    const session = cachedSession && !sessionExpired(cachedSession)
      ? cachedSession
      : await backend.validateSession({ token: context.token, documentId: documentName })
    sessions.set(key, session)
    const state = documentState(documentName)
    state.token = context.token
    if (!state.initialized) {
      ensureInitialMarkdown(state.document, session.markdown || '')
      state.lastPersistedMarkdown = markdownFromDoc(state.document)
      state.initialized = true
    }
    return state.document
  }

  function storeDocument({ documentName, document, context }) {
    const state = documentState(documentName)
    state.document = document
    state.token = context?.token || state.token
    if (state.snapshotTimer) clearTimer(state.snapshotTimer)
    state.snapshotTimer = setTimer(() => {
      state.snapshotTimer = null
      persistSnapshot(documentName, state, context?.token).catch((error) => {
        logger.error(`Failed to persist journal snapshot for ${documentName}:`, error)
      })
    }, debounceMs)
  }

  async function disconnect({ documentName, document, context }) {
    const state = documents.get(documentName)
    if (!state || !document) return
    state.connections = Math.max(0, state.connections - 1)
    state.document = document
    state.token = context?.token || state.token
    if (state.snapshotTimer) {
      clearTimer(state.snapshotTimer)
      state.snapshotTimer = null
    }
    await persistSnapshot(documentName, state, context?.token).catch((error) => {
      logger.error(`Failed to persist journal snapshot for ${documentName}:`, error)
    })
    scheduleIdleEviction(documentName, state)
  }

  return {
    authenticate,
    connected,
    loadDocument,
    storeDocument,
    disconnect,
    documentCount: () => documents.size,
    sessionCount: () => sessions.size,
    stateFor: (documentName) => documents.get(documentName)
  }
}
