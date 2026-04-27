package com.lunva.api.media

import com.lunva.api.domain.*
import com.lunva.api.security.RequestContext
import com.lunva.api.tenant.TenantService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant

data class SingleMediaSummary(val id: String, val title: String, val ownerUserId: Long, val createdAt: Instant)
data class MediaSummary(val media: List<SingleMediaSummary>)

data class MediaDetail(val id: String, val title: String, val ownerUserId: Long, val createdAt: Instant)

@ApplicationScoped
class MediaService @Inject constructor(
    private val mediaRepo: MediaRepo,
    private val mediaUserShareRepo: MediaUserShareRepo,
    private val mediaGroupShareRepo: MediaGroupShareRepo,
    private val userRepo: UserRepo,
    private val tenantMemberRepo: TenantMemberRepo,
    private val tenantGroupRepo: TenantGroupRepo,
    private val tenantService: TenantService,
    private val requestContext: RequestContext,
) {

    fun listAccessibleMedia(): MediaSummary {
        val tenant = requestContext.tenant!!
        val user = requestContext.user!!

        return MediaSummary(
            mediaRepo.listAccessible(tenant, user)
                .map { it.toSummary() }
        )
    }

    //    fun getAccessibleMedia(tenantName: String, mediaId: Long): MediaDetail {
//        val membership = tenantService.requireMembership(tenantName)
//        val media = mediaRepo.findAccessible(membership.tenant, membership.user, mediaId)
//            ?: throw NotFoundException("Media not found")
//        return media.toDetail()
//    }
//
//    @Transactional
//    fun shareWithUser(tenantName: String, mediaId: Long, userId: Long) {
//        val (membership, media) = requireCanManage(tenantName, mediaId)
//        val user = userRepo.findById(userId)
//            ?: throw NotFoundException("User not found")
//        if (tenantMemberRepo.findByTenantAndUser(membership.tenant, user) == null) {
//            throw BadRequestException("User is not a member of this tenant")
//        }
//        if (mediaUserShareRepo.findByMediaAndUser(media, user) != null) {
//            return
//        }
//
//        MediaUserShare().also {
//            it.id = MediaUserShareId(media.id, user.id)
//            it.media = media
//            it.user = user
//            it.createdAt = Instant.now()
//            mediaUserShareRepo.persist(it)
//        }
//    }
//
//    @Transactional
//    fun removeUserShare(tenantName: String, mediaId: Long, userId: Long) {
//        val (_, media) = requireCanManage(tenantName, mediaId)
//        val user = userRepo.findById(userId) ?: return
//        mediaUserShareRepo.findByMediaAndUser(media, user)?.delete()
//    }
//
//    @Transactional
//    fun shareWithGroup(tenantName: String, mediaId: Long, groupId: Long) {
//        val (membership, media) = requireCanManage(tenantName, mediaId)
//        val group = tenantGroupRepo.findById(groupId)
//            ?: throw NotFoundException("Group not found")
//        if (group.tenant.id != membership.tenant.id) {
//            throw BadRequestException("Group is not part of this tenant")
//        }
//        if (mediaGroupShareRepo.findByMediaAndGroup(media, group) != null) {
//            return
//        }
//
//        MediaGroupShare().also {
//            it.id = MediaGroupShareId(media.id, group.id)
//            it.media = media
//            it.group = group
//            it.createdAt = Instant.now()
//            mediaGroupShareRepo.persist(it)
//        }
//    }
//
//    @Transactional
//    fun removeGroupShare(tenantName: String, mediaId: Long, groupId: Long) {
//        val (_, media) = requireCanManage(tenantName, mediaId)
//        val group = tenantGroupRepo.findById(groupId) ?: return
//        mediaGroupShareRepo.findByMediaAndGroup(media, group)?.delete()
//    }
//
//    private fun requireCanManage(tenantName: String, mediaId: Long): Pair<TenantMember, Media> {
//        val membership = tenantService.requireMembership(tenantName)
//        val media = mediaRepo.findByTenantAndId(membership.tenant, mediaId)
//            ?: throw NotFoundException("Media not found")
//        if (media.owner.id != membership.user.id) {
//            throw ForbiddenException("Only the media owner can manage shares")
//        }
//        return membership to media
//    }
//
    private fun Media.toSummary(): SingleMediaSummary =
        SingleMediaSummary(publicId, title, owner.id, createdAt)

//    private fun Media.toDetail(): MediaDetail =
//        MediaDetail(id, title, owner.id, createdAt)
}
