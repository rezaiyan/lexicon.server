package com.alirezaiyan.vokab.server.notification.rescue

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface RescuePreviewRepository : JpaRepository<RescuePreview, Long> {
    fun findAllByOrderByCreatedAtDescIdDesc(pageable: Pageable): List<RescuePreview>
}

interface RescueCopyRatingRepository : JpaRepository<RescueCopyRating, Long> {
    fun findByPreviewIdAndCandidateIndex(previewId: Long, candidateIndex: Int): RescueCopyRating?

    fun findAllByPreviewIdIn(previewIds: Collection<Long>): List<RescueCopyRating>
}
