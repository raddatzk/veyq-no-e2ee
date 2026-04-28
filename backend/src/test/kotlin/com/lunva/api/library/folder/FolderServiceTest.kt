package com.lunva.api.library.folder

import com.lunva.api.auth.AuthResource
import com.lunva.api.auth.AuthService
import com.lunva.api.auth.PasswordService
import com.lunva.api.domain.MediaRepo
import com.lunva.api.domain.ServerRole
import com.lunva.api.domain.TenantGroupMemberRepo
import com.lunva.api.domain.TenantGroupRepo
import com.lunva.api.domain.TenantMemberRepo
import com.lunva.api.domain.TenantRepo
import com.lunva.api.domain.UserRepo
import com.lunva.api.library.CreateLibraryItemRequest
import com.lunva.api.security.RequestContext
import com.lunva.api.setup.AdminSetupRequest
import com.lunva.api.setup.SetupService
import com.lunva.api.tenant.CreateTenantRequest
import com.lunva.api.tenant.TenantService
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.transaction.UserTransaction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@Disabled("Private WIP FolderService test; not part of the public library API test suite")
class FolderServiceTest {
    @Inject lateinit var userRepo: UserRepo
    @Inject lateinit var tenantRepo: TenantRepo
    @Inject lateinit var tenantMemberRepo: TenantMemberRepo
    @Inject lateinit var tenantGroupRepo: TenantGroupRepo
    @Inject lateinit var tenantGroupMemberRepo: TenantGroupMemberRepo
    @Inject lateinit var mediaRepo: MediaRepo
    @Inject lateinit var passwordService: PasswordService
    @Inject lateinit var transaction: UserTransaction
    @Inject lateinit var setupService: SetupService
    @Inject lateinit var authService: AuthService
    @Inject lateinit var tenantService: TenantService
    @Inject lateinit var requestContext: RequestContext
    @Inject lateinit var folderService: FolderService

    private lateinit var runId: String
    private lateinit var ownerEmail: String
    private lateinit var tenantName: String

    @BeforeEach
    fun setUp() {
        runId = UUID.randomUUID().toString().take(8)
        ownerEmail = "owner-$runId@example.com"
        tenantName = "explorer-$runId"
        createUser("owner-$runId", "admin123", ServerRole.ServerOwner)
        login(ownerEmail, "admin123")
        createTenant(tenantName, "Explorer $runId")
    }

    @Test
    fun test() {
        val summary = folderService.createFolder(CreateLibraryItemRequest("/", "folder", "Folder"))
        assertThat(summary.name).isEqualTo("folder")
    }

    private fun createUser(name: String, password: String, role: ServerRole) =
        setupService.createAdmin(AdminSetupRequest(password, name, ownerEmail))

    private fun login(email: String, password: String) {
        authService.login(AuthResource.LoginRequest(email, password))
        requestContext.user = userRepo.findByEmail(email)
    }

    private fun createTenant(name: String, displayName: String) {
        tenantService.createTenantForCurrentUser(CreateTenantRequest(name, displayName))
        requestContext.tenant = tenantRepo.findByName(name)
    }
}
