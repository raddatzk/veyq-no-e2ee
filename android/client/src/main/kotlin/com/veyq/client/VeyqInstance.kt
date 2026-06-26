package com.veyq.client

/**
 * The base address of an already-bootstrapped veyq instance — the Android twin of Apple's `VeyqInstance`.
 * Native never bootstraps (first-run setup is browser-only); the login/unlock orchestration hangs off this.
 *
 * @param baseUrl the instance origin, no trailing slash (e.g. `https://veyq.example` or, from the emulator,
 *   `http://10.0.2.2:8080`). The generated clients append the absolute `/api/...` operation paths to it.
 */
data class VeyqInstance(val baseUrl: String)
