package com.alirezaiyan.vokab.server.user

import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.study.DayActivity
import com.alirezaiyan.vokab.server.subscription.FeatureAccessResponse
import com.alirezaiyan.vokab.server.study.LanguagePair
import com.alirezaiyan.vokab.server.study.ProfileStatsResponse
import com.alirezaiyan.vokab.server.auth.AuthService
import com.alirezaiyan.vokab.server.subscription.ClientFeatureFlags
import com.alirezaiyan.vokab.server.subscription.FeatureAccessService
import com.alirezaiyan.vokab.server.study.ProfileStatsService
import com.alirezaiyan.vokab.server.subscription.UserFeatureAccess
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class UserControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var userService: UserService

    @MockitoBean
    private lateinit var authService: AuthService

    @MockitoBean
    private lateinit var featureAccessService: FeatureAccessService

    @MockitoBean
    private lateinit var profileStatsService: ProfileStatsService

    @MockitoBean
    private lateinit var avatarService: AvatarService

    private val mockUser = User(
        id = 1L,
        email = "test@example.com",
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(mockUser.requireId()), null, emptyList())

    // ── GET /api/v1/users/me ───────────────────────────────────────────────────

    @Test
    fun `GET me should return 200 with current user dto`() {
        `when`(userService.getUserById(1L)).thenReturn(createUserDto())

        mockMvc.perform(
            get("/api/v1/users/me")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.email").value("test@example.com"))
            .andExpect(jsonPath("$.data.name").value("Test User"))
    }

    @Test
    fun `GET me should return 400 when service throws exception`() {
        `when`(userService.getUserById(1L))
            .thenThrow(IllegalArgumentException("User not found"))

        mockMvc.perform(
            get("/api/v1/users/me")
                .with(authentication(auth))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `GET me should return 4xx when not authenticated`() {
        mockMvc.perform(
            get("/api/v1/users/me")
        )
            .andExpect(status().is4xxClientError)
    }

    // ── PATCH /api/v1/users/me ─────────────────────────────────────────────────

    @Test
    fun `PATCH me should return 200 with updated user dto`() {
        val request = UpdateProfileRequest(name = "New Name", displayAlias = "newAlias")
        `when`(userService.updateUser(1L, "New Name", "newAlias"))
            .thenReturn(createUserDto(name = "New Name"))

        mockMvc.perform(
            patch("/api/v1/users/me")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.name").value("New Name"))
    }

    @Test
    fun `PATCH me should return 200 when only name is provided`() {
        val request = UpdateProfileRequest(name = "Updated Name")
        `when`(userService.updateUser(1L, "Updated Name", null))
            .thenReturn(createUserDto(name = "Updated Name"))

        mockMvc.perform(
            patch("/api/v1/users/me")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `PATCH me should return 400 when service throws exception`() {
        val request = UpdateProfileRequest(name = "New Name")
        `when`(userService.updateUser(1L, "New Name", null))
            .thenThrow(IllegalArgumentException("invalid alias"))

        mockMvc.perform(
            patch("/api/v1/users/me")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `PATCH me should return 400 when display alias is too short`() {
        val request = UpdateProfileRequest(displayAlias = "x") // min 2 chars

        mockMvc.perform(
            patch("/api/v1/users/me")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── POST /api/v1/users/me/avatar ───────────────────────────────────────────

    @Test
    fun `POST avatar should return 200 with profile image url`() {
        val file = MockMultipartFile("file", "avatar.jpg", "image/jpeg", ByteArray(100))
        `when`(avatarService.uploadAvatar(mockUser.id!!, file))
            .thenReturn("https://cdn.example.com/avatars/1.jpg")

        mockMvc.perform(
            multipart("/api/v1/users/me/avatar")
                .file(file)
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.profileImageUrl").value("https://cdn.example.com/avatars/1.jpg"))
    }

    @Test
    fun `POST avatar should return 400 when upload fails`() {
        val file = MockMultipartFile("file", "avatar.jpg", "image/jpeg", ByteArray(100))
        `when`(avatarService.uploadAvatar(mockUser.id!!, file))
            .thenThrow(IllegalArgumentException("file too large"))

        mockMvc.perform(
            multipart("/api/v1/users/me/avatar")
                .file(file)
                .with(authentication(auth))
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── DELETE /api/v1/users/me/avatar ────────────────────────────────────────

    @Test
    fun `DELETE avatar should return 200 when deleted successfully`() {
        mockMvc.perform(
            delete("/api/v1/users/me/avatar")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Avatar deleted successfully"))
    }

    @Test
    fun `DELETE avatar should return 500 when delete fails`() {
        doThrow(RuntimeException("file system error"))
            .`when`(avatarService).deleteAvatar(1L)

        mockMvc.perform(
            delete("/api/v1/users/me/avatar")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── DELETE /api/v1/users/me ────────────────────────────────────────────────

    @Test
    fun `DELETE me should return 200 when account deleted successfully`() {
        mockMvc.perform(
            delete("/api/v1/users/me")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Account deleted successfully"))
    }

    @Test
    fun `DELETE me should return 500 when deletion fails`() {
        doThrow(RuntimeException("deletion error"))
            .`when`(authService).deleteAccount(1L)

        mockMvc.perform(
            delete("/api/v1/users/me")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/users/feature-access ──────────────────────────────────────

    @Test
    fun `GET feature-access should return 200 with feature flags and user access`() {
        `when`(featureAccessService.getFeatureAccess(1L))
            .thenReturn(
                FeatureAccessResponse(
                    featureFlags = ClientFeatureFlags(pushNotificationsEnabled = true),
                    userAccess = UserFeatureAccess(hasPremiumAccess = false),
                )
            )

        mockMvc.perform(
            get("/api/v1/users/feature-access")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.featureFlags.pushNotificationsEnabled").value(true))
            .andExpect(jsonPath("$.data.userAccess.hasPremiumAccess").value(false))
    }

    @Test
    fun `GET feature-access should return 200 with premium access for subscribed user`() {
        `when`(featureAccessService.getFeatureAccess(1L))
            .thenReturn(
                FeatureAccessResponse(
                    featureFlags = ClientFeatureFlags(pushNotificationsEnabled = true),
                    userAccess = UserFeatureAccess(hasPremiumAccess = true),
                )
            )

        mockMvc.perform(
            get("/api/v1/users/feature-access")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.userAccess.hasPremiumAccess").value(true))
    }

    @Test
    fun `GET feature-access should return 500 when service throws exception`() {
        `when`(featureAccessService.getFeatureAccess(1L))
            .thenThrow(RuntimeException("config error"))

        mockMvc.perform(
            get("/api/v1/users/feature-access")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/users/feature-flags ───────────────────────────────────────

    @Test
    fun `GET feature-flags should return 200 without authentication`() {
        `when`(featureAccessService.getClientFeatureFlags())
            .thenReturn(ClientFeatureFlags(pushNotificationsEnabled = false))

        mockMvc.perform(
            get("/api/v1/users/feature-flags")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.pushNotificationsEnabled").value(false))
    }

    @Test
    fun `GET feature-flags should return 500 when service throws exception`() {
        `when`(featureAccessService.getClientFeatureFlags())
            .thenThrow(RuntimeException("config error"))

        mockMvc.perform(
            get("/api/v1/users/feature-flags")
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── GET /api/v1/users/profile-stats ───────────────────────────────────────

    @Test
    fun `GET profile-stats should return 200 with profile stats`() {
        `when`(profileStatsService.getProfileStats(mockUser.requireId())).thenReturn(createProfileStatsResponse())

        mockMvc.perform(
            get("/api/v1/users/profile-stats")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.currentStreak").value(5))
            .andExpect(jsonPath("$.data.longestStreak").value(10))
            .andExpect(jsonPath("$.data.memberSince").value("2024-01-01"))
    }

    @Test
    fun `GET profile-stats should return 200 with empty weekly activity`() {
        `when`(profileStatsService.getProfileStats(mockUser.requireId()))
            .thenReturn(createProfileStatsResponse(weeklyActivity = emptyList()))

        mockMvc.perform(
            get("/api/v1/users/profile-stats")
                .with(authentication(auth))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.weeklyActivity").isArray)
            .andExpect(jsonPath("$.data.weeklyActivity").isEmpty)
    }

    @Test
    fun `GET profile-stats should return 500 when service throws exception`() {
        `when`(profileStatsService.getProfileStats(mockUser.requireId()))
            .thenThrow(RuntimeException("stats error"))

        mockMvc.perform(
            get("/api/v1/users/profile-stats")
                .with(authentication(auth))
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── factory functions ──────────────────────────────────────────────────────

    private fun createUserDto(
        id: Long = 1L,
        email: String = "test@example.com",
        name: String = "Test User",
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
    ): UserDto = UserDto(
        id = id,
        email = email,
        name = name,
        subscriptionStatus = subscriptionStatus,
        subscriptionExpiresAt = null,
        currentStreak = 0,
        displayAlias = null,
        profileImageUrl = null,
    )

    private fun createProfileStatsResponse(
        currentStreak: Int = 5,
        longestStreak: Int = 10,
        memberSince: String = "2024-01-01",
        weeklyActivity: List<DayActivity> = listOf(
            DayActivity(date = "2024-01-01", reviewCount = 10),
            DayActivity(date = "2024-01-02", reviewCount = 0),
        ),
        languages: List<LanguagePair> = listOf(
            LanguagePair(sourceLanguage = "en", targetLanguage = "de", wordCount = 50),
        ),
    ): ProfileStatsResponse = ProfileStatsResponse(
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        memberSince = memberSince,
        weeklyActivity = weeklyActivity,
        languages = languages,
    )
}
