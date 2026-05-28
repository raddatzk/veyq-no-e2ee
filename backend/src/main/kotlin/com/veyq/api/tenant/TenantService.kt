package com.veyq.api.tenant

import com.veyq.api.auth.BadRequestException
import com.veyq.api.auth.ConflictException
import com.veyq.api.auth.ForbiddenException
import com.veyq.api.auth.NotFoundException
import com.veyq.api.auth.PayloadTooLargeException
import com.veyq.api.auth.filterchain.RequestContext
import com.veyq.api.common.NoticeMarkdown
import com.veyq.api.domain.*
import com.veyq.api.generated.model.CreateTenantRequest
import com.veyq.api.generated.model.TenantRole as ApiTenantRole
import com.veyq.api.generated.model.TenantSettingsResponse
import com.veyq.api.generated.model.TenantSummary
import com.veyq.api.generated.model.TenantsSummary
import com.veyq.api.generated.model.UpdateTenantSettingsRequest
import com.veyq.api.generated.model.ManagedUserTenantSummary
import com.veyq.api.generated.model.UserSummary
import com.veyq.api.library.LibraryDeletionService
import com.veyq.api.library.media.MediaConfig
import com.veyq.api.usermgmt.user.ownAvatarUrl
import com.veyq.api.usermgmt.user.toUsersApiRole
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

@ApplicationScoped
class TenantService @Inject constructor(
    private val tenantRepo: TenantRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val libraryDeletionService: LibraryDeletionService,
    private val requestContext: RequestContext,
    private val noticeMarkdown: NoticeMarkdown,
    private val mediaConfig: MediaConfig,
) {

    @Transactional
    fun createTenantForCurrentUser(request: CreateTenantRequest): TenantSummary {
        val user = requestContext.user!!
        val name = request.name!!.trim().lowercase()
        if (!TenantName.isValid(name)) throw BadRequestException("Invalid tenant name")
        val tenant = createTenant(name, request.displayName!!, request.domain!!, user)

        return tenant.toSummary(ApiTenantRole.TenantOwner, listOf(user.toTenantAdminSummary()))
    }

    fun listMyTenants(): TenantsSummary {
        return TenantsSummary(
            requestContext.user!!.tenantMemberships
                .filter { it.acceptedAt != null }
                .sortedBy { it.tenant.name }
                .map {
                    val tenantOwners = findUsersWithRole(it.tenant, TenantRole.TenantOwner).map { user -> user.toTenantAdminSummary() }
                    it.tenant.toSummary(it.role.toTenantsApiRole(), tenantOwners)
                }
        )
    }

    private fun findUsersWithRole(tenant: Tenant, role: TenantRole): List<User> {
        return tenant.members.filter { it.role == role && it.acceptedAt != null }.map { it.user }
    }

    fun requireTenantMembership(tenant: Tenant, user: User) {
        tenantMemberRepo.findByTenantAndUser(tenant, user)?.takeIf { it.acceptedAt != null }
            ?: throw ForbiddenException("User is not member of this tenant")
    }

    fun addUserToTenant(tenant: Tenant, user: User, role: TenantRole = TenantRole.TenantMember, acceptedAt: Instant? = null): TenantMember {
        tenantMemberRepo.findByTenantAndUser(tenant, user)?.let { return it }

        TenantMember().also {
            it.id = TenantMemberId(tenant.id, user.id)
            it.tenant = tenant
            it.user = user
            it.role = role
            it.acceptedAt = acceptedAt
            it.persist()
            return it
        }
    }

    private fun createTenant(name: String, displayName: String, domain: String, owner: User): Tenant {
        if (tenantRepo.existsByName(name)) {
            throw ConflictException("Tenant name already exists")
        }
        val tenant = Tenant().also {
            it.name = name
            it.displayName = displayName.trim()
            it.domain = domain
            tenantRepo.persist(it)
        }
        tenantRepo.flush()

        TenantMember().also {
            it.id = TenantMemberId(tenant.id, owner.id)
            it.tenant = tenant
            it.user = owner
            it.role = TenantRole.TenantOwner
            it.acceptedAt = Instant.now()
            tenantMemberRepo.persist(it)
        }

        return tenant
    }

    fun tenantSettings(): TenantSettingsResponse =
        tenant().toSettingsResponse()

    fun tenantLogo(): File =
        logoFile(tenant())

    @Transactional
    fun updateTenantLogo(uploadPath: Path): TenantSettingsResponse {
        val tenant = tenant()
        val size = Files.size(uploadPath)
        if (size <= 0) throw BadRequestException("Tenant logo file is required", "TENANT_LOGO_REQUIRED")
        if (size > MAX_LOGO_BYTES) throw PayloadTooLargeException("Tenant logo file is too large")
        val contentType = detectLogoContentType(uploadPath)
        val oldStorageKey = tenant.logoStorageKey
        val storageKey = logoStorageKey(tenant, contentType)
        writeLogoFile(uploadPath, storageKey)
        tenant.logoStorageKey = storageKey
        tenant.logoContentType = contentType
        tenant.logoUpdatedAt = Instant.now()
        oldStorageKey?.let(::deleteLogoFile)
        return tenant.toSettingsResponse()
    }

    @Transactional
    fun deleteTenantLogo(): TenantSettingsResponse {
        val tenant = tenant()
        val oldStorageKey = tenant.logoStorageKey
        tenant.logoStorageKey = null
        tenant.logoContentType = null
        tenant.logoUpdatedAt = null
        oldStorageKey?.let(::deleteLogoFile)
        return tenant.toSettingsResponse()
    }

    @Transactional
    fun updateTenantSettings(request: UpdateTenantSettingsRequest): TenantSettingsResponse {
        val tenant = tenant()
        tenant.displayName = request.displayName!!.trim()
        request.termsMarkdownByLocale?.let { terms ->
            val normalized = noticeMarkdown.normalize(terms)
            if (noticeMarkdown.changed(tenant.termsMarkdownByLocale, normalized)) {
                tenant.termsMarkdownByLocale = noticeMarkdown.serialize(normalized)
                tenant.termsVersion += 1
            }
        }
        return tenant.toSettingsResponse()
    }

    @Transactional
    fun deleteTenant() {
        libraryDeletionService.deleteTenant(tenant())
        requestContext.tenant = null
    }

    fun validateTenantName(name: String) {
        if (tenantRepo.existsByName(name)) {
            throw ConflictException("Tenant name already exists")
        }
    }

    fun findTenantByName(name: String): Tenant {
        return tenantRepo.findByName(name)
            ?: throw NotFoundException("Tenant not found")
    }

    fun normalizeDomain(input: String): String {
        val domain = input.trim().lowercase()
        if (
            domain.isBlank() ||
            domain.contains("://") ||
            domain.contains("/") ||
            domain.contains(":") ||
            domain.length > 255 ||
            !DOMAIN_REGEX.matches(domain)
        ) {
            throw BadRequestException("Invalid tenant domain")
        }
        return domain
    }

    private fun tenant(): Tenant {
        val tenantName = requestContext.tenant!!.name
        return tenantRepo.findByName(tenantName) ?: throw NotFoundException("Tenant not found")
    }

    private fun Tenant.toSummary(role: ApiTenantRole, admins: List<UserSummary>): TenantSummary =
        TenantSummary(
            id = id,
            name = name,
            displayName = displayName,
            domain = domain,
            role = role,
            admins = admins,
            logoUrl = logoUrl(),
        )

    private fun Tenant.toSettingsResponse(): TenantSettingsResponse =
        TenantSettingsResponse(
            tenantId = id,
            name = name,
            displayName = displayName,
            logoUrl = logoUrl(),
            termsVersion = termsVersion,
            termsMarkdownByLocale = noticeMarkdown.deserialize(termsMarkdownByLocale).takeIf { it.isNotEmpty() },
        )

    private fun logoFile(tenant: Tenant): File {
        val storageKey = tenant.logoStorageKey ?: throw NotFoundException("Tenant logo not found")
        tenant.logoContentType ?: throw NotFoundException("Tenant logo not found")
        val path = storagePath(storageKey)
        if (!Files.exists(path)) throw NotFoundException("Tenant logo not found")
        return path.toFile()
    }

    private fun detectLogoContentType(path: Path): String {
        val image = ImageIO.read(path.toFile()) ?: throw BadRequestException("Tenant logo image is invalid", "TENANT_LOGO_INVALID")
        if (image.width <= 0 || image.height <= 0) throw BadRequestException("Tenant logo image is invalid", "TENANT_LOGO_INVALID")
        ImageIO.createImageInputStream(path.toFile()).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) throw BadRequestException("Tenant logo image is invalid", "TENANT_LOGO_INVALID")
            val reader = readers.next()
            val format = reader.formatName.lowercase()
            reader.dispose()
            return when (format) {
                "jpeg", "jpg" -> "image/jpeg"
                "png" -> "image/png"
                else -> throw BadRequestException("Tenant logo image must be JPEG or PNG", "TENANT_LOGO_TYPE_UNSUPPORTED")
            }
        }
    }

    private fun logoStorageKey(tenant: Tenant, contentType: String): String {
        val extension = if (contentType == "image/png") "png" else "jpg"
        return "tenants/${tenant.id}/logo-${UUID.randomUUID()}.$extension"
    }

    private fun writeLogoFile(source: Path, storageKey: String) {
        val target = storagePath(storageKey)
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling("${target.fileName}.tmp")
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun deleteLogoFile(storageKey: String) {
        Files.deleteIfExists(storagePath(storageKey))
    }

    private fun storagePath(storageKey: String): Path {
        val root = mediaConfig.storageDir.toAbsolutePath().normalize()
        val path = root.resolve(storageKey).normalize()
        if (!path.startsWith(root)) throw BadRequestException("Invalid tenant logo storage key")
        return path
    }

    private companion object {
        val DOMAIN_REGEX = Regex("^(?=.{1,255}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
        private const val MAX_LOGO_BYTES = 5L * 1024L * 1024L
    }
}

fun Tenant.logoUrl(): String? =
    logoUpdatedAt?.takeIf { logoStorageKey != null }?.let { "/api/tenants/${url(name)}/logo?v=${url(it.toString())}" }

private fun url(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

fun Tenant.toManagedTenantSummary(): ManagedUserTenantSummary =
    ManagedUserTenantSummary(id, name, displayName)

private fun TenantRole.toTenantsApiRole(): ApiTenantRole =
    when (this) {
        TenantRole.TenantOwner -> ApiTenantRole.TenantOwner
        TenantRole.TenantMember -> ApiTenantRole.TenantMember
    }

private fun User.toTenantAdminSummary(): UserSummary =
    UserSummary(
        userId = id,
        email = email,
        displayName = displayName,
        permalink = permalink,
        serverRole = role.toUsersApiRole(),
        passkeyCount = passkeyCredentials.size,
        hasPassword = passwordSetAt != null,
        avatarUrl = ownAvatarUrl(),
    )
