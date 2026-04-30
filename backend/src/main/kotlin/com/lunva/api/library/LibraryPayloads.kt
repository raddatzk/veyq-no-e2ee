package com.lunva.api.library

import jakarta.validation.constraints.NotBlank

data class CreateLibraryItemRequest(
    val parentPath: String?,
    @field:NotBlank(message = "name must not be blank") val name: String?,
    @field:NotBlank(message = "displayName must not be blank") val displayName: String?,
    val scope: LibraryScopeRequest? = null,
)

data class LibraryScopeRequest(
    val type: String?,
    val groupName: String? = null,
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
