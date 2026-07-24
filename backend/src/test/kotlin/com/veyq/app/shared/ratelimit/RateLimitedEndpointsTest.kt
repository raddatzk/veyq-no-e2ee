package com.veyq.app.shared.ratelimit

import com.veyq.app.features.admin.setup.SetupResource
import com.veyq.app.features.admin.tenant.TenantsResource
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins that the public (`@PermitAll`) account-creation endpoints carry `@RateLimited` (cr-0130). The auth
 * limiter is name-bound, so a silently dropped annotation would un-limit an unauthenticated write with no other
 * signal. The filter *mechanism* (per-IP fixed window, 429) is covered by `AuthRateLimitTest` /
 * `AuthRateLimitWindowResetTest`; this test guards the wiring.
 *
 * The two tenant-owner member-invite mint endpoints deliberately do **not** carry `@RateLimited`
 * (cr-email-invite-send-hardening): the per-IP filter shares one global window with the unauthenticated auth
 * endpoints, so an active owner could trip the login limit. Their anti-bombing guard is the handler-level,
 * per-(tenant,recipient) `InviteMailRateLimiter` instead (see `MemberAddSliceTest`). A future declarative
 * auth+rate-limit **posture** annotation + guard is the right home for asserting their declaration.
 */
class RateLimitedEndpointsTest {

    private fun rateLimited(cls: Class<*>, method: String): Boolean =
        cls.methods.any { it.name == method && it.isAnnotationPresent(RateLimited::class.java) }

    @Test
    fun publicAccountCreationEndpointsAreRateLimited() {
        assertTrue(rateLimited(SetupResource::class.java, "createServerOwner"), "SetupResource.createServerOwner missing @RateLimited")
        assertTrue(rateLimited(TenantsResource::class.java, "previewTenantInvite"), "TenantsResource.previewTenantInvite missing @RateLimited")
        assertTrue(rateLimited(TenantsResource::class.java, "registerAndAcceptTenantInvite"), "TenantsResource.registerAndAcceptTenantInvite missing @RateLimited")
    }
}
