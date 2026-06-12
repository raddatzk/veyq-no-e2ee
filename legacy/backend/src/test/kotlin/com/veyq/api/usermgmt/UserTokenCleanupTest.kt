package com.veyq.api.usermgmt

import com.veyq.api.cron.UserTokenCleanup
import com.veyq.api.domain.ResetPasswordToken
import com.veyq.api.domain.ResetPasswordTokenRepo
import com.veyq.api.domain.ServerRole
import com.veyq.api.domain.Tenant
import com.veyq.api.domain.TenantMember
import com.veyq.api.domain.TenantMemberId
import com.veyq.api.domain.TenantMemberRepo
import com.veyq.api.domain.TenantRepo
import com.veyq.api.domain.TenantRole
import com.veyq.api.domain.User
import com.veyq.api.domain.UserEmailChange
import com.veyq.api.domain.UserEmailChangeRepo
import com.veyq.api.domain.UserInvitation
import com.veyq.api.domain.UserInvitationRepo
import com.veyq.api.domain.UserRepo
import io.quarkus.test.TestTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test

@QuarkusTest
class UserTokenCleanupTest {
    @Inject
    lateinit var userTokenCleanup: UserTokenCleanup
    @Inject
    lateinit var resetPasswordTokenRepo: ResetPasswordTokenRepo
    @Inject
    lateinit var userInvitationRepo: UserInvitationRepo
    @Inject
    lateinit var userEmailChangeRepo: UserEmailChangeRepo
    @Inject
    lateinit var userRepo: UserRepo
    @Inject
    lateinit var tenantRepo: TenantRepo
    @Inject
    lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject
    lateinit var entityManager: EntityManager

    @Test
    @TestTransaction
    fun `cleanup deletes old invalid user workflow tokens and keeps active or recent invalid ones`() {
        val user = createUser()
        val tenant = createTenant()
        val now = Instant.now()

        val oldExpiredReset = resetPasswordToken(user, now.minus(400, ChronoUnit.DAYS))
        val oldAcceptedReset = resetPasswordToken(user, now.plus(7, ChronoUnit.DAYS), acceptedAt = now.minus(400, ChronoUnit.DAYS))
        val oldRevokedReset = resetPasswordToken(user, now.plus(7, ChronoUnit.DAYS), revokedAt = now.minus(400, ChronoUnit.DAYS))
        val activeReset = resetPasswordToken(user, now.plus(7, ChronoUnit.DAYS))
        val recentExpiredReset = resetPasswordToken(user, now.minus(2, ChronoUnit.DAYS))

        val oldExpiredInvitation = invitation(user, tenant, now.minus(400, ChronoUnit.DAYS))
        val oldAcceptedInvitation = invitation(user, tenant, now.plus(7, ChronoUnit.DAYS), acceptedAt = now.minus(400, ChronoUnit.DAYS))
        val oldRevokedInvitation = invitation(user, tenant, now.plus(7, ChronoUnit.DAYS), revokedAt = now.minus(400, ChronoUnit.DAYS))
        val activeInvitation = invitation(user, tenant, now.plus(7, ChronoUnit.DAYS))
        val recentExpiredInvitation = invitation(user, tenant, now.minus(2, ChronoUnit.DAYS))

        val oldExpiredEmailChange = emailChange(user, now.minus(400, ChronoUnit.DAYS))
        val oldAcceptedEmailChange = emailChange(user, now.plus(7, ChronoUnit.DAYS), acceptedAt = now.minus(400, ChronoUnit.DAYS))
        val oldRevokedEmailChange = emailChange(user, now.plus(7, ChronoUnit.DAYS), revokedAt = now.minus(400, ChronoUnit.DAYS))
        val activeEmailChange = emailChange(user, now.plus(7, ChronoUnit.DAYS))
        val recentExpiredEmailChange = emailChange(user, now.minus(2, ChronoUnit.DAYS))
        val inactiveExpiredTenant = inactiveTenantWithOwnerInvitation(user, now.minus(1, ChronoUnit.DAYS))
        val inactiveOpenTenant = inactiveTenantWithOwnerInvitation(user, now.plus(1, ChronoUnit.DAYS))
        val activeTenantWithExpiredInvitation = activeTenantWithOwnerInvitation(user, now.minus(1, ChronoUnit.DAYS))

        resetPasswordTokenRepo.flush()
        userInvitationRepo.flush()
        userEmailChangeRepo.flush()
        tenantRepo.flush()
        entityManager.clear()

        userTokenCleanup.cleanup()
        entityManager.clear()

        assertThat(resetPasswordTokenRepo.findById(oldExpiredReset.id)).isNull()
        assertThat(resetPasswordTokenRepo.findById(oldAcceptedReset.id)).isNull()
        assertThat(resetPasswordTokenRepo.findById(oldRevokedReset.id)).isNull()
        assertThat(resetPasswordTokenRepo.findById(activeReset.id)).isNotNull()
        assertThat(resetPasswordTokenRepo.findById(recentExpiredReset.id)).isNotNull()

        assertThat(userInvitationRepo.findById(oldExpiredInvitation.id)).isNull()
        assertThat(userInvitationRepo.findById(oldAcceptedInvitation.id)).isNull()
        assertThat(userInvitationRepo.findById(oldRevokedInvitation.id)).isNull()
        assertThat(userInvitationRepo.findById(activeInvitation.id)).isNotNull()
        assertThat(userInvitationRepo.findById(recentExpiredInvitation.id)).isNotNull()

        assertThat(userEmailChangeRepo.findById(oldExpiredEmailChange.id)).isNull()
        assertThat(userEmailChangeRepo.findById(oldAcceptedEmailChange.id)).isNull()
        assertThat(userEmailChangeRepo.findById(oldRevokedEmailChange.id)).isNull()
        assertThat(userEmailChangeRepo.findById(activeEmailChange.id)).isNotNull()
        assertThat(userEmailChangeRepo.findById(recentExpiredEmailChange.id)).isNotNull()

        assertThat(tenantRepo.findById(inactiveExpiredTenant.id)).isNull()
        assertThat(tenantRepo.findById(inactiveOpenTenant.id)).isNotNull()
        assertThat(tenantRepo.findById(activeTenantWithExpiredInvitation.id)).isNotNull()
    }

    private fun createTenant(): Tenant {
        val unique = UUID.randomUUID().toString().take(8)
        return Tenant().also {
            it.name = "user-token-cleanup-$unique"
            it.displayName = "User Token Cleanup $unique"
            it.domain = "user-token-cleanup-$unique.example.test"
            it.activatedAt = Instant.now()
            tenantRepo.persist(it)
        }
    }

    private fun inactiveTenantWithOwnerInvitation(user: User, expiresAt: Instant): Tenant =
        tenantWithOwnerInvitation(user, expiresAt, activatedAt = null)

    private fun activeTenantWithOwnerInvitation(user: User, expiresAt: Instant): Tenant =
        tenantWithOwnerInvitation(user, expiresAt, activatedAt = Instant.now())

    private fun tenantWithOwnerInvitation(user: User, expiresAt: Instant, activatedAt: Instant?): Tenant {
        val unique = UUID.randomUUID().toString().take(8)
        val tenant = Tenant().also {
            it.name = "tenant-lifecycle-$unique"
            it.displayName = "Tenant Lifecycle $unique"
            it.domain = "tenant-lifecycle-$unique.example.test"
            it.activatedAt = activatedAt
            tenantRepo.persist(it)
        }
        tenantRepo.flush()
        TenantMember().also {
            it.id = TenantMemberId(tenant.id, user.id)
            it.tenant = tenant
            it.user = user
            it.role = TenantRole.TenantOwner
            it.acceptedAt = activatedAt
            tenantMemberRepo.persist(it)
        }
        invitation(user, tenant, expiresAt)
        return tenant
    }

    private fun createUser(): User {
        val unique = UUID.randomUUID().toString().take(8)
        return User().also {
            it.passwordHash = "\$2y\$10\$2bNfOGM2k9b8CKi85z9LqekdJp5EJk4j7n4JrTxf2j2Ff0cRImwR6"
            it.displayName = "User Token Cleanup $unique"
            it.permalink = "user-token-cleanup-$unique"
            it.email = "user-token-cleanup-$unique@example.com"
            it.role = ServerRole.ServerOwner
            it.passwordSetAt = Instant.now()
            userRepo.persist(it)
        }
    }

    private fun resetPasswordToken(
        user: User,
        expiresAt: Instant,
        acceptedAt: Instant? = null,
        revokedAt: Instant? = null,
    ): ResetPasswordToken =
        ResetPasswordToken().also {
            it.user = user
            it.tokenHash = tokenHash()
            it.createdAt = expiresAt.minus(1, ChronoUnit.DAYS)
            it.expiresAt = expiresAt
            it.acceptedAt = acceptedAt
            it.revokedAt = revokedAt
            resetPasswordTokenRepo.persist(it)
        }

    private fun invitation(
        user: User,
        tenant: Tenant,
        expiresAt: Instant,
        acceptedAt: Instant? = null,
        revokedAt: Instant? = null,
    ): UserInvitation =
        UserInvitation().also {
            it.user = user
            it.tenant = tenant
            it.tokenHash = tokenHash()
            it.createdAt = expiresAt.minus(1, ChronoUnit.DAYS)
            it.expiresAt = expiresAt
            it.acceptedAt = acceptedAt
            it.revokedAt = revokedAt
            userInvitationRepo.persist(it)
        }

    private fun emailChange(
        user: User,
        expiresAt: Instant,
        acceptedAt: Instant? = null,
        revokedAt: Instant? = null,
    ): UserEmailChange =
        UserEmailChange().also {
            it.user = user
            it.pendingEmail = "pending-${UUID.randomUUID()}@example.com"
            it.tokenHash = tokenHash()
            it.createdAt = expiresAt.minus(1, ChronoUnit.DAYS)
            it.expiresAt = expiresAt
            it.acceptedAt = acceptedAt
            it.revokedAt = revokedAt
            userEmailChangeRepo.persist(it)
        }

    private fun tokenHash(): String =
        UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
}
