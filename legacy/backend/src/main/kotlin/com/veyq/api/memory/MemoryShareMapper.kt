package com.veyq.api.memory

import com.veyq.api.common.NoticeMarkdown
import com.veyq.api.common.toApiOffsetDateTime
import com.veyq.api.domain.Memory
import com.veyq.api.domain.MemoryShare
import com.veyq.api.domain.MemoryShareIdentity
import com.veyq.api.domain.MemoryShareRole
import com.veyq.api.domain.TenantFolder
import com.veyq.api.domain.User
import com.veyq.api.generated.model.MemoryShareSummary
import com.veyq.api.generated.model.PublicSharePermissions
import com.veyq.api.generated.model.PublicShareNotice
import com.veyq.api.generated.model.PublicShareNoticeScope
import com.veyq.api.generated.model.PublicShareSummary
import com.veyq.api.library.LibraryScopes
import jakarta.inject.Singleton

@Singleton
class MemoryShareMapper(
    private val noticeMarkdown: NoticeMarkdown,
    private val publicShareMemoryMapper: PublicShareMemoryMapper,
) {
    fun toManagedSummary(share: MemoryShare): MemoryShareSummary =
        MemoryShareSummary(
            id = share.publicId,
            memoryId = share.rootMemory.publicId,
            name = share.name,
            role = share.role.toApiRole(),
            enabled = share.enabled,
            shareUrl = "/s/${share.publicId}",
            hasPassword = share.passwordHash != null,
            noticeVersion = share.noticeVersion,
            noticeMarkdownByLocale = noticeMarkdown.deserialize(share.noticeMarkdownByLocale).takeIf { it.isNotEmpty() },
            createdAt = share.createdAt.toApiOffsetDateTime(),
            updatedAt = share.updatedAt.toApiOffsetDateTime(),
        )

    fun toPublicSummary(access: PublicShareSummaryAccess): PublicShareSummary =
        PublicShareSummary(
            id = access.share.publicId,
            role = access.share.role.toApiRole(),
            authenticated = access.authenticatedUser != null || access.shareIdentity != null,
            passwordRequired = access.share.passwordHash != null,
            displayNameRequired = access.share.requiresDisplayName() &&
                access.shareIdentity == null &&
                access.authenticatedUser == null,
            canViewGallery = access.canViewGallery,
            canUpload = access.canUpload,
            canEdit = access.canEdit,
            canDelete = access.canDelete,
            permissions = access.permissions,
            notices = publicShareNotices(access.share),
            rootMemory = if (access.exposeRootMemory) {
                publicShareMemoryMapper.toSummary(access.share.rootMemory, access.share)
            } else {
                null
            },
            redirectUrl = access.authenticatedUser?.let {
                "/${access.share.tenant.name}${access.share.rootMemory.pathRelativeToTenant()}"
            },
        )

    private fun publicShareNotices(share: MemoryShare): List<PublicShareNotice> =
        listOfNotNull(
            noticeMarkdown.deserialize(share.tenant.termsMarkdownByLocale)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    PublicShareNotice(
                        scope = PublicShareNoticeScope.TENANT,
                        tenantName = share.tenant.name,
                        tenantDomain = share.tenant.domain,
                        version = share.tenant.termsVersion,
                        markdownByLocale = it,
                    )
                },
            noticeMarkdown.deserialize(share.noticeMarkdownByLocale)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    PublicShareNotice(
                        scope = PublicShareNoticeScope.SHARE,
                        tenantName = share.tenant.name,
                        tenantDomain = share.tenant.domain,
                        version = share.noticeVersion,
                        markdownByLocale = it,
                    )
                },
        )

    private fun Memory.pathRelativeToTenant(): String =
        (parentMemory?.pathRelativeToTenant()?.trimEnd('/')
            ?: parentFolder?.pathRelativeToTenant()?.trimEnd('/')
            ?: LibraryScopes.pathPrefix(LibraryScopes.scope(this)).trimEnd('/')) + "/" + name

    private fun TenantFolder.pathRelativeToTenant(): String =
        (parentFolder?.pathRelativeToTenant()?.trimEnd('/')
            ?: LibraryScopes.pathPrefix(LibraryScopes.scope(this)).trimEnd('/')) + "/" + name
}

data class PublicShareSummaryAccess(
    val share: MemoryShare,
    val shareIdentity: MemoryShareIdentity?,
    val authenticatedUser: User?,
    val canViewGallery: Boolean,
    val canUpload: Boolean,
    val canEdit: Boolean,
    val canDelete: Boolean,
    val permissions: PublicSharePermissions,
    val exposeRootMemory: Boolean,
)

fun com.veyq.api.generated.model.MemoryShareRole.toDomainRole(): MemoryShareRole =
    MemoryShareRole.valueOf(value)

fun MemoryShareRole.toApiRole(): com.veyq.api.generated.model.MemoryShareRole =
    com.veyq.api.generated.model.MemoryShareRole.decode(name)

fun MemoryShare.requiresDisplayName(): Boolean =
    role != MemoryShareRole.VIEWER
