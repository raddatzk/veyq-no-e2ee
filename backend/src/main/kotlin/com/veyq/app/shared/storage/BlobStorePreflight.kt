package com.veyq.app.shared.storage

import com.veyq.app.shared.config.AppConfig
import com.veyq.app.shared.config.BlobStoreConfig
import com.veyq.app.shared.config.UploadsConfig
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.jboss.logging.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Direct-to-store preflight (ADR 0021). Transport is direct-only: clients PUT/GET frames straight to the object
 * store via presigned URLs the backend signs for its PUBLIC endpoint — the backend never proxies bytes, so there
 * is no graceful fallback. The S3 client constructor already verifies the INTERNAL endpoint (the bucket exists),
 * but NOT that the PUBLIC presign target is reachable by clients or CORS-ready. This bean probes that at startup
 * and logs a clear, actionable message, so a misconfiguration surfaces LOUDLY in the operator's logs instead of
 * as silently-broken media for end users. It never blocks startup — a probe can fail for benign reasons (the
 * backend can't always hairpin to its own public host), so the signal is advisory.
 */
@ApplicationScoped
class BlobStorePreflight(
    private val store: BlobStore,
    private val blobCfg: BlobStoreConfig,
    private val uploadsCfg: UploadsConfig,
    app: AppConfig,
) {
    private val log = Logger.getLogger(BlobStorePreflight::class.java)
    private val webOrigin = app.baseUrl().trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun onStart(@Observes ev: StartupEvent) {
        if (!blobCfg.backend().equals("s3", ignoreCase = true)) {
            log.warn("veyq.blob-store.backend='${blobCfg.backend()}': direct-to-store transport is UNAVAILABLE (only the S3 backend can presign). Media upload/download will not work — this backend is for dev/test only.")
            return
        }
        if (!store.supportsPresign()) {
            log.error("The S3 blob store reports no presign support — direct-to-store transport (the only transport) is broken.")
            return
        }
        val publicEndpoint = blobCfg.s3PublicEndpoint().map { it.trim() }.filter { it.isNotEmpty() }
            .orElse(blobCfg.s3Endpoint().map { it.trim() }.orElse(""))
        if (publicEndpoint.isEmpty()) {
            log.warn("No public blob-store endpoint resolved; skipping the direct-to-store preflight.")
            return
        }
        try {
            probe(publicEndpoint)
        } catch (e: Exception) {
            log.warnf(
                "Direct-to-store preflight could NOT reach the public store endpoint (%s): %s. Clients transfer media directly to that URL — if it is not reachable from browsers/apps (or the reverse-proxy path route is wrong), media will fail. Check BLOB_STORE_S3_PUBLIC_ENDPOINT and the store's public route.",
                publicEndpoint, e.message ?: e.toString(),
            )
        }
    }

    private fun probe(publicEndpoint: String) {
        // Presign a throwaway PUT and OPTIONS-probe it exactly as a browser's CORS preflight would (Origin +
        // Access-Control-Request-Method), confirming the public route answers and — for a cross-origin store —
        // advertises CORS for our web origin. OPTIONS is an unauthenticated CORS preflight, so the PUT signature
        // is irrelevant; nothing is written.
        val url = store.presignPut("__veyq_preflight__/0", uploadsCfg.presignTtlSeconds()) ?: return
        val req = HttpRequest.newBuilder(URI.create(url))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .header("Origin", webOrigin)
            .header("Access-Control-Request-Method", "PUT")
            .timeout(Duration.ofSeconds(5))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
        val sameOrigin = url.startsWith("$webOrigin/")
        val allowOrigin = resp.headers().firstValue("access-control-allow-origin").orElse(null)
        when {
            sameOrigin -> log.infof("Direct-to-store preflight OK: the store is served same-origin with the web app (%s) — no CORS needed.", webOrigin)
            allowOrigin != null -> log.infof("Direct-to-store preflight OK: the cross-origin store advertises CORS (allow-origin=%s) for the web origin.", allowOrigin)
            else -> log.warnf(
                "Direct-to-store store at %s is reachable but returned no Access-Control-Allow-Origin for %s. Browser uploads/downloads will be BLOCKED by CORS — configure the bucket/store CORS to allow this origin (methods PUT+GET, expose ETag). Native apps are unaffected.",
                publicEndpoint, webOrigin,
            )
        }
    }
}
