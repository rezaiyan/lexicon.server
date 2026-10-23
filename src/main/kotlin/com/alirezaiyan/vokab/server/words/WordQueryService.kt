package com.alirezaiyan.vokab.server.words

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** Read-only lookups over a user's words, filtered and paged in the database: search and due words. */
@Service
class WordQueryService(
    private val wordRepository: WordRepository,
    private val clock: Clock,
) {
    /** One page of a search plus where the next page starts, null on the last page. */
    data class SearchPage(val words: List<WordDto>, val nextCursor: Long?)

    /** Words due for review now, most overdue first. */
    @Transactional(readOnly = true)
    fun listDue(userId: Long, limit: Int): List<WordDto> {
        val ids = wordRepository.findDueIds(userId, Instant.now(clock).toEpochMilli(), PageRequest.of(0, limit))
        return loadInOrder(ids)
    }

    @Transactional(readOnly = true)
    fun countDue(userId: Long): Long = wordRepository.countDue(userId, Instant.now(clock).toEpochMilli())

    /**
     * Words matching [query] (word or translation), [language] and [tagId], [limit] at a time in id
     * order. [cursor] is the previous page's [SearchPage.nextCursor]; ids only ever grow, so pages
     * stay stable while words are added.
     */
    @Transactional(readOnly = true)
    fun search(
        userId: Long,
        query: String?,
        language: String?,
        tagId: Long?,
        limit: Int,
        cursor: Long? = null,
        newestFirst: Boolean = false,
    ): SearchPage {
        val needle = query?.trim()?.lowercase().orEmpty()
        val pattern = "%" + needle.replace("#", "##").replace("%", "#%").replace("_", "#_") + "%"
        val lang = language?.trim()?.lowercase().orEmpty()
        val page = PageRequest.of(0, limit + 1)
        val ids = if (newestFirst) {
            wordRepository.searchIdsNewestFirst(userId, pattern, lang, tagId ?: 0L, cursor ?: 0L, page)
        } else {
            wordRepository.searchIds(userId, pattern, lang, tagId ?: 0L, cursor ?: 0L, page)
        }
        val pageIds = ids.take(limit)
        return SearchPage(loadInOrder(pageIds), nextCursor = pageIds.lastOrNull()?.takeIf { ids.size > limit })
    }

    private fun loadInOrder(ids: List<Long>): List<WordDto> {
        if (ids.isEmpty()) return emptyList()
        val byId = wordRepository.findAllByIdInWithTags(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toDto() }
    }
}
