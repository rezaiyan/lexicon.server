package com.alirezaiyan.vokab.server.shared

import com.alirezaiyan.vokab.server.withAssignedId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.alirezaiyan.vokab.server.notification.NotificationSchedule
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.words.Tag
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.words.Word

class JpaEntityTest {

    private fun user(id: Long? = null, email: String = "a@example.com") = User(id = id, email = email, name = "A")

    @Test
    fun `entities of the same type with the same id are equal regardless of other fields`() {
        val a = user(id = 1L, email = "a@example.com")
        val b = user(id = 1L, email = "b@example.com").also { it.currentStreak = 9 }

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `entities with different ids are not equal`() {
        assertNotEquals(user(id = 1L), user(id = 2L))
    }

    @Test
    fun `entities of different types with the same id are not equal`() {
        val tag = Tag(id = 1L, name = "t")
        val word = Word(id = 1L)

        assertFalse(tag.equals(word))
    }

    @Test
    fun `unsaved entities are equal only to themselves`() {
        val a = user()
        val b = user()

        assertEquals(a, a)
        assertNotEquals(a, b)
        assertNotEquals(a, user(id = 1L))
    }

    @Test
    fun `hashCode does not change when the id is assigned, so set membership survives persist`() {
        val word = Word()
        val set = hashSetOf(word)

        word.withAssignedId(42L)

        assertTrue(word in set)
    }

    @Test
    fun `non-null placeholder id of zero counts as unsaved`() {
        val user = user(id = 1L)
        val a = NotificationSchedule(user = user)
        val b = NotificationSchedule(user = user)

        assertNotEquals(a, b)
        assertEquals(a, a)
        assertEquals(NotificationSchedule(id = 5L, user = user), NotificationSchedule(id = 5L, user = user))
    }

    @Test
    fun `user toString exposes no personal data`() {
        val text = user(id = 7L, email = "secret@example.com").also { it.name = "Secret Name" }.toString()

        assertEquals("User(id=7, subscriptionStatus=FREE)", text)
        assertFalse("secret" in text.lowercase())
    }

    @Test
    fun `default toString shows only type and id`() {
        assertEquals("Tag(id=3)", Tag(id = 3L, name = "private").toString())
    }

    @Test
    fun `store-owned user columns change in memory only through mirror functions`() {
        val user = user(id = 1L)

        user.mirrorSubscription(SubscriptionStatus.ACTIVE, null)
        user.mirrorGrant(null, "manual")
        user.mirrorRevenueCatUserId("rc-1")

        assertEquals(SubscriptionStatus.ACTIVE, user.subscriptionStatus)
        assertEquals("manual", user.premiumGrantReason)
        assertEquals("rc-1", user.revenueCatUserId)
    }
}
