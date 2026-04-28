package com.lunva.api.library

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class CreateLibraryItemRequest(
    val parentPath: String?,
    @field:NotBlank(message = "name must not be blank") val name: String?,
    @field:NotBlank(message = "displayName must not be blank") val displayName: String?,
)

data class UpdateLibraryItemRequest(
    val path: String?,
    val name: String?,
    val displayName: String?,
)

data class MoveLibraryItemRequest(
    @field:NotBlank(message = "path must not be blank") val path: String?,
    val newParentPath: String?,
    val name: String?,
)

data class AlbumMediaRequest(
    @field:NotBlank(message = "albumPath must not be blank") val albumPath: String?,
    @field:NotBlank(message = "mediaId must not be blank") val mediaId: String?,
    val hidden: Boolean?,
    val position: Int?,
)

data class SetAlbumCoverRequest(
    @field:NotBlank(message = "albumPath must not be blank") val albumPath: String?,
    @field:NotBlank(message = "mediaId must not be blank") val mediaId: String?,
    val hidden: Boolean?,
)

data class ShareUserRequest(
    @field:NotNull(message = "userId must not be blank") val userId: Long?
)

data class ShareGroupRequest(
    @field:NotNull(message = "groupId must not be blank") val groupId: Long?
)

data class LibrarySharesSummary(
    val users: List<UserShareSummary>,
    val groups: List<GroupShareSummary>,
)

data class UserShareSummary(
    val userId: Long,
    val email: String,
    val displayName: String
)

data class GroupShareSummary(
    val groupId: Long,
    val name: String,
    val displayName: String
)